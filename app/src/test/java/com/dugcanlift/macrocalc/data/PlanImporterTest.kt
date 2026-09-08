package com.dugcanlift.macrocalc.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlanImporterTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /**
     * These repositories are process-wide singletons cached in a companion
     * `instance` field, by design (one file per app run). Robolectric reuses
     * its instrumented classloader — and therefore these singletons' cached
     * `instance` — across test methods in this class, even though each test
     * gets a fresh [ApplicationProvider] context with its own temp
     * filesDir. Left alone, test two would silently observe test one's
     * state (or vice versa, depending on run order). Clearing the cached
     * instance before each test is what makes these tests order-independent.
     */
    @Before
    fun resetSingletons() {
        resetInstance(RecipeRepository::class.java)
        resetInstance(RoutineRepository::class.java)
        resetInstance(ScheduledSessionRepository::class.java)
        resetInstance(ImportedPlanStore::class.java)
    }

    private fun resetInstance(clazz: Class<*>) {
        val field = clazz.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    private fun samplePayload() = PlanPayload(
        coachName = "Doug",
        recipes = listOf(PlanRecipe(name = "Beef Chilli", servings = 4.0)),
        meals = listOf(PlanMeal(date = "2026-09-10", mealSlot = 2, recipeIndex = 0, servings = 2.0)),
        workouts = listOf(PlanWorkout(name = "Lower A", exercises = listOf(
            PlanWorkoutExercise(name = "Back Squat", sets = listOf(
                PlanSet(weightLb = 225.0, reps = 5, rpe = 8.0),
                PlanSet(weightLb = 225.0, reps = 5, rpe = 8.0),
                PlanSet(weightLb = 245.0, reps = 3, rpe = 9.0)
            ))
        ))),
        sessions = listOf(PlanSession(date = "2026-09-10", workoutIndex = 0)),
        rawJson = """{"v":1,"t":"plan","l":"x","n":"Doug","test":"unique-payload-1"}"""
    )

    @Test
    fun `accept imports every record and reduces the ramp to a most-common target`() = runTest {
        val result = PlanImporter.accept(samplePayload(), context)
        assertTrue(result is PlanImportResult.Imported)
        val imported = result as PlanImportResult.Imported
        assertEquals(1, imported.recipeCount)
        assertEquals(1, imported.mealCount)
        assertEquals(1, imported.routineCount)
        assertEquals(1, imported.sessionCount)

        val routine = RoutineRepository.get(context).routines.value.first()
        val exercise = routine.exercises.first()
        assertEquals(3, exercise.targetSets)
        assertEquals(225.0, exercise.targetWeightLb) // most common of 225, 225, 245
        assertEquals(5, exercise.targetReps)
    }

    @Test
    fun `re-accepting the same payload is a no-op`() = runTest {
        val payload = samplePayload()
        PlanImporter.accept(payload, context)
        val second = PlanImporter.accept(payload, context)
        assertEquals(PlanImportResult.AlreadyImported, second)
        assertEquals(1, RoutineRepository.get(context).routines.value.size)
        assertEquals(1, RecipeRepository.get(context).recipes.value.size)
        assertEquals(1, RecipeRepository.get(context).plan.value.size)
        assertEquals(1, ScheduledSessionRepository.get(context).sessions.value.size)
    }

    @Test
    fun `mostCommon breaks a genuine tie by first occurrence`() = runTest {
        // Four sets, two distinct weights each appearing exactly twice: 100 first, then 110,
        // then 100 again, then 110 again — a genuine tie (2 vs 2). Per the plan's global
        // constraint, ties are broken by first occurrence, so the expected target is 100.
        val payload = PlanPayload(
            coachName = "Doug",
            recipes = emptyList(),
            meals = emptyList(),
            workouts = listOf(
                PlanWorkout(
                    name = "Tie Day",
                    exercises = listOf(
                        PlanWorkoutExercise(
                            name = "Bench Press",
                            sets = listOf(
                                PlanSet(weightLb = 100.0, reps = 5),
                                PlanSet(weightLb = 110.0, reps = 5),
                                PlanSet(weightLb = 100.0, reps = 5),
                                PlanSet(weightLb = 110.0, reps = 5)
                            )
                        )
                    )
                )
            ),
            sessions = emptyList(),
            rawJson = """{"v":1,"t":"plan","l":"x","n":"Doug","test":"tie-payload-1"}"""
        )

        val result = PlanImporter.accept(payload, context)
        assertTrue(result is PlanImportResult.Imported)

        val routine = RoutineRepository.get(context).routines.value.first()
        val exercise = routine.exercises.first()
        assertEquals(4, exercise.targetSets)
        assertEquals(100.0, exercise.targetWeightLb) // tie between 100 and 110 (2 each) -> first occurrence wins
    }
}
