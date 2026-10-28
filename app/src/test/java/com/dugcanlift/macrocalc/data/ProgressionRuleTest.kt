package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * A port of LIFT iPhone's `ProgressionRuleTests`, case for case, then the gates and "Use" as this
 * build's data has them: a routine found by name, no done tick, pounds only.
 */
class ProgressionRuleTest {
    private val monday = "2027-01-04"
    private val earlier = "2026-12-29"

    private fun session(weight: Double, reps: List<Int>, on: String = monday) =
        ProgressionRule.Session(date = on, sets = reps.map { ProgressionRule.LoggedSet(weight, it) })

    @Test fun `every set reached its reps goes up a step`() {
        val next = ProgressionRule.next(target = 8, setCount = 3, sessions = listOf(session(185.0, listOf(8, 8, 9))), step = 5.0)
        assertEquals(ProgressionRule.Next.Up(to = 190.0, from = 185.0, reps = 8, date = monday), next)
    }

    @Test fun `one short set means the same weight again`() {
        val next = ProgressionRule.next(target = 8, setCount = 3, sessions = listOf(session(185.0, listOf(8, 8, 6))), step = 5.0)
        assertEquals(ProgressionRule.Next.Same(185.0, reps = listOf(8, 8, 6), target = 8, date = monday), next)
    }

    /** Two sets of eight is not three sets of eight. */
    @Test fun `fewer sets than the routine asks for is not done`() {
        val next = ProgressionRule.next(target = 8, setCount = 3, sessions = listOf(session(185.0, listOf(8, 8))), step = 5.0)
        assertEquals(185.0, next?.weight)
    }

    /**
     * The top weight decides: a lighter back-off set short of the reps does not hold back a session
     * whose heavy sets all got there.
     */
    @Test fun `only the top weights sets count`() {
        val last = session(185.0, listOf(8, 8, 8)).let { it.copy(sets = it.sets + ProgressionRule.LoggedSet(135.0, 5)) }
        assertEquals(190.0, ProgressionRule.next(target = 8, setCount = 3, sessions = listOf(last), step = 5.0)?.weight)
    }

    @Test fun `short twice running at the same weight backs off`() {
        val sessions = listOf(session(185.0, listOf(8, 7, 6)), session(185.0, listOf(8, 6, 6), on = earlier))
        val next = ProgressionRule.next(target = 8, setCount = 3, sessions = sessions, step = 5.0)
        assertEquals(ProgressionRule.Next.BackOff(to = 165.0, from = 185.0, target = 8), next)
    }

    /** Short now, but the time before was at a lighter weight: that was the first try at this one, not a stall. */
    @Test fun `short once after going up is not a stall`() {
        val sessions = listOf(session(190.0, listOf(8, 7, 6)), session(185.0, listOf(8, 8, 8), on = earlier))
        assertEquals(190.0, ProgressionRule.next(target = 8, setCount = 3, sessions = sessions, step = 5.0)?.weight)
    }

    @Test fun `nothing honest to say is no suggestion`() {
        assertNull(ProgressionRule.next(target = 8, setCount = 3, sessions = emptyList(), step = 5.0))
        assertNull(ProgressionRule.next(target = 0, setCount = 3, sessions = listOf(session(185.0, listOf(8))), step = 5.0))
        // Bodyweight: no step to take.
        assertNull(ProgressionRule.next(target = 8, setCount = 3, sessions = listOf(session(0.0, listOf(10, 10, 10))), step = 5.0))
    }

    /**
     * A weight that has been through arithmetic reads back a hair off; the next one is still a real
     * plate pair. The iPhone's case is a pound weight stored as kilograms; this build stores pounds,
     * and a weight from the watch is rounded to the hundredth on the way in, so the hair here is a
     * double's last bit.
     */
    @Test fun `a weight a hair off steps cleanly`() {
        val stored = 184.99999999999997
        val next = ProgressionRule.next(target = 8, setCount = 1, sessions = listOf(session(stored, listOf(8))), step = 5.0)
        assertEquals(190.0, next?.weight)
    }

    /**
     * The iPhone's step is two of the smallest plate owned for a bar, otherwise 5 lb or 2.5 kg. This
     * build has neither plates nor a unit, so every lift steps the pound step.
     */
    @Test fun `the step is five pounds for every lift`() {
        assertEquals(5.0, ProgressionRule.step(), 0.0)
    }

    @Test fun `the lines say why`() {
        val us = Locale.US
        assertTrue(
            ProgressionRule.line(ProgressionRule.Next.Up(to = 190.0, from = 185.0, reps = 8, date = monday), us)
                .startsWith("Next: 190 lb. Every set reached 8 at 185 last time (")
        )
        assertTrue(
            ProgressionRule.line(ProgressionRule.Next.Same(185.0, reps = listOf(8, 8, 6), target = 8, date = monday), us)
                .startsWith("Next: 185 lb again. 8, 8, 6 at 185 last time (")
        )
        assertEquals(
            "Next: 75 lb. Short of 5 at 82.5 two sessions running, so back off and build up again.",
            ProgressionRule.line(ProgressionRule.Next.BackOff(to = 75.0, from = 82.5, target = 5), us)
        )
        assertEquals(
            "Next: 190 lb. Every set reached 8 at 185 last time (Mon, Jan 4).",
            ProgressionRule.line(ProgressionRule.Next.Up(to = 190.0, from = 185.0, reps = 8, date = monday), us)
        )
        assertEquals("Use 192.5 lb", ProgressionRule.useLabel(ProgressionRule.Next.Same(192.5, listOf(5), 5, monday)))
    }

    // MARK: - From the stores

    private val bench = RoutineExercise(name = "Barbell Bench Press - Medium Grip", equipment = "Barbell", targetSets = 3, targetReps = 8)
    private val push = Routine(name = "Push", folder = "PPL", exercises = listOf(bench))
    private val today = "2027-01-11"

    private fun logged(date: String, weight: Double, reps: List<Int>, name: String = "Push", side: SetSide? = null) =
        WorkoutSession(
            date = date, name = name,
            exercises = listOf(
                LoggedExercise(
                    name = bench.name, equipment = bench.equipment,
                    sets = reps.map { WorkoutSet(weightLb = weight, reps = it, side = side) }
                )
            )
        )

    private fun suggest(
        todays: WorkoutSession,
        history: List<WorkoutSession>,
        routines: List<Routine> = listOf(push),
        bookings: List<ScheduledSession> = emptyList(),
        enabled: Boolean = true,
        perSide: Boolean = false
    ) = ProgressionRule.suggestion(
        enabled = enabled, perSide = perSide, exercise = todays.exercises.first(), session = todays,
        today = today, all = history + todays, routines = routines, bookings = bookings
    )

    @Test fun `today's session of your own routine gets the next weight from the last one`() {
        val todays = push.toSession(today)
        val got = suggest(todays, listOf(logged(monday, 185.0, listOf(8, 8, 8))))
        assertEquals(ProgressionRule.Next.Up(190.0, 185.0, 8, monday), got?.next)
    }

    /** The coach programmes those numbers; a second voice on the same set is a contradiction. */
    @Test fun `none for a routine a coach scheduled`() {
        val todays = push.toSession(today)
        val booking = ScheduledSession(routineId = push.id, routineName = push.name, date = "2027-01-13")
        assertNull(suggest(todays, listOf(logged(monday, 185.0, listOf(8, 8, 8))), bookings = listOf(booking)))
    }

    @Test fun `none for a first session, a side at a time, another day, or with the setting off`() {
        val history = listOf(logged(monday, 185.0, listOf(8, 8, 8)))
        val todays = push.toSession(today)
        assertNull(suggest(todays, emptyList()))
        assertNull(suggest(todays, history, perSide = true))
        assertNull(suggest(todays, history, enabled = false))
        assertNull(suggest(push.toSession("2027-01-10"), history))
        // The last session logged a side at a time: two lifts, so there is no earlier session of this one.
        assertNull(suggest(todays, listOf(logged(monday, 60.0, listOf(8, 8, 8), side = SetSide.LEFT))))
    }

    /** A bodyweight lift has no weight in its history, and no step to take. */
    @Test fun `none for a bodyweight lift`() {
        val dips = RoutineExercise(name = "Dips - Triceps Version", equipment = "Body Only", targetSets = 3, targetReps = 10)
        val routine = Routine(name = "Push", exercises = listOf(dips))
        val todays = routine.toSession(today)
        val before = WorkoutSession(
            date = monday, name = "Push",
            exercises = listOf(LoggedExercise(name = dips.name, equipment = dips.equipment, sets = List(3) { WorkoutSet(reps = 10) }))
        )
        assertNull(suggest(todays, listOf(before), routines = listOf(routine)))
    }

    /** A routine with no rep target has nothing to measure a set against. */
    @Test fun `none without a rep target`() {
        val routine = push.copy(exercises = listOf(bench.copy(targetReps = null)))
        assertNull(suggest(routine.toSession(today), listOf(logged(monday, 185.0, listOf(8, 8, 8))), routines = listOf(routine)))
    }

    /**
     * Which routine a session came from is its name -- unless a booking recorded starting it. Renamed,
     * it is no routine; named like two routines, it cannot be told.
     */
    @Test fun `the routine is found by name, and two of that name cannot be told`() {
        val history = listOf(logged(monday, 185.0, listOf(8, 8, 8)))
        assertNull(suggest(push.toSession(today).copy(name = "Chest day"), history))
        val twin = push.copy(id = "twin", folder = "Other")
        assertNull(suggest(push.toSession(today), history, routines = listOf(push, twin)))
    }

    /** Every session of a date is that day, as the iPhone's one `WorkoutDay` per date is. */
    @Test fun `two sessions on one earlier day are one day`() {
        val history = listOf(logged(monday, 185.0, listOf(8, 8)), logged(monday, 185.0, listOf(8), name = "Extra"))
        assertEquals(190.0, suggest(push.toSession(today), history)?.next?.weight)
    }

    /**
     * "Use" fills the rows the routine laid out and leaves every set the lifter added alone: this
     * build has no done tick, so a row still exactly as laid out is the only set it can call not done.
     */
    @Test fun `use fills only the rows still as the routine laid them out`() {
        val todays = push.toSession(today)
        val added = WorkoutSet(weightLb = 185.0, reps = 8)
        val exercise = todays.exercises.first().let { it.copy(sets = listOf(added) + it.sets.drop(1)) }
        val got = suggest(todays.copy(exercises = listOf(exercise)), listOf(logged(monday, 185.0, listOf(8, 8, 8))))!!

        assertEquals(2, ProgressionRule.open(exercise.sets, got.laidOut, got.next.weight).size)
        val used = ProgressionRule.use(exercise, got)
        assertEquals(listOf(185.0, 190.0, 190.0), used.sets.map { it.weightLb })
        assertEquals(exercise.sets.map { it.id }, used.sets.map { it.id })
        // Nothing left to fill, so nothing is offered.
        assertTrue(ProgressionRule.open(used.sets, got.laidOut, got.next.weight).isEmpty())
    }

    @Test fun `the rule's own numbers are what the routine asks`() {
        assertEquals(ProgressionRule.Target(reps = 8, sets = 3), ProgressionRule.target(bench))
        val ramp = bench.copy(prescribed = listOf(PrescribedSet(reps = 8), PrescribedSet(reps = 8), PrescribedSet(reps = 6), PrescribedSet()))
        assertEquals(ProgressionRule.Target(reps = 8, sets = 3), ProgressionRule.target(ramp))
    }
}
