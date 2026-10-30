package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A port of LIFT iPhone's `WorkoutCSVTests`, case for case, against its fixtures copied unchanged
 * (`fixtures/csv-*.csv`, built from the shapes of real exports people have published; none of the
 * three apps documents its format). Pounds where the iPhone asserts kilograms, because this build
 * stores pounds; plus what this build does differently -- warm-ups left out and counted, two
 * workouts on a day kept as two sessions, a zero read as a blank.
 */
class WorkoutCsvTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("fixtures/$name.csv")!!.readText()

    private fun lb(kg: Double) = Math.round(kg / WorkoutCsv.KG_PER_LB * 100) / 100.0

    private fun refused(text: String): WorkoutCsv.Failure.Reason {
        try {
            WorkoutCsv.read(text)
        } catch (e: WorkoutCsv.Failure) {
            return e.reason
        }
        fail("expected a refusal"); throw AssertionError()
    }

    // MARK: - Reading

    @Test fun `strong's semicolon export is kilograms and skips its pseudo rows`() {
        val reading = WorkoutCsv.read(fixture("csv-strong-semicolon"))
        assertEquals(WorkoutCsv.Source.STRONG, reading.source)
        assertFalse(reading.needsUnit)
        val workouts = reading.parse(null)
        assertEquals(listOf("Upper A", "Lower A"), workouts.map { it.name })
        val bench = workouts[0].exercises.first()
        assertEquals("Bench Press", bench.name)
        assertEquals("Barbell", bench.equipment)
        // The Note and Rest Timer rows are not sets; W is a warm-up; F is a working set.
        assertEquals(true, bench.sets.first().isWarmup)
        assertEquals(4, bench.sets.last().reps)
        assertEquals(lb(80.0), bench.sets.last().weightLb!!, 0.0)
        assertEquals(4, bench.sets.size)
        assertFalse(bench.sets.any { it.reps == null && it.weightLb == null && it.durationSec == null })
        val pulldown = workouts[0].exercises.first { it.name == "Lat Pulldown" }
        assertEquals(listOf(lb(55.0), lb(40.0)), pulldown.sets.map { it.weightLb }) // D is a working set
        val plank = workouts[0].exercises.first { it.name == "Plank" }
        assertEquals(60, plank.sets.first().durationSec)
        assertEquals("2026-03-04", workouts[1].date.toLocalDate().toString())
    }

    /** No unit anywhere in the file, so nothing is built until one is chosen, and the choice decides the numbers. */
    @Test fun `strong's comma export asks for the unit`() {
        val reading = WorkoutCsv.read(fixture("csv-strong-comma"))
        assertTrue(reading.needsUnit)
        val benchLb = reading.parse(WorkoutCsv.Unit.POUNDS).first().exercises.first().sets.first()
        assertEquals(135.0, benchLb.weightLb!!, 0.0)
        assertEquals(lb(135.0), reading.parse(WorkoutCsv.Unit.KILOGRAMS).first().exercises.first().sets.first().weightLb!!, 0.0)
        // Zero distance and seconds are unused columns, not a 0 m run.
        assertNull(benchLb.distanceMeters)
        assertNull(benchLb.durationSec)
    }

    @Test fun `hevy names its unit and its set types`() {
        val reading = WorkoutCsv.read(fixture("csv-hevy"))
        assertEquals(WorkoutCsv.Source.HEVY, reading.source)
        val workouts = reading.parse(null)
        assertEquals(listOf("Morning run", "Push A"), workouts.map { it.name }) // oldest first
        val run = workouts[0].exercises.first().sets.first()
        assertEquals(5210.0, run.distanceMeters!!, 0.001)
        assertEquals(1860, run.durationSec)
        val bench = workouts[1].exercises.first()
        assertEquals(listOf(true, false, false), bench.sets.map { it.isWarmup })
        assertEquals(8.0, bench.sets[1].rpe!!, 0.0)
        assertEquals("Triceps Pushdown", workouts[1].exercises.last().name)
        assertNull(workouts[1].exercises.last().equipment)
    }

    @Test fun `hevy in pounds is converted`() {
        val text = """
            "title","start_time","end_time","description","exercise_title","superset_id","exercise_notes","set_index","set_type","weight_lbs","reps","distance_miles","duration_seconds","rpe"
            "A","Oct 1, 2026, 8:28 PM","Oct 1, 2026, 9:10 PM","","Squat (Barbell)",,"",0,"normal",225,5,,,
            "A","Oct 1, 2026, 8:28 PM","Oct 1, 2026, 9:10 PM","","Walk",,"",0,"normal",,,1.5,,
        """.trimIndent()
        val workouts = WorkoutCsv.read(text).parse(null)
        assertEquals(225.0, workouts.first().exercises.first().sets.first().weightLb!!, 0.0)
        assertEquals(2414.016, workouts.first().exercises.last().sets.first().distanceMeters!!, 0.001)
        assertEquals("2026-10-01", workouts.first().date.toLocalDate().toString())
    }

    /** FitNotes says the unit on every row, and one file can mix them. */
    @Test fun `fitnotes mixes units row by row`() {
        val reading = WorkoutCsv.read(fixture("csv-fitnotes"))
        assertEquals(WorkoutCsv.Source.FIT_NOTES, reading.source)
        val workouts = reading.parse(null)
        assertEquals(2, workouts.size)
        val raise = workouts[0].exercises.first { it.name == "Dumbbell Lateral Raise" }
        assertEquals(25.0, raise.sets.first().weightLb!!, 0.0)
        assertEquals("shoulders", raise.primaryMuscle)
        val run = workouts[1].exercises.first().sets.first()
        assertEquals(3.2 * 1609.344, run.distanceMeters!!, 0.001)
        assertEquals(31 * 60, run.durationSec)
        // "0.0" kgs on a pull-up is a bodyweight lift, a blank here rather than a 0 lb load.
        assertNull(workouts[0].exercises.first { it.name == "Pull Up" }.sets.first().weightLb)
    }

    @Test fun `a file from somewhere else is refused`() {
        assertEquals(WorkoutCsv.Failure.Reason.NOT_RECOGNIZED, refused("Name,Calories\nApple,95\n"))
        assertEquals(WorkoutCsv.Failure.Reason.EMPTY, refused(""))
    }

    @Test fun `the csv reader handles quotes bom and crlf`() {
        assertEquals(listOf(listOf("a", "b"), listOf("x, \"y\"", "2")), WorkoutCsv.parse("\uFEFFa,b\r\n\"x, \"\"y\"\"\",2\r\n"))
    }

    @Test fun `equipment comes out of the name`() {
        assertEquals("Bench Press" to "Barbell", WorkoutCsv.splitEquipment("Bench Press (Barbell)"))
        assertEquals("Pull Up" to null, WorkoutCsv.splitEquipment("Pull Up"))
        assertEquals("(Odd)" to null, WorkoutCsv.splitEquipment("(Odd)"))
    }

    // MARK: - Into the log

    /** A day already trained is left alone, and the same file twice adds nothing the second time. */
    @Test fun `a day already logged is left alone and a reimport adds nothing`() {
        val workouts = WorkoutCsv.read(fixture("csv-strong-semicolon")).parse(null)
        val mine = WorkoutSession(date = "2026-03-04", name = "Mine",
            exercises = listOf(LoggedExercise(name = "My squat", sets = listOf(WorkoutSet(reps = 5)))))

        val first = WorkoutCsv.plan(workouts, listOf(mine), ZoneOffset.UTC)
        assertEquals(1, first.outcome.daysAdded)
        assertEquals(1, first.outcome.daysSkipped)
        assertEquals(6, first.outcome.setsAdded) // 7 sets on Mar 2, one of them a warm-up
        assertEquals(1, first.outcome.warmupsLeftOut)
        assertEquals(listOf("2026-03-02"), first.sessions.map { it.date })
        assertEquals("Upper A", first.sessions.single().name)

        val again = WorkoutCsv.plan(workouts, listOf(mine) + first.sessions, ZoneOffset.UTC)
        assertEquals(0, again.outcome.daysAdded)
        assertEquals(2, again.outcome.daysSkipped)
        assertTrue(again.sessions.isEmpty())
    }

    /** An empty session on a day is not training on it. */
    @Test fun `a day with only an empty session is imported`() {
        val workouts = WorkoutCsv.read(fixture("csv-strong-comma")).parse(WorkoutCsv.Unit.POUNDS)
        val empty = WorkoutSession(date = "2025-09-15", name = "Started, nothing logged")
        assertEquals(2, WorkoutCsv.plan(workouts, listOf(empty)).outcome.daysAdded)
    }

    /** Two workouts on one day stay two sessions, where the iPhone folds them into its one day. */
    @Test fun `two workouts on a day are two sessions on one day`() {
        val text = """
            "title","start_time","end_time","description","exercise_title","superset_id","exercise_notes","set_index","set_type","weight_kg","reps","distance_km","duration_seconds","rpe"
            "AM","1 Oct 2026, 07:00","1 Oct 2026, 07:30","","Squat (Barbell)",,"",0,"normal",100,5,,,
            "PM","1 Oct 2026, 18:00","1 Oct 2026, 18:30","","Bench Press (Barbell)",,"",0,"normal",80,5,,,
        """.trimIndent()
        val plan = WorkoutCsv.plan(WorkoutCsv.read(text).parse(null), emptyList())
        assertEquals(listOf("AM", "PM"), plan.sessions.map { it.name })
        assertEquals(1, plan.outcome.daysAdded)
        assertEquals("Added 1 day (2 sets).", WorkoutCsv.summary(plan.outcome))
    }

    @Test fun `the words before and after`() {
        assertEquals(
            "Added 3 days (40 sets). 2 days you already had training on were left as they were. " +
                "1 warm-up set was left out: LIFT for Android doesn't mark warm-ups.",
            WorkoutCsv.summary(WorkoutCsv.Outcome(daysAdded = 3, setsAdded = 40, daysSkipped = 2, warmupsLeftOut = 1))
        )
        assertEquals("Added 1 day (1 set).", WorkoutCsv.summary(WorkoutCsv.Outcome(daysAdded = 1, setsAdded = 1)))
        val workouts = WorkoutCsv.read(fixture("csv-strong-semicolon")).parse(null)
        assertEquals("2 workouts, 8 sets, 2026-03-02 to 2026-03-04.", WorkoutCsv.describe(workouts) { it.toString() })
        assertEquals("No sets found in this file.", WorkoutCsv.describe(emptyList()) { it.toString() })
        assertEquals(
            "1 warm-up set will be left out: LIFT for Android doesn't mark warm-ups.",
            WorkoutCsv.warmupLine(WorkoutCsv.warmups(workouts), done = false)
        )
    }

    // MARK: - Out

    @Test fun `the export is one row a set in kilograms`() {
        val text = WorkoutCsv.export(listOf(
            WorkoutCsv.ExportRow("2026-10-26", "Push, heavy", "Bench Press", "Barbell", 1, true, null, 60.0, 8, null, null, null),
            WorkoutCsv.ExportRow("2026-10-26", "Push, heavy", "Split \"Squat\"", null, 1, false, "left", 22.5, 10, 8.5, null, null),
        ))
        assertEquals(listOf(
            "Date,Workout,Exercise,Equipment,Set,Warmup,Side,Weight (kg),Reps,RPE,Seconds,Distance (m)",
            "2026-10-26,\"Push, heavy\",Bench Press,Barbell,1,yes,,60,8,,,",
            "2026-10-26,\"Push, heavy\",\"Split \"\"Squat\"\"\",,1,,left,22.5,10,8.5,,",
        ).joinToString("\n") + "\n", text)
    }

    /** Pounds out as kilograms, a blank weight blank rather than 0, and no bare trailing point. */
    @Test fun `this build's pounds go out as kilograms and blanks stay blank`() {
        val session = WorkoutSession(date = "2026-10-26", name = "Pull", exercises = listOf(
            LoggedExercise(name = "Row", equipment = "Cable", sets = listOf(
                WorkoutSet(weightLb = 135.0, reps = 10, side = SetSide.RIGHT),
                WorkoutSet(reps = 12)
            ))
        ))
        val rows = WorkoutCsv.exportRows(listOf(session))
        assertEquals(
            "2026-10-26,Pull,Row,Cable,1,,right,61.23,10,,,\n2026-10-26,Pull,Row,Cable,2,,,,12,,,\n",
            WorkoutCsv.export(rows).substringAfter("\n")
        )
        assertEquals("1", WorkoutCsv.export(listOf(rows[0].copy(weightKg = 1.0001))).lines()[1].split(",")[7])
        assertEquals(LocalDate.of(2026, 10, 26).toString(), rows[0].dayKey)
    }
}
