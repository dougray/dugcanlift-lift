package com.dugcanlift.macrocalc

import com.dugcanlift.macrocalc.data.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Focus used to be a field-visibility switch and nothing else, and Bodybuilding
 * and Powerlifting carried identical flags — so picking between the two most
 * likely options changed nothing. These pin the behaviour that replaced that,
 * and they pin it against the browser, which implements the same rules in
 * `lift/app.js`.
 */
class FocusMetricsTest {

    private fun set(
        weight: Double? = null, reps: Int? = null,
        seconds: Int? = null, metres: Double? = null
    ) = WorkoutSet(weightLb = weight, reps = reps, durationSec = seconds, distanceMeters = metres)

    private fun session(vararg sets: WorkoutSet) = WorkoutSession(
        date = "2026-09-13",
        exercises = listOf(LoggedExercise(name = "Back Squat", equipment = "Barbell", sets = sets.toList()))
    )

    private val strength = session(set(weight = 225.0, reps = 5), set(weight = 205.0, reps = 8))
    private val conditioning = session(
        set(weight = 100.0, reps = 10, seconds = 120, metres = 400.0),
        set(seconds = 150, metres = 600.0)
    )

    // MARK: - The summary line differs per focus

    @Test fun `bodybuilding is summarised by volume`() {
        assertEquals("2 sets - 2765 lb volume", focusSummary(strength, TrainingFocus.BODYBUILDING))
    }

    @Test fun `powerlifting is summarised by its top set`() {
        assertEquals("2 sets - top 225 x 5", focusSummary(strength, TrainingFocus.POWERLIFTING))
    }

    @Test fun `bodybuilding and powerlifting no longer agree`() {
        // The whole point: these two were byte-identical before.
        assertEquals(
            false,
            focusSummary(strength, TrainingFocus.BODYBUILDING) ==
                focusSummary(strength, TrainingFocus.POWERLIFTING)
        )
    }

    @Test fun `crossfit is summarised by working time`() {
        assertEquals("2 sets - 4:30 working", focusSummary(conditioning, TrainingFocus.CROSSFIT))
    }

    @Test fun `hyrox and endurance are summarised by distance and time`() {
        assertEquals("2 sets - 1.00 km - 4:30", focusSummary(conditioning, TrainingFocus.HYROX))
        assertEquals("2 sets - 1.00 km - 4:30", focusSummary(conditioning, TrainingFocus.ENDURANCE))
    }

    // MARK: - A session holding none of the focus's metric

    @Test fun `a timed focus over a weights-only session says nothing about distance`() {
        // Not "2 sets - 0 m": the session has no distance, so the line omits it
        // rather than reporting a zero that was never logged.
        assertEquals("2 sets", focusSummary(strength, TrainingFocus.ENDURANCE))
        assertEquals("2 sets", focusSummary(strength, TrainingFocus.HYROX))
        assertEquals("2 sets", focusSummary(strength, TrainingFocus.CROSSFIT))
    }

    @Test fun `a volume focus over a timed-only session falls back to the count`() {
        val timedOnly = session(set(seconds = 300), set(seconds = 240))
        assertEquals("2 sets", focusSummary(timedOnly, TrainingFocus.BODYBUILDING))
        assertEquals("2 sets", focusSummary(timedOnly, TrainingFocus.POWERLIFTING))
    }

    @Test fun `one set is not one sets`() {
        assertEquals("1 set - 1125 lb volume", focusSummary(session(set(weight = 225.0, reps = 5)), TrainingFocus.BODYBUILDING))
    }

    // MARK: - The top set is the heaviest one that also has reps

    @Test fun `a weight with no reps is not a set`() {
        val s = session(set(weight = 315.0), set(weight = 225.0, reps = 5))
        assertEquals(225.0, s.topSet()?.weightLb)
    }

    @Test fun `a session with no weighted set has no top set`() {
        assertNull(session(set(seconds = 300)).topSet())
    }

    // MARK: - Labels

    @Test fun `time reads mm ss until it passes an hour`() {
        assertEquals("0:45", clockLabel(45))
        assertEquals("4:30", clockLabel(270))
        assertEquals("1:00:00", clockLabel(3600))
        assertEquals("1:02:05", clockLabel(3725))
    }

    @Test fun `distance turns into km at a kilometre`() {
        assertEquals("400 m", distanceLabel(400.0))
        assertEquals("999 m", distanceLabel(999.0))
        assertEquals("1.00 km", distanceLabel(1000.0))
        assertEquals("5.20 km", distanceLabel(5200.0))
    }

    // MARK: - What a new set starts at

    @Test fun `the rep default is a training decision, not a constant`() {
        assertEquals(10, TrainingFocus.BODYBUILDING.defaultReps)
        assertEquals(5, TrainingFocus.POWERLIFTING.defaultReps)
        assertEquals(8, TrainingFocus.EVERYTHING.defaultReps)
    }

    @Test fun `the timed focuses have no opinion about reps`() {
        assertNull(TrainingFocus.CROSSFIT.defaultReps)
        assertNull(TrainingFocus.HYROX.defaultReps)
        assertNull(TrainingFocus.ENDURANCE.defaultReps)
    }

    // MARK: - Nothing is stored differently

    @Test fun `every focus keeps every field it was given`() {
        // Switching focus hides inputs; it must never drop what is already there.
        val rich = conditioning.allSets().first()
        assertEquals(100.0, rich.weightLb)
        assertEquals(10, rich.reps)
        assertEquals(120, rich.durationSec)
        assertEquals(400.0, rich.distanceMeters)
    }
}
