package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rest after a tick, once the session knows its set types ([SessionRest]). A port of LIFT
 * iPhone's `SessionRestTests` (in its `SetTypesTests.swift`), case for case.
 */
class SessionRestTest {

    private fun s(done: Boolean = false, kind: SetKind? = null) = SessionRest.SetState(done = done, kind = kind)

    private fun exercise(name: String, group: Int? = null, sets: List<SessionRest.SetState>) =
        SessionRest.Exercise(name = name, restName = "$name (Gear)", group = group, sets = sets)

    private fun rest(seconds: Int, before: String?) = SessionRest.After.Rest(seconds, before)
    private fun noRest(cue: String) = SessionRest.After.NoRest(cue)

    @Test fun `an ungrouped day rests as it always did`() {
        val first = s(); val second = s()
        val day = listOf(exercise("Squat", sets = listOf(first, second)))
        assertEquals(rest(120, "Squat (Gear)"), SessionRest.after(first.id, day, 120))
        assertEquals("the last set rests too", rest(120, "Squat (Gear)"), SessionRest.after(second.id, day, 120))
    }

    @Test fun `rest turned off starts and says nothing`() {
        val a = s(); val b = s()
        val day = listOf(exercise("Bench", 1, listOf(a)), exercise("Rows", 1, listOf(b)))
        assertEquals(SessionRest.After.Nothing, SessionRest.after(a.id, day, null))
        assertEquals(SessionRest.After.Nothing, SessionRest.after(a.id, day, 0))
    }

    @Test fun `inside a superset round there is no rest until its last set`() {
        val a1 = s(); val a2 = s(); val b1 = s(); val b2 = s()
        val day = listOf(exercise("Bench", 1, listOf(a1, a2)), exercise("Seated Cable Rows", 1, listOf(b1, b2)))
        assertEquals(noRest("Next: Seated Cable Rows"), SessionRest.after(a1.id, day, 90))
        val done = listOf(day[0].copy(sets = listOf(a1.copy(done = true), a2)), day[1])
        assertEquals("the round's last starts the full rest", rest(90, "Bench (Gear)"), SessionRest.after(b1.id, done, 90))
    }

    @Test fun `a round is not over while an earlier set of it is unticked`() {
        val a1 = s(); val b1 = s(); val a2 = s(); val b2 = s()
        val day = listOf(exercise("Bench", 1, listOf(a1, a2)), exercise("Rows", 1, listOf(b1, b2)))
        assertEquals("ticked out of order, Bench is still to do", noRest("Next: Bench"), SessionRest.after(b1.id, day, 90))
    }

    @Test fun `a circuit skips a member that has run out`() {
        val swing = List(3) { s() }; val push = List(3) { s() }; val plank = List(2) { s() }
        val day = listOf(exercise("Kettlebell Swing", 2, swing), exercise("Push-Up", 2, push), exercise("Plank", 2, plank))
        assertEquals(listOf("00", "10", "20", "01", "11", "21", "02", "12"),
            SessionRest.order(day).map { "${it.exercise}${it.set}" })
        // Round three: Swing, then Push-Up, which is the round's last.
        fun ticked(list: List<SessionRest.SetState>, n: Int) = list.mapIndexed { i, x -> if (i < n) x.copy(done = true) else x }
        val roundThree = listOf(day[0].copy(sets = ticked(swing, 3)), day[1].copy(sets = ticked(push, 3)), day[2].copy(sets = ticked(plank, 2)))
        assertEquals(rest(60, "Push-Up (Gear)"), SessionRest.after(push[2].id, roundThree, 60))
        val pushNotDone = roundThree.toMutableList().also { it[1] = day[1].copy(sets = ticked(push, 2)) }
        assertEquals(noRest("Next: Push-Up"), SessionRest.after(swing[2].id, pushNotDone, 60))
    }

    @Test fun `a drop set next means no rest`() {
        val sets = List(4) { i -> s(kind = if (i >= 2) SetKind.DROP else null) }
        val day = listOf(exercise("Triceps Pushdown", sets = sets))
        assertEquals(rest(90, "Triceps Pushdown (Gear)"), SessionRest.after(sets[0].id, day, 90))
        assertEquals(noRest("Drop set next"), SessionRest.after(sets[1].id, day, 90))
        assertEquals("a drop after a drop", noRest("Drop set next"), SessionRest.after(sets[2].id, day, 90))
        assertEquals(rest(90, "Triceps Pushdown (Gear)"), SessionRest.after(sets[3].id, day, 90))
    }

    @Test fun `rest-pause next is fifteen seconds`() {
        val first = s(); val pause = s(kind = SetKind.REST_PAUSE)
        val day = listOf(exercise("Barbell Curl", sets = listOf(first, pause)))
        assertEquals(rest(15, "Barbell Curl (Gear)"), SessionRest.after(first.id, day, 120))
    }

    @Test fun `the next set to do skips ones already ticked`() {
        val a = s(); val b = s(done = true); val c = s(kind = SetKind.DROP)
        val day = listOf(exercise("Pushdown", sets = listOf(a, b, c)))
        assertEquals(noRest("Drop set next"), SessionRest.after(a.id, day, 90))
    }

    /** The iPhone reads its day through `RoutineSetTypes`; this build reads the session's own fields. */
    @Test fun `the logged session is read with the first set always straight`() {
        val first = WorkoutSet(weightLb = 50.0, reps = 10, kind = SetKind.DROP)
        val second = WorkoutSet(weightLb = 40.0, reps = 10, kind = SetKind.REST_PAUSE)
        val session = WorkoutSession(exercises = listOf(
            LoggedExercise(name = "Curl", equipment = "Barbell", sets = listOf(first, second), group = 3)
        ))
        assertEquals(
            listOf(SessionRest.Exercise(
                name = "Curl", restName = "Curl (Barbell)", group = 3,
                sets = listOf(SessionRest.SetState(first.id, false, null), SessionRest.SetState(second.id, true, SetKind.REST_PAUSE))
            )),
            SessionRest.exercises(session, done = setOf(second.id))
        )
    }
}
