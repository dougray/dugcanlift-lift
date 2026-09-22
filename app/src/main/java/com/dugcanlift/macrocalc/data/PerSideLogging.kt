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
        // "One-Legged Deadlift" is the same lift as "One-Leg Deadlift", and
        // whole-word matching cannot see the shorter term inside the longer
        // word. Listed in lift/sides.js too -- the two lists are one list.
        "one legged", "single legged", "one armed", "single armed",
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

    /**
     * "L 3 · R 3", the line that makes a missed side obvious. Null until some
     * set names a side.
     *
     * Sets logged before the toggle went on are still in this exercise and
     * still real, so they are counted too — "L 3 · R 3 · 2 both", LIFT web's
     * `countsLabel` exactly. Quietly leaving them out would make the line
     * disagree with the rows underneath it.
     */
    fun sideCountLabel(exercise: LoggedExercise): String? {
        if (!exercise.hasPerSideSets) return null
        val left = exercise.setCount(SetSide.LEFT)
        val right = exercise.setCount(SetSide.RIGHT)
        val both = exercise.setCount(null)
        return "L $left · R $right" + if (both > 0) " · $both both" else ""
    }

    /* ---------- a coach's prescription ----------
     *
     * A plan can say an exercise is done each side -- every prescribed set on
     * both, so "3 x 8 each side" is six sets, three a side -- and that a set is
     * for one side only, once (PLAN-FORMAT "Sides"). These read what a
     * prescription asks for against what has been logged. They suggest; the log
     * records what happened, and progression, the imbalance figure and volume
     * never read a prescription.
     *
     * A port of LIFT web's `lift/sides.js` "a coach's prescription" block,
     * function for function, like [SideBalance]: the rule changes there first. */

    /** Sets asked for on each side, and two-sided ones. */
    data class Targets(val left: Int, val right: Int, val both: Int)

    /** Whether this prescription says anything about sides at all. */
    fun prescribesSides(prescribed: List<PrescribedSet>?, eachSide: Boolean): Boolean =
        eachSide || prescribed.orEmpty().any { it.side != null }

    /**
     * An each-side set counts once on each side; a named set once on its own
     * side. Each side plus one extra left set is left 4, right 3.
     */
    fun prescribedTargets(prescribed: List<PrescribedSet>?, eachSide: Boolean): Targets {
        var left = 0
        var right = 0
        var both = 0
        prescribed.orEmpty().forEach {
            when {
                it.side == SetSide.LEFT -> left++
                it.side == SetSide.RIGHT -> right++
                eachSide -> { left++; right++ }
                else -> both++
            }
        }
        return Targets(left, right, both)
    }

    /** The sided sets in the order the coach wrote them: an each-side set is left then right. */
    private fun prescribedOrder(prescribed: List<PrescribedSet>?, eachSide: Boolean): List<Pair<SetSide, PrescribedSet>> =
        prescribed.orEmpty().flatMap {
            when {
                it.side != null -> listOf(it.side to it)
                eachSide -> listOf(SetSide.LEFT to it, SetSide.RIGHT to it)
                else -> emptyList()
            }
        }

    /**
     * The side of the next prescribed set nothing logged has filled yet, or
     * null when every sided set is filled (the caller then offers whichever
     * side is behind, [defaultSide]). Logged sets fill prescribed ones side by
     * side, in order, so three lefts logged first leave the first right unfilled.
     */
    fun nextPrescribedSide(prescribed: List<PrescribedSet>?, eachSide: Boolean, sets: List<WorkoutSet>): SetSide? {
        val have = mapOf(SetSide.LEFT to sets.count { it.side == SetSide.LEFT },
            SetSide.RIGHT to sets.count { it.side == SetSide.RIGHT })
        val used = mutableMapOf(SetSide.LEFT to 0, SetSide.RIGHT to 0)
        for ((side, _) in prescribedOrder(prescribed, eachSide)) {
            if (used.getValue(side) >= have.getValue(side)) return side
            used[side] = used.getValue(side) + 1
        }
        return null
    }

    /** The prescribed set the next set on [side] answers, to prefill it from, or null once that side's is used up. */
    fun prescribedSetFor(prescribed: List<PrescribedSet>?, eachSide: Boolean, sets: List<WorkoutSet>, side: SetSide?): PrescribedSet? {
        side ?: return null
        val mine = prescribedOrder(prescribed, eachSide).filter { it.first == side }
        return mine.getOrNull(sets.count { it.side == side })?.second
    }

    /**
     * "L 0/3 · R 0/3": logged against asked, per side. Over is shown as over --
     * "L 4/3" -- because it is what happened and the coach should see it. A side
     * the plan does not ask for appears only once something is logged on it,
     * and two-sided sets are counted when there are any ("R 0/1 · 2/2 both").
     * Null when the plan says nothing about sides, so the caller keeps its
     * plain [sideCountLabel].
     */
    fun targetsLabel(prescribed: List<PrescribedSet>?, eachSide: Boolean, sets: List<WorkoutSet>): String? {
        val t = prescribedTargets(prescribed, eachSide)
        if (t.left == 0 && t.right == 0) return null
        val left = sets.count { it.side == SetSide.LEFT }
        val right = sets.count { it.side == SetSide.RIGHT }
        val both = sets.count { it.side == null }
        val parts = mutableListOf<String>()
        if (t.left > 0) parts += "L $left/${t.left}" else if (left > 0) parts += "L $left"
        if (t.right > 0) parts += "R $right/${t.right}" else if (right > 0) parts += "R $right"
        if (t.both > 0) parts += "$both/${t.both} both" else if (both > 0) parts += "$both both"
        return parts.joinToString(" · ")
    }

    /** [targetsLabel] for a logged exercise, null unless it started from a prescription with sides. */
    fun targetsLabel(exercise: LoggedExercise): String? =
        targetsLabel(exercise.prescribed, exercise.eachSide, exercise.sets)

    /**
     * A named set on a lift not logged per side -- one right-arm set in a bench
     * session -- still needs somewhere to be logged on that side. Until it is,
     * the form offers L and R, with Both beside them, without changing the
     * lifter's per-side preference for the lift.
     */
    fun pendingNamedSide(exercise: LoggedExercise, logsPerSide: Boolean): Boolean =
        !logsPerSide && exercise.prescribed != null &&
            nextPrescribedSide(exercise.prescribed, exercise.eachSide, exercise.sets) != null

    /**
     * Which side the form starts on: the side the next unfilled prescribed set
     * names, and past the prescription, or with none, whichever side is behind.
     */
    fun startingSide(exercise: LoggedExercise): SetSide =
        nextPrescribedSide(exercise.prescribed, exercise.eachSide, exercise.sets) ?: defaultSide(exercise)
}
