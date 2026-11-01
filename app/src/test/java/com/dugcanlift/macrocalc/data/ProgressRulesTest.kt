package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * A port of LIFT iPhone's `ProgressRulesTests`, case for case. Pounds where the iPhone converts
 * from kilograms: this build stores and shows pounds, so the iPhone's 200 lb and 192.4 lb are
 * written as they read.
 */
class ProgressRulesTest {

    private fun date(y: Int, m: Int, d: Int): LocalDate = LocalDate.of(y, m, d)

    // MARK: - The year

    @Test fun `levels are fixed steps`() {
        assertEquals(listOf(0, 1, 1, 2, 2, 3, 3), listOf(0, 1, 9, 10, 19, 20, 60).map(TrainingYear::level))
    }

    @Test fun `the year ends with this week and nothing after today is a rest day`() {
        val today = date(2026, 10, 28) // a Wednesday
        val weeks = TrainingYear.weeks(mapOf(today.toString() to 12), today, firstDayOfWeek = DayOfWeek.MONDAY)
        assertEquals(53, weeks.size)
        assertTrue(weeks.all { it.size == 7 })
        val last = weeks.last()
        assertEquals("2026-10-26", last[0].key) // Monday first
        assertEquals(2, last[2].level)
        assertFalse(last[2].isFuture)
        assertTrue(last[3].isFuture)
        assertEquals(0, last[3].level)
    }

    /** The reader's calendar decides the first day: a Sunday-first week starts the day before. */
    @Test fun `a sunday first calendar starts its weeks on sunday`() {
        val weeks = TrainingYear.weeks(emptyMap(), date(2026, 10, 28), firstDayOfWeek = DayOfWeek.SUNDAY)
        assertEquals("2026-10-25", weeks.last()[0].key)
    }

    @Test fun `training days count the last 365 only`() {
        val today = date(2026, 10, 28)
        val counts = mapOf(
            "2026-10-28" to 5, "2026-10-01" to 20, "2025-10-29" to 3, // in
            "2025-10-28" to 8,                                         // a year and a day ago
            "2026-10-02" to 0,                                         // nothing done
        )
        assertEquals(3, TrainingYear.trainingDays(counts, today))
    }

    @Test fun `every session on a day adds to that day`() {
        fun session(date: String, sets: Int) = WorkoutSession(
            date = date,
            exercises = listOf(LoggedExercise(name = "Squat", sets = List(sets) { WorkoutSet(reps = 5) }))
        )
        val counts = TrainingYear.setsByDay(listOf(session("2026-10-27", 4), session("2026-10-27", 3), session("2026-10-28", 2)))
        assertEquals(mapOf("2026-10-27" to 7, "2026-10-28" to 2), counts)
        assertEquals("1 training day in the last 12 months", TrainingYear.totalLine(1))
        assertEquals("2 training days in the last 12 months", TrainingYear.totalLine(2))
    }

    // MARK: - Muscles

    @Test fun `sets go to the logged muscle then the library's then unplaced`() {
        val lookup = mapOf(
            MuscleWeek.key("Barbell Squat", "Barbell") to "quadriceps",
            MuscleWeek.key("Face Pull", null) to "shoulders",
        )
        val logged = listOf(
            MuscleWeek.Logged("Bench Press", "Barbell", primaryMuscle = "Chest", workingSets = 4),
            MuscleWeek.Logged("Barbell Squat", "Barbell", primaryMuscle = null, workingSets = 4),
            MuscleWeek.Logged("Face Pull", "Cable", primaryMuscle = null, workingSets = 3),
            MuscleWeek.Logged("My Own Thing", null, primaryMuscle = null, workingSets = 2),
            MuscleWeek.Logged("Warmup only", null, primaryMuscle = "calves", workingSets = 0),
        )
        val result = MuscleWeek.tally(logged, lookup, listOf("chest", "quadriceps", "shoulders", "calves"))
        assertEquals(listOf("chest", "quadriceps", "shoulders"), result.trained.map { it.muscle })
        assertEquals(listOf(4, 4, 3), result.trained.map { it.sets })
        assertEquals(listOf("calves"), result.untrained)
        assertEquals(2, result.unplacedSets)
    }

    /** The library's lowercase equipment finds a lift this app stored title-cased. */
    @Test fun `the library places a lift logged with its stored equipment`() {
        val library = listOf(
            LibraryExercise("Barbell Squat", "quadriceps", "barbell", "strength", "beginner"),
            LibraryExercise("Pushups", "chest", "body only", "strength", "beginner"),
        )
        val lookup = MuscleWeek.lookup(library)
        val result = MuscleWeek.tally(
            listOf(
                MuscleWeek.Logged("Barbell Squat", "Barbell", null, 5),
                MuscleWeek.Logged("Pushups", "", null, 3), // no equipment: found by name alone
            ),
            lookup,
            MuscleWeek.allMuscles(lookup)
        )
        assertEquals(listOf(MuscleWeek.Trained("quadriceps", 5), MuscleWeek.Trained("chest", 3)), result.trained)
        assertEquals(0, result.unplacedSets)
    }

    @Test fun `this week runs from its first day to today`() {
        val today = date(2026, 10, 28)
        val start = MuscleWeek.weekStart(today, DayOfWeek.MONDAY)
        assertEquals(date(2026, 10, 26), start)
        fun session(date: String) = WorkoutSession(
            date = date,
            exercises = listOf(LoggedExercise(name = "Squat", sets = listOf(WorkoutSet(reps = 5))))
        )
        val logged = MuscleWeek.loggedThisWeek(
            listOf(session("2026-10-25"), session("2026-10-26"), session("2026-10-28")), today, start
        )
        assertEquals(2, logged.size)
        assertEquals("Middle Back", MuscleWeek.display("middle back"))
        assertEquals("1 set is from an exercise LIFT can't place on a muscle.", MuscleWeek.unplacedLine(1))
        assertEquals("2 sets are from exercises LIFT can't place on a muscle.", MuscleWeek.unplacedLine(2))
        assertNull(MuscleWeek.unplacedLine(0))
    }

    // MARK: - Body weight

    /** The store is keyed by day, so a day holds one weigh-in; this pins the window and order. */
    @Test fun `the last 90 days oldest first and nothing that is not a weigh-in`() {
        val today = date(2026, 10, 28)
        val points = BodyWeightTrend.points(
            mapOf(
                "2026-10-27" to 81.0, "2026-10-20" to 82.0,
                "2026-07-30" to 90.0,           // exactly 90 days ago: in
                "2026-07-29" to 91.0,           // 91 days ago: out
                "2026-10-21" to 0.0, "2026-10-22" to Double.NaN, "not a day" to 80.0,
            ),
            today
        )
        assertEquals(listOf(90.0, 82.0, 81.0), points.map { it.weightLb })
    }

    @Test fun `the summary says the change and the goal`() {
        val points = listOf(
            BodyWeightTrend.Point(date(2026, 8, 1), 200.0),
            BodyWeightTrend.Point(date(2026, 10, 27), 192.4),
        )
        val day = { d: LocalDate -> "${d.monthValue}/${d.dayOfMonth}" }
        val line = BodyWeightTrend.summary(points, goalLb = 185.0, day = day)!!
        assertTrue(line, line.startsWith("192.4 lb · down 7.6 since "))
        assertTrue(line, line.endsWith(" · 7.4 from your goal of 185"))
        assertNull(BodyWeightTrend.summary(emptyList(), goalLb = null, day = day))
        assertEquals("87.3 lb", BodyWeightTrend.summary(listOf(BodyWeightTrend.Point(date(2026, 10, 27), 87.3)), null, day))
        assertEquals(
            "185 lb · no change since 8/1 · at your goal",
            BodyWeightTrend.summary(
                listOf(BodyWeightTrend.Point(date(2026, 8, 1), 185.02), BodyWeightTrend.Point(date(2026, 8, 2), 185.0)),
                goalLb = 185.0, day = day
            )
        )
    }
}
