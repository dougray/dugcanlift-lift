package com.dugcanlift.macrocalc.watchlink

import com.dugcanlift.liftkit.link.LinkPayloads
import com.dugcanlift.liftkit.link.LogSide
import com.dugcanlift.liftkit.link.PlanSource
import com.dugcanlift.macrocalc.data.PrescribedSet
import com.dugcanlift.macrocalc.data.Routine
import com.dugcanlift.macrocalc.data.RoutineExercise
import com.dugcanlift.macrocalc.data.SetSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a coach's sides look like by the time they reach a wrist.
 *
 * Before LIFT Link version 2 they did not reach one at all: [WatchPlanMapper] read only the flattened
 * `target*` fields, so "3 x 8 each side" arrived as three ordinary sets with no sides — half the work
 * asked for, and no mention of the limbs. A missing side is silent, which is why it lasted.
 *
 * A ramp was flattened the same way, and that is fixed by the same change: a routine that prescribes
 * set by set now sends its rows as they are.
 */
class WatchPlanSidesTest {

    private fun routine(vararg exercises: RoutineExercise) =
        Routine(id = "r1", name = "Legs", exercises = exercises.toList())

    private fun plan(vararg exercises: RoutineExercise) =
        WatchPlanMapper.plan(routine(*exercises), "2026-09-25", PlanSource.ROUTINE, emptyList(), 1)

    // ---- each side ----------------------------------------------------------------------------

    @Test fun `an each-side routine reaches the watch saying so`() {
        val exercise = plan(
            RoutineExercise(
                name = "Split Squat", targetSets = 3, targetReps = 8,
                prescribed = List(3) { PrescribedSet(reps = 8) },
                eachSide = true,
            )
        ).exercises.single()
        assertTrue(exercise.eachSide)
        assertEquals(3, exercise.sets.size)
        // Three rows, six sets to perform. The watch does that expansion; the wire carries the rows.
        assertTrue(exercise.sets.all { it.side == null })
    }

    @Test fun `a named side travels as that side and nothing else changes`() {
        val exercise = plan(
            RoutineExercise(
                name = "Split Squat", targetSets = 3, targetReps = 8,
                prescribed = listOf(
                    PrescribedSet(reps = 8),
                    PrescribedSet(reps = 8, side = SetSide.LEFT),
                    PrescribedSet(reps = 8, side = SetSide.RIGHT),
                ),
                eachSide = true,
            )
        ).exercises.single()
        assertNull(exercise.sets[0].side)
        assertEquals(LogSide.LEFT, exercise.sets[1].side)
        assertEquals(LogSide.RIGHT, exercise.sets[2].side)
    }

    @Test fun `a routine that says nothing about sides says nothing about sides`() {
        val exercise = plan(
            RoutineExercise(name = "Bench Press", targetSets = 4, targetReps = 6)
        ).exercises.single()
        assertFalse(exercise.eachSide)
        assertTrue(exercise.sets.all { it.side == null })
        assertEquals(4, exercise.sets.size)
    }

    // ---- set by set, which was flattened before ------------------------------------------------

    @Test fun `a ramp reaches the watch as a ramp rather than as one number three times`() {
        val exercise = plan(
            RoutineExercise(
                name = "Squat", targetSets = 3, targetWeightLb = 135.0, targetReps = 5,
                prescribed = listOf(
                    PrescribedSet(weightLb = 135.0, reps = 5),
                    PrescribedSet(weightLb = 155.0, reps = 5),
                    PrescribedSet(weightLb = 175.0, reps = 3),
                ),
            )
        ).exercises.single()
        assertEquals(
            listOf(135.0, 155.0, 175.0),
            exercise.sets.map { Math.round(WatchPlanMapper.kgToLb(it.weightKg!!)).toDouble() },
        )
        assertEquals(listOf(5, 5, 3), exercise.sets.map { it.reps })
    }

    @Test fun `a prescribed row in pounds still reaches the wire in kilograms`() {
        val set = plan(
            RoutineExercise(
                name = "Bench Press", targetSets = 1,
                prescribed = listOf(PrescribedSet(weightLb = 185.0, reps = 5)),
            )
        ).exercises.single().sets.single()
        assertEquals(83.91458845, set.weightKg!!, 1e-6)
    }

    @Test fun `a row prescribing reps and no weight sends no weight`() {
        // Doug's own routine. A zero here would be a lift of nothing on a wrist.
        val set = plan(
            RoutineExercise(
                name = "Bench Press", targetSets = 4,
                prescribed = List(4) { PrescribedSet(reps = 6) },
            )
        ).exercises.single().sets.first()
        assertNull(set.weightKg)
        assertEquals(6, set.reps)
    }

    @Test fun `an out-of-range row is refused field by field rather than refusing the plan`() {
        val set = plan(
            RoutineExercise(
                name = "Squat", targetSets = 1,
                prescribed = listOf(PrescribedSet(weightLb = -5.0, reps = 0, rpe = 11.0)),
            )
        ).exercises.single().sets.single()
        assertNull(set.weightKg)
        assertNull(set.reps)
        assertNull(set.rpe)
        // And it still encodes, which a reps of 0 or an RPE of 11 would not.
        LinkPayloads.encodePlan(
            plan(
                RoutineExercise(
                    name = "Squat", targetSets = 1,
                    prescribed = listOf(PrescribedSet(weightLb = -5.0, reps = 0, rpe = 11.0)),
                )
            )
        )
    }

    @Test fun `a conditioning row with only time and distance reaches the watch as an empty set`() {
        // The wire's prescription carries neither, which is what it already did when every row was
        // flattened. Named here so it is a known gap rather than a surprise.
        val set = plan(
            RoutineExercise(
                name = "Row", targetSets = 1,
                prescribed = listOf(PrescribedSet(durationSec = 600, distanceMeters = 1600.0)),
            )
        ).exercises.single().sets.single()
        assertNull(set.weightKg)
        assertNull(set.reps)
        assertNull(set.rpe)
    }

    @Test fun `an empty set-by-set list falls back to the flattened targets`() {
        val exercise = plan(
            RoutineExercise(name = "Row", targetSets = 3, targetReps = 10, prescribed = emptyList())
        ).exercises.single()
        assertEquals(3, exercise.sets.size)
        assertEquals(10, exercise.sets.first().reps)
    }

    // ---- the bytes ------------------------------------------------------------------------------

    @Test fun `a routine with no sides encodes to exactly what it encoded to before`() {
        val plain = plan(RoutineExercise(name = "Bench Press", targetSets = 4, targetReps = 6))
        assertFalse(plain.prescribesSides)
        assertEquals(
            LinkPayloads.encodePlan(plain).toList(),
            LinkPayloads.encodePlan(plain.withoutSides()).toList(),
        )
    }

    @Test fun `a sided plan survives the real codec`() {
        val sided = plan(
            RoutineExercise(
                name = "Split Squat", targetSets = 2, targetReps = 8,
                prescribed = listOf(PrescribedSet(reps = 8), PrescribedSet(reps = 8, side = SetSide.LEFT)),
                eachSide = true,
            )
        )
        assertTrue(sided.prescribesSides)
        assertEquals(sided, LinkPayloads.decodePlan(LinkPayloads.encodePlan(sided)))
    }
}
