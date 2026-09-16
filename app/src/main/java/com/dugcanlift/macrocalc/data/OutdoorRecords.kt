package com.dugcanlift.macrocalc.data

import java.util.Locale
import kotlin.math.roundToLong

/**
 * What Train shows under Outdoor: the last route and the personal bests.
 *
 * The same rules as LIFT for iOS's `OutdoorRecords`, kept out of the screen so
 * they are tested — a best that counts an unfinished recording, or a fastest
 * pace set by a 40 m GPS blip, is a wrong number a person will chase.
 */
object OutdoorRecords {

    /** Shorter than this and a pace is mostly GPS noise: a few metres of drift
     *  on a short recording reads as a world-record mile. */
    const val MINIMUM_PACE_DISTANCE_METERS = 1_000.0

    data class Bests(
        val type: OutdoorActivityType,
        val count: Int,
        val longestDistanceMeters: Double?,
        val longestDurationMs: Long?,
        /** Seconds per metre. Null until one recording reaches the minimum. */
        val fastestPaceSecondsPerMeter: Double?
    )

    /** The newest finished activity with a route worth drawing. A recording
     *  still in progress has no end, and one with a single fix has no line. */
    fun lastRoute(activities: List<OutdoorActivity>): OutdoorActivity? =
        activities
            .filter { it.endedAtEpochMs != null && it.routePoints.size > 1 }
            .maxByOrNull { it.startedAtEpochMs }

    /** One entry per type with at least one finished activity, in Start button
     *  order. Blank stays blank: no distance recorded is no farthest, not 0. */
    fun bests(activities: List<OutdoorActivity>): List<Bests> {
        val finished = activities.filter { it.endedAtEpochMs != null }
        return OutdoorActivityType.entries.mapNotNull { type ->
            val ofType = finished.filter { it.activityType == type }
            if (ofType.isEmpty()) return@mapNotNull null
            Bests(
                type = type,
                count = ofType.size,
                longestDistanceMeters = ofType.map { it.distanceMeters }.filter { it > 0 }.maxOrNull(),
                longestDurationMs = ofType.mapNotNull { it.durationMs }.filter { it > 0 }.maxOrNull(),
                fastestPaceSecondsPerMeter = ofType
                    .filter { it.distanceMeters >= MINIMUM_PACE_DISTANCE_METERS }
                    .mapNotNull { it.averagePaceSecondsPerMeter }
                    .filter { it > 0 }
                    .minOrNull()
            )
        }
    }

    /** "28:40", or "1:02:10" past the hour. */
    fun durationText(durationMs: Long): String {
        val total = (durationMs / 1000.0).roundToLong()
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    /** "8:03 /mi". This app shows distance in miles everywhere. */
    fun paceText(secondsPerMeter: Double): String {
        val perMile = (secondsPerMeter * METERS_PER_MILE).roundToLong()
        return String.format(Locale.US, "%d:%02d /mi", perMile / 60, perMile % 60)
    }

    /** "3.12 mi". */
    fun distanceText(meters: Double): String =
        String.format(Locale.US, "%.2f mi", meters.metersToMiles())

    private const val METERS_PER_MILE = 1609.344
}
