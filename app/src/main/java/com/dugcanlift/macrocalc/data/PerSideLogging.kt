package com.dugcanlift.macrocalc.data

import java.util.Locale

/**
 * Whether an exercise is logged left and right separately.
 *
 * The bundled exercise library has no "unilateral" field, and it never will —
 * `exercises.json` is copied byte for byte from the site. So this is a guess
 * from the name, and it is only ever used to decide which way the toggle
 * starts. Once the lifter touches the toggle, their answer is what sticks,
 * for that exercise, keyed the way the dictionary is: `name|equipment`.
 *
 * Pure and free of Android so it can be tested directly, the way
 * `FocusMetrics` is.
 */
object PerSideLogging {

    /**
     * Names that usually mean one limb at a time. Matched against the name with
     * everything that is not a letter or a digit turned into a space, so
     * "Single-Arm", "Single Arm" and "single_arm" are one term, and both the
     * singular and the plural are listed rather than matched by prefix —
     * "step" would otherwise tick "Step Through Lunge"'s cousins and "Stepmill".
     */
    private val UNILATERAL_TERMS = listOf(
        "single arm", "one arm", "1 arm", "single handed",
        "single leg", "one leg", "1 leg", "single limb",
        "bulgarian", "split squat", "split squats",
        "pistol", "pistols", "lunge", "lunges",
        "step up", "step ups", "stepup", "stepups",
        "unilateral"
    )

    /** Letters and digits only, single-spaced, padded so a term can be matched as whole words. */
    private fun normalise(name: String): String {
        val cleaned = name.lowercase(Locale.US).map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
        return " " + cleaned.split(' ').filter { it.isNotEmpty() }.joinToString(" ") + " "
    }

    /**
     * Does this name look like a one-limb-at-a-time movement?
     *
     * A heuristic, and it is allowed to be wrong in both directions: it decides
     * where a toggle starts and nothing else. It never reads or writes a set.
     */
    fun looksUnilateral(name: String): Boolean {
        val padded = normalise(name)
        return UNILATERAL_TERMS.any { padded.contains(" $it ") }
    }

    /** The preference key for an exercise, from its `name|equipment` match key. */
    fun prefKey(matchKey: String): String = "per_side|$matchKey"

    /** The name half of a match key, for [looksUnilateral] when only the key is to hand. */
    fun nameOf(matchKey: String): String = matchKey.substringBefore('|')

    /**
     * Which side a new set should start on: whichever has fewer sets logged for
     * this exercise today, so the control alternates on its own and logging a
     * pair costs one tap, not two. Ties go to left, which is where a pair
     * starts.
     */
    fun defaultSide(exercise: LoggedExercise): SetSide =
        if (exercise.setCount(SetSide.RIGHT) < exercise.setCount(SetSide.LEFT)) SetSide.RIGHT else SetSide.LEFT

    /** "L 3 · R 3", the line that makes a missed side obvious. Null when neither side has a set yet. */
    fun sideCountLabel(exercise: LoggedExercise): String? {
        val left = exercise.setCount(SetSide.LEFT)
        val right = exercise.setCount(SetSide.RIGHT)
        if (left == 0 && right == 0) return null
        return "L $left · R $right"
    }
}
