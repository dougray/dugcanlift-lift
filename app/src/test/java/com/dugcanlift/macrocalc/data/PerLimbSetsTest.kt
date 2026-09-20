package com.dugcanlift.macrocalc.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
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
 * A set's side, through the JSON this app stores and the backup file it writes.
 *
 * BACKUP-FORMAT spells it as a named field, `side: "left" | "right"`, omitted
 * when both — named rather than packed into a number because a backup is read
 * by a human and by three platforms, one of which has never heard of it.
 *
 * `fixtures/backup-main-no-sides.json` was written by `BackupStore.build` on
 * main, before any of this existed. It is the whole point of the "loads
 * unchanged" test and must not be regenerated from this branch.
 */
@RunWith(RobolectricTestRunner::class)
class PerLimbSetsTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun resetSingletons() {
        listOf(FoodRepository::class.java, WorkoutRepository::class.java,
               RoutineRepository::class.java, RecipeRepository::class.java,
               CoachStore::class.java, SettingsStore::class.java, GoalStore::class.java).forEach {
            val field = it.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        }
    }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    /* ---------- the stored shape ---------- */

    @Test
    fun `a side round trips through the stored json`() {
        listOf(SetSide.LEFT, SetSide.RIGHT, null).forEach { side ->
            val set = WorkoutSet(weightLb = 60.0, reps = 8, side = side)
            assertEquals(side, workoutSetFromJson(JSONObject(set.toJson().toString())).side)
        }
    }

    @Test
    fun `both is the absence of the field, not a spelling of it`() {
        val json = WorkoutSet(weightLb = 60.0, reps = 8).toJson()
        assertFalse("a both-sided set writes no side at all", json.has("side"))
        assertEquals("left", WorkoutSet(weightLb = 60.0, reps = 8, side = SetSide.LEFT).toJson().getString("side"))
        assertEquals("right", WorkoutSet(weightLb = 60.0, reps = 8, side = SetSide.RIGHT).toJson().getString("side"))
    }

    @Test
    fun `an absent, null or unknown side reads as both`() {
        assertNull(workoutSetFromJson(JSONObject("""{"weightLb":60,"reps":8}""")).side)
        assertNull(workoutSetFromJson(JSONObject("""{"weightLb":60,"reps":8,"side":null}""")).side)
        assertNull(workoutSetFromJson(JSONObject("""{"weightLb":60,"reps":8,"side":""}""")).side)
        // A side a future format might add is not a side this build can claim.
        assertNull(workoutSetFromJson(JSONObject("""{"weightLb":60,"reps":8,"side":"both"}""")).side)
        assertNull(workoutSetFromJson(JSONObject("""{"weightLb":60,"reps":8,"side":"alternating"}""")).side)
    }

    @Test
    fun `a side is not a set on its own`() {
        assertTrue(WorkoutSet(side = SetSide.LEFT).isEmpty)
    }

    /* ---------- the file main wrote ---------- */

    @Test
    fun `a backup written by main restores unchanged, every set on both sides`() {
        val result = BackupStore.restore(context, fixture("backup-main-no-sides.json"))
        assertTrue(result.problem ?: "restore failed", result.ok)

        val session = WorkoutRepository.get(context).sessions.value.single()
        assertEquals("session-main-1", session.id)
        assertEquals("2026-09-18", session.date)
        assertEquals("Legs", session.name)
        assertEquals(1_789_000_000_000L, session.startedAt)
        assertEquals(2, session.exercises.size)

        val split = session.exercises[0]
        assertEquals("Bulgarian Split Squat", split.name)
        assertEquals("Dumbbell", split.equipment)
        assertEquals("slow eccentric", split.note)
        assertEquals(listOf(60.0, 60.0, 55.0), split.sets.map { it.weightLb })
        assertEquals(listOf(8, 8, 8), split.sets.map { it.reps })
        assertEquals(8.0, split.sets[0].rpe!!, 1e-9)
        // The name looks unilateral, and it changes nothing: no set written
        // before sides existed is guessed at.
        assertTrue(PerSideLogging.looksUnilateral(split.name))
        assertTrue(split.sets.all { it.side == null })
        assertFalse(split.hasPerSideSets)

        val squat = session.exercises[1]
        assertEquals("Back Squat", squat.name)
        assertEquals(225.0, squat.sets.single().weightLb!!, 1e-9)
        assertNull(squat.sets.single().side)
    }

    @Test
    fun `left and right survive a backup and its restore`() = runBlocking {
        WorkoutRepository.get(context).save(
            WorkoutSession(
                id = "s1", date = "2026-09-19", name = "Legs", startedAt = 1_789_100_000_000L,
                exercises = listOf(LoggedExercise(
                    id = "e1", name = "Bulgarian Split Squat", equipment = "Dumbbell",
                    sets = listOf(
                        WorkoutSet(id = "a", weightLb = 60.0, reps = 8, side = SetSide.LEFT),
                        WorkoutSet(id = "b", weightLb = 55.0, reps = 8, side = SetSide.RIGHT),
                        WorkoutSet(id = "c", weightLb = 60.0, reps = 8)
                    )))
            )
        )
        val file = BackupStore.build(context)
        // Named, and only where there is a side to name.
        val sets = JSONObject(file).getJSONObject("data").getJSONArray("workouts")
            .getJSONObject(0).getJSONArray("exercises").getJSONObject(0).getJSONArray("sets")
        assertEquals("left", sets.getJSONObject(0).getString("side"))
        assertEquals("right", sets.getJSONObject(1).getString("side"))
        assertFalse(sets.getJSONObject(2).has("side"))

        WorkoutRepository.get(context).clear()
        assertTrue(BackupStore.restore(context, file).ok)
        val restored = WorkoutRepository.get(context).sessions.value.single().exercises.single()
        assertEquals(listOf(SetSide.LEFT, SetSide.RIGHT, null), restored.sets.map { it.side })
        assertEquals(1, restored.setCount(SetSide.LEFT))
        assertEquals(1, restored.setCount(SetSide.RIGHT))
        assertEquals(1, restored.setCount(null))
    }

    /* ---------- the per-exercise preference ---------- */

    @Test
    fun `a name that looks unilateral pre-ticks the toggle, and the choice wins after that`() {
        val settings = SettingsStore.get(context)
        val split = "bulgarian split squat|dumbbell"
        val bench = "bench press|barbell"

        assertTrue(settings.logsPerSide(split))
        assertFalse(settings.logsPerSide(bench))

        // Turned off for something the guess ticked: the answer sticks.
        settings.setLogsPerSide(split, false)
        assertFalse(settings.logsPerSide(split))

        // And on for something it did not.
        settings.setLogsPerSide(bench, true)
        assertTrue(settings.logsPerSide(bench))

        // Kept per exercise, keyed like the dictionary: equipment is part of it.
        assertFalse(settings.logsPerSide("bench press|dumbbell"))
    }
}
