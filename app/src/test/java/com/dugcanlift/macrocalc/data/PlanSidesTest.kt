package com.dugcanlift.macrocalc.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dugcanlift.kit.PlanDecodeResult
import com.dugcanlift.kit.PlanLinkCodec
import com.dugcanlift.kit.PlanPayload
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
 * A coach's per-side prescription (PLAN-FORMAT "Sides"), from the link to the
 * header count. A port of LIFT web's `lift/plan-sides.test.mjs`, case for case
 * where the two apps share a rule.
 *
 * `fixtures/web-plan-per-side.txt` is a link Coach web's own encoder wrote.
 * Never regenerate it from Kotlin.
 */
@RunWith(RobolectricTestRunner::class)
class PlanSidesTest {

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

    private fun fixture(): PlanPayload {
        val link = javaClass.classLoader!!.getResource("fixtures/web-plan-per-side.txt")!!.readText().trim()
        return (PlanLinkCodec.decode(link.substringAfter('#'), "a1b2c3d4") as PlanDecodeResult.Success).payload
    }

    private fun accepted(): Routine = runBlocking {
        PlanImporter.accept(fixture(), context)
        RoutineRepository.get(context).routines.value.single()
    }

    private fun started(): Map<String, LoggedExercise> =
        accepted().toSession("2026-09-28").exercises.associateBy { it.name }

    private val set = PrescribedSet(weightLb = 40.0, reps = 8)
    private val left = PrescribedSet(weightLb = 40.0, reps = 8, side = SetSide.LEFT)
    private val right = PrescribedSet(weightLb = 40.0, reps = 8, side = SetSide.RIGHT)
    private fun logged(vararg sides: SetSide?) = sides.map { WorkoutSet(weightLb = 40.0, reps = 8, side = it) }

    /* ---------- accepting the plan ---------- */

    @Test
    fun `the fixture is kept set by set where it says anything about sides`() {
        val e = accepted().exercises.associateBy { it.name }
        assertNull("a two-sided lift is exactly what it was", e.getValue("Back Squat").prescribed)
        assertFalse(e.getValue("Back Squat").eachSide)
        assertEquals(3, e.getValue("Back Squat").targetSets)

        val row = e.getValue("Single-Arm Dumbbell Row")
        assertTrue(row.eachSide)
        assertEquals(List(3) { PrescribedSet(weightLb = 30.0, reps = 8) }, row.prescribed)

        val split = e.getValue("Bulgarian Split Squat")
        assertTrue(split.eachSide)
        assertEquals(listOf(null, null, null, SetSide.LEFT), split.prescribed!!.map { it.side })
        assertEquals("Extra set on the left.", split.note)

        val bench = e.getValue("Dumbbell Bench Press")
        assertFalse(bench.eachSide)
        assertEquals(listOf(PrescribedSet(60.0, 8), PrescribedSet(60.0, 8), PrescribedSet(40.0, 10, side = SetSide.RIGHT)),
            bench.prescribed)

        assertEquals(listOf(PrescribedSet(durationSec = 600, distanceMeters = 1600.0, side = SetSide.LEFT)),
            e.getValue("Suitcase Carry").prescribed)
    }

    @Test
    fun `accepting an each-side exercise turns per-side logging on for that lift, and only that`() {
        accepted()
        val prefs = context.getSharedPreferences("dcl_settings", Context.MODE_PRIVATE)
        assertEquals(
            mapOf("per_side|single-arm dumbbell row|dumbbell" to true, "per_side|bulgarian split squat|dumbbell" to true),
            prefs.all.filterKeys { it.startsWith("per_side|") }
        )
        val stored = SettingsStore.get(context)
        assertTrue(stored.logsPerSide("single-arm dumbbell row|dumbbell"))
        assertTrue(stored.logsPerSide("bulgarian split squat|dumbbell"))
        // A named set does not touch the preference: nothing was stored for these.
        assertFalse(stored.logsPerSide("dumbbell bench press|dumbbell"))
        assertFalse(stored.logsPerSide("suitcase carry|kettlebells"))
        assertFalse(stored.logsPerSide("back squat|barbell"))
    }

    @Test
    fun `a lifter who turned per-side off keeps a named set's lift off`() {
        SettingsStore.get(context).setLogsPerSide("dumbbell bench press|dumbbell", false)
        accepted()
        assertFalse(SettingsStore.get(context).logsPerSide("dumbbell bench press|dumbbell"))
    }

    @Test
    fun `starting it logs sided sets as they are done, and copies two-sided ones as before`() {
        val logged = started()
        assertEquals(List(3) { listOf<Any?>(225.0, 5, 8.0) },
            logged.getValue("Back Squat").sets.map { listOf(it.weightLb, it.reps, it.rpe) })
        assertNull(logged.getValue("Back Squat").prescribed)
        assertEquals(0, logged.getValue("Single-Arm Dumbbell Row").sets.size)
        assertEquals(0, logged.getValue("Bulgarian Split Squat").sets.size)
        assertEquals("the two both-sides sets are copied; the right one waits",
            listOf(listOf<Any?>(60.0, 8, null), listOf<Any?>(60.0, 8, null)),
            logged.getValue("Dumbbell Bench Press").sets.map { listOf(it.weightLb, it.reps, it.side) })
        assertEquals(0, logged.getValue("Suitcase Carry").sets.size)
    }

    /* ---------- targets and the side offered next ---------- */

    @Test
    fun `the header counts against the target - L 0 of 3, and L 4 of 3 when over`() {
        val logged = started()
        fun label(name: String) = PerSideLogging.targetsLabel(logged.getValue(name))
        assertEquals("L 0/3 · R 0/3", label("Single-Arm Dumbbell Row"))
        assertEquals("the seven-set case", "L 0/4 · R 0/3", label("Bulgarian Split Squat"))
        assertEquals("R 0/1 · 2/2 both", label("Dumbbell Bench Press"))
        assertEquals("L 0/1", label("Suitcase Carry"))
        assertNull("nothing about sides: the plain count stands", label("Back Squat"))

        val row = logged.getValue("Single-Arm Dumbbell Row").copy(
            sets = logged(SetSide.LEFT, SetSide.RIGHT, SetSide.LEFT, SetSide.RIGHT, SetSide.LEFT, SetSide.RIGHT, SetSide.LEFT))
        assertEquals("over is shown, not capped", "L 4/3 · R 3/3", PerSideLogging.targetsLabel(row))
    }

    @Test
    fun `the seven-set case expects four left and three right`() {
        assertEquals(PerSideLogging.Targets(left = 4, right = 3, both = 0),
            PerSideLogging.prescribedTargets(listOf(set, set, set, left), eachSide = true))
        assertEquals(PerSideLogging.Targets(left = 0, right = 1, both = 2),
            PerSideLogging.prescribedTargets(listOf(set, set, right), eachSide = false))
    }

    @Test
    fun `the offer starts on the side the next unfilled prescribed set names`() {
        val seven = listOf(set, set, set, left)
        fun next(vararg sides: SetSide?) = PerSideLogging.nextPrescribedSide(seven, true, logged(*sides))
        val l = SetSide.LEFT
        val r = SetSide.RIGHT
        assertEquals(l, next())
        assertEquals(r, next(l))
        assertEquals("three lefts first leave the rights", r, next(l, l, l))
        assertEquals("the extra left set", l, next(l, r, l, r, l, r))
        assertNull("filled: back to whichever is behind", next(l, r, l, r, l, r, l))
        // A named right set on a two-sided lift: logged both-sides sets fill nothing.
        val bench = listOf(set, set, right)
        assertEquals(r, PerSideLogging.nextPrescribedSide(bench, false, logged(null, null)))
        assertNull(PerSideLogging.nextPrescribedSide(bench, false, logged(null, null, r)))
        assertNull("no prescription, no suggestion", PerSideLogging.nextPrescribedSide(null, false, emptyList()))
    }

    @Test
    fun `the form starts on the prescribed side, and past it on whichever is behind`() {
        val split = LoggedExercise(name = "Bulgarian Split Squat", prescribed = listOf(set, set, set, left), eachSide = true)
        assertEquals(SetSide.LEFT, PerSideLogging.startingSide(split))
        assertEquals(SetSide.RIGHT, PerSideLogging.startingSide(split.copy(sets = logged(SetSide.LEFT, SetSide.LEFT))))
        val filled = split.copy(sets = logged(SetSide.LEFT, SetSide.RIGHT, SetSide.LEFT, SetSide.RIGHT, SetSide.LEFT, SetSide.RIGHT, SetSide.LEFT))
        assertEquals("filled: the side that is behind", SetSide.RIGHT, PerSideLogging.startingSide(filled))
    }

    @Test
    fun `a new set on a side prefills from the prescribed set it answers`() {
        val seven = listOf(PrescribedSet(40.0, 8), PrescribedSet(40.0, 8), PrescribedSet(45.0, 6),
            PrescribedSet(30.0, 12, side = SetSide.LEFT))
        val l = SetSide.LEFT
        val r = SetSide.RIGHT
        assertEquals(seven[0], PerSideLogging.prescribedSetFor(seven, true, emptyList(), l))
        assertEquals(seven[2], PerSideLogging.prescribedSetFor(seven, true, logged(l, r, l, r), l))
        assertEquals(seven[3], PerSideLogging.prescribedSetFor(seven, true, logged(l, r, l, r, l, r), l))
        assertNull("right is used up", PerSideLogging.prescribedSetFor(seven, true, logged(l, r, l, r, l, r), r))
        assertNull(PerSideLogging.prescribedSetFor(seven, true, emptyList(), null))
    }

    @Test
    fun `a named set on a lift not logged per side offers L and R until it is logged`() {
        val bench = started().getValue("Dumbbell Bench Press")
        assertTrue(PerSideLogging.pendingNamedSide(bench, logsPerSide = false))
        assertFalse("per side already: the ordinary control", PerSideLogging.pendingNamedSide(bench, logsPerSide = true))
        val done = bench.copy(sets = bench.sets + WorkoutSet(weightLb = 40.0, reps = 10, side = SetSide.RIGHT))
        assertFalse("logged: back to a two-sided lift", PerSideLogging.pendingNamedSide(done, logsPerSide = false))
        assertFalse(PerSideLogging.pendingNamedSide(started().getValue("Back Squat"), logsPerSide = false))
    }

    @Test
    fun `a prescription without sides changes nothing`() {
        assertFalse(PerSideLogging.prescribesSides(listOf(PrescribedSet(225.0, 5)), false))
        assertNull(PerSideLogging.targetsLabel(listOf(PrescribedSet(225.0, 5)), false, emptyList()))
    }

    @Test
    fun `flags are masked, never compared - 2, 4, 3, 5, 6`() = runBlocking {
        val tuples = listOf(2, 4, 3, 5, 6, 0, 1).joinToString(",") { "[30,8,null,null,null,$it]" }
        val json = """{"v":1,"t":"plan","l":"x","w":[{"n":"x","e":[{"n":"Row","s":[$tuples,[30,8]]}]}]}"""
        val fragment = "1u" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        PlanImporter.accept((PlanLinkCodec.decode(fragment, "x") as PlanDecodeResult.Success).payload, context)
        val sets = RoutineRepository.get(context).routines.value.single().exercises.single().prescribed!!
        assertEquals(listOf(SetSide.LEFT, SetSide.RIGHT, SetSide.LEFT, SetSide.RIGHT, null, null, null, null), sets.map { it.side })
        sets.forEach { assertEquals(30.0, it.weightLb); assertEquals(8, it.reps) }
    }

    /* ---------- stored, and through a backup ---------- */

    private val cases = mapOf(
        "both" to (listOf(PrescribedSet(185.0, 5)) to false),
        "left" to (listOf(PrescribedSet(30.0, 8, side = SetSide.LEFT)) to false),
        "right" to (listOf(PrescribedSet(35.0, 8, side = SetSide.RIGHT)) to false),
        "each side" to (listOf(set, set, set) to true),
        "the combination" to (listOf(set, set, set, left) to true)
    )

    @Test
    fun `round trip through the stored json - both, left, right, each side and the combination`() {
        cases.forEach { (name, case) ->
            val (prescribed, each) = case
            val routine = RoutineExercise(name = "Row", prescribed = prescribed, eachSide = each)
            val back = routineExerciseFromJson(JSONObject(routine.toJson().toString()))
            assertEquals(name, prescribed, back.prescribed)
            assertEquals(name, each, back.eachSide)
            val loggedEx = LoggedExercise(name = "Row", prescribed = prescribed, eachSide = each)
            val again = loggedExerciseFromJson(JSONObject(loggedEx.toJson().toString()))
            assertEquals(name, loggedEx, again)
        }
    }

    @Test
    fun `an exercise with no prescription writes the object it always did`() {
        val routine = RoutineExercise(id = "r", name = "Squat", targetSets = 3, targetReps = 5).toJson()
        assertEquals(setOf("id", "name", "equipment", "targetSets", "targetReps", "note"), routine.keys().asSequence().toSet())
        val loggedEx = LoggedExercise(id = "e", name = "Squat").toJson()
        assertEquals(setOf("id", "name", "equipment", "note", "sets"), loggedEx.keys().asSequence().toSet())
        // eachSide is written only as true, and a both-sides prescribed set names no side.
        val each = LoggedExercise(name = "Row", prescribed = listOf(set), eachSide = true).toJson()
        assertEquals(true, each.get("eachSide"))
        assertFalse(each.getJSONArray("prescribed").getJSONObject(0).has("side"))
        assertEquals("left", LoggedExercise(name = "Row", prescribed = listOf(left)).toJson()
            .getJSONArray("prescribed").getJSONObject(0).getString("side"))
    }

    @Test
    fun `an unknown side or a non-true eachSide reads as today's meaning`() {
        val o = JSONObject("""{"name":"Row","sets":[],"eachSide":"yes","prescribed":[{"weightLb":30,"reps":8,"side":"sideways"},7]}""")
        val back = loggedExerciseFromJson(o)
        assertFalse(back.eachSide)
        assertEquals(listOf(PrescribedSet(30.0, 8)), back.prescribed)
    }

    @Test
    fun `a started session's prescription survives a backup and its restore`() = runBlocking {
        val session = accepted().toSession("2026-09-28")
        WorkoutRepository.get(context).save(session)
        val file = BackupStore.build(context)
        WorkoutRepository.get(context).clear()
        assertTrue(BackupStore.restore(context, file).ok)
        val restored = WorkoutRepository.get(context).sessions.value.single().exercises.associateBy { it.name }
        assertEquals("L 0/4 · R 0/3", PerSideLogging.targetsLabel(restored.getValue("Bulgarian Split Squat")))
        assertEquals("R 0/1 · 2/2 both", PerSideLogging.targetsLabel(restored.getValue("Dumbbell Bench Press")))
        assertEquals(session.exercises, WorkoutRepository.get(context).sessions.value.single().exercises)
    }
}
