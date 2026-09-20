package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guess that decides where the toggle starts, and the one extra tap
 * logging a per-side set is allowed to cost.
 */
class PerSideLoggingTest {

    private fun exercise(vararg sides: SetSide?) = LoggedExercise(
        name = "Bulgarian Split Squat", equipment = "Dumbbell",
        sets = sides.map { WorkoutSet(weightLb = 60.0, reps = 8, side = it) }
    )

    @Test
    fun `names that mean one limb at a time are ticked`() {
        listOf(
            "Single-Arm Dumbbell Row", "One-Arm Cable Row", "Single Leg Deadlift",
            "Bulgarian Split Squat", "Split Squat", "Pistol Squat", "Walking Lunge",
            "Reverse Lunges", "Step-Up", "Step Ups", "Unilateral Leg Press"
        ).forEach { assertTrue(it, PerSideLogging.looksUnilateral(it)) }
    }

    @Test
    fun `two-sided lifts are not`() {
        listOf(
            "Bench Press", "Back Squat", "Barbell Row", "Deadlift", "Lat Pulldown",
            "Leg Press", "Armed Forces Press", "Stepmill", "Arm Curl"
        ).forEach { assertFalse(it, PerSideLogging.looksUnilateral(it)) }
    }

    @Test
    fun `punctuation and case are not part of the name`() {
        assertTrue(PerSideLogging.looksUnilateral("SINGLE_ARM ROW"))
        assertTrue(PerSideLogging.looksUnilateral("single-arm row"))
        assertTrue(PerSideLogging.looksUnilateral("1 Arm Row"))
    }

    @Test
    fun `the preference key carries the whole exercise identity`() {
        assertEquals("per_side|lat pulldown|cable", PerSideLogging.prefKey("lat pulldown|cable"))
        assertEquals("lat pulldown", PerSideLogging.nameOf("lat pulldown|cable"))
    }

    /* ---------- the L / R control ---------- */

    @Test
    fun `a new exercise starts on the left`() {
        assertEquals(SetSide.LEFT, PerSideLogging.defaultSide(exercise()))
    }

    @Test
    fun `the control alternates, defaulting to the side with fewer sets today`() {
        assertEquals(SetSide.RIGHT, PerSideLogging.defaultSide(exercise(SetSide.LEFT)))
        assertEquals(SetSide.LEFT, PerSideLogging.defaultSide(exercise(SetSide.LEFT, SetSide.RIGHT)))
        assertEquals(SetSide.RIGHT, PerSideLogging.defaultSide(
            exercise(SetSide.LEFT, SetSide.RIGHT, SetSide.LEFT)))
    }

    @Test
    fun `a side left behind is the one offered next, however far behind it is`() {
        assertEquals(SetSide.RIGHT, PerSideLogging.defaultSide(
            exercise(SetSide.LEFT, SetSide.LEFT, SetSide.LEFT)))
    }

    @Test
    fun `sets logged as both do not pull the control either way`() {
        assertEquals(SetSide.LEFT, PerSideLogging.defaultSide(exercise(null, null)))
    }

    @Test
    fun `the header counts each side, and says nothing until there is one`() {
        assertNull(PerSideLogging.sideCountLabel(exercise()))
        assertNull("a both-sided set is not a side", PerSideLogging.sideCountLabel(exercise(null)))
        assertEquals("L 3 · R 3", PerSideLogging.sideCountLabel(exercise(
            SetSide.LEFT, SetSide.RIGHT, SetSide.LEFT, SetSide.RIGHT, SetSide.LEFT, SetSide.RIGHT)))
        assertEquals("L 2 · R 1", PerSideLogging.sideCountLabel(exercise(
            SetSide.LEFT, SetSide.RIGHT, SetSide.LEFT)))
    }

    @Test
    fun `sets logged before the toggle went on are counted, not hidden`() {
        // LIFT web's countsLabel exactly: the two unmarked sets are still in
        // this exercise and still on screen underneath the line.
        assertEquals("L 1 · R 1 · 2 both", PerSideLogging.sideCountLabel(exercise(
            null, null, SetSide.LEFT, SetSide.RIGHT)))
    }

    @Test
    fun `the -ed spellings count too, as in One-Legged Deadlift`() {
        // Whole-word matching cannot see "one leg" inside "one legged", and the
        // bundled library has two of these. Same list as lift/sides.js.
        for (name in listOf("Kettlebell One-Legged Deadlift", "One-Legged Cable Kickback",
                            "Single-Legged Press", "One-Armed Row")) {
            assertTrue(name, PerSideLogging.looksUnilateral(name))
        }
        assertFalse("Cold Plunge", PerSideLogging.looksUnilateral("Cold Plunge"))
    }
}
