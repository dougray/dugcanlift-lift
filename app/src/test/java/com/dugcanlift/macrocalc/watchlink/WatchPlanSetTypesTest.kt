package com.dugcanlift.macrocalc.watchlink

import com.dugcanlift.liftkit.link.LinkPayloads
import com.dugcanlift.liftkit.link.LogSide
import com.dugcanlift.liftkit.link.PlanSource
import com.dugcanlift.liftkit.link.SetKind
import com.dugcanlift.macrocalc.data.PrescribedSet
import com.dugcanlift.macrocalc.data.Routine
import com.dugcanlift.macrocalc.data.RoutineExercise
import com.dugcanlift.macrocalc.data.SetKind as AppSetKind
import com.dugcanlift.macrocalc.data.SetSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a coach's set types look like by the time they reach a wrist (LIFT Link version 3).
 *
 * Before this the phone parsed them from a plan link, showed them, and sent the watch none of them:
 * a superset reached the wrist as two unrelated exercises and a drop set as an ordinary set.
 */
class WatchPlanSetTypesTest {

    private fun plan(vararg exercises: RoutineExercise) =
        WatchPlanMapper.plan(Routine(id = "r1", name = "Upper B", exercises = exercises.toList()),
            "2026-11-02", PlanSource.COACH_PLAN, emptyList(), 1)

    @Test fun `a coach's superset reaches the watch as a group`() {
        val exercises = plan(
            RoutineExercise(name = "Dumbbell Bench Press", equipment = "Dumbbell", targetSets = 3, targetReps = 10, group = 1),
            RoutineExercise(name = "Seated Cable Row", equipment = "Cable", targetSets = 3, targetReps = 12, group = 1),
            RoutineExercise(name = "Barbell Curl", targetSets = 2, targetReps = 8),
        ).exercises
        assertEquals(listOf(1, 1, null), exercises.map { it.group })
    }

    @Test fun `a coach's drop and rest-pause sets ride on their own rows, with their sides`() {
        val exercise = plan(
            RoutineExercise(
                name = "Triceps Pushdown", targetSets = 4, targetReps = 10,
                prescribed = listOf(
                    PrescribedSet(weightLb = 70.0, reps = 12),
                    PrescribedSet(weightLb = 50.0, reps = 10, kind = AppSetKind.DROP),
                    PrescribedSet(reps = 10, side = SetSide.LEFT, kind = AppSetKind.DROP),
                    PrescribedSet(weightLb = 50.0, reps = 4, kind = AppSetKind.REST_PAUSE),
                ),
            )
        ).exercises.single()
        assertEquals(listOf(null, SetKind.DROP, SetKind.DROP, SetKind.REST_PAUSE), exercise.sets.map { it.kind })
        assertEquals(LogSide.LEFT, exercise.sets[2].side)
        // Blank stays blank beside a kind.
        assertNull(exercise.sets[2].weightKg)
    }

    @Test fun `a routine of your own sends its kinds by position`() {
        val exercise = plan(
            RoutineExercise(name = "Curl", targetSets = 3, targetReps = 8, targetWeightLb = 65.0)
                .withSetKind(2, AppSetKind.REST_PAUSE)
        ).exercises.single()
        assertEquals(listOf(null, null, SetKind.REST_PAUSE), exercise.sets.map { it.kind })
        assertEquals(65.0 * WatchPlanMapper.KG_PER_LB, exercise.sets[2].weightKg!!, 1e-9)
    }

    @Test fun `set types survive the wire, and a version-2 watch gets the plan without them`() {
        val sent = plan(
            RoutineExercise(name = "Bench", targetSets = 2, targetReps = 10, group = 1),
            RoutineExercise(name = "Row", targetSets = 2, targetReps = 12, group = 1),
            RoutineExercise(name = "Curl", targetSets = 2, targetReps = 8).withSetKind(1, AppSetKind.DROP),
        )
        assertTrue(sent.prescribesSetTypes)
        assertEquals(sent, LinkPayloads.decodePlan(LinkPayloads.encodePlan(sent)))

        val stripped = sent.withoutSetTypes()
        assertFalse(stripped.prescribesSetTypes)
        assertEquals(listOf("Bench", "Row", "Curl"), stripped.exercises.map { it.name })
        assertEquals(8, stripped.exercises[2].sets[1].reps)
    }

    @Test fun `a routine with no set types is the bytes it was before set types`() {
        val plain = plan(
            RoutineExercise(name = "Bench", targetSets = 3, targetReps = 5, targetWeightLb = 185.0),
            RoutineExercise(name = "Row", targetSets = 3, targetReps = 8),
        )
        assertFalse(plain.prescribesSetTypes)
        assertEquals(
            LinkPayloads.encodePlan(plain).toList(),
            LinkPayloads.encodePlan(plain.withoutSetTypes()).toList(),
        )
    }
}
