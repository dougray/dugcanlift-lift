package com.dugcanlift.macrocalc.data

import com.dugcanlift.kit.RecipeNutrition
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * The coach's week beside the log that answers it.
 *
 * A port of LIFT web's `lift/plan-log.test.mjs`, case for case, against the same week and the same
 * sentences — because [PlanLog] is a port of `lift/plan-log.js` and a port that agrees only with
 * itself proves nothing. The line-discipline tests at the foot are web's own list plus the words
 * this screen could reach for and Coach's could not.
 */
class PlanLogTest {

    /* The week everything below sits in: Mon 12 Oct 2026 to Sun 18 Oct 2026. "today" is Thu 15 Oct
     * unless a test says otherwise, so the week has three days behind it, one being lived and three
     * still ahead -- which is the state this card exists for. */
    private val mon = "2026-10-12"
    private val tue = "2026-10-13"
    private val wed = "2026-10-14"
    private val thu = "2026-10-15"
    private val fri = "2026-10-16"
    private val sat = "2026-10-17"
    private val sun = "2026-10-18"
    private val today = thu

    /**
     * A day's month and weekday are printed in the reader's own language, as web's
     * `toLocaleDateString(undefined, ...)` and Coach Android's card both leave them. One is pinned
     * here only so a test can assert a string.
     */
    private var previousLocale: Locale = Locale.getDefault()

    @Before fun pinLocale() { previousLocale = Locale.getDefault(); Locale.setDefault(Locale.US) }
    @After fun restoreLocale() { Locale.setDefault(previousLocale) }

    /* ---------------- the two halves, as the app stores them ---------------- */

    private var seq = 0
    private fun nextId(): String = "id${++seq}"

    /** A prescribed set. */
    private fun ask(
        weightLb: Double? = null,
        reps: Int? = null,
        rpe: Double? = null,
        durationSec: Int? = null,
        distanceMeters: Double? = null,
        side: SetSide? = null
    ) = PrescribedSet(weightLb, reps, rpe, durationSec, distanceMeters, side)

    /** A logged set. */
    private fun did(
        weightLb: Double? = null,
        reps: Int? = null,
        rpe: Double? = null,
        durationSec: Int? = null,
        distanceMeters: Double? = null,
        side: SetSide? = null
    ) = WorkoutSet(nextId(), weightLb, reps, rpe, durationSec, distanceMeters, side)

    private fun <T> mostCommon(values: List<T?>): T? =
        values.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /**
     * One prescribed exercise **exactly as `PlanImporter` stores one**: the flattened targets filled
     * in, and the coach's sets kept beside them only when [Prescription] says the targets would lose
     * something. So a plain "2 x 5 @ 185" fixture really is targets-only here, which is the case
     * that would read wrongly if the card read `targetWeightLb` instead of the sets.
     */
    private fun asked(
        name: String,
        equipment: String,
        sets: List<PrescribedSet>,
        eachSide: Boolean = false
    ) = RoutineExercise(
        id = nextId(),
        name = name,
        equipment = equipment,
        targetSets = sets.size.coerceAtLeast(1),
        targetReps = mostCommon(sets.map { it.reps }),
        targetWeightLb = mostCommon(sets.map { it.weightLb }),
        targetRpe = mostCommon(sets.map { it.rpe }),
        targetDurationSec = mostCommon(sets.map { it.durationSec }),
        targetDistanceMeters = mostCommon(sets.map { it.distanceMeters }),
        prescribed = sets.takeIf { Prescription.needsSetBySet(it, eachSide) },
        eachSide = eachSide
    )

    private fun logged(name: String, equipment: String, sets: List<WorkoutSet>) =
        LoggedExercise(id = nextId(), name = name, equipment = equipment, sets = sets)

    /** A booking as a plan link leaves it: a [ScheduledSession] on a date, and the [Routine] it names. */
    private inner class Plan(
        date: String,
        name: String,
        exercises: List<RoutineExercise>,
        fromCoach: String? = "Doug",
        startedSessionId: String? = null
    ) {
        val routine = Routine(id = nextId(), name = name, exercises = exercises)
        val session = ScheduledSession(
            id = nextId(), routineId = routine.id, routineName = name, date = date,
            fromCoach = fromCoach, startedSessionId = startedSessionId
        )
    }

    private fun plan(
        date: String,
        name: String,
        exercises: List<RoutineExercise>,
        fromCoach: String? = "Doug",
        startedSessionId: String? = null
    ) = Plan(date, name, exercises, fromCoach, startedSessionId)

    /** The bookings, resolved against their routines the way the screen resolves them. */
    private fun bookings(plans: List<Plan>): List<PlanLog.Booking> =
        PlanLog.bookings(plans.map { it.session }, plans.map { it.routine })

    private fun session(date: String, name: String, exercises: List<LoggedExercise>) =
        WorkoutSession(id = nextId(), date = date, name = name, exercises = exercises)

    private fun run(
        plans: List<Plan>,
        sessions: List<WorkoutSession>,
        today: String = this.today,
        anchor: String = this.today
    ): PlanLog.Result? = PlanLog.compare(bookings(plans), sessions, today, anchor)

    private fun day(result: PlanLog.Result?, index: Int) = result!!.days[index]

    private fun dayOn(result: PlanLog.Result?, key: String) = result!!.days.first { it.key == key }

    private fun liftStartingWith(day: PlanLog.DayRow, prefix: String) =
        day.exercises.first { it.key.startsWith(prefix) }

    /* A fixed week used by several tests: Monday's "Lower A" booked and logged whole, Tuesday's
     * "Upper B" booked and nothing logged, Wednesday free and trained anyway, Friday booked and
     * still ahead. */
    private fun weekTraining(): List<Plan> = listOf(
        plan(mon, "Lower A", listOf(
            asked("Back Squat", "Barbell", listOf(ask(225.0, 5), ask(225.0, 5), ask(245.0, 3))),
            asked("Romanian Deadlift", "Barbell", listOf(ask(185.0, 8), ask(185.0, 8), ask(185.0, 8), ask(185.0, 8))),
            asked("Bulgarian Split Squat", "Dumbbell",
                listOf(ask(40.0, 8), ask(40.0, 8), ask(40.0, 8)), eachSide = true),
            asked("Overhead Press", "Barbell", listOf(ask(95.0, 8)))
        )),
        plan(tue, "Upper B", listOf(asked("Bench Press", "Barbell", listOf(ask(185.0, 5), ask(185.0, 5))))),
        plan(fri, "Lower B", listOf(
            asked("Front Squat", "Barbell", listOf(ask(165.0, 5), ask(165.0, 5))),
            asked("Split Squat", "Dumbbell", listOf(ask(35.0, 10), ask(35.0, 10)), eachSide = true)
        ))
    )

    private fun weekWorkouts(): List<WorkoutSession> = listOf(
        session(mon, "Lower A", listOf(
            logged("Back Squat", "Barbell", listOf(did(225.0, 5), did(225.0, 5), did(245.0, 2))),
            logged("Romanian Deadlift", "Barbell", listOf(did(185.0, 8), did(185.0, 8), did(185.0, 6))),
            logged("Bulgarian Split Squat", "Dumbbell", listOf(
                did(40.0, 8, side = SetSide.LEFT), did(40.0, 8, side = SetSide.LEFT),
                did(40.0, 5, side = SetSide.LEFT),
                did(40.0, 8, side = SetSide.RIGHT), did(40.0, 8, side = SetSide.RIGHT))),
            logged("Leg Press", "Machine", listOf(did(300.0, 10), did(300.0, 10), did(300.0, 10)))
        )),
        session(wed, "Arms", listOf(logged("Barbell Curl", "Barbell", listOf(did(65.0, 10), did(65.0, 10)))))
    )

    /* ---------------- a week with a plan and a complete log ---------------- */

    @Test
    fun `a week booked and logged whole reads back as logged, lift by lift`() {
        val r = run(
            listOf(plan(mon, "Lower A", listOf(
                asked("Back Squat", "Barbell", listOf(ask(225.0, 5), ask(225.0, 5), ask(245.0, 3)))))),
            listOf(session(mon, "Lower A", listOf(
                logged("Back Squat", "Barbell", listOf(did(225.0, 5), did(225.0, 5), did(245.0, 3))))))
        )
        assertEquals("Booked 1 day, 12–18 Oct · logged 1", r!!.head)
        assertEquals(
            listOf(
                "Booked 1 day, 12–18 Oct · logged 1",
                "Mon 12 Oct · Lower A · logged",
                "Back Squat (Barbell)",
                "Asked 225 x 5 · 225 x 5 · 245 x 3",
                "Logged 225 x 5 · 225 x 5 · 245 x 3",
                PlanLog.FOOTER
            ),
            PlanLog.lines(r)
        )
    }

    /* ---------------- a partial log ---------------- */

    @Test
    fun `a partial log prints both rows and aligns nothing`() {
        val r = run(weekTraining(), weekWorkouts())
        val mondayRow = day(r, 0)
        assertEquals("logged", mondayRow.state)
        val rdl = liftStartingWith(mondayRow, "romanian")
        assertEquals("Asked 4 sets · logged 3", rdl.countLine)
        assertEquals("185 x 8 · 185 x 8 · 185 x 8 · 185 x 8", rdl.asked!!.text)
        assertEquals("185 x 8 · 185 x 8 · 185 x 6", rdl.logged!!.text)
    }

    @Test
    fun `a lift the plan asked for and the log does not hold reads not logged`() {
        val press = liftStartingWith(day(run(weekTraining(), weekWorkouts()), 0), "overhead press")
        assertEquals("Overhead Press (Barbell) · not logged", press.title)
        // A lift nothing was logged against is one line, not a recital.
        assertNull(press.asked)
        assertNull(press.logged)
    }

    @Test
    fun `a lift nobody asked for is counted against nothing, under Also logged`() {
        assertEquals(
            listOf("Leg Press (Machine) · 3 sets"),
            day(run(weekTraining(), weekWorkouts()), 0).alsoLogged.map { it.text }
        )
    }

    @Test
    fun `a booked day in the past with nothing logged reads not logged, never missed`() {
        val tuesday = dayOn(run(weekTraining(), weekWorkouts()), tue)
        assertEquals("notLogged", tuesday.state)
        assertEquals("Tue 13 Oct · Upper B · not logged", tuesday.text)
        assertEquals(listOf("Bench Press (Barbell) · not logged"), tuesday.exercises.map { it.title })
        // The day is one tap away and its own prescribed card still holds every set.
        assertEquals(listOf<PlanLog.SetRow?>(null), tuesday.exercises.map { it.asked })
    }

    @Test
    fun `the head counts the week without grading it`() {
        val r = run(weekTraining(), weekWorkouts())
        assertEquals("Booked 3 days, 12–18 Oct · logged 1 · 1 to do · 1 other day logged", r!!.head)
        assertEquals(
            PlanLog.Counts(booked = 3, training = 3, meals = 0, logged = 1, notLogged = 1,
                toDo = 1, other = 1),
            r.counts
        )
    }

    /* ---------------- days still ahead ---------------- */

    @Test
    fun `a booked day that has not happened yet is to do, not an absence`() {
        val friday = dayOn(run(weekTraining(), weekWorkouts()), fri)
        assertEquals("toDo", friday.state)
        assertEquals("Fri 16 Oct · Lower B · to do", friday.text)
        // Train cannot be moved to a day that has not happened.
        assertFalse(friday.openable)
    }

    @Test
    fun `a day still ahead prints what it asks for, because nowhere else can`() {
        val friday = dayOn(run(weekTraining(), weekWorkouts()), fri)
        assertEquals(
            listOf("Front Squat (Barbell)", "Split Squat (Dumbbell) · each side"),
            friday.exercises.map { it.title }
        )
        assertEquals("165 x 5 · 165 x 5", friday.exercises[0].asked!!.text)
        assertEquals("35 x 10 · 35 x 10 each side", friday.exercises[1].asked!!.text)
        assertNull(friday.exercises[0].logged)
    }

    @Test
    fun `a day still ahead is never a row of noughts`() {
        val friday = dayOn(run(weekTraining(), weekWorkouts()), fri)
        assertEquals("Each side · L 2 · R 2", friday.exercises[1].sideLine)
        assertNull(friday.exercises[0].sideLine)
        assertNull(friday.exercises[0].countLine)
    }

    @Test
    fun `today is still to do until something is logged against it`() {
        val r = run(
            listOf(plan(thu, "Upper A", listOf(asked("Bench Press", "Barbell", listOf(ask(185.0, 5)))))),
            emptyList()
        )
        assertEquals("toDo", day(r, 0).state)
        assertEquals("Thu 15 Oct · Upper A · to do", day(r, 0).text)
        assertTrue(day(r, 0).openable)
    }

    @Test
    fun `a week booked entirely in the days ahead does not open with a nought`() {
        val r = run(
            listOf(
                plan(fri, "Lower B", listOf(asked("Front Squat", "Barbell", listOf(ask(165.0, 5))))),
                plan(sat, "Upper B", listOf(asked("Bench Press", "Barbell", listOf(ask(185.0, 5)))))
            ),
            emptyList()
        )
        assertEquals("Booked 2 days, 12–18 Oct · 2 to do", r!!.head)
    }

    @Test
    fun `a week that is over says what it counted, whatever it counted`() {
        val r = run(
            listOf(plan(mon, "Lower A", listOf(asked("Back Squat", "Barbell", listOf(ask(225.0, 5)))))),
            emptyList(),
            today = "2026-10-25",
            anchor = thu
        )
        assertEquals("Booked 1 day, 12–18 Oct · logged 0", r!!.head)
        assertEquals("notLogged", day(r, 0).state)
    }

    /* ---------------- a session done on a different day ---------------- */

    @Test
    fun `a session lifted the day after the one it was booked for is two rows, adjacent`() {
        val r = run(
            listOf(plan(tue, "Upper B",
                listOf(asked("Bench Press", "Barbell", listOf(ask(185.0, 5), ask(185.0, 5)))))),
            listOf(session(wed, "Upper B",
                listOf(logged("Bench Press", "Barbell", listOf(did(185.0, 5), did(185.0, 5))))))
        )
        assertEquals(
            listOf("Tue 13 Oct · Upper B · not logged", "Wed 14 Oct · Upper B · not booked"),
            r!!.days.map { it.text }
        )
        assertEquals("Booked 1 day, 12–18 Oct · logged 0 · 1 other day logged", r.head)
    }

    @Test
    fun `nothing claims the moved session answered the booking`() {
        val r = run(
            listOf(plan(tue, "Upper B", listOf(asked("Bench Press", "Barbell", listOf(ask(185.0, 5)))))),
            listOf(session(wed, "Upper B", listOf(logged("Bench Press", "Barbell", listOf(did(185.0, 5))))))
        )
        val wednesday = dayOn(r, wed)
        assertEquals("notBooked", wednesday.state)
        // A day nobody booked is held against no prescription.
        assertEquals(emptyList<PlanLog.ExerciseLines>(), wednesday.exercises)
        assertEquals(listOf("Bench Press (Barbell) · 1 set"), wednesday.alsoLogged.map { it.text })
        assertEquals(0, r!!.counts.logged)
    }

    @Test
    fun `a day of your own inside a week nobody booked is not a card at all`() {
        assertNull(run(emptyList(), weekWorkouts()))
    }

    /* ---------------- sides ---------------- */

    @Test
    fun `an each-side lift short on one side reads L 3 of 3 and R 2 of 3`() {
        val split = liftStartingWith(day(run(weekTraining(), weekWorkouts()), 0), "bulgarian")
        assertEquals("Bulgarian Split Squat (Dumbbell) · each side", split.title)
        assertEquals("L 3/3 · R 2/3", split.sideLine)
        assertEquals("40 x 8 · 40 x 8 · 40 x 8 each side", split.asked!!.text)
        assertEquals("L 40 x 8 · 40 x 8 · 40 x 5   R 40 x 8 · 40 x 8", split.logged!!.text)
    }

    @Test
    fun `the side counts are the session header's own, not a second reading`() {
        val askedSets = listOf(ask(40.0, 8), ask(40.0, 8), ask(40.0, 8))
        val loggedSets = listOf(
            did(40.0, 8, side = SetSide.LEFT), did(40.0, 8, side = SetSide.LEFT),
            did(40.0, 5, side = SetSide.LEFT),
            did(40.0, 8, side = SetSide.RIGHT), did(40.0, 8, side = SetSide.RIGHT)
        )
        val r = run(
            listOf(plan(mon, "Lower A", listOf(asked("Split Squat", "Dumbbell", askedSets, eachSide = true)))),
            listOf(session(mon, "Lower A", listOf(logged("Split Squat", "Dumbbell", loggedSets))))
        )
        assertEquals(
            PerSideLogging.targetsLabel(askedSets, true, loggedSets),
            day(r, 0).exercises[0].sideLine
        )
    }

    @Test
    fun `over is shown as over, never capped`() {
        val r = run(
            listOf(plan(mon, "Lower A", listOf(asked("Split Squat", "Dumbbell",
                listOf(ask(40.0, 8), ask(40.0, 8), ask(40.0, 8)), eachSide = true)))),
            listOf(session(mon, "Lower A", listOf(logged("Split Squat", "Dumbbell", listOf(
                did(40.0, 8, side = SetSide.LEFT), did(40.0, 8, side = SetSide.LEFT),
                did(40.0, 8, side = SetSide.LEFT), did(40.0, 8, side = SetSide.LEFT),
                did(40.0, 8, side = SetSide.RIGHT), did(40.0, 8, side = SetSide.RIGHT),
                did(40.0, 8, side = SetSide.RIGHT))))))
        )
        assertEquals("L 4/3 · R 3/3", day(r, 0).exercises[0].sideLine)
    }

    @Test
    fun `an each-side exercise's ask is twice its tuples`() {
        val r = run(
            listOf(plan(mon, "Lower A", listOf(asked("Split Squat", "Dumbbell",
                listOf(ask(40.0, 8), ask(40.0, 8), ask(40.0, 8)), eachSide = true)))),
            listOf(session(mon, "Lower A", listOf(logged("Split Squat", "Dumbbell",
                listOf(did(40.0, 8, side = SetSide.LEFT))))))
        )
        assertEquals("L 1/3 · R 0/3", day(r, 0).exercises[0].sideLine)
    }

    @Test
    fun `a named side on a lift that is not each side is one set on that side`() {
        val r = run(
            listOf(plan(mon, "Lower A", listOf(asked("Calf Raise", "Machine",
                listOf(ask(90.0, 12), ask(90.0, 12, side = SetSide.LEFT)))))),
            listOf(session(mon, "Lower A", listOf(logged("Calf Raise", "Machine",
                listOf(did(90.0, 12), did(90.0, 12, side = SetSide.LEFT))))))
        )
        val ex = day(r, 0).exercises[0]
        assertEquals("Calf Raise (Machine)", ex.title)
        assertEquals("L 1/1 · 1/1 both", ex.sideLine)
        assertEquals("L 90 x 12   Both 90 x 12", ex.asked!!.text)
    }

    @Test
    fun `a plan with no sides produces no side line at all`() {
        val r = run(
            listOf(plan(mon, "Lower A", listOf(asked("Back Squat", "Barbell",
                listOf(ask(225.0, 5), ask(225.0, 5)))))),
            listOf(session(mon, "Lower A", listOf(logged("Back Squat", "Barbell",
                listOf(did(225.0, 5), did(225.0, 5))))))
        )
        assertNull(day(r, 0).exercises[0].sideLine)
    }

    @Test
    fun `sides logged against a plan that asked for none are still said`() {
        val r = run(
            listOf(plan(mon, "Lower A", listOf(asked("Calf Raise", "Machine",
                listOf(ask(90.0, 12), ask(90.0, 12)))))),
            listOf(session(mon, "Lower A", listOf(logged("Calf Raise", "Machine",
                listOf(did(90.0, 12, side = SetSide.LEFT), did(90.0, 12, side = SetSide.RIGHT))))))
        )
        assertEquals("L 1 · R 1", day(r, 0).exercises[0].sideLine)
    }

    @Test
    fun `sets logged before per-side logging are a Both group beside the two limbs`() {
        val r = run(
            listOf(plan(mon, "Lower A", listOf(asked("Calf Raise", "Machine", listOf(ask(90.0, 12)))))),
            listOf(session(mon, "Lower A", listOf(logged("Calf Raise", "Machine", listOf(
                did(90.0, 12), did(90.0, 12, side = SetSide.LEFT), did(90.0, 12, side = SetSide.RIGHT))))))
        )
        assertEquals("L 90 x 12   R 90 x 12   Both 90 x 12", day(r, 0).exercises[0].logged!!.text)
    }

    /* ---------------- a lift substituted ---------------- */

    @Test
    fun `a substitution is one pair on name alone, labelled`() {
        val r = run(
            listOf(plan(mon, "Lower A", listOf(asked("Bench Press", "Barbell",
                listOf(ask(185.0, 5), ask(185.0, 5)))))),
            listOf(session(mon, "Lower A", listOf(logged("Bench Press", "Smith machine",
                listOf(did(185.0, 5), did(185.0, 5))))))
        )
        val ex = day(r, 0).exercises[0]
        assertEquals("logged", ex.state)
        assertEquals("Asked Barbell · logged Smith machine", ex.substitution)
        // A substitution is one row, not two.
        assertEquals(emptyList<PlanLog.AlsoLogged>(), day(r, 0).alsoLogged)
    }

    @Test
    fun `an exact name and equipment match always wins over a name-only one`() {
        val r = run(
            listOf(plan(mon, "Pull", listOf(
                asked("Lat Pulldown", "Cable", listOf(ask(120.0, 10))),
                asked("Lat Pulldown", "Machine", listOf(ask(140.0, 10)))))),
            listOf(session(mon, "Pull", listOf(
                logged("Lat Pulldown", "Machine", listOf(did(140.0, 10))),
                logged("Lat Pulldown", "Cable", listOf(did(120.0, 10))))))
        )
        val (cable, machine) = day(r, 0).exercises
        assertEquals("120 x 10", cable.logged!!.text)
        assertEquals("140 x 10", machine.logged!!.text)
        assertNull(cable.substitution)
        assertNull(machine.substitution)
    }

    @Test
    fun `a lift with no equipment either side says so in words`() {
        val r = run(
            listOf(plan(mon, "Core", listOf(asked("Plank", "", listOf(ask(durationSec = 60)))))),
            listOf(session(mon, "Core", listOf(logged("Plank", "Band", listOf(did(durationSec = 45))))))
        )
        assertEquals("Asked no equipment · logged Band", day(r, 0).exercises[0].substitution)
    }

    /* ---------------- pooling ---------------- */

    @Test
    fun `the same lift asked for twice in a day is one prescription of more sets`() {
        val r = run(
            listOf(plan(mon, "Lower A", listOf(
                asked("Back Squat", "Barbell", listOf(ask(225.0, 5))),
                asked("Back Squat", "Barbell", listOf(ask(245.0, 3)))))),
            listOf(session(mon, "Lower A", listOf(logged("Back Squat", "Barbell",
                listOf(did(225.0, 5), did(245.0, 3))))))
        )
        assertEquals(1, day(r, 0).exercises.size)
        assertEquals("225 x 5 · 245 x 3", day(r, 0).exercises[0].asked!!.text)
    }

    @Test
    fun `the same lift logged twice in a day pools too`() {
        val r = run(
            listOf(plan(mon, "Lower A", listOf(asked("Back Squat", "Barbell",
                listOf(ask(225.0, 5), ask(245.0, 3)))))),
            listOf(session(mon, "Lower A", listOf(
                logged("Back Squat", "Barbell", listOf(did(225.0, 5))),
                logged("Back Squat", "Barbell", listOf(did(245.0, 3))))))
        )
        assertEquals("225 x 5 · 245 x 3", day(r, 0).exercises[0].logged!!.text)
        assertEquals(emptyList<PlanLog.AlsoLogged>(), day(r, 0).alsoLogged)
    }

    /* ---------------- which session answers a booking ----------------
     *
     * Web picks the session a booking was *started as* out of a day that holds two, from its
     * `startedSessionId`. Nothing on this platform records that -- starting a booked routine writes
     * an ordinary session with a fresh id -- so a booked day is compared against everything logged
     * on it, pooled, which is exactly web's own fallback when that session has been deleted. The
     * lift nobody asked for still lands under "Also logged", counted against nothing. */

    @Test
    fun `a day holding a booked session and one of your own compares the day`() {
        val r = run(
            listOf(plan(mon, "Lower A", listOf(asked("Back Squat", "Barbell", listOf(ask(225.0, 5)))))),
            listOf(
                session(mon, "Arms", listOf(logged("Barbell Curl", "Barbell", listOf(did(65.0, 10))))),
                session(mon, "Lower A", listOf(logged("Back Squat", "Barbell", listOf(did(225.0, 5)))))
            )
        )
        assertEquals("225 x 5", day(r, 0).exercises[0].logged!!.text)
        assertEquals(listOf("Barbell Curl (Barbell) · 1 set"), day(r, 0).alsoLogged.map { it.text })
    }

    @Test
    fun `a booked day whose only session is empty has nothing logged against it`() {
        val r = run(
            listOf(plan(mon, "Lower A", listOf(asked("Back Squat", "Barbell", listOf(ask(225.0, 5)))))),
            listOf(session(mon, "", emptyList()))
        )
        assertEquals("notLogged", day(r, 0).state)
        // An empty session is not an "other day logged" either.
        assertEquals(1, r!!.days.size)
    }

    @Test
    fun `a booking whose routine has been deleted keeps its name and asks for nothing`() {
        val gone = plan(mon, "Lower A", listOf(asked("Back Squat", "Barbell", listOf(ask(225.0, 5)))))
        val r = PlanLog.compare(
            // The booking, with no routine to resolve it against.
            PlanLog.bookings(listOf(gone.session), emptyList()),
            emptyList(), today, today
        )
        assertEquals("Mon 12 Oct · Lower A · not logged", day(r, 0).text)
        assertEquals(emptyList<PlanLog.ExerciseLines>(), day(r, 0).exercises)
    }

    /* ---------------- the ask comes from the plan, not the session ---------------- */

    @Test
    fun `a ramp is asked for set by set, never the flattened targets`() {
        // targetWeightLb is 225 for this exercise, so a card reading the targets would print three
        // 225s and lose the top single the coach actually wrote.
        val ramp = asked("Back Squat", "Barbell", listOf(ask(225.0, 5), ask(225.0, 5), ask(245.0, 3)))
        assertEquals(225.0, ramp.targetWeightLb)
        val r = run(
            listOf(plan(mon, "Lower A", listOf(ramp))),
            listOf(session(mon, "Lower A", listOf(logged("Back Squat", "Barbell", listOf(did(225.0, 5))))))
        )
        assertEquals("225 x 5 · 225 x 5 · 245 x 3", day(r, 0).exercises[0].asked!!.text)
        assertEquals("Asked 3 sets · logged 1", day(r, 0).exercises[0].countLine)
    }

    @Test
    fun `a prescription the targets say in full is still asked for in full`() {
        // Three identical sets are stored as targets alone, with no `prescribed` at all.
        val flat = asked("Bench Press", "Barbell", listOf(ask(185.0, 5), ask(185.0, 5), ask(185.0, 5)))
        assertNull(flat.prescribed)
        val r = run(
            listOf(plan(mon, "Upper A", listOf(flat))),
            listOf(session(mon, "Upper A", listOf(logged("Bench Press", "Barbell",
                listOf(did(185.0, 5), did(185.0, 5), did(185.0, 5))))))
        )
        assertEquals("185 x 5 · 185 x 5 · 185 x 5", day(r, 0).exercises[0].asked!!.text)
        assertNull(day(r, 0).exercises[0].countLine)
    }

    @Test
    fun `an edited session does not change what the plan asked for`() {
        // The session's own sets are what happened; the routine still holds the ask. Starting a
        // booked session pre-fills its sets from the prescription, so a card reading the session
        // would agree with the plan until the first edit and quietly stop afterwards.
        val r = run(
            listOf(plan(mon, "Lower A", listOf(asked("Back Squat", "Barbell",
                listOf(ask(225.0, 5), ask(225.0, 5)))))),
            listOf(session(mon, "Lower A", listOf(logged("Back Squat", "Barbell",
                listOf(did(135.0, 8), did(135.0, 8))))))
        )
        assertEquals("225 x 5 · 225 x 5", day(r, 0).exercises[0].asked!!.text)
        assertEquals("135 x 8 · 135 x 8", day(r, 0).exercises[0].logged!!.text)
    }

    /* ---------------- blank stays blank ---------------- */

    @Test
    fun `blank stays blank - reps with no weight is 5 reps, never 0 x 5`() {
        assertEquals("5 reps", PlanLog.setText(PrescribedSet(reps = 5)))
        assertEquals("225 lb", PlanLog.setText(PrescribedSet(weightLb = 225.0)))
        assertEquals("as written", PlanLog.setText(PrescribedSet()))
        assertEquals("10:00 1600 m", PlanLog.setText(PrescribedSet(durationSec = 600, distanceMeters = 1600.0)))
        assertEquals("45s", PlanLog.setText(PrescribedSet(durationSec = 45)))
        assertEquals("225 x 5 @8", PlanLog.setText(PrescribedSet(weightLb = 225.0, reps = 5, rpe = 8.0)))
    }

    @Test
    fun `a conditioning piece keeps its blanks through the card`() {
        val r = run(
            listOf(plan(mon, "Conditioning", listOf(asked("Row", "Machine",
                listOf(ask(durationSec = 600, distanceMeters = 1600.0)))))),
            listOf(session(mon, "Conditioning", listOf(logged("Row", "Machine",
                listOf(did(durationSec = 540, distanceMeters = 1600.0))))))
        )
        val ex = day(r, 0).exercises[0]
        assertEquals("10:00 1600 m", ex.asked!!.text)
        assertEquals("9:00 1600 m", ex.logged!!.text)
    }

    /* ---------------- the week ---------------- */

    @Test
    fun `the week runs Monday to Sunday, whichever day it is anchored on`() {
        assertEquals(mon to sun, PlanLog.weekOf(mon))
        assertEquals(mon to sun, PlanLog.weekOf(thu))
        assertEquals(mon to sun, PlanLog.weekOf(sun))
        assertEquals("2026-10-19" to "2026-10-25", PlanLog.weekOf("2026-10-19"))
    }

    @Test
    fun `a week that crosses a month says both months`() {
        assertEquals("28 Sep–4 Oct", PlanLog.rangeText("2026-09-28", "2026-10-04"))
        assertEquals("12–18 Oct", PlanLog.rangeText(mon, sun))
        assertEquals("12 Oct", PlanLog.rangeText(mon, mon))
    }

    @Test
    fun `only the anchored week is read - last week's plan is not this week's`() {
        val last = listOf(plan("2026-10-05", "Lower A",
            listOf(asked("Back Squat", "Barbell", listOf(ask(225.0, 5))))))
        assertNull(PlanLog.compare(bookings(last), emptyList(), today, today))
        val back = PlanLog.compare(bookings(last), emptyList(), today, "2026-10-05")
        assertEquals("Booked 1 day, 5–11 Oct · logged 0", back!!.head)
    }

    @Test
    fun `a day row says which day it is, because a week can cross a month`() {
        val r = run(
            listOf(plan("2026-10-01", "Lower A", listOf(asked("Back Squat", "Barbell", listOf(ask(225.0, 5)))))),
            emptyList(),
            today = "2026-10-01",
            anchor = "2026-10-01"
        )
        assertEquals("Thu 1 Oct · Lower A · to do", day(r, 0).text)
        assertEquals("Booked 1 day, 28 Sep–4 Oct · 1 to do", r!!.head)
    }

    @Test
    fun `the arrows move between weeks a coach booked, never into an empty one`() {
        val training = bookings(listOf(
            plan("2026-09-28", "Lower A", listOf(asked("Back Squat", "Barbell", listOf(ask(225.0, 5))))),
            plan(mon, "Lower A", listOf(asked("Back Squat", "Barbell", listOf(ask(225.0, 5))))),
            plan("2026-11-02", "Lower A", listOf(asked("Back Squat", "Barbell", listOf(ask(225.0, 5)))))
        ))
        // Two empty weeks sit between 12 Oct and 2 Nov, and the arrow skips both -- a card that
        // vanished on the way would take its own arrows with it.
        assertEquals("2026-11-02", PlanLog.adjacentWeek(training, mon, 1))
        assertEquals("2026-09-28", PlanLog.adjacentWeek(training, mon, -1))
        assertNull(PlanLog.adjacentWeek(training, "2026-09-28", -1))
        assertNull(PlanLog.adjacentWeek(training, "2026-11-02", 1))
        assertNull(PlanLog.adjacentWeek(emptyList(), mon, 1))
    }

    @Test
    fun `a week reached by an arrow is the same card as one reached by a date`() {
        val training = bookings(listOf(plan("2026-09-28", "Lower A",
            listOf(asked("Back Squat", "Barbell", listOf(ask(225.0, 5)))))))
        val back = PlanLog.adjacentWeek(training, mon, -1)!!
        val r = PlanLog.compare(training, emptyList(), today, back)
        assertEquals("Booked 1 day, 28 Sep–4 Oct · logged 0", r!!.head)
    }

    /* ---------------- a week with no plan at all ---------------- */

    @Test
    fun `a plan never accepted is no card and no explanation`() {
        assertNull(run(emptyList(), emptyList()))
        assertEquals(0, PlanLog.lines(null).size)
    }

    @Test
    fun `a week with no booking is no card, however much was logged in it`() {
        // Your own training is never held up against a plan nobody wrote.
        assertNull(run(
            listOf(plan("2026-10-05", "Lower A", listOf(asked("Back Squat", "Barbell", listOf(ask(225.0, 5)))))),
            weekWorkouts()
        ))
    }

    @Test
    fun `a booking with no date books nothing`() {
        val orphan = ScheduledSession(routineId = "r", routineName = "Lower A", date = "")
        assertEquals(emptyList<PlanLog.Booking>(), PlanLog.bookings(listOf(orphan), emptyList()))
        assertNull(PlanLog.compare(PlanLog.bookings(listOf(orphan), emptyList()), emptyList(), today, today))
    }

    /* ---------------- which session answers a booking ----------------
     *
     * Web's own four cases (`plan-log.test.mjs`), plus the one this build has that web never had: a
     * log written before the link was recorded at all. */

    @Test
    fun `the session started from the booking is the one compared`() {
        val booked = session(mon, "Lower A",
            listOf(logged("Back Squat", "Barbell", listOf(did(225.0, 5)))))
        val own = session(mon, "Arms", listOf(logged("Barbell Curl", "Barbell", listOf(did(65.0, 10)))))
        val r = run(
            listOf(plan(mon, "Lower A",
                listOf(asked("Back Squat", "Barbell", listOf(ask(225.0, 5)))),
                startedSessionId = booked.id)),
            listOf(own, booked)
        )
        val row = day(r, 0)
        assertEquals("225 x 5", row.exercises[0].logged!!.text)
        assertEquals(listOf("Barbell Curl (Barbell) · 1 set"), row.alsoLogged.map { it.text })
    }

    @Test
    fun `a session logged on a booked day that answers nothing is only Also logged`() {
        // Nothing the coach asked for came back, and a lift nobody asked for did. The booked lift is
        // "not logged" beside it; neither line says anything about the other.
        val booked = session(mon, "Lower A", emptyList())
        val own = session(mon, "Arms", listOf(logged("Barbell Curl", "Barbell", listOf(did(65.0, 10)))))
        val r = run(
            listOf(plan(mon, "Lower A",
                listOf(asked("Back Squat", "Barbell", listOf(ask(225.0, 5)))),
                startedSessionId = booked.id)),
            listOf(booked, own),
            today = tue
        )
        val row = day(r, 0)
        assertEquals("notLogged", row.state)
        assertEquals(listOf("Barbell Curl (Barbell) · 1 set"), row.alsoLogged.map { it.text })
        assertEquals(
            listOf(
                "Booked 1 day, 12–18 Oct · logged 0",
                "Mon 12 Oct · Lower A · not logged",
                "Back Squat (Barbell) · not logged",
                "Also logged",
                "Barbell Curl (Barbell) · 1 set",
                PlanLog.FOOTER
            ),
            PlanLog.lines(r)
        )
    }

    @Test
    fun `two sessions on one booked day are the booked one and the other one`() {
        // The whole of what the link buys: the second session repeats a planned lift, and without the
        // link its sets would pool into the comparison and read as the plan answered twice over.
        val booked = session(mon, "Lower A",
            listOf(logged("Back Squat", "Barbell", listOf(did(225.0, 5), did(225.0, 5)))))
        val later = session(mon, "Extra",
            listOf(logged("Back Squat", "Barbell", listOf(did(135.0, 12)))))
        val plans = listOf(plan(mon, "Lower A",
            listOf(asked("Back Squat", "Barbell", listOf(ask(225.0, 5), ask(225.0, 5)))),
            startedSessionId = booked.id))
        val row = day(run(plans, listOf(booked, later)), 0)
        assertEquals("225 x 5 · 225 x 5", row.exercises[0].logged!!.text)
        assertNull("the ask was answered exactly, so nothing counts sets", row.exercises[0].countLine)
        assertEquals(listOf("Back Squat (Barbell) · 1 set"), row.alsoLogged.map { it.text })
    }

    @Test
    fun `a booking whose session was deleted falls back to the day`() {
        val r = run(
            listOf(plan(mon, "Lower A",
                listOf(asked("Back Squat", "Barbell", listOf(ask(225.0, 5)))),
                startedSessionId = "gone")),
            listOf(session(mon, "Lower A",
                listOf(logged("Back Squat", "Barbell", listOf(did(225.0, 5))))))
        )
        assertEquals("logged", day(r, 0).state)
        assertEquals("225 x 5", day(r, 0).exercises[0].logged!!.text)
        assertEquals(emptyList<String>(), day(r, 0).alsoLogged.map { it.text })
    }

    @Test
    fun `a log written before this change is pooled exactly as it was`() {
        // Every booking in a file written before the link was recorded: no id, so the day is the
        // join, both sessions on it pooled into one answer -- the behaviour this build shipped with
        // and the behaviour it keeps wherever there is no id to read.
        val plans = listOf(plan(mon, "Lower A",
            listOf(asked("Back Squat", "Barbell", listOf(ask(225.0, 5), ask(225.0, 5))))))
        val row = day(run(plans, listOf(
            session(mon, "Lower A", listOf(logged("Back Squat", "Barbell", listOf(did(225.0, 5))))),
            session(mon, "Extra", listOf(logged("Back Squat", "Barbell", listOf(did(135.0, 12)))))
        )), 0)
        // Both sessions' sets under one "Logged" row, and the second session is not "Also logged":
        // with no id there is nothing to tell the two apart, which the card does not pretend to.
        assertEquals("225 x 5 · 135 x 12", row.exercises[0].logged!!.text)
        assertEquals(emptyList<String>(), row.alsoLogged.map { it.text })
    }

    @Test
    fun `an id is only ever used to pick a session out of its own day`() {
        // A booking pointing at a session logged on another day is a booking with nothing logged
        // against it, and that session is a day of its own. Nothing pairs across dates.
        val elsewhere = session(tue, "Lower A",
            listOf(logged("Back Squat", "Barbell", listOf(did(225.0, 5)))))
        val r = run(
            listOf(plan(mon, "Lower A",
                listOf(asked("Back Squat", "Barbell", listOf(ask(225.0, 5)))),
                startedSessionId = elsewhere.id)),
            listOf(elsewhere)
        )
        assertEquals("notLogged", dayOn(r, mon).state)
        assertEquals("notBooked", dayOn(r, tue).state)
        assertEquals(listOf("Back Squat (Barbell) · 1 set"), dayOn(r, tue).alsoLogged.map { it.text })
    }

    /* ---------------- who sent it ----------------
     *
     * Web's own two cases plus the two a phone has and a browser does not: a plan accepted before a
     * name was ever stored, and a name with no end to it. */

    @Test
    fun `the week is signed the way the prescribed card signs a session`() {
        val training = weekTraining()
        val r = run(training, weekWorkouts())
        assertEquals("From Doug", PlanLog.sentBy(r!!, bookings(training)))
    }

    @Test
    fun `a week no plan named anybody for reads exactly as it always did`() {
        // Every booking a build before this wrote: `fromCoach` absent from the file, so null here.
        val training = listOf(plan(mon, "Lower A",
            listOf(asked("Back Squat", "Barbell", listOf(ask(225.0, 5)))), fromCoach = null))
        val r = run(training, emptyList())
        assertEquals("From your coach", PlanLog.sentBy(r!!, bookings(training)))
        assertEquals(PlanLog.SENT_BY, PlanLog.sentBy(r, bookings(training)))
        // A blank name is nobody, not an empty "From ".
        val blank = listOf(plan(mon, "Lower A", emptyList(), fromCoach = "   "))
        assertEquals(PlanLog.SENT_BY,
            PlanLog.sentBy(PlanLog.compare(bookings(blank), emptyList(), today, today)!!, bookings(blank)))
    }

    @Test
    fun `two coaches who booked one week are both named, once each`() {
        val training = listOf(
            plan(mon, "Lower A", emptyList(), fromCoach = "Doug"),
            plan(tue, "Upper B", emptyList(), fromCoach = "Sam"),
            plan(wed, "Lower B", emptyList(), fromCoach = "Doug")
        )
        val r = run(training, emptyList())
        assertEquals("From Doug · Sam", PlanLog.sentBy(r!!, bookings(training)))
    }

    @Test
    fun `only the week on screen is signed`() {
        // A coach who booked last week does not sign this one: `sentBy` reads the window `compare`
        // read, and nothing carries from one week to the next here either.
        val training = listOf(
            plan(mon, "Lower A", emptyList(), fromCoach = "Doug"),
            plan("2026-10-05", "Lower A", emptyList(), fromCoach = "Sam")
        )
        val r = run(training, emptyList())
        assertEquals("From Doug", PlanLog.sentBy(r!!, bookings(training)))
    }

    @Test
    fun `a name with no end to it reaches the line whole and unread`() {
        // The sentence is not shortened here: three platforms share it, and `PlanWeekCard` draws it
        // in two lines and an ellipsis rather than one of them printing a different string. What is
        // guaranteed here is that nothing is silently dropped and nothing is interpreted -- a name
        // is a string on a screen and never a key, a pattern or a tag.
        val long = "Coach " + "Wolfeschlegelsteinhausenbergerdorff ".repeat(20).trim()
        val training = listOf(plan(mon, "Lower A", emptyList(), fromCoach = long))
        val r = run(training, emptyList())
        assertEquals("From $long", PlanLog.sentBy(r!!, bookings(training)))
        // And the name reaches nowhere else. Not one line of the card carries it.
        PlanLog.lines(r).forEach { line ->
            assertFalse("a name reached $line", line.contains("Wolfeschlegel"))
        }
    }

    @Test
    fun `nothing below the signature is signed`() {
        // The card names a coach once, above the head, and never again -- not on a day row, not on a
        // lift, not in the footer. The signature is the only line `lines()` leaves out for that
        // reason: it is not a sentence about the week.
        val r = everyState()
        PlanLog.lines(r).forEach { line -> assertFalse("$line names a coach", line.contains("Doug")) }
    }

    /* ---------------- the meals a coach booked ----------------
     *
     * The card states them and says nothing about the food log, whatever the food log holds. Coach's
     * card does the other half -- the foods a client stamped with that slot, a count above them, a
     * note under the card -- and none of it is repeated here: see the head of [PlanLog] for why at
     * length. Web's `plan-log.test.mjs` is the reference, case for case. */

    /** A meal a coach booked, as [PlanImporter] writes one out of a plan's `m` entry. */
    private fun meal(
        date: String,
        slot: String,
        recipeName: String,
        servings: Double = 1.0,
        coachName: String? = "Doug",
        loggedFoodEntryId: String? = null
    ) = PlannedMeal(
        id = nextId(), recipeId = nextId(), date = date, meal = slot, servings = servings,
        recipeName = recipeName,
        snapshotNutrition = RecipeNutrition(600.0, 40.0, 50.0, 20.0, 6.0),
        loggedFoodEntryId = loggedFoodEntryId, fromCoach = true, coachName = coachName
    )

    /** A meal placed on this device, which no coach booked. */
    private fun myMeal(date: String, slot: String, recipeName: String, servings: Double = 1.0) =
        meal(date, slot, recipeName, servings).copy(fromCoach = false, coachName = null)

    private fun mealWeek(): List<PlannedMeal> = listOf(
        meal(mon, "BREAKFAST", "Overnight Oats", 1.0),
        meal(mon, "DINNER", "Beef Chilli", 2.0),
        meal(fri, "DINNER", "Beef Chilli", 2.0)
    )

    private fun runMeals(
        plans: List<Plan>,
        sessions: List<WorkoutSession>,
        plan: List<PlannedMeal>,
        today: String = this.today,
        anchor: String = this.today
    ): PlanLog.Result? = PlanLog.compare(bookings(plans), sessions, today, anchor, plan)

    @Test
    fun `a week of booked meals states each one, in the order a day is eaten`() {
        val r = runMeals(weekTraining(), weekWorkouts(), mealWeek())
        val monday = day(r, 0)
        assertEquals(
            listOf("Breakfast · Overnight Oats · 1 serving", "Dinner · Beef Chilli · 2 servings"),
            monday.meals.map { it.title }
        )
        assertEquals("Mon 12 Oct · Lower A · logged · 2 meals booked", monday.text)
    }

    @Test
    fun `a meal row is the slot, the dish and how much of it, and nothing else`() {
        val r = runMeals(emptyList(), emptyList(), listOf(meal(mon, "DINNER", "Beef Chilli", 2.0)),
            today = mon, anchor = mon)
        val row = day(r, 0).meals.first()
        assertEquals(
            listOf("date", "detail", "name", "servings", "slot", "slotLabel", "title"),
            PlanLog.MealRow::class.java.declaredFields
                .map { it.name }.filterNot { it.startsWith("$") }.sorted()
        )
        // The whole line and its two columns cannot drift: the view draws the columns and the tests
        // read the line.
        assertEquals("${row.slotLabel} · ${row.detail}", row.title)
        assertEquals("Beef Chilli · 2 servings", row.detail)
    }

    @Test
    fun `nothing on a meal row says anything about what was eaten`() {
        val r = runMeals(weekTraining(), weekWorkouts(), mealWeek())
        val every = PlanLog.lines(r).joinToString(" · ").lowercase(Locale.US)
        listOf("logged at", "nothing logged at", "not itemised", "no food logged", "foods logged",
            "not tied to a meal", "only they know", "only you know at").forEach { phrase ->
            assertFalse("\"$phrase\" reached a screen: $every", every.contains(phrase))
        }
        // Coach's per-slot verdict and its note have no counterpart here at all: a result is a week's
        // two records and its counts, with no meal footer and no line about the food log.
        assertEquals(
            listOf("counts", "days", "footer", "from", "head", "range", "to"),
            PlanLog.Result::class.java.declaredFields
                .map { it.name }.filterNot { it.startsWith("$") }.sorted()
        )
    }

    @Test
    fun `what the food log holds changes nothing on a meal row`() {
        fun lines(logged: List<String?>): List<String> = PlanLog.lines(
            runMeals(weekTraining(), weekWorkouts(),
                mealWeek().mapIndexed { i, m -> m.copy(loggedFoodEntryId = logged.getOrNull(i)) })
        )
        // This device really does know a planned meal was logged -- loggedFoodEntryId -- which is the
        // whole point: none of it, one of them, or all three, one card three times.
        val none = lines(emptyList())
        assertEquals(none, lines(listOf("f1")))
        assertEquals(none, lines(listOf("f1", "f2", "f3")))
        assertTrue(mealWeek().all { !it.isLogged })
    }

    @Test
    fun `the card reads no food log, in the source as well as in its output`() {
        // Comments are stripped first: the head of the file discusses the food log at length, and
        // names loggedFoodEntryId to explain why it is not read. The package and import lines go too
        // -- this app is called `macrocalc`, so its own name would answer for `macro`.
        val code = java.io.File("src/main/java/com/dugcanlift/macrocalc/data/PlanLog.kt")
            .readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("(?m)^\\s*//.*$"), "")
            .replace(Regex("(?m)^\\s*(package|import) .*$"), "")
        listOf("FoodEntry", "loggedFoodEntryId", "snapshotNutrition", "calorie", "kcal",
            "nutrition", "Nutrition", "macro", "proteinG").forEach { token ->
            assertFalse("$token is read by the card", code.contains(token))
        }
    }

    @Test
    fun `a meal you placed yourself is not your coach's plan`() {
        val own = listOf(myMeal(mon, "DINNER", "Beef Chilli", 2.0), myMeal(fri, "LUNCH", "Overnight Oats"))
        // Your own note-taking is not an expectation, and Cook is where you move it.
        assertNull(runMeals(emptyList(), emptyList(), own))
        val r = runMeals(weekTraining(), weekWorkouts(), own + mealWeek())
        assertEquals(
            listOf("Breakfast · Overnight Oats · 1 serving", "Dinner · Beef Chilli · 2 servings"),
            day(r, 0).meals.map { it.title }
        )
        assertEquals(3, r!!.counts.meals)
        assertEquals(emptyList<PlanLog.MealRow>(), PlanLog.bookedMeals(own))
    }

    @Test
    fun `a plan of meals with no training is a card, where before there was none`() {
        val r = runMeals(emptyList(), emptyList(), mealWeek())
        assertEquals(
            listOf(
                "Booked 2 days, 12–18 Oct · 3 meals booked · 1 to do",
                "Mon 12 Oct · 2 meals booked",
                "Meals",
                "Breakfast · Overnight Oats · 1 serving",
                "Dinner · Beef Chilli · 2 servings",
                "Fri 16 Oct · 1 meal booked · to do",
                "Meals",
                "Dinner · Beef Chilli · 2 servings",
                PlanLog.FOOTER
            ),
            PlanLog.lines(r)
        )
        assertEquals(listOf("meals", "toDo"), r!!.days.map { it.state })
        assertEquals(listOf(emptyList<PlanLog.ExerciseLines>(), emptyList()), r.days.map { it.exercises })
    }

    @Test
    fun `a booked meal on a day still ahead is to do, not an absence`() {
        val r = runMeals(emptyList(), emptyList(), listOf(meal(fri, "DINNER", "Beef Chilli", 2.0)))
        assertEquals("toDo", day(r, 0).state)
        assertEquals("Fri 16 Oct · 1 meal booked · to do", day(r, 0).text)
        assertEquals("Booked 1 day, 12–18 Oct · 1 meal booked · 1 to do", r!!.head)
    }

    @Test
    fun `a day in the past that booked only food carries no verdict at all`() {
        val r = runMeals(emptyList(), emptyList(), listOf(meal(mon, "DINNER", "Beef Chilli", 2.0)))
        assertEquals("meals", day(r, 0).state)
        assertEquals("Mon 12 Oct · 1 meal booked", day(r, 0).text)
        // Never "not logged" against a meal, and no word standing in for one.
        PlanLog.WORDS.values.forEach { word ->
            assertFalse("$word judged a meal", day(r, 0).text.contains(word))
        }
        assertFalse("the one state with no word of its own", PlanLog.WORDS.containsKey("meals"))
        assertEquals("Booked 1 day, 12–18 Oct · 1 meal booked", r!!.head)
    }

    @Test
    fun `a day booked for food that you trained anyway says both, on one row`() {
        val r = runMeals(
            emptyList(),
            listOf(session(mon, "Arms", listOf(logged("Barbell Curl", "Barbell", listOf(did(65.0, 10)))))),
            listOf(meal(mon, "DINNER", "Beef Chilli", 2.0))
        )
        // Coach's word for training nobody booked, on the row rather than in a second row of its own:
        // this card opens one date at a time.
        assertEquals(listOf("Mon 12 Oct · Arms · not booked · 1 meal booked"), r!!.days.map { it.text })
        assertEquals(listOf("Barbell Curl (Barbell) · 1 set"), day(r, 0).alsoLogged.map { it.text })
        assertEquals(listOf("Dinner · Beef Chilli · 2 servings"), day(r, 0).meals.map { it.title })
        assertEquals(1, r.counts.other)
    }

    @Test
    fun `a booked session keeps its own name and its blank`() {
        // The fallback to a logged session's name is for a day that books no session at all. A
        // booking with a blank name reads as it always has.
        val r = run(
            listOf(plan(mon, "", listOf(asked("Back Squat", "Barbell", listOf(ask(225.0, 5)))))),
            listOf(session(mon, "Arms", listOf(logged("Back Squat", "Barbell", listOf(did(225.0, 5))))))
        )
        assertEquals("Mon 12 Oct · logged", day(r, 0).text)
    }

    @Test
    fun `two dishes at one meal are two dishes, in the order they were booked`() {
        val r = runMeals(emptyList(), emptyList(), listOf(
            meal(mon, "DINNER", "Beef Chilli", 2.0), meal(mon, "DINNER", "Overnight Oats", 1.0),
            meal(mon, "SNACK", "Overnight Oats", 1.0), meal(mon, "LUNCH", "Beef Chilli", 1.0)))
        assertEquals(
            listOf(
                "Lunch · Beef Chilli · 1 serving",
                "Dinner · Beef Chilli · 2 servings",
                "Dinner · Overnight Oats · 1 serving",
                "Snack · Overnight Oats · 1 serving"
            ),
            day(r, 0).meals.map { it.title }
        )
    }

    @Test
    fun `a slot this build cannot read is still a dish a coach booked`() {
        val r = runMeals(emptyList(), emptyList(), listOf(
            meal(mon, "BRUNCH", "Beef Chilli", 1.0), meal(mon, "BREAKFAST", "Overnight Oats", 1.0)))
        // Sorted last rather than dropped: hiding it would hide the plan. This build really can hold
        // one -- a slot is free text in the file and `mealOrDefault` coerces it only for the log.
        assertEquals(
            listOf("Breakfast · Overnight Oats · 1 serving", "Beef Chilli · 1 serving"),
            day(r, 0).meals.map { it.title }
        )
        assertEquals("", day(r, 0).meals[1].slotLabel)
        assertNull(day(r, 0).meals[1].slot)
    }

    @Test
    fun `the servings are the coach's own number, and a dish always has a name`() {
        val r = runMeals(emptyList(), emptyList(), listOf(
            meal(mon, "LUNCH", "Beef Chilli", 0.5),
            meal(mon, "DINNER", "", 0.0),
            meal(mon, "SNACK", "   ", Double.NaN),
            meal(mon, "BREAKFAST", "Overnight Oats", 3.0)))
        assertEquals(
            listOf(
                "Overnight Oats · 3 servings",
                "Beef Chilli · 0.5 servings",
                "Recipe · 1 serving",
                "Recipe · 1 serving"
            ),
            day(r, 0).meals.map { it.detail }
        )
    }

    @Test
    fun `the slots are PLAN-FORMAT's own four, in its own order`() {
        assertEquals(listOf(Meal.BREAKFAST, Meal.LUNCH, Meal.DINNER, Meal.SNACK), PlanLog.MEAL_SLOTS)
        assertEquals(listOf("Breakfast", "Lunch", "Dinner", "Snack"), PlanLog.MEAL_SLOTS.map { it.label })
    }

    @Test
    fun `a meal booked outside the week on screen is not in it`() {
        val r = runMeals(weekTraining(), weekWorkouts(),
            listOf(meal("2026-10-19", "DINNER", "Beef Chilli")))
        assertEquals(0, r!!.counts.meals)
        assertTrue(r.days.all { it.meals.isEmpty() })
    }

    @Test
    fun `the head counts meals booked, and never meals eaten`() {
        val r = runMeals(weekTraining(), weekWorkouts(), mealWeek())
        // `logged 1` under `Booked 3 days` would read as one day of three when one of them booked no
        // session, so once meals are in the line the figure says what it counts. Coach's sentence.
        assertEquals(
            "Booked 3 days, 12–18 Oct · 3 meals booked · 3 training days, 1 logged · 1 to do · 1 other day logged",
            r!!.head
        )
        assertEquals(
            PlanLog.Counts(booked = 3, training = 3, meals = 3, logged = 1, notLogged = 1,
                toDo = 1, other = 1),
            r.counts
        )
    }

    @Test
    fun `a week of food entirely ahead does not open with a nought`() {
        val r = runMeals(emptyList(), emptyList(),
            listOf(meal(fri, "DINNER", "Beef Chilli"), meal(sat, "LUNCH", "Beef Chilli")))
        assertEquals("Booked 2 days, 12–18 Oct · 2 meals booked · 2 to do", r!!.head)
    }

    @Test
    fun `the arrows reach a week a coach booked food in`() {
        val theirs = listOf(meal("2026-10-05", "DINNER", "Beef Chilli"))
        assertEquals("2026-10-05", PlanLog.adjacentWeek(emptyList(), mon, -1, theirs))
        assertNull("and only when there are meals to reach",
            PlanLog.adjacentWeek(emptyList(), mon, -1))
        // A meal placed on this device books no week: the arrow would land on a card that is not
        // there.
        assertNull(PlanLog.adjacentWeek(emptyList(), mon, -1,
            listOf(myMeal("2026-10-05", "DINNER", "Beef Chilli"))))
    }

    @Test
    fun `a week of food is signed by whoever sent it`() {
        val plans = mealWeek()
        val r = runMeals(emptyList(), emptyList(), plans)!!
        assertEquals("From Doug", PlanLog.sentBy(r, emptyList(), plans))
        assertEquals(PlanLog.SENT_BY, PlanLog.sentBy(r, emptyList(),
            listOf(meal(mon, "DINNER", "Beef Chilli", 2.0, coachName = null))))
        assertEquals("a meal you placed yourself does not sign a week", PlanLog.SENT_BY,
            PlanLog.sentBy(r, emptyList(), listOf(myMeal(mon, "DINNER", "Beef Chilli", 2.0))))
    }

    /* ---------------- the line discipline ----------------
     *
     * Coach web's list, word for word, plus the words this screen could reach for and Coach's could
     * not. Coach reads about somebody else and can only patronise them by accident; this reads about
     * the person holding the phone, which is where a training app turns into a scolding one. */

    private val forbidden = listOf("should", "fix", "warning", "target", "too ", "concern",
        "missed", "skipped", "failed", "poor", "behind", "compliance", "adherence", "streak", "%")

    private val forbiddenHere = listOf("score", "grade", "percent", "rate", "average",
        "well done", "good job", "great work", "keep it up", "nice work", "on track", "off track",
        "slack", "lazy", "proud", "ashamed", "congrat", "deserve", "reward", "excuse", "must ",
        "need to", "make up", "catch up", "you did not", "you have not", "perfect week", "consistency")

    /* Every state the card has, in one week: a day booked and logged whole, a day booked and logged
     * in part, a day booked with nothing logged, a day still ahead, a day logged that nothing was
     * booked for; and a matched lift, a substituted one, one short on a side, one short on sets, one
     * not logged at all and one nobody asked for. */
    private fun everyStateTraining(): List<Plan> = listOf(
        plan(mon, "Lower A", listOf(
            asked("Back Squat", "Barbell", listOf(ask(225.0, 5), ask(225.0, 5), ask(245.0, 3))),
            asked("Romanian Deadlift", "Barbell",
                listOf(ask(185.0, 8), ask(185.0, 8), ask(185.0, 8), ask(185.0, 8))),
            asked("Bulgarian Split Squat", "Dumbbell",
                listOf(ask(40.0, 8), ask(40.0, 8), ask(40.0, 8)), eachSide = true),
            asked("Overhead Press", "Barbell", listOf(ask(95.0, 8))),
            // Logged below on a Smith machine: the substitution.
            asked("Bench Press", "Barbell", listOf(ask(185.0, 5)))
        )),
        plan(tue, "Upper B", listOf(asked("Bench Press", "Barbell", listOf(ask(185.0, 5), ask(185.0, 5))))),
        plan(fri, "Lower B", listOf(
            asked("Front Squat", "Barbell", listOf(ask(165.0, 5), ask(165.0, 5))),
            asked("Split Squat", "Dumbbell", listOf(ask(35.0, 10), ask(35.0, 10)), eachSide = true)
        )),
        plan(sat, "Upper A", listOf(asked("Bench Press", "Barbell", listOf(ask(185.0, 5)))))
    )

    private fun everyState(): PlanLog.Result {
        val workouts = weekWorkouts().toMutableList()
        workouts[0] = workouts[0].copy(
            exercises = workouts[0].exercises + logged("Bench Press", "Smith machine", listOf(did(185.0, 5)))
        )
        return PlanLog.compare(bookings(everyStateTraining()), workouts, today, today)!!
    }

    /* And the words food reaches for, which is where a training app turns into a diet one fastest.
     * Web's own list. Nothing here says what was eaten, so none of these has anywhere to come from --
     * which is the point of listing them: the day one does, this fails. */
    private val forbiddenFood = listOf("ate ", "eaten", "logged at", "not itemised", "no food",
        "foods logged", "calorie", "kcal", "macro", "protein", "cheat", "treat", "diet", "junk",
        "clean eating", "binge", "indulge", "craving", "hungry", "willpower", "over budget",
        "under budget", "left today")

    /* The same week with the food half of a plan in it, and a day in the past that booked food and
     * nothing else -- the one state the training card could not have. */
    private fun everyStateWithMeals(): PlanLog.Result {
        val training = everyStateTraining()
        val workouts = weekWorkouts().toMutableList()
        workouts[0] = workouts[0].copy(
            exercises = workouts[0].exercises + logged("Bench Press", "Smith machine", listOf(did(185.0, 5)))
        )
        // The session nobody booked moves to today, leaving Wednesday free to be the one state the
        // training card could not have: a day in the past that booked food and nothing else.
        workouts[1] = workouts[1].copy(date = thu)
        return PlanLog.compare(
            bookings(training), workouts, today, today,
            mealWeek() + listOf(
                meal(tue, "LUNCH", "Beef Chilli", 1.0),
                meal(wed, "BREAKFAST", "Overnight Oats", 1.0),
                meal(thu, "LUNCH", "Beef Chilli", 1.0),
                meal(sat, "SNACK", "Overnight Oats", 1.0)
            )
        )!!
    }

    @Test
    fun `nothing in this card tells a lifter what to do, with meals in it too`() {
        listOf(everyState(), everyStateWithMeals()).forEach { fixture ->
            val every = PlanLog.lines(fixture).joinToString(" · ").lowercase(Locale.US)
            assertTrue("the fixture should exercise the whole card", every.length > 400)
            (forbidden + forbiddenHere + forbiddenFood).forEach { word ->
                assertFalse("\"$word\" reached a screen: $every", every.contains(word))
            }
        }
    }

    @Test
    fun `every state the food half has is in that fixture too`() {
        val r = everyStateWithMeals()
        assertEquals(
            listOf("logged", "meals", "notBooked", "notLogged", "toDo"),
            r.days.map { it.state }.distinct().sorted()
        )
        // A day booked for a session and for food; a day booked for food alone, in the past and still
        // ahead; a day booked for food that was trained anyway.
        assertTrue(r.days.any { it.meals.isNotEmpty() && it.exercises.isNotEmpty() })
        assertTrue(r.days.any { it.meals.isNotEmpty() && it.state == "meals" })
        assertTrue(r.days.any { it.meals.isNotEmpty() && it.state == "toDo" })
        assertTrue(r.days.any { it.meals.isNotEmpty() && it.state == "notBooked" })
        assertEquals(7, r.counts.meals)
    }

    /**
     * The whole card, line for line, **frozen from the build that shipped on 2026-09-24** -- before
     * any of this. The meal count, the clause and the rows appear only where there is a booked meal
     * to carry them, so a training week is untouched; and no meals at all, a list of this device's
     * own meals, and no `plan` argument are the same week. LIFT web and LIFT iPhone pin the same 35
     * lines, and all three printed them identically before this change.
     */
    @Test
    fun `a week a coach booked no meals in reads exactly as it did`() {
        val before = listOf(
            "Booked 4 days, 12–18 Oct · logged 1 · 2 to do · 1 other day logged",
            "Mon 12 Oct · Lower A · logged",
            "Back Squat (Barbell)",
            "Asked 225 x 5 · 225 x 5 · 245 x 3",
            "Logged 225 x 5 · 225 x 5 · 245 x 2",
            "Romanian Deadlift (Barbell)",
            "Asked 4 sets · logged 3",
            "Asked 185 x 8 · 185 x 8 · 185 x 8 · 185 x 8",
            "Logged 185 x 8 · 185 x 8 · 185 x 6",
            "Bulgarian Split Squat (Dumbbell) · each side",
            "L 3/3 · R 2/3",
            "Asked 40 x 8 · 40 x 8 · 40 x 8 each side",
            "Logged L 40 x 8 · 40 x 8 · 40 x 5   R 40 x 8 · 40 x 8",
            "Overhead Press (Barbell) · not logged",
            "Bench Press (Barbell)",
            "Asked 185 x 5",
            "Logged 185 x 5",
            "Asked Barbell · logged Smith machine",
            "Also logged",
            "Leg Press (Machine) · 3 sets",
            "Tue 13 Oct · Upper B · not logged",
            "Bench Press (Barbell) · not logged",
            "Wed 14 Oct · Arms · not booked",
            "Also logged",
            "Barbell Curl (Barbell) · 2 sets",
            "Fri 16 Oct · Lower B · to do",
            "Front Squat (Barbell)",
            "Asked 165 x 5 · 165 x 5",
            "Split Squat (Dumbbell) · each side",
            "Each side · L 2 · R 2",
            "Asked 35 x 10 · 35 x 10 each side",
            "Sat 17 Oct · Upper A · to do",
            "Bench Press (Barbell)",
            "Asked 185 x 5",
            PlanLog.FOOTER
        )
        assertEquals(before, PlanLog.lines(everyState()))

        fun week(plan: List<PlannedMeal>): List<String> {
            val workouts = weekWorkouts().toMutableList()
            workouts[0] = workouts[0].copy(
                exercises = workouts[0].exercises +
                    logged("Bench Press", "Smith machine", listOf(did(185.0, 5)))
            )
            return PlanLog.lines(
                PlanLog.compare(bookings(everyStateTraining()), workouts, today, today, plan))
        }
        assertEquals(before, week(emptyList()))
        assertEquals(before, week(listOf(myMeal(mon, "DINNER", "Beef Chilli", 2.0))))
        assertEquals(0, everyState().counts.meals)
        assertEquals("every booked day of a training week books training",
            4, everyState().counts.training)
    }

    @Test
    fun `the footer is unchanged by the meals under it`() {
        val r = everyStateWithMeals()
        // Coach's third sentence is its meal note, which exists to disclaim a join Coach cannot make.
        // This card names no logged food at all, so it has nothing to disclaim and the footer it
        // already had is the footer it keeps -- once, at the foot.
        assertEquals(PlanLog.FOOTER, r.footer)
        assertEquals(1, PlanLog.lines(r).count { it == PlanLog.FOOTER })
        assertFalse(PlanLog.lines(r).any { it.contains("was this dish, only they know") })
    }

    @Test
    fun `nothing in this card tells a lifter what to do`() {
        val every = PlanLog.lines(everyState()).joinToString(" · ").lowercase(Locale.US)
        assertTrue("the fixture should exercise the whole card", every.length > 400)
        (forbidden + forbiddenHere).forEach { word ->
            assertFalse("\"$word\" reached a screen: $every", every.contains(word))
        }
    }

    @Test
    fun `every state the card has is in that fixture`() {
        val r = everyState()
        assertEquals(
            listOf("logged", "notBooked", "notLogged", "toDo"),
            r.days.map { it.state }.distinct().sorted()
        )
        assertEquals(
            listOf("logged", "notLogged", "toDo"),
            r.days.flatMap { day -> day.exercises.map { it.state } }.distinct().sorted()
        )
        assertTrue("a lift nobody asked for", r.days.any { it.alsoLogged.isNotEmpty() })
        assertTrue("a substitution", r.days.any { day -> day.exercises.any { it.substitution != null } })
        assertTrue("a side line", r.days.any { day -> day.exercises.any { it.sideLine != null } })
        assertTrue("a count line", r.days.any { day -> day.exercises.any { it.countLine != null } })
    }

    @Test
    fun `nothing here aggregates a week into a score`() {
        val r = everyState()

        // A week counts the days it booked and the days it holds, and nothing else: no all-time
        // figure, no trend, nothing carried to next week.
        // `training` and `meals` are counts of what a coach wrote -- days that book a session, and
        // dishes booked. Neither carries a figure for what came back: `logged` is that figure for
        // training, and there is none for a meal.
        assertEquals(
            listOf("booked", "logged", "meals", "notLogged", "other", "toDo", "training"),
            PlanLog.Counts::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }.sorted()
        )
        val banned = Regex("score|percent|ratio|average|total|streak|grade|adherence|compliance",
            RegexOption.IGNORE_CASE)
        PlanLog.lines(r).forEach { line ->
            assertFalse("$line carries a percentage", line.contains("%"))
            assertFalse("$line reads as a grade", banned.containsMatchIn(line))
        }
        // Nothing the rule exposes is named as one either -- web's own check, over the names this
        // object hands out rather than the private helpers behind them.
        PlanLog::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .forEach { method ->
                assertFalse("${method.name} reads as a grade", banned.containsMatchIn(method.name))
            }
        PlanLog.Counts::class.java.declaredFields.forEach { field ->
            assertFalse("${field.name} reads as a grade", banned.containsMatchIn(field.name))
        }
    }

    @Test
    fun `nothing in this feature carries from one week to the next`() {
        // compare() is handed one week and reads one week: its own window is the only stretch of the
        // log it ever touches.
        val training = weekTraining() + plan("2026-10-05", "Lower A",
            listOf(asked("Back Squat", "Barbell", listOf(ask(225.0, 5)))))
        val r = run(training, weekWorkouts())
        assertEquals(3, r!!.counts.booked)
        assertTrue(r.days.all { it.key >= mon && it.key <= sun })
    }

    @Test
    fun `the footer is this card's own, not the one written for a coach`() {
        assertEquals(
            "Your coach’s plan beside your own log. What else the week held, only you know.",
            PlanLog.FOOTER
        )
        // Neither of Coach's two third-party sentences can reach this screen, in any state the card
        // has: one is about a link somebody else received, the other about a window somebody else
        // chose to send.
        val every = PlanLog.lines(everyState()).joinToString(" · ")
        assertFalse("Coach's footer is a sentence about somebody else",
            every.contains("opened it, only they know"))
        assertFalse("the log is right here; the state cannot arise",
            every.contains("outside the log they sent"))
        assertFalse(PlanLog.WORDS.values.contains("outside the log they sent"))
    }

    @Test
    fun `the words a coach reads and the words a lifter reads are the same words`() {
        // The four verdicts, and the two row labels, taken from Coach's unchanged -- so describing a
        // week to each other does not mean translating it first. `to do` is the one this side adds,
        // because only the person living the week has a day that has not happened yet.
        assertEquals(
            mapOf("logged" to "logged", "toDo" to "to do",
                "notLogged" to "not logged", "notBooked" to "not booked"),
            PlanLog.WORDS
        )
        val r = everyState()
        val labels = r.days.flatMap { day ->
            day.exercises.flatMap { listOfNotNull(it.asked?.label, it.logged?.label) }
        }.distinct().sorted()
        assertEquals(listOf("Asked", "Logged"), labels)
    }

    /* ---------------- how it reads aloud ----------------
     *
     * The card is built out of short muted lines with `·` between their clauses, which is a comma
     * sighted and a fragment aloud -- and on Android worse than anywhere, because a `Row` of two
     * `Text`s is two accessibility nodes and "Asked" arrives two swipes before its numbers.
     * [PlanLog.spokenLines] is the same card said; these pin the rules rather than the strings.
     */

    @Test
    fun `a day row is one sentence, not three fragments`() {
        val spoken = PlanLog.spokenLines(everyState())
        assertFalse(spoken.joinToString(" | "), spoken.any { it.contains(" · ") })
        assertTrue(
            "no spoken day row: ${spoken.take(4)}",
            spoken.any { it == "Monday 12 October, Lower A, logged" }
        )
    }

    @Test
    fun `the date a day row says is the date it draws, in words`() {
        assertEquals("Mon 12 Oct", PlanLog.dayLabel(mon))
        assertEquals("Monday 12 October", PlanLog.spokenDayLabel(mon))
    }

    @Test
    fun `a week is a range aloud, not an en dash`() {
        assertEquals("12–18 Oct", PlanLog.rangeText("2026-10-12", "2026-10-18"))
        assertEquals("12 to 18 October", PlanLog.spokenRange("2026-10-12", "2026-10-18"))
        assertEquals("28 September to 4 October", PlanLog.spokenRange("2026-09-28", "2026-10-04"))
        assertEquals("12 October", PlanLog.spokenRange("2026-10-12", "2026-10-12"))
    }

    @Test
    fun `the asked row and the logged row are one comparison, under the lift`() {
        val r = run(
            listOf(plan(mon, "Lower A", listOf(
                asked("Back Squat", "Barbell", listOf(ask(225.0, 5), ask(225.0, 5), ask(245.0, 3)))))),
            listOf(session(mon, "Lower A", listOf(
                logged("Back Squat", "Barbell", listOf(did(225.0, 5), did(225.0, 5))))))
        )
        val lift = day(r, 0).exercises[0]
        assertEquals(
            "Back Squat (Barbell). Asked 3 sets, logged 2. " +
                "Asked 225 by 5, 225 by 5, 245 by 3. Logged 225 by 5, 225 by 5",
            lift.spoken
        )
        // ` x ` never reaches it: read literally it is the letter.
        assertFalse(lift.spoken, lift.spoken.contains(" x "))
    }

    @Test
    fun `L 3 of 3 R 2 of 3 is said in words`() {
        val r = run(
            listOf(plan(mon, "Lower A", listOf(
                asked("Split Squat", "Dumbbell",
                    listOf(ask(40.0, 8), ask(40.0, 8), ask(40.0, 8)), eachSide = true)))),
            listOf(session(mon, "Lower A", listOf(
                logged("Split Squat", "Dumbbell", listOf(
                    did(40.0, 8, side = SetSide.LEFT), did(40.0, 8, side = SetSide.LEFT),
                    did(40.0, 8, side = SetSide.LEFT), did(40.0, 8, side = SetSide.RIGHT),
                    did(40.0, 8, side = SetSide.RIGHT))))))
        )
        val lift = day(r, 0).exercises[0]
        assertEquals("the drawn line is unchanged", "L 3/3 · R 2/3", lift.sideLine)
        assertEquals("left 3 of 3, right 2 of 3", lift.spokenSideLine)
        assertEquals(
            "Logged left 40 by 8, 40 by 8, 40 by 8; right 40 by 8, 40 by 8",
            lift.logged!!.spoken
        )
        val askedRow = lift.asked!!
        assertTrue(askedRow.spoken, askedRow.spoken.endsWith(" each side"))
    }

    @Test
    fun `a day still ahead says its each-side ask in words, not in noughts`() {
        val r = run(
            listOf(plan(fri, "Lower B", listOf(
                asked("Split Squat", "Dumbbell", listOf(ask(35.0, 10), ask(35.0, 10)),
                    eachSide = true)))),
            emptyList()
        )
        val ahead = dayOn(r, fri).exercises[0]
        assertEquals("Each side · L 2 · R 2", ahead.sideLine)
        assertEquals("Each side, left 2, right 2", ahead.spokenSideLine)
    }

    @Test
    fun `a set row says its label and its numbers in one breath`() {
        // On Android they are two `Text`s in a `Row`, so two nodes -- which is why the label is
        // part of the spoken form rather than left to the view to prepend.
        val row = PlanLog.SetRow("Asked",
            PlanLog.askedGroups(listOf(ask(225.0, 5))), "")
        assertEquals("Asked 225 x 5", "${row.label} ${row.text}")
        assertEquals("Asked 225 by 5", row.spoken)
    }

    @Test
    fun `nothing a screen reader is handed changes what the card draws`() {
        // The spoken layer is labels, and the drawn lines keep their punctuation.
        val drawn = PlanLog.lines(everyState())
        assertTrue("the card still draws `·`", drawn.any { it.contains(" · ") })
        assertTrue("and still draws `L 3/3`", drawn.any { it.contains("L 3/3") })
        assertTrue("and still draws ` x `", drawn.any { it.contains(" x ") })
    }

    @Test
    fun `nothing a screen reader is handed tells a lifter what to do`() {
        // The same discipline as the drawn lines, over the announced ones: a label is a sentence
        // you read, and "not logged" must be as flat aloud as it is on screen.
        val every = PlanLog.spokenLines(everyState()).joinToString(" · ").lowercase(Locale.US)
        assertTrue("the fixture should exercise the whole card", every.length > 400)
        (forbidden + forbiddenHere).forEach { word ->
            assertFalse("\"$word\" reached a screen reader: $every", every.contains(word))
        }
    }

}
