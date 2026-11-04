package com.dugcanlift.macrocalc.data

/**
 * The words for heart rate, which only a session the Wear OS watch ran can carry: "max 142 bpm" on a
 * set row and "Heart rate: average 118 · max 156 bpm" for the session. **The same words in every
 * build** -- LIFT for Wear OS, LIFT for Apple Watch and LIFT iPhone say exactly these (the LIFT
 * superproject's build plan, item 16) -- or changed in all of them.
 *
 * Shown, never graded, the discipline saturated fat and the left-right imbalance follow: no zone, no
 * color, no threshold, no chart, no trend, no "recovered" or "ready", and no percentage of a
 * maximum heart rate, which would need an age-based guess the app is not qualified to make.
 * `HeartRateLinesTest` reads the strings for any of that.
 *
 * Here rather than in a composable for the reason [SetTypes] is: a rule inside a `@Composable` is a
 * rule nobody can test.
 */
object HeartRateLines {
    /** "max 142 bpm", or null for a set with no figure -- which shows nothing at all. */
    fun set(maxBpm: Int?): String? = maxBpm?.takeIf { it > 0 }?.let { "max $it bpm" }

    /**
     * "Heart rate: average 118 · max 156 bpm", or null when neither was measured. One half alone says
     * only that half; the watch always sends both, so that is only ever a hand-edited backup.
     */
    fun session(averageBpm: Int?, maxBpm: Int?): String? {
        val parts = listOfNotNull(
            averageBpm?.takeIf { it > 0 }?.let { "average $it" },
            maxBpm?.takeIf { it > 0 }?.let { "max $it" },
        )
        return if (parts.isEmpty()) null else "Heart rate: ${parts.joinToString(" · ")} bpm"
    }

    fun session(session: WorkoutSession): String? = session(session.averageBpm, session.maxBpm)
}
