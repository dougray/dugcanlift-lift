package com.dugcanlift.macrocalc.data

import com.dugcanlift.kit.IngredientParser
import com.dugcanlift.kit.PlanDecodeResult
import com.dugcanlift.kit.PlanLinkCodec
import com.dugcanlift.kit.PlanPayload
import com.dugcanlift.kit.PlanRecipe
import com.dugcanlift.kit.PlanWorkoutExercise
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Old decoder degradation: what a LIFT Android build that has never heard of
 * PLAN-FORMAT's `rf` does with a plan carrying road picks.
 *
 * `fixtures/web-plan-road-picks.txt` is a link **Coach web's own encoder
 * wrote** (`dugcanlift-coach/coach/fixtures/`, the same bytes, md5
 * 5c2f783c291f9c757e86a75a014510e6). Never regenerate it from Kotlin: its
 * whole value is that a different implementation wrote it.
 *
 * **First run on 2026-09-23 against main itself** -- commit 0453a0d, the
 * pinned kit's `PlanLinkCodec` and main's own `PlanImporter.accept`, with
 * nothing in this repo changed -- through Robolectric, writing to real
 * repositories. It recorded:
 *
 *     decode   = Success
 *     accept   = Imported(recipeCount=1, mealCount=2, routineCount=1, sessionCount=1)
 *     summarize= "1 recipe, 2 meals, 1 workout scheduled across 1 day"
 *     recipes.json / meal_plan.json / routines.json / scheduled_sessions.json
 *              contained none of the six `rf` ids, and no "rf" key
 *
 * Those are the assertions below. `rf` is purely additive, so `v` stays 1 and
 * an old build reads the meals and the training exactly as before and simply
 * never looks at the key -- which is the promise this file exists to keep.
 *
 * The decode is still the shipped kit's ([PlanLinkCodec] is pinned to a release
 * tag and reads no `rf` at all -- [PlanPayload] has no field for one). The app
 * side of main's import is frozen below, verbatim in what it reads, because
 * `PlanImporter` itself now stores picks. [PlanRoadPicksTest] is what this
 * build does with the same link.
 */
class PlanRoadPicksOldDecoderTest {

    private fun payload(): PlanPayload {
        val link = javaClass.classLoader!!.getResource("fixtures/web-plan-road-picks.txt")!!.readText().trim()
        val decoded = PlanLinkCodec.decode(link.substringAfter('#'), expectedLifterId = "a1b2c3d4")
        assertTrue("main accepts the link", decoded is PlanDecodeResult.Success)
        return (decoded as PlanDecodeResult.Success).payload
    }

    /* ---------------- main's app-side mapping, frozen ---------------- */

    /** main's `PlanImporter.withDetails`, unchanged. */
    private fun oldWithDetails(pr: PlanRecipe) = pr.nutritionPerServing?.let { n ->
        val ux = pr.nutrientDetailsPerServing ?: return@let n
        n.copy(
            saturatedFatG = n.saturatedFatG ?: ux.saturatedFatG,
            sugarG = n.sugarG ?: ux.sugarG,
            sodiumMg = n.sodiumMg ?: ux.sodiumMg
        )
    }

    /** main's recipe half of `accept`: name, servings, parsed ingredients, steps, macros. */
    private fun oldRecipes(payload: PlanPayload) = payload.recipes.map { pr ->
        Recipe(
            name = pr.name,
            servings = pr.servings,
            ingredients = pr.ingredients.map { IngredientParser.parse(it) },
            steps = pr.steps,
            nutritionPerServing = oldWithDetails(pr)
        )
    }

    /** main's `mealSlotToMeal` and its meal half: a meal whose `x` misses is dropped. */
    private fun oldMeals(payload: PlanPayload): List<Triple<String, Meal, Double>> =
        payload.meals.mapNotNull { pm ->
            payload.recipes.getOrNull(pm.recipeIndex) ?: return@mapNotNull null
            val meal = when (pm.mealSlot) {
                0 -> Meal.BREAKFAST
                1 -> Meal.LUNCH
                3 -> Meal.SNACK
                else -> Meal.DINNER
            }
            Triple(pm.date, meal, pm.servings)
        }

    /** main's `PlanImporter.summarize`, before picks were named in it. */
    private fun oldSummarize(payload: PlanPayload): String {
        fun plural(count: Int, noun: String) = "$count $noun${if (count == 1) "" else "s"}"
        val parts = mutableListOf<String>()
        if (payload.recipes.isNotEmpty()) parts += plural(payload.recipes.size, "recipe")
        if (payload.meals.isNotEmpty()) parts += plural(payload.meals.size, "meal")
        if (payload.workouts.isNotEmpty()) {
            val dayCount = payload.sessions.map { it.date }.distinct().size
            parts += if (dayCount > 0)
                "${plural(payload.workouts.size, "workout")} scheduled across ${plural(dayCount, "day")}"
            else plural(payload.workouts.size, "workout")
        } else if (payload.sessions.isNotEmpty()) {
            parts += "sessions scheduled across ${plural(payload.sessions.map { it.date }.distinct().size, "day")}"
        }
        return if (parts.isEmpty()) "Nothing to import" else parts.joinToString(", ")
    }

    /** main's `toRoutineExercise`, reduced to what a reader can see of it. */
    private fun oldExercise(pe: PlanWorkoutExercise): List<Any?> {
        fun <T> mostCommon(values: List<T?>): T? =
            values.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return listOf(
            pe.name, pe.equipment, pe.sets.size.coerceAtLeast(1),
            mostCommon(pe.sets.map { it.reps }), mostCommon(pe.sets.map { it.weightLb }), pe.eachSide
        )
    }

    /* ---------------- what it does with the fixture ---------------- */

    @Test
    fun `a build that never heard of rf reads the link and takes the rest in unchanged`() {
        val payload = payload()
        val raw = JSONObject(payload.rawJson)
        assertEquals("no version bump, so an old build does not refuse the link", 1, raw.getInt("v"))
        assertEquals("the fixture does carry picks", 6, raw.getJSONArray("rf").length())
        assertEquals("Doug", payload.coachName)

        val recipes = oldRecipes(payload)
        assertEquals(listOf("Beef Chilli" to 4.0), recipes.map { it.name to it.servings })
        assertEquals(
            listOf(438.0, 36.0, 31.0, 19.0, 9.0, 620.0),
            recipes[0].nutritionPerServing!!.let {
                listOf(it.calories, it.proteinG, it.carbsG, it.fatG, it.fiberG, it.sodiumMg)
            }
        )
        assertEquals(3, recipes[0].ingredients.size)

        assertEquals(
            listOf(
                Triple("2026-09-28", Meal.DINNER, 2.0),
                Triple("2026-09-29", Meal.LUNCH, 1.0)
            ),
            oldMeals(payload)
        )

        assertEquals(1, payload.workouts.size)
        assertEquals(
            listOf(
                listOf<Any?>("Back Squat", "Barbell", 3, 5, 225.0, false),
                listOf<Any?>("Bulgarian Split Squat", "Dumbbell", 2, 8, 40.0, true)
            ),
            payload.workouts[0].exercises.map(::oldExercise)
        )
        assertEquals(listOf("2026-09-28" to 0), payload.sessions.map { it.date to it.workoutIndex })

        assertEquals("1 recipe, 2 meals, 1 workout scheduled across 1 day", oldSummarize(payload))
    }

    @Test
    fun `nothing about a pick reaches anything main stores`() {
        val payload = payload()
        // Everything main's import path can see of the plan, as text. The kit's
        // PlanPayload has no field for `rf`, so the ids exist only in rawJson,
        // which main hashes for "already imported" and never reads a key out of.
        val modelled = listOf(
            oldRecipes(payload).toString(),
            oldMeals(payload).toString(),
            payload.workouts.flatMap { it.exercises }.map(::oldExercise).toString(),
            payload.sessions.toString(),
            oldSummarize(payload)
        ).joinToString("\n")

        JSONObject(payload.rawJson).getJSONArray("rf").let { rf ->
            (0 until rf.length()).map { rf.getString(it) }
        }.forEach { id -> assertFalse(id, modelled.contains(id)) }
        assertFalse(modelled.contains("wendys"))
        assertFalse(modelled.contains("chickfila"))

        assertFalse(
            "the shipped decoder models no picks at all",
            PlanPayload::class.java.declaredFields.any { it.name.lowercase().contains("pick") }
        )
    }
}
