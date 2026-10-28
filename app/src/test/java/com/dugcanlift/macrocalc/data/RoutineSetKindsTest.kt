package com.dugcanlift.macrocalc.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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
 * Drop sets and rest-pause on a routine of your own: [RoutineExercise.setKinds], by set position.
 *
 * A routine of your own is a set count, so its kinds are a list beside that count -- index 0 is set
 * 1 and always straight, trailing straights are trimmed, it never runs past [RoutineExercise.targetSets],
 * and it is absent when every set is straight. BACKUP-FORMAT spells it
 * `"setKinds": [null, "drop", "restPause"]`, the shape LIFT web writes.
 */
@RunWith(RobolectricTestRunner::class)
class RoutineSetKindsTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun resetSingletons() {
        listOf(RecipeRepository::class.java, RoutineRepository::class.java, WorkoutRepository::class.java,
            ScheduledSessionRepository::class.java, ImportedPlanStore::class.java, SettingsStore::class.java,
            FoodRepository::class.java, CoachStore::class.java, GoalStore::class.java).forEach {
            val field = it.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        }
    }

    private val bench = RoutineExercise(
        id = "bench", name = "Barbell Bench Press - Medium Grip", equipment = "Barbell",
        targetSets = 4, targetReps = 8, targetWeightLb = 185.0
    )

    /* ---------- the rule ---------- */

    @Test
    fun `kinds are stored by position, first straight, trailing straights trimmed, never past the count`() {
        assertNull(SetTypes.routineSetKinds(null, 4))
        assertNull(SetTypes.routineSetKinds(listOf(null, null, null), 4))
        // Set 1 has nothing before it to drop from.
        assertNull(SetTypes.routineSetKinds(listOf(SetKind.DROP), 4))
        assertEquals(listOf(null, SetKind.DROP), SetTypes.routineSetKinds(listOf(SetKind.DROP, SetKind.DROP, null, null), 4))
        assertEquals(listOf(null, null, SetKind.REST_PAUSE), SetTypes.routineSetKinds(listOf(null, null, SetKind.REST_PAUSE, null), 4))
        // Past the count is gone, and what is left is trimmed again.
        assertEquals(listOf(null, SetKind.DROP), SetTypes.routineSetKinds(listOf(null, SetKind.DROP, null, SetKind.DROP), 3))
        assertNull(SetTypes.routineSetKinds(listOf(null, SetKind.DROP), 1))
    }

    @Test
    fun `setting a kind on a set, and clearing it back to straight`() {
        val one = bench.withSetKind(2, SetKind.DROP)
        assertEquals(listOf(null, null, SetKind.DROP), one.setKinds)
        val two = one.withSetKind(3, SetKind.REST_PAUSE)
        assertEquals(listOf(null, null, SetKind.DROP, SetKind.REST_PAUSE), two.setKinds)
        assertEquals(listOf(null, null, SetKind.DROP), two.withSetKind(3, null).setKinds)
        assertNull(two.withSetKind(3, null).withSetKind(2, null).setKinds)
        assertEquals(SetKind.DROP, two.setKind(2))
        assertNull(two.setKind(0))
        assertNull(two.setKind(9))
    }

    @Test
    fun `the first set, a set past the count, and a coach's sets take no kind here`() {
        assertEquals(bench, bench.withSetKind(0, SetKind.DROP))
        assertEquals(bench, bench.withSetKind(4, SetKind.DROP))
        // A coach's sets carry their own kinds, on PrescribedSet; setKinds is never written beside them.
        val coach = bench.copy(prescribed = listOf(PrescribedSet(reps = 8), PrescribedSet(reps = 8, kind = SetKind.DROP)))
        assertEquals(coach, coach.withSetKind(1, SetKind.REST_PAUSE))
    }

    @Test
    fun `lowering the set count trims the kinds`() {
        val kinded = bench.withSetKind(2, SetKind.DROP).withSetKind(3, SetKind.REST_PAUSE)
        val fewer = kinded.withTargetSets(3)
        assertEquals(3, fewer.targetSets)
        assertEquals(listOf(null, null, SetKind.DROP), fewer.setKinds)
        assertNull(kinded.withTargetSets(2).setKinds)
        // And a list that outran its count in memory is still trimmed on the way to disk.
        val outran = kinded.copy(targetSets = 3)
        assertEquals(3, outran.toJson().getJSONArray("setKinds").length())
        assertNull(outran.setKind(3))
    }

    /* ---------- JSON / backup spelling ---------- */

    @Test
    fun `setKinds is written as BACKUP-FORMAT spells it, and read back`() {
        val kinded = bench.withSetKind(1, SetKind.DROP).withSetKind(2, SetKind.REST_PAUSE)
        val json = kinded.toJson()
        val array = json.getJSONArray("setKinds")
        assertEquals(3, array.length())
        assertTrue(array.isNull(0))
        assertEquals("drop", array.getString(1))
        assertEquals("restPause", array.getString(2))
        assertEquals(kinded, routineExerciseFromJson(json))
    }

    /** A frozen copy of main's `RoutineExercise.toJson`, before setKinds existed. */
    private fun mainToJson(e: RoutineExercise): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("name", e.name)
        put("equipment", e.equipment)
        put("targetSets", e.targetSets)
        e.targetReps?.let { put("targetReps", it) }
        e.targetWeightLb?.let { put("targetWeightLb", it) }
        e.targetRpe?.let { put("targetRpe", it) }
        e.targetDurationSec?.let { put("targetDurationSec", it) }
        e.targetDistanceMeters?.let { put("targetDistanceMeters", it) }
        put("note", e.note)
        putPrescription(e.prescribed, e.eachSide)
        e.group?.let { put("group", it) }
    }

    @Test
    fun `a routine with no kinds writes byte for byte what main wrote`() {
        val plain = listOf(
            bench,
            bench.copy(id = "row", name = "Seated Cable Rows", equipment = "Cable", group = 1, note = "slow"),
            RoutineExercise(id = "erg", name = "Row erg", targetSets = 1, targetDurationSec = 600, targetDistanceMeters = 1600.0),
            bench.copy(id = "coach", prescribed = listOf(PrescribedSet(weightLb = 60.0, reps = 8), PrescribedSet(weightLb = 70.0, reps = 6, kind = SetKind.DROP)))
        )
        plain.forEach { assertEquals(it.name, mainToJson(it).toString(), it.toJson().toString()) }
        // Every set set back to straight is no kinds at all, not a list of nulls.
        val cleared = bench.withSetKind(2, SetKind.DROP).withSetKind(2, null)
        assertEquals(mainToJson(bench).toString(), cleared.toJson().toString())
        assertFalse(cleared.toJson().has("setKinds"))
    }

    @Test
    fun `setKinds is never written beside a coach's sets`() {
        val coach = bench.copy(
            prescribed = listOf(PrescribedSet(reps = 8), PrescribedSet(reps = 8, kind = SetKind.DROP)),
            setKinds = listOf(null, SetKind.REST_PAUSE)
        )
        assertFalse(coach.toJson().has("setKinds"))
        val read = routineExerciseFromJson(JSONObject(coach.toJson().toString()).put("setKinds", JSONArray("[null, \"drop\"]")))
        assertNull(read.setKinds)
        assertEquals(SetKind.DROP, read.prescribed!![1].kind)
    }

    @Test
    fun `setKinds is read leniently`() {
        fun read(setKinds: Any?, targetSets: Int = 4): List<SetKind?>? =
            routineExerciseFromJson(JSONObject().put("id", "x").put("name", "X").put("targetSets", targetSets).put("setKinds", setKinds)).setKinds

        // Not an array: none.
        assertNull(read("drop"))
        assertNull(read(JSONObject().put("1", "drop")))
        assertNull(read(3))
        // An entry this build does not know is straight; case and space are forgiven.
        assertEquals(listOf(null, SetKind.DROP, null, SetKind.REST_PAUSE),
            read(JSONArray("""[null, "DROP", "superset", " restpause "]""")))
        assertNull(read(JSONArray("""[null, 2, true, {"kind":"drop"}]""")))
        // A kind on set 1 is dropped, and so is anything past the count.
        assertEquals(listOf(null, SetKind.DROP), read(JSONArray("""["drop", "drop"]""")))
        assertEquals(listOf(null, SetKind.DROP), read(JSONArray("""[null, "drop", null, "drop"]"""), targetSets = 3))
        // A routine written before setKinds existed.
        assertNull(routineExerciseFromJson(mainToJson(bench)).setKinds)
    }

    /* ---------- the summary and a started day ---------- */

    @Test
    fun `the summary names the kinded sets`() {
        assertEquals("4 sets x 8 @ 185 lb", bench.summary)
        val kinded = bench.withSetKind(2, SetKind.DROP).withSetKind(3, SetKind.REST_PAUSE)
        assertEquals("4 sets x 8 @ 185 lb · set 3 drop, set 4 rest-pause", kinded.summary)
    }

    @Test
    fun `starting the routine copies each kind onto its pre-filled set`() {
        val routine = Routine(name = "Push", exercises = listOf(bench.withSetKind(2, SetKind.DROP).withSetKind(3, SetKind.REST_PAUSE)))
        val sets = routine.toSession("2027-01-11").exercises.single().sets
        assertEquals(listOf(null, null, SetKind.DROP, SetKind.REST_PAUSE), sets.map { it.kind })
        assertEquals(List(4) { 185.0 }, sets.map { it.weightLb })
        assertEquals(listOf(null, null, SetKind.DROP, SetKind.REST_PAUSE), routine.exercises.single().askedSets.map { it.kind })
        // A routine without kinds starts exactly as it did.
        assertTrue(Routine(name = "Push", exercises = listOf(bench)).toSession("2027-01-11").exercises.single().sets.all { it.kind == null })
    }

    /* ---------- "Save as routine" ---------- */

    /** LIFT web's `fromSession`: a set's kind is kept by its position, as a superset already is. */
    @Test
    fun `saving a workout as a routine keeps each set's kind by position`() {
        val day = WorkoutSession(date = "2027-01-11", name = "Push", exercises = listOf(
            LoggedExercise(name = bench.name, equipment = bench.equipment, sets = listOf(
                WorkoutSet(weightLb = 185.0, reps = 8),
                WorkoutSet(weightLb = 185.0, reps = 8),
                WorkoutSet(weightLb = 135.0, reps = 10, kind = SetKind.DROP),
                WorkoutSet(weightLb = 135.0, reps = 4, kind = SetKind.REST_PAUSE),
                WorkoutSet(weightLb = 185.0, reps = 8)
            )),
            LoggedExercise(name = "Standing Military Press", equipment = "Barbell",
                sets = List(3) { WorkoutSet(weightLb = 95.0, reps = 10) })
        ))
        val saved = day.toRoutine("Again").exercises
        assertEquals(5, saved[0].targetSets)
        assertEquals(listOf(null, null, SetKind.DROP, SetKind.REST_PAUSE), saved[0].setKinds)
        assertNull(saved[1].setKinds)
        // And starting it again lays the kinds back out where they were.
        assertEquals(listOf(null, null, SetKind.DROP, SetKind.REST_PAUSE, null),
            Routine(name = "Again", exercises = saved).toSession("2027-01-18").exercises[0].sets.map { it.kind })
    }

    /** A side at a time, one position is two logged sets, so a position cannot carry a kind. */
    @Test
    fun `saving a workout as a routine keeps no kinds for a lift logged a side at a time`() {
        val day = WorkoutSession(date = "2027-01-11", name = "Pull", exercises = listOf(
            LoggedExercise(name = "Single-Arm Dumbbell Row", equipment = "Dumbbell", sets = listOf(
                WorkoutSet(weightLb = 60.0, reps = 10, side = SetSide.LEFT),
                WorkoutSet(weightLb = 60.0, reps = 10, side = SetSide.RIGHT),
                WorkoutSet(weightLb = 40.0, reps = 12, side = SetSide.LEFT, kind = SetKind.DROP),
                WorkoutSet(weightLb = 40.0, reps = 12, side = SetSide.RIGHT, kind = SetKind.DROP)
            ))
        ))
        assertNull(day.toRoutine("Again").exercises.single().setKinds)
    }

    /* ---------- storage and the backup ---------- */

    @Test
    fun `backup round trip - a routine's set kinds come back as they went`() = runBlocking {
        val routine = Routine(id = "r1", name = "Push", createdAt = 1L, exercises = listOf(
            bench.withSetKind(2, SetKind.DROP).withSetKind(3, SetKind.REST_PAUSE),
            RoutineExercise(id = "ohp", name = "Standing Military Press", equipment = "Barbell", targetSets = 3, targetReps = 10)
        ))
        RoutineRepository.get(context).save(routine)

        val file = BackupStore.build(context)
        val exercises = JSONObject(file).getJSONObject("data").getJSONArray("routines").getJSONObject(0).getJSONArray("exercises")
        assertEquals("""[null,null,"drop","restPause"]""", exercises.getJSONObject(0).getJSONArray("setKinds").toString())
        assertFalse(exercises.getJSONObject(1).has("setKinds"))

        RoutineRepository.get(context).delete("r1")
        assertTrue(BackupStore.restore(context, file).ok)
        assertEquals(routine, RoutineRepository.get(context).routines.value.single())
    }
}
