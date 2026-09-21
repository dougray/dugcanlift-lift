package com.dugcanlift.macrocalc.data

import kotlin.math.floor

/** One occurrence of an exercise, reduced to a best estimated 1RM per side. Null is "that side wasn't trained". */
data class SideSession(val date: String, val leftE1rm: Double?, val rightE1rm: Double?)

/**
 * Whether the gap has grown or shrunk across the window.
 *
 * [UNKNOWN] is LIFT web's `trend: null` — fewer than [SideBalance.MIN_FOR_TREND]
 * sessions on a side, so there is no earlier figure honest enough to compare
 * against. It is not "steady".
 */
enum class ImbalanceTrend { WIDENING, CLOSING, STEADY, UNKNOWN }

/**
 * The imbalance between two sides of one lift: `(strong − weak) / strong` on
 * estimated 1RM, as a fraction of 1.
 *
 * [stronger] is null when the two are exactly equal, which is the only time
 * there is no stronger side to name. [was] is the same figure over each side's
 * *first* three sessions, which is what [trend] compares against; it is null
 * whenever the trend is [ImbalanceTrend.UNKNOWN].
 */
data class SideImbalance(
    val fraction: Double,
    val stronger: SetSide?,
    val trend: ImbalanceTrend,
    val was: Double?,
    val leftSessions: Int,
    val rightSessions: Int
) {
    /**
     * The percentage as the card prints it: one decimal, a trailing `.0`
     * dropped -- "5", "4.5", "16.7". Rounded half up, which is what Coach web's
     * `Math.round(percent * 1000) / 10` does; Kotlin's `round` is half-even and
     * would print a different figure on an exact half.
     */
    val percentText: String
        get() {
            val tenths = floor(fraction * 1000 + 0.5).toLong()
            return if (tenths % 10 == 0L) (tenths / 10).toString()
            else "${tenths / 10}.${tenths % 10}"
        }
}

/**
 * What the progression card prints about a per-limb lift: a short [headline]
 * for the Imbalance row and a quieter [detail] line saying what it was
 * measured over.
 *
 * **This is Coach web's `coach/sides.js` `imbalanceLines`, word for word**, as
 * Coach iOS, Coach Android, LIFT web and LIFT iOS print it: six apps, one
 * sentence, so a lifter and their trainer read the same thing from one log.
 *
 * Tracked and shown, never targeted -- the discipline saturated fat, sugar and
 * sodium follow. It states the gap and what it was measured over and stops
 * there: no threshold, no warning, no advice. A 10% difference is ordinary in
 * most people, and what yours means is a question for a trainer.
 */
data class ImbalanceLines(val headline: String, val detail: String)

/**
 * The per-side maths behind the progression card, kept pure and free of
 * Compose so it can be tested directly, the way [FocusMetrics] is.
 *
 * **This is a port of LIFT web's `lift/sides.js`, function for function**, and
 * it is a port rather than a second opinion on purpose: three platforms
 * printing different percentages from the same log is worse than any one of
 * them printing a slightly better number. `imbalance` here is `imbalance`
 * there, with the same thresholds, the same averaging and the same epsilon. If
 * the rule changes, it changes in `sides.js` first and is ported again.
 *
 * Everything reads estimated 1RM, because that is the one number that compares
 * 60x8 on the left against 65x6 on the right. Warmups are not marked per side
 * and do not need to be: the best set of a session is never a warmup.
 */
object SideBalance {

    /**
     * How many sessions each side needs before an imbalance figure is shown.
     *
     * Three, from the spec. A figure drawn from one session each would move ten
     * points on a day someone went in tired, and be read as a finding.
     */
    const val MIN_SESSIONS = 3

    /**
     * And how many before a *trend* is. With exactly three, the first three and
     * the last three are the same sessions, so "steady" would be arithmetic
     * rather than an observation.
     */
    const val MIN_FOR_TREND = 4

    /**
     * Half a percentage point. Below it the gap has not done anything — this is
     * an estimate built out of an estimate, and `sides.js` draws the line in
     * the same place.
     */
    private const val TREND_EPSILON = 0.005

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
     * Each side's figure is the **mean of its last [MIN_SESSIONS] sessions**,
     * not its best and not its latest: `sides.js`'s rule, for the reason the
     * threshold exists at all. One heavy day is not a change in strength, and
     * one tired day is not either.
     *
     * Note "its last three *sessions*", counted on that side's own recorded
     * ones — a day that trained only the left is not a right-side zero, it is a
     * day the right has nothing to say about.
     */
    fun imbalance(sessions: List<SideSession>): SideImbalance? {
        val left = recorded(sessions.map { it.leftE1rm })
        val right = recorded(sessions.map { it.rightE1rm })
        if (left.size < MIN_SESSIONS || right.size < MIN_SESSIONS) return null

        val nowLeft = mean(left.takeLast(MIN_SESSIONS))
        val nowRight = mean(right.takeLast(MIN_SESSIONS))
        val fraction = gap(nowLeft, nowRight) ?: return null

        val was = earlierGap(left, right)
        return SideImbalance(
            fraction = fraction,
            stronger = when {
                nowLeft == nowRight -> null
                nowLeft > nowRight -> SetSide.LEFT
                else -> SetSide.RIGHT
            },
            trend = trendFrom(fraction, was),
            was = was,
            leftSessions = left.size,
            rightSessions = right.size
        )
    }

    /**
     * Widening or closing, from the gap over each side's first [MIN_SESSIONS]
     * sessions against the gap over its last [MIN_SESSIONS].
     *
     * The comparison is of the *size* of the gap, not of which side is ahead: a
     * lifter whose weaker side overtakes has closed one gap and opened another,
     * and calling that "widening" at the crossover would be wrong.
     */
    fun trend(sessions: List<SideSession>): ImbalanceTrend {
        val left = recorded(sessions.map { it.leftE1rm })
        val right = recorded(sessions.map { it.rightE1rm })
        if (left.size < MIN_SESSIONS || right.size < MIN_SESSIONS) return ImbalanceTrend.UNKNOWN
        val now = gap(mean(left.takeLast(MIN_SESSIONS)), mean(right.takeLast(MIN_SESSIONS)))
            ?: return ImbalanceTrend.UNKNOWN
        return trendFrom(now, earlierGap(left, right))
    }

    /**
     * The card's two lines for this window. Below [MIN_SESSIONS] a side there
     * is no figure, so the headline is an em dash and the detail counts what
     * each side has -- saying what is missing beats a blank a lifter would read
     * as "no imbalance".
     */
    fun lines(sessions: List<SideSession>): ImbalanceLines {
        val imbalance = imbalance(sessions) ?: return ImbalanceLines(
            headline = "\u2014",
            detail = "Needs $MIN_SESSIONS sessions a side \u00b7 " +
                "${recorded(sessions.map { it.leftE1rm }).size} left, " +
                "${recorded(sessions.map { it.rightE1rm }).size} right so far"
        )
        val trend = when (imbalance.trend) {
            ImbalanceTrend.WIDENING -> " \u00b7 gap widening"
            ImbalanceTrend.CLOSING -> " \u00b7 gap closing"
            ImbalanceTrend.STEADY -> " \u00b7 gap steady"
            ImbalanceTrend.UNKNOWN -> ""
        }
        return ImbalanceLines(
            headline = imbalance.stronger
                ?.let { "${it.label} ahead by ${imbalance.percentText}%" }
                ?: "Sides level",
            detail = "Mean estimated 1RM of the last $MIN_SESSIONS sessions each$trend"
        )
    }

    /** True once any occurrence in the window recorded a side, which is what turns the card's per-side parts on. */
    fun hasPerSideHistory(history: List<Pair<String, LoggedExercise>>): Boolean =
        history.any { it.second.hasPerSideSets }

    /* ---------- the pieces, in `sides.js` order ---------- */

    /** A side's usable figures. A null, a NaN or a zero is "that side wasn't trained", never a zero 1RM. */
    private fun recorded(values: List<Double?>): List<Double> =
        values.filter { it != null && it.isFinite() && it > 0.0 }.map { it!! }

    private fun mean(values: List<Double>): Double = values.sum() / values.size

    /** `(strong − weak) / strong`, or null when there is no strong side to divide by. */
    private fun gap(left: Double, right: Double): Double? {
        val strong = maxOf(left, right)
        if (!strong.isFinite() || strong <= 0.0) return null
        val weak = minOf(left, right)
        return (strong - weak) / strong
    }

    /** The same figure over each side's first [MIN_SESSIONS], or null until both have [MIN_FOR_TREND]. */
    private fun earlierGap(left: List<Double>, right: List<Double>): Double? {
        if (left.size < MIN_FOR_TREND || right.size < MIN_FOR_TREND) return null
        return gap(mean(left.take(MIN_SESSIONS)), mean(right.take(MIN_SESSIONS)))
    }

    private fun trendFrom(now: Double, was: Double?): ImbalanceTrend {
        if (was == null) return ImbalanceTrend.UNKNOWN
        val moved = now - was
        return when {
            moved > TREND_EPSILON -> ImbalanceTrend.WIDENING
            moved < -TREND_EPSILON -> ImbalanceTrend.CLOSING
            else -> ImbalanceTrend.STEADY
        }
    }
}
