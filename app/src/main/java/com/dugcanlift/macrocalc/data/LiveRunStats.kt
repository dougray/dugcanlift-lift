package com.dugcanlift.macrocalc.data

/**
 * The numbers of a run that is still recording: the one place Train's live card and the full
 * recording screen both read them from, so they can never disagree. A pure function of the fixes
 * so far, when the run began and what time it is now.
 *
 * Nothing here can be NaN or negative. A run with no fix yet is `0:00`, 0 distance and no pace;
 * a clock that reads before the start (set back mid-run) is also `0:00`.
 */
data class LiveRunStats(
    val elapsedMs: Long,
    val distanceMeters: Double,
    /** Seconds per meter, or null until the run has gone [MINIMUM_PACE_DISTANCE_METERS]. */
    val paceSecondsPerMeter: Double?
) {
    fun elapsedText(): String = OutdoorRecords.durationText(elapsedMs)

    fun distanceText(): String = OutdoorRecords.distanceText(distanceMeters)

    fun paceText(): String = paceSecondsPerMeter?.let(OutdoorRecords::paceText) ?: "—"

    companion object {
        /** Under this the pace of a run is GPS noise, not a pace. */
        const val MINIMUM_PACE_DISTANCE_METERS = 10.0

        fun compute(points: List<RoutePoint>, startedAtEpochMs: Long?, nowEpochMs: Long): LiveRunStats {
            val elapsedMs = if (startedAtEpochMs == null) 0L else maxOf(0L, nowEpochMs - startedAtEpochMs)
            val distance = OutdoorActivityMath.totalDistanceMeters(points)
            val pace = if (distance >= MINIMUM_PACE_DISTANCE_METERS && elapsedMs > 0L) {
                (elapsedMs / 1000.0) / distance
            } else null
            return LiveRunStats(elapsedMs, distance, pace)
        }
    }
}
