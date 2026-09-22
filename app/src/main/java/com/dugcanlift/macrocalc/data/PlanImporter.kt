package com.dugcanlift.macrocalc.data

import android.content.Context
import com.dugcanlift.kit.IngredientParser
import com.dugcanlift.kit.PlanPayload
import com.dugcanlift.kit.PlanRecipe
import com.dugcanlift.kit.RecipeNutrition
import com.dugcanlift.kit.PlanSet
import com.dugcanlift.kit.PlanWorkoutExercise
import com.dugcanlift.kit.ShareSide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

sealed class PlanImportResult {
    data class Imported(
        val recipeCount: Int,
        val mealCount: Int,
        val routineCount: Int,
        val sessionCount: Int
    ) : PlanImportResult()
    object AlreadyImported : PlanImportResult()
}

object PlanImporter {

    fun summarize(payload: PlanPayload): String {
        val parts = mutableListOf<String>()
        if (payload.recipes.isNotEmpty()) parts += pluralize(payload.recipes.size, "recipe")
        if (payload.meals.isNotEmpty()) parts += pluralize(payload.meals.size, "meal")
        if (payload.workouts.isNotEmpty()) {
            val dayCount = payload.sessions.map { it.date }.distinct().size
            parts += if (dayCount > 0)
                "${pluralize(payload.workouts.size, "workout")} scheduled across ${pluralize(dayCount, "day")}"
            else
                pluralize(payload.workouts.size, "workout")
        } else if (payload.sessions.isNotEmpty()) {
            // Defensive: sessions with no workout templates shouldn't be silently dropped either.
            val dayCount = payload.sessions.map { it.date }.distinct().size
            parts += "sessions scheduled across ${pluralize(dayCount, "day")}"
        }
        return if (parts.isEmpty()) "Nothing to import" else parts.joinToString(", ")
    }

    private fun pluralize(count: Int, noun: String): String = "$count $noun${if (count == 1) "" else "s"}"

    /** A validated (recipeIndex-resolved) meal, ready to write with no further I/O or lookups. */
    private data class ValidMeal(val recipe: Recipe, val date: String, val meal: Meal, val servings: Double)

    /** A validated (workoutIndex-resolved) session, ready to write with no further I/O or lookups. */
    private data class ValidSession(val routine: Routine, val workoutName: String, val date: String)

    suspend fun accept(payload: PlanPayload, context: Context): PlanImportResult {
        val hash = sha256(payload.rawJson)
        val importedStore = ImportedPlanStore.get(context)
        if (importedStore.contains(hash)) return PlanImportResult.AlreadyImported

        // --- Pass 1: build and validate everything in memory. No repository writes here —
        // if anything throws, nothing has been persisted yet ("no partial accept"). ---

        val recipes = payload.recipes.map { pr ->
            Recipe(
                name = pr.name,
                servings = pr.servings,
                ingredients = pr.ingredients.map { IngredientParser.parse(it) },
                steps = pr.steps,
                nutritionPerServing = withDetails(pr)
            )
        }

        val validMeals = payload.meals.mapNotNull { pm ->
            payload.recipes.getOrNull(pm.recipeIndex) ?: return@mapNotNull null
            val savedRecipe = recipes.getOrNull(pm.recipeIndex) ?: return@mapNotNull null
            ValidMeal(
                recipe = savedRecipe,
                date = pm.date,
                meal = mealSlotToMeal(pm.mealSlot),
                servings = pm.servings
            )
        }

        val routines = payload.workouts.map { pw ->
            Routine(
                name = pw.name,
                exercises = pw.exercises.map { pe -> toRoutineExercise(pe) }
            )
        }

        val validSessions = payload.sessions.mapNotNull { ps ->
            val workout = payload.workouts.getOrNull(ps.workoutIndex) ?: return@mapNotNull null
            val routine = routines.getOrNull(ps.workoutIndex) ?: return@mapNotNull null
            ValidSession(routine = routine, workoutName = workout.name, date = ps.date)
        }

        // --- Pass 2: everything above built successfully — now write it all. ---

        val recipeRepo = RecipeRepository.get(context)
        val routineRepo = RoutineRepository.get(context)
        val sessionRepo = ScheduledSessionRepository.get(context)

        recipes.forEach { recipeRepo.addRecipe(it) }
        validMeals.forEach { vm ->
            recipeRepo.plan(recipe = vm.recipe, date = vm.date, meal = vm.meal, servings = vm.servings)
        }
        routines.forEach { routineRepo.save(it) }
        // An each-side exercise turns on "Left and right separately" for that
        // lift when the plan is accepted, if it is not on already. The lifter
        // can turn it back off; that choice is theirs from then on. A set that
        // only names a side changes nothing here: the session offers L and R
        // for it without touching the preference.
        val settings = SettingsStore.get(context)
        routines.flatMap { it.exercises }.filter { it.eachSide }.forEach {
            settings.setLogsPerSide(LoggedExercise(name = it.name, equipment = it.equipment).matchKey, true)
        }
        validSessions.forEach { vs ->
            sessionRepo.add(ScheduledSession(routineId = vs.routine.id, routineName = vs.workoutName, date = vs.date))
        }
        importedStore.add(hash)

        return PlanImportResult.Imported(
            recipeCount = recipes.size,
            mealCount = validMeals.size,
            routineCount = routines.size,
            sessionCount = validSessions.size
        )
    }

    /**
     * The recipe's macros with the plan's `ux` -- saturated fat, sugar and
     * sodium per serving -- on them. The kit already folds `ux` in when `u`
     * came too; this keeps any value it did not. A plan that sent `ux` with no
     * `u` has nowhere to put it: a recipe's nutrition cannot exist without
     * calories, and inventing zeros for them would log a zero-calorie meal.
     */
    internal fun withDetails(pr: PlanRecipe): RecipeNutrition? {
        val nutrition = pr.nutritionPerServing ?: return null
        val ux = pr.nutrientDetailsPerServing ?: return nutrition
        return nutrition.copy(
            saturatedFatG = nutrition.saturatedFatG ?: ux.saturatedFatG,
            sugarG = nutrition.sugarG ?: ux.sugarG,
            sodiumMg = nutrition.sodiumMg ?: ux.sodiumMg
        )
    }

    private fun mealSlotToMeal(slot: Int): Meal = when (slot) {
        0 -> Meal.BREAKFAST
        1 -> Meal.LUNCH
        3 -> Meal.SNACK
        else -> Meal.DINNER
    }

    /**
     * Reduces a possibly-ramping set list to Android's single-target shape — see plan Global Constraints.
     *
     * Deliberately uses most-common (not [WorkoutSession.toRoutine]'s `maxOrNull()`-for-weight) for
     * every field: this is a *prescription*, not a record of a workout actually performed, so it
     * should reflect what the coach literally wrote as a set rather than synthesizing an untested
     * weight/rep combination from the extremes.
     */
    private fun toRoutineExercise(pe: PlanWorkoutExercise): RoutineExercise {
        fun <T> mostCommon(values: List<T?>): T? =
            values.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

        return RoutineExercise(
            name = pe.name,
            equipment = pe.equipment,
            note = pe.note,
            targetSets = pe.sets.size.coerceAtLeast(1),
            targetReps = mostCommon(pe.sets.map { it.reps }),
            targetWeightLb = mostCommon(pe.sets.map { it.weightLb }),
            targetRpe = mostCommon(pe.sets.map { it.rpe }),
            targetDurationSec = mostCommon(pe.sets.map { it.durationSec }),
            targetDistanceMeters = mostCommon(pe.sets.map { it.distanceMeters }),
            // The targets above cannot say "each side" or "plus one on the
            // left", so a prescription with sides in it is also kept set by
            // set. One without them is exactly what it was.
            prescribed = pe.sets.map(::toPrescribedSet)
                .takeIf { PerSideLogging.prescribesSides(it, pe.eachSide) },
            eachSide = pe.eachSide
        )
    }

    private fun toPrescribedSet(set: PlanSet) = PrescribedSet(
        weightLb = set.weightLb,
        reps = set.reps,
        rpe = set.rpe,
        durationSec = set.durationSec,
        distanceMeters = set.distanceMeters,
        side = when (set.side) {
            ShareSide.LEFT -> SetSide.LEFT
            ShareSide.RIGHT -> SetSide.RIGHT
            null -> null
        }
    )

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

class ImportedPlanStore private constructor(context: Context) {
    private val file = File(context.applicationContext.filesDir, "imported_plans.json")

    suspend fun contains(hash: String): Boolean = withContext(Dispatchers.IO) {
        read().contains(hash)
    }

    suspend fun add(hash: String) = withContext(Dispatchers.IO) {
        val updated = read() + hash
        write(updated)
    }

    @Synchronized
    private fun write(hashes: Set<String>) {
        val temp = File(file.parentFile, "imported_plans.json.tmp")
        temp.writeText(org.json.JSONArray(hashes.toList()).toString())
        temp.renameTo(file)
    }

    private fun read(): Set<String> {
        if (!file.exists()) return emptySet()
        return try {
            val array = org.json.JSONArray(file.readText())
            (0 until array.length()).map { array.getString(it) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    companion object {
        @Volatile private var instance: ImportedPlanStore? = null
        fun get(context: Context): ImportedPlanStore =
            instance ?: synchronized(this) { instance ?: ImportedPlanStore(context).also { instance = it } }
    }
}
