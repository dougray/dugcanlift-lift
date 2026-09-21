package com.dugcanlift.macrocalc.watchlink

import com.dugcanlift.liftkit.link.AckOutcome
import com.dugcanlift.liftkit.link.FinishedExercise
import com.dugcanlift.liftkit.link.FinishedSession
import com.dugcanlift.liftkit.link.LinkPayloads
import com.dugcanlift.liftkit.link.LogSide
import com.dugcanlift.liftkit.link.LoggedSet
import com.dugcanlift.liftkit.link.PlanSource
import com.dugcanlift.liftkit.link.PrescribedSet
import com.dugcanlift.macrocalc.data.LoggedExercise
import com.dugcanlift.macrocalc.data.Routine
import com.dugcanlift.macrocalc.data.RoutineExercise
import com.dugcanlift.macrocalc.data.SetSide
import com.dugcanlift.macrocalc.data.WorkoutSession
import com.dugcanlift.macrocalc.data.WorkoutSet
import com.dugcanlift.macrocalc.data.toJson
import com.dugcanlift.macrocalc.data.workoutSessionFromJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchPlanMapperTest {

    // ---- units: the 2.2x mistake ------------------------------------------------------------

    @Test
    fun `a target in pounds reaches the wire in kilograms`() {
        val plan = WatchPlanMapper.plan(routine(RoutineExercise(name = "Bench Press", targetWeightLb = 185.0, targetReps = 5)), "2026-09-21", PlanSource.ROUTINE, emptyList(), 1)
        assertEquals(83.91458845, plan.exercises.single().sets.first().weightKg!!, 1e-6)
    }

    @Test
    fun `the conversion is the exact pound, not an approximation of one`() {
        assertEquals(0.45359237, WatchPlanMapper.KG_PER_LB, 0.0)
        assertEquals(100.0, WatchPlanMapper.lbToKg(220.46226218487757), 1e-9)
    }

    @Test
    fun `185 lb survives the round trip through the real wire as 185 lb`() {
        // Whole grams on the wire turn 185 lb into 185.0009 lb on the way back. Storing that would
        // put a number in the log nobody lifted. Through the actual codec, not a re-derivation.
        listOf(185.0, 132.5, 45.0, 2.5, 402.25).forEach { lb ->
            val sent = finished(LoggedSet(weightKg = WatchPlanMapper.lbToKg(lb), reps = 5))
            val received = LinkPayloads.decodeSession(LinkPayloads.encodeSession(sent))
            assertEquals("$lb lb", lb, WatchPlanMapper.session(received).exercises.single().sets.single().weightLb!!, 0.0)
        }
    }

    @Test
    fun `a set logged on the watch in kilograms is stored in pounds`() {
        val stored = WatchPlanMapper.session(finished(LoggedSet(weightKg = 100.0, reps = 5)))
        assertEquals(220.46, stored.exercises.single().sets.single().weightLb!!, 0.0)
    }

    // ---- blank stays blank ------------------------------------------------------------------

    @Test
    fun `a routine with no target weight sends no weight, not zero`() {
        val set = WatchPlanMapper.plan(routine(RoutineExercise(name = "Pull-up", targetReps = 8)), null, PlanSource.ROUTINE, emptyList(), 1)
            .exercises.single().sets.first()
        assertEquals(PrescribedSet(reps = 8), set)
    }

    @Test
    fun `a routine that targets nothing sends sets that prescribe nothing`() {
        val sets = WatchPlanMapper.plan(routine(RoutineExercise(name = "Row", targetSets = 3)), null, PlanSource.ROUTINE, emptyList(), 1)
            .exercises.single().sets
        assertEquals(List(3) { PrescribedSet() }, sets)
    }

    @Test
    fun `a value the wire cannot carry is left blank rather than clamped`() {
        val set = WatchPlanMapper.plan(
            routine(RoutineExercise(name = "Squat", targetReps = 0, targetRpe = 11.0, targetWeightLb = -5.0)),
            null, PlanSource.ROUTINE, emptyList(), 1,
        ).exercises.single().sets.first()
        assertEquals(PrescribedSet(), set)
        // …and so the plan encodes, rather than throwing on the way to the watch.
        LinkPayloads.encodePlan(WatchPlanMapper.plan(routine(RoutineExercise(name = "Squat", targetRpe = 11.0)), null, PlanSource.ROUTINE, emptyList(), 1))
    }

    @Test
    fun `no rest is sent, because this app stores none`() {
        val set = WatchPlanMapper.plan(routine(RoutineExercise(name = "Bench Press", targetReps = 5)), null, PlanSource.ROUTINE, emptyList(), 1)
            .exercises.single().sets.first()
        assertNull(set.restSeconds)
    }

    @Test
    fun `a set with no weight from the watch is stored with no weight`() {
        val stored = WatchPlanMapper.session(finished(LoggedSet(reps = 10)))
        assertNull(stored.exercises.single().sets.single().weightLb)
    }

    @Test
    fun `blank equipment is sent as no equipment`() {
        val exercise = WatchPlanMapper.plan(routine(RoutineExercise(name = "Push-up", equipment = "  ")), null, PlanSource.ROUTINE, emptyList(), 1)
            .exercises.single()
        assertNull(exercise.equipment)
    }

    // ---- sides ------------------------------------------------------------------------------

    @Test
    fun `left and right come back as left and right, and no side comes back as both`() {
        val stored = WatchPlanMapper.session(
            finished(LoggedSet(reps = 8, side = LogSide.LEFT), LoggedSet(reps = 8, side = LogSide.RIGHT), LoggedSet(reps = 8))
        )
        assertEquals(listOf(SetSide.LEFT, SetSide.RIGHT, null), stored.exercises.single().sets.map { it.side })
    }

    // ---- last performed -----------------------------------------------------------------------

    @Test
    fun `last performed is the top set of the most recent session, with its day`() {
        val history = listOf(
            session("2026-09-07", 1_000, LoggedExercise(name = "Bench Press", equipment = "Barbell", sets = listOf(WorkoutSet(weightLb = 175.0, reps = 5)))),
            session("2026-09-14", 2_000, LoggedExercise(name = "Bench Press", equipment = "Barbell", sets = listOf(
                WorkoutSet(weightLb = 185.0, reps = 5, rpe = 8.0),
                WorkoutSet(weightLb = 155.0, reps = 10),   // a back-off, not the number that mattered
            ))),
        )
        val last = WatchPlanMapper.lastPerformed(RoutineExercise(name = "Bench Press", equipment = "Barbell"), history)!!
        assertEquals(WatchPlanMapper.lbToKg(185.0), last.weightKg!!, 1e-9)
        assertEquals(5, last.reps)
        assertEquals(8.0, last.rpe!!, 0.0)
        assertEquals("2026-09-14", last.performedOn)
    }

    @Test
    fun `last performed matches name and equipment, because a cable pulldown is not a machine pulldown`() {
        val history = listOf(
            session("2026-09-14", 2_000, LoggedExercise(name = "Lat Pulldown", equipment = "Machine", sets = listOf(WorkoutSet(weightLb = 140.0, reps = 10)))),
        )
        assertNull(WatchPlanMapper.lastPerformed(RoutineExercise(name = "Lat Pulldown", equipment = "Cable"), history))
    }

    @Test
    fun `no history means no last performed, not an empty one`() {
        assertNull(WatchPlanMapper.lastPerformed(RoutineExercise(name = "Deadlift"), emptyList()))
    }

    // ---- identity and revisions ---------------------------------------------------------------

    @Test
    fun `the same routine on the same day is the same plan`() {
        assertEquals(WatchPlanMapper.planId("r1", "2026-09-21"), WatchPlanMapper.planId("r1", "2026-09-21"))
        assertNotEquals(WatchPlanMapper.planId("r1", "2026-09-21"), WatchPlanMapper.planId("r1", "2026-09-22"))
        assertNotEquals(WatchPlanMapper.planId("r1", "2026-09-21"), WatchPlanMapper.planId("r2", "2026-09-21"))
    }

    @Test
    fun `a plan's revision moves only when its content does`() {
        assertEquals(1, WatchPlanMapper.nextPlanRevision(null, 42))
        assertEquals(3, WatchPlanMapper.nextPlanRevision(AppliedRevision(3, 42), 42))
        assertEquals(4, WatchPlanMapper.nextPlanRevision(AppliedRevision(3, 42), 43))
    }

    @Test
    fun `a plan's fingerprint ignores its revision and notices its content`() {
        val base = WatchPlanMapper.plan(routine(RoutineExercise(name = "Bench Press", targetReps = 5)), null, PlanSource.ROUTINE, emptyList(), 1)
        assertEquals(WatchPlanMapper.fingerprint(base), WatchPlanMapper.fingerprint(base.copy(revision = 9)))
        assertNotEquals(WatchPlanMapper.fingerprint(base), WatchPlanMapper.fingerprint(base.copy(name = "Push B")))
    }

    // ---- reconciliation -------------------------------------------------------------------------

    @Test
    fun `a session never seen is inserted`() {
        assertEquals(AckOutcome.INSERTED, WatchPlanMapper.reconcile(null, 1, null))
    }

    @Test
    fun `the same revision again is idempotent`() {
        val stored = WatchPlanMapper.session(finished(LoggedSet(reps = 5)))
        val applied = AppliedRevision(2, WatchPlanMapper.fingerprint(stored))
        assertEquals(AckOutcome.IDEMPOTENT, WatchPlanMapper.reconcile(applied, 2, stored))
    }

    @Test
    fun `an older revision is ignored`() {
        val stored = WatchPlanMapper.session(finished(LoggedSet(reps = 5)))
        assertEquals(AckOutcome.IGNORED, WatchPlanMapper.reconcile(AppliedRevision(3, WatchPlanMapper.fingerprint(stored)), 2, stored))
    }

    @Test
    fun `a newer revision replaces a session nobody has touched on the phone`() {
        val stored = WatchPlanMapper.session(finished(LoggedSet(reps = 5)))
        assertEquals(AckOutcome.ACCEPTED, WatchPlanMapper.reconcile(AppliedRevision(1, WatchPlanMapper.fingerprint(stored)), 2, stored))
    }

    @Test
    fun `the watch never silently overwrites an edit made on the phone`() {
        val stored = WatchPlanMapper.session(finished(LoggedSet(reps = 5)))
        val applied = AppliedRevision(1, WatchPlanMapper.fingerprint(stored))
        val edited = stored.copy(note = "felt heavy")
        assertEquals(AckOutcome.IGNORED, WatchPlanMapper.reconcile(applied, 2, edited))
    }

    @Test
    fun `a session deleted on the phone does not come back because the watch remembered it`() {
        val stored = WatchPlanMapper.session(finished(LoggedSet(reps = 5)))
        assertEquals(AckOutcome.IGNORED, WatchPlanMapper.reconcile(AppliedRevision(1, WatchPlanMapper.fingerprint(stored)), 2, null))
    }

    @Test
    fun `mapping the same revision twice gives the same session, so a fingerprint means something`() {
        val a = WatchPlanMapper.session(finished(LoggedSet(reps = 5), LoggedSet(weightKg = 60.0, reps = 8, side = LogSide.LEFT)))
        val b = WatchPlanMapper.session(finished(LoggedSet(reps = 5), LoggedSet(weightKg = 60.0, reps = 8, side = LogSide.LEFT)))
        assertEquals(a, b)
        assertEquals(WatchPlanMapper.fingerprint(a), WatchPlanMapper.fingerprint(b))
    }

    @Test
    fun `a session that trains one lift twice keeps both, with distinct ids`() {
        val twice = FinishedSession("s", 1, "2026-09-21", "Upper A", 1758441600, listOf(
            FinishedExercise("Bench Press", "Barbell", sets = listOf(LoggedSet(reps = 5))),
            FinishedExercise("Bench Press", "Barbell", sets = listOf(LoggedSet(reps = 12))),
        ))
        val stored = WatchPlanMapper.session(twice)
        assertEquals(2, stored.exercises.size)
        assertNotEquals(stored.exercises[0].id, stored.exercises[1].id)
    }

    @Test
    fun `a fingerprint survives being written to disk and read back`() {
        // The stored hash is compared with a session read back from workout_log.json, possibly in
        // another process. hashCode() would not survive that — enums hash by identity — which
        // would make every session with a left or right set look edited the next day.
        val stored = WatchPlanMapper.session(finished(LoggedSet(weightKg = 60.0, reps = 8, rpe = 7.5, side = LogSide.RIGHT), LoggedSet(reps = 5)))
        val reread = workoutSessionFromJson(JSONObject(stored.toJson().toString()))
        assertEquals(WatchPlanMapper.fingerprint(stored), WatchPlanMapper.fingerprint(reread))
    }

    @Test
    fun `a started time in seconds is stored in milliseconds`() {
        val stored = WatchPlanMapper.session(finished(LoggedSet(reps = 5)))
        assertEquals(1758441600_000L, stored.startedAt)
        assertEquals("2026-09-21", stored.date)
        assertTrue(stored.exercises.single().sets.single().reps == 5)
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun routine(vararg exercises: RoutineExercise) = Routine(id = "r1", name = "Upper A", exercises = exercises.toList())

    private fun session(date: String, startedAt: Long, vararg exercises: LoggedExercise) =
        WorkoutSession(date = date, startedAt = startedAt, exercises = exercises.toList())

    private fun finished(vararg sets: LoggedSet) = FinishedSession(
        sessionId = "1f4c8a26-70d5-4b93-8e11-6a0b3d9c2457",
        revision = 1,
        day = "2026-09-21",
        name = "Upper A",
        startedAtEpochSeconds = 1758441600,
        exercises = listOf(FinishedExercise("Bench Press", "Barbell", sets = sets.toList())),
    )
}
