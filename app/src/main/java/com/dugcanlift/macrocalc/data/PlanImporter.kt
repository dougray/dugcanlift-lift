package com.dugcanlift.macrocalc.data

import android.content.Context
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
        if (payload.recipes.isNotEmpty()) parts += "${payload.recipes.size} recipe${if (payload.recipes.size == 1) "" else "s"}"
        if (payload.workouts.isNotEmpty()) {
            val dayCount = payload.sessions.map { it.date }.distinct().size
            parts += if (dayCount > 0)
                "${payload.workouts.size} workout${if (payload.workouts.size == 1) "" else "s"} scheduled for $dayCount day${if (dayCount == 1) "" else "s"} this week"
            else
                "${payload.workouts.size} workout${if (payload.workouts.size == 1) "" else "s"}"
        }
        return if (parts.isEmpty()) "Nothing to import" else parts.joinToString(", ")
    }

    suspend fun accept(payload: PlanPayload, context: Context): PlanImportResult {
        val hash = sha256(payload.rawJson)
        val importedStore = ImportedPlanStore.get(context)
        if (importedStore.contains(hash)) return PlanImportResult.AlreadyImported

        // Build every record first — no partial accept if something here throws.
        val recipes = payload.recipes.map { pr ->
            Recipe(
                name = pr.name,
                servings = pr.servings,
                ingredients = pr.ingredients.map { RecipeIngredient(rawText = it) },
                steps = pr.steps,
                nutritionPerServing = pr.nutritionPerServing
            )
        }

        val recipeRepo = RecipeRepository.get(context)
        val routineRepo = RoutineRepository.get(context)
        val sessionRepo = ScheduledSessionRepository.get(context)

        recipes.forEach { recipeRepo.addRecipe(it) }

        var mealCount = 0
        payload.meals.forEach { pm ->
            payload.recipes.getOrNull(pm.recipeIndex) ?: return@forEach
            val savedRecipe = recipes.getOrNull(pm.recipeIndex) ?: return@forEach
            recipeRepo.plan(
                recipe = savedRecipe,
                date = pm.date,
                meal = mealSlotToMeal(pm.mealSlot),
                servings = pm.servings
            )
            mealCount++
        }

        val routines = payload.workouts.map { pw ->
            Routine(
                name = pw.name,
                exercises = pw.exercises.map { pe -> toRoutineExercise(pe) }
            )
        }
        routines.forEach { routineRepo.save(it) }

        var sessionCount = 0
        payload.sessions.forEach { ps ->
            val workout = payload.workouts.getOrNull(ps.workoutIndex) ?: return@forEach
            val routine = routines.getOrNull(ps.workoutIndex) ?: return@forEach
            sessionRepo.add(ScheduledSession(routineId = routine.id, routineName = workout.name, date = ps.date))
            sessionCount++
        }

        importedStore.add(hash)

        return PlanImportResult.Imported(
            recipeCount = recipes.size,
            mealCount = mealCount,
            routineCount = routines.size,
            sessionCount = sessionCount
        )
    }

    private fun mealSlotToMeal(slot: Int): Meal = when (slot) {
        0 -> Meal.BREAKFAST
        1 -> Meal.LUNCH
        3 -> Meal.SNACK
        else -> Meal.DINNER
    }

    /** Reduces a possibly-ramping set list to Android's single-target shape — see plan Global Constraints. */
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
            targetDistanceMeters = mostCommon(pe.sets.map { it.distanceMeters })
        )
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

class ImportedPlanStore private constructor(context: Context) {
    private val file = File(context.applicationContext.filesDir, "imported_plans.json")

    fun contains(hash: String): Boolean = read().contains(hash)

    fun add(hash: String) {
        val updated = read() + hash
        val temp = File(file.parentFile, "imported_plans.json.tmp")
        temp.writeText(org.json.JSONArray(updated.toList()).toString())
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
