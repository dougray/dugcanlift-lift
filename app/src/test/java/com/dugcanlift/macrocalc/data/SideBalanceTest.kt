package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The imbalance maths, and the grouping that feeds it.
 *
 * All of it is pure, which is the point: a rule that decides what a number on
 * the progression card says cannot live in a composable's state where nothing
 * can reach it.
 *
 * The rule is LIFT web's `lift/sides.js`, ported: the mean of each side's last
 * three sessions, three sessions a side before there is a figure and four
 * before there is a trend, half a percentage point of movement before the gap
 * has done anything. Three platforms printing different percentages from one
 * log is worse than any of them printing a slightly better number, so these
 * pin the web's answers rather than this file's own opinion.
 */
class SideBalanceTest {

    private fun set(weight: Double, reps: Int, side: SetSide?) =
        WorkoutSet(weightLb = weight, reps = reps, side = side)

    /** One occurrence: the same reps each side, so the weights are the whole story. */
    private fun day(date: String, leftLb: Double?, rightLb: Double?, reps: Int = 8) = date to LoggedExercise(
        name = "Bulgarian Split Squat",
        equipment = "Dumbbell",
        sets = listOfNotNull(
            leftLb?.let { set(it, reps, SetSide.LEFT) },
            rightLb?.let { set(it, reps, SetSide.RIGHT) }
        )
    )

    /* ---------- not enough data ---------- */

    @Test
    fun `two sessions a side is not enough to name an imbalance`() {
        val history = listOf(day("2026-09-01", 60.0, 55.0), day("2026-09-08", 60.0, 55.0))
        assertNull(SideBalance.imbalance(SideBalance.sessions(history)))
    }

    @Test
    fun `three sessions on one side and two on the other is still not enough`() {
        val history = listOf(
            day("2026-09-01", 60.0, 55.0),
            day("2026-09-08", 60.0, 55.0),
            day("2026-09-15", 60.0, null)
        )
        assertNull(SideBalance.imbalance(SideBalance.sessions(history)))
    }

    @Test
    fun `a lift with no per-side sets has no per-side history at all`() {
        val history = listOf(
            "2026-09-01" to LoggedExercise(name = "Bench Press", equipment = "Barbell",
                sets = listOf(set(185.0, 5, null), set(185.0, 5, null)))
        )
        assertFalse(SideBalance.hasPerSideHistory(history))
        assertNull(SideBalance.imbalance(SideBalance.sessions(history)))
    }

    /* ---------- perfectly balanced ---------- */

    @Test
    fun `two sides that match exactly are zero percent apart and name no stronger side`() {
        val history = listOf(
            day("2026-09-01", 60.0, 60.0),
            day("2026-09-08", 62.5, 62.5),
            day("2026-09-15", 65.0, 65.0)
        )
        val imbalance = SideBalance.imbalance(SideBalance.sessions(history))!!
        assertEquals(0.0, imbalance.fraction, 1e-9)
        assertEquals(0, imbalance.percent)
        assertNull(imbalance.stronger)
        // Exactly three sessions: the first three and the last three are the
        // same sessions, so there is no trend to state and the line says so by
        // saying nothing.
        assertEquals(ImbalanceTrend.UNKNOWN, imbalance.trend)
        assertNull(imbalance.was)
        assertEquals("Even", imbalance.description)
    }

    @Test
    fun `a trend needs a fourth session on each side`() {
        val three = listOf(
            day("2026-09-01", 100.0, 90.0),
            day("2026-09-04", 100.0, 90.0),
            day("2026-09-08", 100.0, 90.0)
        )
        assertEquals(ImbalanceTrend.UNKNOWN, SideBalance.trend(SideBalance.sessions(three)))
        val four = three + day("2026-09-11", 100.0, 90.0)
        assertEquals(ImbalanceTrend.STEADY, SideBalance.trend(SideBalance.sessions(four)))
        assertEquals(SideBalance.MIN_FOR_TREND, 4)
    }

    /* ---------- a real gap ---------- */

    @Test
    fun `the gap is strong minus weak over strong, on estimated 1RM`() {
        // 8 reps both sides: Epley scales both by the same 1 + 8/30, so the
        // gap is the weights' own -- 10 lb on 100 is 10%.
        val history = listOf(
            day("2026-09-01", 100.0, 90.0),
            day("2026-09-08", 100.0, 90.0),
            day("2026-09-15", 100.0, 90.0)
        )
        val imbalance = SideBalance.imbalance(SideBalance.sessions(history))!!
        assertEquals(0.10, imbalance.fraction, 1e-9)
        assertEquals(10, imbalance.percent)
        assertEquals(SetSide.LEFT, imbalance.stronger)
        assertEquals(3, imbalance.leftSessions)
        assertEquals(3, imbalance.rightSessions)
    }

    @Test
    fun `reps count, not just the weight on the bar`() {
        // Right lifts less but for more reps: 90 x 10 estimates higher than
        // 100 x 5, and the stronger side is the right one.
        val history = listOf(
            "a" to LoggedExercise(name = "Row", equipment = "Dumbbell",
                sets = listOf(set(100.0, 5, SetSide.LEFT), set(90.0, 10, SetSide.RIGHT))),
            "b" to LoggedExercise(name = "Row", equipment = "Dumbbell",
                sets = listOf(set(100.0, 5, SetSide.LEFT), set(90.0, 10, SetSide.RIGHT))),
            "c" to LoggedExercise(name = "Row", equipment = "Dumbbell",
                sets = listOf(set(100.0, 5, SetSide.LEFT), set(90.0, 10, SetSide.RIGHT)))
        )
        val imbalance = SideBalance.imbalance(SideBalance.sessions(history))!!
        assertEquals(SetSide.RIGHT, imbalance.stronger)
        // left 116.67, right 120: (120 - 116.67) / 120
        assertEquals(0.0278, imbalance.fraction, 1e-4)
    }

    @Test
    fun `each side is the mean of its last three sessions, not its best in the window`() {
        // The left holds 100 all four sessions; the right was there and then
        // fell away. Its last three sessions average 90.78 against the left's
        // 126.67, which is a 28% gap.
        //
        // Taking each side's BEST in the window instead -- the rule this file
        // carried before it was ported from `lift/sides.js` -- would compare
        // 126.67 against the right's best 120.33 and print 5%, a number that
        // describes a fortnight ago. This test is the one that catches that.
        val history = listOf(
            day("2026-09-01", 100.0, 95.0),
            day("2026-09-04", 100.0, 95.0),
            day("2026-09-08", 100.0, 60.0),
            day("2026-09-11", 100.0, 60.0)
        )
        val imbalance = SideBalance.imbalance(SideBalance.sessions(history))!!
        assertEquals(0.2833, imbalance.fraction, 1e-4)
        assertEquals(28, imbalance.percent)
        assertEquals(SetSide.LEFT, imbalance.stronger)
        assertEquals(ImbalanceTrend.WIDENING, imbalance.trend)
    }

    @Test
    fun `a single tired session moves the figure, but only by its third`() {
        // The same tired day the "best in the window" rule used to discard. It
        // is one of three, so it moves the mean by a third of the drop rather
        // than not at all -- 100, 100, 70 averages 90.
        val history = listOf(
            day("2026-09-01", 100.0, 90.0),
            day("2026-09-08", 100.0, 90.0),
            day("2026-09-15", 70.0, 63.0)
        )
        val imbalance = SideBalance.imbalance(SideBalance.sessions(history))!!
        // Both sides fell by the same proportion, so the GAP is unchanged at 10%.
        assertEquals(0.10, imbalance.fraction, 1e-9)
    }

    /* ---------- widening and closing ---------- */

    @Test
    fun `a weak side catching up reads as closing`() {
        val history = listOf(
            day("2026-09-01", 100.0, 80.0),
            day("2026-09-04", 100.0, 80.0),
            day("2026-09-08", 100.0, 95.0),
            day("2026-09-11", 100.0, 95.0)
        )
        val imbalance = SideBalance.imbalance(SideBalance.sessions(history))!!
        assertEquals(ImbalanceTrend.CLOSING, imbalance.trend)
        // Last three on the right: 80, 95, 95 -> mean 114 against the left's
        // 126.67. The first three, 80, 80, 95, were 15% behind.
        assertEquals(0.15, imbalance.was!!, 1e-9)
        assertEquals("Left 10% stronger · gap closing", imbalance.description)
    }

    @Test
    fun `half a percentage point of movement is not a direction`() {
        // The right gains a single pound in the last session: a real movement,
        // and far too small to call. `sides.js` draws the line in the same place.
        val history = listOf(
            day("2026-09-01", 100.0, 90.0),
            day("2026-09-04", 100.0, 90.0),
            day("2026-09-08", 100.0, 90.0),
            day("2026-09-11", 100.0, 91.0)
        )
        val imbalance = SideBalance.imbalance(SideBalance.sessions(history))!!
        assertEquals(ImbalanceTrend.STEADY, imbalance.trend)
        assertTrue("the gap did move, just not enough to name",
            imbalance.fraction < imbalance.was!!)
    }

    @Test
    fun `a strong side pulling away reads as widening`() {
        val history = listOf(
            day("2026-09-01", 100.0, 95.0),
            day("2026-09-04", 100.0, 95.0),
            day("2026-09-08", 120.0, 95.0),
            day("2026-09-11", 120.0, 95.0)
        )
        val imbalance = SideBalance.imbalance(SideBalance.sessions(history))!!
        assertEquals(ImbalanceTrend.WIDENING, imbalance.trend)
    }

    @Test
    fun `a side with three sessions has a figure but no direction`() {
        // Two days that trained only the left. They are not right-side zeros --
        // they are days the right has nothing to say about, so the right counts
        // three sessions, enough for the figure and one short of a trend.
        val history = listOf(
            day("2026-09-01", 100.0, null),
            day("2026-09-04", 100.0, null),
            day("2026-09-08", 100.0, 90.0),
            day("2026-09-11", 100.0, 90.0),
            day("2026-09-14", 100.0, 90.0)
        )
        val sessions = SideBalance.sessions(history)
        assertEquals(ImbalanceTrend.UNKNOWN, SideBalance.trend(sessions))
        val imbalance = SideBalance.imbalance(sessions)!!
        assertEquals(5, imbalance.leftSessions)
        assertEquals(3, imbalance.rightSessions)
        assertEquals("Left 10% stronger", imbalance.description)
    }

    /* ---------- grouping ---------- */

    @Test
    fun `left and right never collapse into one series`() {
        val exercise = LoggedExercise(
            name = "Split Squat", equipment = "Dumbbell",
            sets = listOf(set(60.0, 8, SetSide.LEFT), set(55.0, 8, SetSide.RIGHT), set(60.0, 8, SetSide.LEFT))
        )
        assertEquals(2, exercise.setCount(SetSide.LEFT))
        assertEquals(1, exercise.setCount(SetSide.RIGHT))
        assertEquals(0, exercise.setCount(null))
        val keys = listOf(SetSide.LEFT, SetSide.RIGHT, null).map { exercise.sideKey(it) }
        assertEquals(keys.size, keys.distinct().size)
        assertEquals(60.0 * (1 + 8 / 30.0), SideBalance.bestE1rm(exercise, SetSide.LEFT)!!, 1e-9)
        assertEquals(55.0 * (1 + 8 / 30.0), SideBalance.bestE1rm(exercise, SetSide.RIGHT)!!, 1e-9)
    }

    @Test
    fun `a two-sided lift is one series and is unchanged`() {
        val exercise = LoggedExercise(
            name = "Bench Press", equipment = "Barbell",
            sets = listOf(set(185.0, 5, null), set(185.0, 5, null))
        )
        assertFalse(exercise.hasPerSideSets)
        assertEquals(2, exercise.setCount(null))
        assertNull(SideBalance.bestE1rm(exercise, SetSide.LEFT))
        assertEquals(exercise.matchKey + "|", exercise.sideKey(null))
        // The identity a history lookup matches on is still name and equipment.
        assertEquals("bench press|barbell", exercise.matchKey)
    }

    @Test
    fun `the exercise identity is unchanged, so history still finds a per-side lift`() {
        val sessions = listOf(
            WorkoutSession(date = "2026-09-01", exercises = listOf(
                LoggedExercise(name = "Split Squat", equipment = "Dumbbell",
                    sets = listOf(set(60.0, 8, SetSide.LEFT), set(55.0, 8, SetSide.RIGHT)))))
        )
        val history = sessions.historyFor("Split Squat", "Dumbbell")
        assertEquals(1, history.size)
        assertTrue(SideBalance.hasPerSideHistory(history))
    }
}
