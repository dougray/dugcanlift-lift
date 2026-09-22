package com.dugcanlift.macrocalc.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dugcanlift.kit.PlanDecodeResult
import com.dugcanlift.kit.PlanLinkCodec
import com.dugcanlift.kit.PlanPayload
import com.dugcanlift.kit.PlanSession
import com.dugcanlift.kit.PlanSet
import com.dugcanlift.kit.PlanWorkout
import com.dugcanlift.kit.PlanWorkoutExercise
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
 * Every prescribed set survives import as itself.
 *
 * PLAN-FORMAT: "Sets are listed individually rather than as '3 x 5'. Coaches
 * ramp, and a count-and-tuple shape cannot say 225/225/245 without special
 * cases." This is that rule, end to end: the link, the routine as stored, and
 * the set rows a session lays out.
 *
 * Every field of a set is optional, so blank must stay blank here too -- a
 * weight the coach did not give must never be filled in from the set beside it.
 */
@RunWith(RobolectricTestRunner::class)
class PlanSetFidelityTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun resetSingletons() {
        listOf(RecipeRepository::class.java, RoutineRepository::class.java, WorkoutRepository::class.java,
            ScheduledSessionRepository::class.java, ImportedPlanStore::class.java, SettingsStore::class.java).forEach {
            val field = it.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        }
    }

    private var payloadCounter = 0

    private fun planOf(vararg exercises: PlanWorkoutExercise): PlanPayload {
        payloadCounter += 1
        return PlanPayload(
            coachName = "Doug",
            recipes = emptyList(),
            meals = emptyList(),
            workouts = listOf(PlanWorkout(name = "Lower A", exercises = exercises.toList())),
            sessions = listOf(PlanSession(date = "2026-09-28", workoutIndex = 0)),
            rawJson = """{"v":1,"t":"plan","l":"x","n":"Doug","test":"fidelity-$payloadCounter"}"""
        )
    }

    private fun accept(payload: PlanPayload): Routine = runBlocking {
        PlanImporter.accept(payload, context)
        RoutineRepository.get(context).routines.value.last()
    }

    /** weight, reps, rpe, seconds, metres, side -- the whole of a set, in PLAN-FORMAT's order. */
    private fun shapeOf(set: WorkoutSet) =
        listOf(set.weightLb, set.reps, set.rpe, set.durationSec, set.distanceMeters, set.side)

    private fun shapeOf(set: PrescribedSet) =
        listOf(set.weightLb, set.reps, set.rpe, set.durationSec, set.distanceMeters, set.side)

    /* ---------- a plain ramp, no sides anywhere ---------- */

    private val ramp = PlanWorkoutExercise(
        name = "Back Squat", equipment = "Barbell",
        sets = listOf(
            PlanSet(weightLb = 60.0, reps = 8),
            PlanSet(weightLb = 60.0, reps = 8),
            PlanSet(weightLb = 70.0, reps = 6)
        )
    )

    @Test
    fun `a ramp is stored set by set, not as one set and a count`() {
        val exercise = accept(planOf(ramp)).exercises.single()
        assertEquals(
            listOf(
                listOf<Any?>(60.0, 8, null, null, null, null),
                listOf<Any?>(60.0, 8, null, null, null, null),
                listOf<Any?>(70.0, 6, null, null, null, null)
            ),
            exercise.prescribed?.map(::shapeOf)
        )
        assertEquals("the flattened targets stay, for every reader that has only ever had them",
            3, exercise.targetSets)
    }

    @Test
    fun `starting a ramp lays out 60, 60, 70 -- not three 60s`() {
        val logged = accept(planOf(ramp)).toSession("2026-09-28").exercises.single()
        assertEquals(
            listOf(
                listOf<Any?>(60.0, 8, null, null, null, null),
                listOf<Any?>(60.0, 8, null, null, null, null),
                listOf<Any?>(70.0, 6, null, null, null, null)
            ),
            logged.sets.map(::shapeOf)
        )
    }

    @Test
    fun `a weight the coach left blank is never filled in from the set beside it`() {
        // "[null, 5] is 'five reps, you pick the weight'". Flattening took the
        // one weight that was given and handed it to both sets.
        val exercise = accept(planOf(PlanWorkoutExercise(
            name = "Front Squat", equipment = "Barbell",
            sets = listOf(PlanSet(reps = 5), PlanSet(weightLb = 225.0, reps = 5))
        ))).exercises.single()
        assertEquals(listOf(null, 225.0), exercise.prescribed?.map { it.weightLb })

        val sets = accept(planOf(PlanWorkoutExercise(
            name = "Front Squat", equipment = "Barbell",
            sets = listOf(PlanSet(reps = 5), PlanSet(weightLb = 225.0, reps = 5))
        ))).toSession("2026-09-28").exercises.single().sets
        assertEquals(listOf(null, 225.0), sets.map { it.weightLb })
        assertEquals(listOf(5, 5), sets.map { it.reps })
    }

    @Test
    fun `rpe, duration and distance ramp too`() {
        val logged = accept(planOf(PlanWorkoutExercise(
            name = "Row erg", equipment = "Erg",
            sets = listOf(
                PlanSet(durationSec = 600, distanceMeters = 1600.0),
                PlanSet(durationSec = 300, distanceMeters = 1000.0)
            )
        ))).toSession("2026-09-28").exercises.single()
        assertEquals(listOf(600, 300), logged.sets.map { it.durationSec })
        assertEquals(listOf(1600.0, 1000.0), logged.sets.map { it.distanceMeters })

        val rpes = accept(planOf(PlanWorkoutExercise(
            name = "Deadlift", equipment = "Barbell",
            sets = listOf(PlanSet(weightLb = 315.0, reps = 3, rpe = 7.0),
                PlanSet(weightLb = 315.0, reps = 3, rpe = 8.0),
                PlanSet(weightLb = 315.0, reps = 3, rpe = 9.0))
        ))).toSession("2026-09-28").exercises.single().sets.map { it.rpe }
        assertEquals(listOf(7.0, 8.0, 9.0), rpes)
    }

    /* ---------- the real fixture, whose bench press ramps and names a side ---------- */

    private fun fixture(): PlanPayload {
        val link = javaClass.classLoader!!.getResource("fixtures/web-plan-per-side.txt")!!.readText().trim()
        return (PlanLinkCodec.decode(link.substringAfter('#'), "a1b2c3d4") as PlanDecodeResult.Success).payload
    }

    @Test
    fun `the fixture's bench press keeps 60x8, 60x8 and the right-side 40x10`() {
        val routine = accept(fixture())
        val bench = routine.exercises.single { it.name == "Dumbbell Bench Press" }
        assertEquals(
            listOf(
                listOf<Any?>(60.0, 8, null, null, null, null),
                listOf<Any?>(60.0, 8, null, null, null, null),
                listOf<Any?>(40.0, 10, null, null, null, SetSide.RIGHT)
            ),
            bench.prescribed?.map(::shapeOf)
        )
        val logged = routine.toSession("2026-09-28").exercises.single { it.name == "Dumbbell Bench Press" }
        assertEquals("the two both-sides sets are rows; the right one is logged when it is done",
            listOf(listOf<Any?>(60.0, 8, null, null, null, null), listOf<Any?>(60.0, 8, null, null, null, null)),
            logged.sets.map(::shapeOf))
        assertEquals("R 0/1 · 2/2 both", PerSideLogging.targetsLabel(logged))
    }

    @Test
    fun `the fixture's sided exercises are untouched by this`() {
        val e = accept(fixture()).exercises.associateBy { it.name }
        assertTrue(e.getValue("Single-Arm Dumbbell Row").eachSide)
        assertEquals(listOf(null, null, null, SetSide.LEFT), e.getValue("Bulgarian Split Squat").prescribed!!.map { it.side })
        assertEquals(listOf(listOf<Any?>(null, null, null, 600, 1600.0, SetSide.LEFT)),
            e.getValue("Suitcase Carry").prescribed?.map(::shapeOf))
    }

    /* ---------- nothing changes for a prescription the targets can say ---------- */

    @Test
    fun `a uniform prescription is stored and written exactly as it was`() {
        val exercise = accept(planOf(PlanWorkoutExercise(
            name = "Back Squat", equipment = "Barbell",
            sets = List(3) { PlanSet(weightLb = 225.0, reps = 5, rpe = 8.0) }
        ))).exercises.single()
        assertNull("nothing the targets cannot say", exercise.prescribed)
        assertFalse(exercise.eachSide)
        assertEquals(3, exercise.targetSets)
        assertEquals(225.0, exercise.targetWeightLb)
        assertEquals(5, exercise.targetReps)
        assertEquals(8.0, exercise.targetRpe)
        assertEquals("no `prescribed` key: byte for byte the object main wrote",
            setOf("id", "name", "equipment", "targetSets", "targetReps", "targetWeightLb", "targetRpe", "note"),
            exercise.toJson().keys().asSequence().toSet())
    }

    @Test
    fun `an exercise with no sets at all still starts with one blank row`() {
        val exercise = accept(planOf(PlanWorkoutExercise(name = "Farmer Carry", equipment = "Dumbbell")))
            .exercises.single()
        assertNull(exercise.prescribed)
        assertEquals(1, exercise.targetSets)
        assertEquals(1, Routine(name = "x", exercises = listOf(exercise)).toSession("2026-09-28")
            .exercises.single().sets.size)
    }

    /* ---------- files older builds wrote ---------- */

    @Test
    fun `a routine written before any of this loads and starts from its targets`() {
        // routines.json as LIFT 1.11 wrote it: targets and a count, no `prescribed`.
        val legacy = JSONObject("""
            {"id":"r1","name":"Lower A","folder":"","createdAt":1,"exercises":[
              {"id":"e1","name":"Back Squat","equipment":"Barbell","targetSets":3,
               "targetReps":5,"targetWeightLb":225.0,"targetRpe":8.0,"note":"Belt on."}]}
        """.trimIndent())
        val routine = routineFromJson(legacy)
        val exercise = routine.exercises.single()
        assertNull(exercise.prescribed)
        assertFalse(exercise.eachSide)
        assertEquals(3, exercise.targetSets)

        val logged = routine.toSession("2026-09-28").exercises.single()
        assertEquals(List(3) { listOf<Any?>(225.0, 5, 8.0, null, null, null) }, logged.sets.map(::shapeOf))
        assertNull(logged.prescribed)
        assertEquals("Belt on.", logged.note)
    }

    @Test
    fun `a stored ramp round-trips through the routine file`() {
        val exercise = accept(planOf(ramp)).exercises.single()
        val back = routineExerciseFromJson(JSONObject(exercise.toJson().toString()))
        assertEquals(exercise.prescribed, back.prescribed)
        assertEquals(exercise, back)
    }

    /* ---------- the rule itself ---------- */

    @Test
    fun `only a prescription the targets cannot say is kept set by set`() {
        val plain = PrescribedSet(weightLb = 60.0, reps = 8)
        val heavier = PrescribedSet(weightLb = 70.0, reps = 6)
        val blankWeight = PrescribedSet(reps = 8)
        val left = PrescribedSet(weightLb = 60.0, reps = 8, side = SetSide.LEFT)

        assertFalse("three of the same set is what '3 sets @ 60 x 8' means",
            Prescription.needsSetBySet(List(3) { plain }, eachSide = false))
        assertFalse("nothing prescribed at all", Prescription.needsSetBySet(emptyList(), eachSide = false))
        assertTrue("a ramp", Prescription.needsSetBySet(listOf(plain, plain, heavier), eachSide = false))
        assertTrue("one set says 'you pick the weight'",
            Prescription.needsSetBySet(listOf(blankWeight, plain), eachSide = false))
        assertTrue("each side", Prescription.needsSetBySet(List(3) { plain }, eachSide = true))
        assertTrue("a named side", Prescription.needsSetBySet(listOf(plain, plain, left), eachSide = false))
        assertTrue("every set on the left is still not what the targets say",
            Prescription.needsSetBySet(List(3) { left }, eachSide = false))
    }

    @Test
    fun `a routine exercise summarises a ramp as its sets, not as one of them`() {
        val ramped = accept(planOf(ramp)).exercises.single()
        assertEquals("60 x 8, 60 x 8, 70 x 6", ramped.summary)

        val uniform = RoutineExercise(name = "Back Squat", targetSets = 3, targetReps = 8, targetWeightLb = 185.0)
        assertEquals("unchanged where there is no prescription", "3 sets x 8 @ 185 lb", uniform.summary)

        val eachSide = RoutineExercise(name = "Row", prescribed = List(3) { PrescribedSet(30.0, 8) }, eachSide = true)
        assertEquals("30 x 8, 30 x 8, 30 x 8, each side", eachSide.summary)
        assertEquals("a blank set is 'as written', never a zero",
            "as written", RoutineExercise(name = "Carry", prescribed = listOf(PrescribedSet())).summary)
    }
}
