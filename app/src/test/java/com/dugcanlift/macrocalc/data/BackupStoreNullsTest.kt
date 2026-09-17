package com.dugcanlift.macrocalc.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * JSON `null` and non-finite numbers in a backup (coach/BACKUP-FORMAT.md:
 * unknown stays null, never zero).
 *
 * `fixtures/web-backup-nulls.json` is a trimmed LIFT-web-shaped demo backup of a
 * fictional lifter -- the file that crashed a restore. It was generated in LIFT
 * web's layout rather than saved by the web app, and carries `"amountGrams":
 * null` on every food and planned meal. The web app itself does write `null` for
 * a routine's unset targets, a recipe's `totalWeightGrams` and a meal's
 * `loggedFoodEntryId`, and `JSON.stringify` writes any NaN it holds as `null`.
 * Reading `amountGrams` with `has` + `optDouble` turned that null into NaN, and
 * the write straight after threw "Forbidden numeric value: NaN". Do not
 * regenerate it from this code.
 */
@RunWith(RobolectricTestRunner::class)
class BackupStoreNullsTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    // Process-wide singletons Robolectric's classloader reuses; see BackupStoreTest.
    @Before
    @After
    fun resetSingletons() {
        listOf(FoodRepository::class.java, WorkoutRepository::class.java,
               RoutineRepository::class.java, RecipeRepository::class.java,
               OutdoorActivityRepository::class.java,
               CoachStore::class.java, SettingsStore::class.java, GoalStore::class.java).forEach {
            val field = it.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        }
    }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    // MARK: - The file that crashed

    @Test
    fun `a LIFT-web-shaped backup with null amounts restores and saves again`() {
        val result = BackupStore.restore(context, fixture("web-backup-nulls.json"))
        assertTrue(result.problem, result.ok)
        // 3 foods, 1 workout, 2 routines, 1 outdoor, 2 recipes, 3 planned meals.
        assertEquals(12, result.added)

        val foods = FoodRepository.get(context).entries.value
        assertEquals(3, foods.size)
        foods.forEach { assertNull("amountGrams: null is unknown", it.amountGrams) }
        assertEquals(3.1, foods.first { it.name == "Greek yogurt with berries and granola" }.saturatedFatG!!, 1e-9)

        val plan = RecipeRepository.get(context).planForBackup()
        assertEquals(3, plan.size)
        plan.forEach {
            assertNull(it.amountGrams)
            assertNull(it.snapshotNutritionPerGram)
            assertNull(it.loggedFoodEntryId)
        }
        val oats = RecipeRepository.get(context).recipesForBackup().first { it.name == "Overnight oats with whey" }
        assertNull(oats.totalWeightGrams)
        assertNull(oats.cookMinutes)

        val conditioning = RoutineRepository.get(context).routines.value
            .flatMap { it.exercises }.first { it.name == "Rowing, Stationary" }
        assertNull(conditioning.targetReps)
        assertNull(conditioning.targetWeightLb)
        assertNull(conditioning.targetRpe)
        assertEquals(600, conditioning.targetDurationSec)

        // The crash was on the write after the read: saving must succeed and
        // must not turn an unknown into a number.
        val saved = JSONObject(BackupStore.build(context)).getJSONObject("data")
        val savedFood = saved.getJSONArray("food")
        (0 until savedFood.length()).forEach { assertFalse(savedFood.getJSONObject(it).has("amountGrams")) }
        val savedPlan = saved.getJSONArray("plan")
        (0 until savedPlan.length()).forEach { assertFalse(savedPlan.getJSONObject(it).has("amountGrams")) }
    }

    // MARK: - Every optional number, null and non-finite

    /**
     * [bad] goes wherever a file may leave a number unknown. JSON has no NaN
     * literal and Android's parser refuses a text that overflows to Infinity, so
     * a non-finite number reaches the reader as a string -- which `optDouble`
     * coerces to NaN or Infinity all the same.
     */
    private fun food(id: String, bad: String) = """
        {"id":"$id","name":"Oats","servings":$bad,"amountGrams":$bad,
         "calories":$bad,"proteinG":10,"fatG":$bad,"carbsG":50,"fiberG":$bad,
         "date":"2026-09-10","loggedAt":$bad,"meal":"BREAKFAST",
         "saturatedFatG":$bad,"sugarG":$bad,"sodiumMg":$bad}
    """.trimIndent()

    private fun nutrition(bad: String) =
        """{"calories":$bad,"proteinG":30,"carbsG":$bad,"fatG":10,"fiberG":$bad,
            "saturatedFatG":$bad,"sugarG":$bad,"sodiumMg":$bad,"estimated":false}"""

    private fun file(bad: String, suffix: String) = """
        {"v":1,"app":"lift","saved":"2026-09-17","data":{
          "food":[${food("f-$suffix", bad)}],
          "workouts":[{"id":"w-$suffix","date":"2026-09-10","name":"A","startedAt":$bad,
            "exercises":[{"id":"e-$suffix","name":"Squat","equipment":"Barbell","sets":[
              {"id":"s-$suffix","weightLb":$bad,"reps":$bad,"rpe":$bad,"durationSec":$bad,"distanceMeters":$bad}]}]}],
          "routines":[{"id":"r-$suffix","name":"R","createdAt":$bad,"exercises":[
            {"id":"re-$suffix","name":"Row","equipment":"","targetSets":$bad,"targetReps":$bad,
             "targetWeightLb":$bad,"targetRpe":$bad,"targetDurationSec":$bad,"targetDistanceMeters":$bad}]}],
          "recipes":[{"id":"c-$suffix","name":"Stew","servings":$bad,"totalWeightGrams":$bad,
            "ingredients":[{"rawText":"200 g beef","qty":$bad,"grams":$bad}],"steps":[],
            "nutritionPerServing":${nutrition(bad)},"prepMinutes":$bad,"cookMinutes":$bad,"importedAt":$bad}],
          "plan":[{"id":"p-$suffix","recipeId":"c-$suffix","date":"2026-09-17","meal":"DINNER",
            "servings":$bad,"amountGrams":$bad,"recipeName":"Stew",
            "snapshotNutrition":${nutrition(bad)},"snapshotNutritionPerGram":${nutrition(bad)}}],
          "outdoor":[{"id":"o-$suffix","activityType":"RUN","startedAtEpochMs":1784894700000,
            "endedAtEpochMs":1784896659640,"distanceMeters":$bad,"elevationGainMeters":$bad,
            "routePoints":[{"latitude":47.66,"longitude":-122.34,"altitudeMeters":$bad,
              "recordedAtEpochMs":$bad,"horizontalAccuracyMeters":$bad}]}],
          "goal":{"calories":$bad,"proteinG":150,"fatG":$bad,"carbsG":250,"fiberG":$bad},
          "profile":{"sex":"male","age":$bad,"heightIn":$bad},
          "weights":{"2026-09-10":$bad,"2026-09-11":201.4}
        }}
    """.trimIndent()

    private fun assertRestoresAsUnknown(bad: String) {
        val result = BackupStore.restore(context, file(bad, "x"))
        assertTrue(result.problem, result.ok)
        assertEquals(6, result.added)

        val entry = FoodRepository.get(context).entries.value.single()
        assertEquals(1.0, entry.servings, 0.0)
        assertNull(entry.amountGrams)
        assertEquals(0, entry.calories)
        assertNull(entry.saturatedFatG)
        assertNull(entry.sugarG)
        assertNull(entry.sodiumMg)

        val set = WorkoutRepository.get(context).sessions.value.single().exercises.single().sets.single()
        assertNull(set.weightLb); assertNull(set.reps); assertNull(set.rpe)
        assertNull(set.durationSec); assertNull(set.distanceMeters)

        val target = RoutineRepository.get(context).routines.value.single().exercises.single()
        assertEquals(3, target.targetSets)
        assertNull(target.targetReps); assertNull(target.targetWeightLb); assertNull(target.targetRpe)
        assertNull(target.targetDurationSec); assertNull(target.targetDistanceMeters)

        val recipe = RecipeRepository.get(context).recipesForBackup().single()
        assertEquals(1.0, recipe.servings, 0.0)
        assertNull(recipe.totalWeightGrams)
        assertNull(recipe.prepMinutes); assertNull(recipe.cookMinutes)
        val perServing = recipe.nutritionPerServing!!
        assertEquals(0.0, perServing.calories, 0.0)
        assertNull(perServing.saturatedFatG); assertNull(perServing.sugarG); assertNull(perServing.sodiumMg)

        val meal = RecipeRepository.get(context).planForBackup().single()
        assertEquals(1.0, meal.servings, 0.0)
        assertNull(meal.amountGrams)

        val run = OutdoorActivityRepository.get(context).activitiesForBackup().single()
        assertTrue(run.distanceMeters.isFinite())
        assertEquals(0.0, run.routePoints.single().altitudeMeters, 0.0)

        val coach = CoachStore.get(context)
        assertEquals(0.0, coach.profile!!.heightIn, 0.0)
        assertEquals("a null weigh-in is not a reading", mapOf("2026-09-11" to 201.4), coach.bodyweights())

        // Saving again must not throw, and must not write any non-finite value.
        val saved = BackupStore.build(context)
        assertFalse(saved.contains("NaN")); assertFalse(saved.contains("Infinity"))
        val food = JSONObject(saved).getJSONObject("data").getJSONArray("food").getJSONObject(0)
        listOf("amountGrams", "saturatedFatG", "sugarG", "sodiumMg").forEach { assertFalse(it, food.has(it)) }
    }

    @Test
    fun `null in every optional number restores as unknown`() = assertRestoresAsUnknown("null")

    @Test
    fun `NaN in every optional number restores as unknown`() = assertRestoresAsUnknown("\"NaN\"")

    @Test
    fun `infinity in every optional number restores as unknown`() = assertRestoresAsUnknown("\"-Infinity\"")
}
