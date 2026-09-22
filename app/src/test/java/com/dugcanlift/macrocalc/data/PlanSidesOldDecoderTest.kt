package com.dugcanlift.macrocalc.data

import androidx.test.core.app.ApplicationProvider
import com.dugcanlift.kit.PlanDecodeResult
import com.dugcanlift.kit.PlanLinkCodec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Old decoder degradation: what a LIFT Android build that has never heard of
 * PLAN-FORMAT's `b` or a sixth set position does with a plan carrying both.
 *
 * `fixtures/web-plan-per-side.txt` is a link Coach web's own encoder wrote
 * (dugcanlift-coach `coach/fixtures/`, the same bytes). Never regenerate it
 * from Kotlin: its value is that a different implementation wrote it.
 *
 * Run on 2026-09-22 against main as it stood (kit 1.5.0's `PlanLinkCodec`,
 * main's `PlanImporter` and `Routine.toSession`), before anything here
 * changed: the plan is accepted, `b` and the sixth position are ignored, and
 * every exercise arrives as ordinary two-sided sets with the right count.
 */
@RunWith(RobolectricTestRunner::class)
class PlanSidesOldDecoderTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun resetSingletons() {
        listOf(RecipeRepository::class.java, RoutineRepository::class.java,
            ScheduledSessionRepository::class.java, ImportedPlanStore::class.java).forEach {
            val field = it.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        }
    }

    private fun fixtureFragment(): String {
        val link = javaClass.classLoader!!.getResource("fixtures/web-plan-per-side.txt")!!.readText().trim()
        return link.substringAfter('#')
    }

    @Test
    fun `the per-side fixture is accepted as two-sided sets with the right counts`() = runTest {
        val decoded = PlanLinkCodec.decode(fixtureFragment(), expectedLifterId = "a1b2c3d4")
        assertTrue("accepted, not refused: $decoded", decoded is PlanDecodeResult.Success)
        val payload = (decoded as PlanDecodeResult.Success).payload
        assertEquals(listOf(3, 3, 4, 3, 1), payload.workouts.single().exercises.map { it.sets.size })

        PlanImporter.accept(payload, context)
        val routine = RoutineRepository.get(context).routines.value.single()
        val session = routine.toSession("2026-09-28")
        val logged = session.exercises.associateBy { it.name }

        fun rows(name: String) = logged.getValue(name).sets.map {
            listOf(it.weightLb, it.reps, it.rpe, it.durationSec, it.distanceMeters)
        }
        assertEquals(List(3) { listOf(225.0, 5, 8.0, null, null) }, rows("Back Squat"))
        assertEquals(List(3) { listOf(30.0, 8, null, null, null) }, rows("Single-Arm Dumbbell Row"))
        assertEquals(List(4) { listOf(40.0, 8, null, null, null) }, rows("Bulgarian Split Squat"))
        // main collapses a ramp to its most common set (PlanImporter.toRoutineExercise), so the
        // 40 x 10 third set reads as a third 60 x 8. That is main's rule for every plan, sided or
        // not; the count is right.
        assertEquals(List(3) { listOf(60.0, 8, null, null, null) }, rows("Dumbbell Bench Press"))
        assertEquals(listOf(listOf<Any?>(null, null, null, 600, 1600.0)), rows("Suitcase Carry"))
        // No side leaks through.
        session.exercises.forEach { e -> e.sets.forEach { assertEquals(null, it.side) } }
    }
}
