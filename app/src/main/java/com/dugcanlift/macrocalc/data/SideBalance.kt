package com.dugcanlift.macrocalc.data

import kotlin.math.roundToInt

/** One occurrence of an exercise, reduced to a best estimated 1RM per side. Null is "that side wasn't trained". */
data class SideSession(val date: String, val leftE1rm: Double?, val rightE1rm: Double?)

/** Whether the gap has grown or shrunk across the window. [UNKNOWN] when a half of it has only one side. */
enum class ImbalanceTrend { WIDENING, CLOSING, STEADY, UNKNOWN }

/**
 * The imbalance between two sides of one lift: `(strong − weak) / strong` on
 * estimated 1RM, as a fraction of 1.
 *
 * [stronger] is null when the two are exactly equal, which is the only time
 * there is no stronger side to name.
 */
data class SideImbalance(
    val fraction: Double,
    val stronger: SetSide?,
    val trend: ImbalanceTrend,
    val leftSessions: Int,
    val rightSessions: Int
) {
    /** Whole percent, the only precision this number deserves. */
    val percent: Int get() = (fraction * 100).roundToInt()

    /**
     * The one line the card shows.
     *
     * Tracked and shown, never targeted — the discipline saturated fat, sugar
     * and sodium follow. It states the gap and which way it is going and stops
     * there: no threshold, no warning, no advice. A 10% difference is ordinary
     * in most people, and what yours means is a question for a trainer.
     */
    val description: String
        get() {
            val head = when {
                stronger == null || percent == 0 -> "Even"
                else -> "${stronger.label} ${percent}% stronger"
            }
            val tail = when (trend) {
                ImbalanceTrend.WIDENING -> "gap widening"
                ImbalanceTrend.CLOSING -> "gap closing"
                ImbalanceTrend.STEADY -> "holding steady"
                ImbalanceTrend.UNKNOWN -> null
            }
            return listOfNotNull(head, tail).joinToString(" · ")
        }
}

/**
 * The per-side maths behind the progression card, kept pure and free of
 * Compose so it can be tested directly, the way [FocusMetrics] is.
 *
 * Everything here reads estimated 1RM, because that is the one number that
 * compares 60x8 on the left against 65x6 on the right. Warmups are not marked
 * per side and do not need to be: the best set of a session is never a warmup.
 */
object SideBalance {

    /**
     * How many sessions each side needs before an imbalance figure is shown.
     *
     * Three, from the spec. Two points is a line through noise, and a single
     * heavy day on one side would otherwise read as a permanent difference.
     */
    const val MIN_SESSIONS = 3

    /** A precision, not a judgement: under a point of movement is not a direction. */
    private const val TREND_EPSILON = 0.01

    /** One [SideSession] per occurrence of the exercise, oldest first, as `historyFor` returns them. */
    fun sessions(history: List<Pair<String, LoggedExercise>>): List<SideSession> =
        history.map { (date, exercise) ->
            SideSession(
                date = date,
                leftE1rm = bestE1rm(exercise, SetSide.LEFT),
                rightE1rm = bestE1rm(exercise, SetSide.RIGHT)
            )
        }

    /** The best estimated 1RM among this exercise's sets for one side, or null if that side has none. */
    fun bestE1rm(exercise: LoggedExercise, side: SetSide?): Double? =
        exercise.sets(side).mapNotNull { it.estimatedOneRepMax }.maxOrNull()

    /**
     * The imbalance across a window of sessions, or null when there is not
     * enough to say — fewer than [MIN_SESSIONS] on either side.
     *
     * Each side's figure is its **best** estimated 1RM in the window rather
     * than its latest, for the same reason the card's other headline is a best:
     * one tired session is not a change in strength.
     */
    fun imbalance(sessions: List<SideSession>): SideImbalance? {
        val left = sessions.mapNotNull { it.leftE1rm }
        val right = sessions.mapNotNull { it.rightE1rm }
        if (left.size < MIN_SESSIONS || right.size < MIN_SESSIONS) return null

        val gap = gap(left.max(), right.max()) ?: return null
        return SideImbalance(
            fraction = gap.first,
            stronger = gap.second,
            trend = trend(sessions),
            leftSessions = left.size,
            rightSessions = right.size
        )
    }

    /** `(strong − weak) / strong` and which side is the strong one. Null if either number is unusable. */
    private fun gap(leftBest: Double, rightBest: Double): Pair<Double, SetSide?>? {
        val strong = maxOf(leftBest, rightBest)
        if (!strong.isFinite() || strong <= 0.0) return null
        val weak = minOf(leftBest, rightBest)
        if (!weak.isFinite() || weak < 0.0) return null
        val fraction = (strong - weak) / strong
        val stronger = when {
            leftBest == rightBest -> null
            leftBest > rightBest -> SetSide.LEFT
            else -> SetSide.RIGHT
        }
        return fraction to stronger
    }

    /**
     * Widening or closing, from the gap in the older half of the window against
     * the gap in the newer half. A half with only one side in it answers
     * [ImbalanceTrend.UNKNOWN] rather than guessing a direction.
     *
     * The comparison is of the *size* of the gap, not of which side is ahead:
     * a lifter whose weaker side overtakes has closed a gap and opened another,
     * and calling that "widening" at the crossover would be wrong.
     */
    fun trend(sessions: List<SideSession>): ImbalanceTrend {
        if (sessions.size < 2) return ImbalanceTrend.UNKNOWN
        val half = sessions.size / 2
        val older = halfGap(sessions.take(half)) ?: return ImbalanceTrend.UNKNOWN
        val newer = halfGap(sessions.drop(half)) ?: return ImbalanceTrend.UNKNOWN
        val change = newer - older
        return when {
            change > TREND_EPSILON -> ImbalanceTrend.WIDENING
            change < -TREND_EPSILON -> ImbalanceTrend.CLOSING
            else -> ImbalanceTrend.STEADY
        }
    }

    private fun halfGap(sessions: List<SideSession>): Double? {
        val left = sessions.mapNotNull { it.leftE1rm }.maxOrNull() ?: return null
        val right = sessions.mapNotNull { it.rightE1rm }.maxOrNull() ?: return null
        return gap(left, right)?.first
    }

    /** True once any occurrence in the window recorded a side, which is what turns the card's per-side parts on. */
    fun hasPerSideHistory(history: List<Pair<String, LoggedExercise>>): Boolean =
        history.any { it.second.hasPerSideSets }
}
