package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/**
 * A port of LIFT iPhone's `RoutineRotationTests`, case for case, with the cases this build's own
 * record needs: which routine a session came from is its name or a booking's started session id,
 * not an exercise ref, and a tie in when routines were made goes by the order they were saved.
 */
class RoutineRotationTest {
    private val start = 1_800_000_000_000L

    private fun made(n: Int): Long = start + n * 86_400_000L

    /** Day [n] after a fixed Monday, as a `yyyy-MM-dd` key (2027-01-04 is a Monday). */
    private fun day(n: Int): String = java.time.LocalDate.of(2027, 1, 4).plusDays(n.toLong()).toString()

    private fun member(name: String, made: Int) =
        RoutineRotation.Member(id = UUID.randomUUID().toString(), createdAt = made(made), name = name)

    private fun trained(member: RoutineRotation.Member, on: Int) =
        RoutineRotation.Day(date = day(on), sessionNames = setOf(RoutineRotation.nameKey(member.name)))

    private val push = member("Push", made = 0)
    private val pull = member("Pull", made = 1)
    private val legs = member("Legs", made = 2)
    private val ppl get() = listOf(legs, push, pull)

    @Test fun `before any is started the first made is up next`() {
        val next = RoutineRotation.upNext("PPL", ppl, emptyList())
        assertEquals(RoutineRotation.UpNext(routineId = push.id, after = null, lastDate = null), next)
    }

    @Test fun `the one after the last started is up next and it wraps round`() {
        assertEquals(pull.id, RoutineRotation.upNext("PPL", ppl, listOf(trained(push, 10)))?.routineId)
        assertEquals(
            push.id,
            RoutineRotation.upNext("PPL", ppl, listOf(trained(push, 10), trained(legs, 12)))?.routineId
        )
    }

    /** Order is the order of the days, not of the list it was handed in. */
    @Test fun `the newest day decides`() {
        val days = listOf(trained(legs, 12), trained(pull, 14), trained(push, 10))
        assertEquals(legs.id, RoutineRotation.upNext("PPL", ppl, days)?.routineId)
    }

    /** A day logged by hand, or from another folder, moves nothing on. */
    @Test fun `a day from no routine of the folder is ignored`() {
        val other = RoutineRotation.Day(
            date = day(20), routineIds = setOf(UUID.randomUUID().toString()), sessionNames = setOf("upper a", "")
        )
        val days = listOf(trained(push, 10), other)
        assertEquals(pull.id, RoutineRotation.upNext("PPL", ppl, days)?.routineId)
    }

    @Test fun `one routine or the default folder is no rotation`() {
        assertNull(RoutineRotation.upNext("PPL", listOf(push), emptyList()))
        assertNull(RoutineRotation.upNext("", ppl, emptyList()))
        assertNull(RoutineRotation.upNext("  ", ppl, emptyList()))
    }

    /**
     * Two made at once (the same instant) go in the order they were saved -- a starter split's
     * routines are made together, and by name they would run Legs, Pull, Push. Where this build
     * departs from the iPhone, whose store has no order of its own and breaks the tie by name.
     */
    @Test fun `a tie in when they were made goes by the order they were saved`() {
        val b = RoutineRotation.Member(id = "b", createdAt = start, name = "Push")
        val a = RoutineRotation.Member(id = "a", createdAt = start, name = "Pull")
        val c = RoutineRotation.Member(id = "c", createdAt = start, name = "Legs")
        assertEquals(listOf("Push", "Pull", "Legs"), RoutineRotation.ordered(listOf(b, a, c)).map { it.name })
    }

    @Test fun `the current rotation is the one trained most recently`() {
        val upper = member("Upper", made = 3)
        val lower = member("Lower", made = 4)
        val folders = listOf("PPL" to ppl, "Upper/Lower" to listOf(upper, lower))
        val days = listOf(trained(push, 10), trained(upper, 15))
        val current = RoutineRotation.current(folders, days)
        assertEquals("Upper/Lower", current?.folder)
        assertEquals(lower.id, current?.upNext?.routineId)
    }

    /** Nobody asked for the first routine of every folder. */
    @Test fun `no rotation started suggests nothing`() {
        assertNull(RoutineRotation.current(listOf("PPL" to ppl), emptyList()))
    }

    // MARK: - What this build records

    /** Two of a folder's routines on one day -- here also one name matching two -- the later in the rotation. */
    @Test fun `two of a folders routines on one day, the later in the rotation is where it got to`() {
        val day = RoutineRotation.Day(date = day(10), sessionNames = setOf("push", "legs"))
        assertEquals(push.id, RoutineRotation.upNext("PPL", ppl, listOf(day))?.routineId)
    }

    private fun routine(name: String, folder: String, made: Int) = Routine(
        name = name, folder = folder, createdAt = made(made),
        exercises = listOf(RoutineExercise(name = "Bench Press", equipment = "Barbell", targetReps = 8))
    )

    private fun session(routine: Routine, on: Int, name: String = routine.name) =
        routine.toSession(day(on)).copy(name = name)

    /**
     * A session is its routine's by name, compared trimmed and without case, because that is all
     * [Routine.toSession] writes; a session a coach's booking started is that booking's routine
     * whatever it is called now.
     */
    @Test fun `a session is read by its name, or by the booking that started it`() {
        val a = routine("Day A", "AB", 0)
        val b = routine("Day B", "AB", 1)
        val routines = listOf(a, b)

        val byName = RoutineRotation.days(listOf(session(a, 10, name = "  day a ")), emptyList())
        assertEquals(b.id, RoutineRotation.upNext("AB", routines.map(RoutineRotation::member), byName)?.routineId)

        // Renamed after it started: by name it is nobody's, by its booking it is still Day B's.
        val renamed = session(b, 12, name = "Thursday")
        assertEquals(
            RoutineRotation.UpNext(a.id, after = null, lastDate = null),
            RoutineRotation.upNext(
                "AB", routines.map(RoutineRotation::member), RoutineRotation.days(listOf(renamed), emptyList())
            )
        )
        val booking = ScheduledSession(routineId = b.id, routineName = b.name, date = day(12), startedSessionId = renamed.id)
        val recorded = RoutineRotation.days(listOf(renamed), listOf(booking))
        assertEquals(a.id, RoutineRotation.upNext("AB", routines.map(RoutineRotation::member), recorded)?.routineId)
    }

    /** A session with nothing in it is nothing trained. */
    @Test fun `an empty session moves nothing on`() {
        val a = routine("Day A", "AB", 0)
        val b = routine("Day B", "AB", 1)
        val empty = WorkoutSession(date = day(10), name = "Day A")
        val days = RoutineRotation.days(listOf(empty), emptyList())
        assertEquals(
            RoutineRotation.UpNext(a.id, after = null, lastDate = null),
            RoutineRotation.upNext("AB", listOf(a, b).map(RoutineRotation::member), days)
        )
    }

    /** A named folder lists in rotation order; the default folder keeps the order it always had. */
    @Test fun `routines list a named folder in rotation order`() {
        val legs = routine("Legs", "PPL", 2)
        val push = routine("Push", "PPL", 0)
        val pull = routine("Pull", "PPL", 1)
        assertEquals(listOf("Push", "Pull", "Legs"), RoutineRotation.listed(listOf(legs, push, pull)).map { it.name })
        val loose = listOf(routine("B", "", 2), routine("A", "", 0))
        assertEquals(listOf("B", "A"), RoutineRotation.listed(loose).map { it.name })
    }

    @Test fun `the offer and the footer read as the iPhone's`() {
        assertEquals("Up next: Pull · Push/Pull/Legs", RoutineRotation.offer("Pull", "Push/Pull/Legs"))
        assertEquals(
            "A folder is a rotation: Up next is the routine after the last one you started from it.",
            RoutineRotation.FOOTER
        )
    }
}
