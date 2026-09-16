package com.dugcanlift.macrocalc.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class OutdoorActivityType(val displayName: String) {
    RUN("Run"),
    WALK("Walk"),
    HIKE("Hike")
}

/**
 * One recorded GPS point. A plain value, not stored as its own JSON file entry —
 * a run can produce thousands of points, so a whole activity's points are stored
 * as one encoded array on the owning [OutdoorActivity] (see Task 2), the same
 * way this codebase already stores route/plan data as inline JSON rather than
 * one-row-per-point.
 */
data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val recordedAtEpochMs: Long,
    val horizontalAccuracyMeters: Double,
    val verticalAccuracyMeters: Double
)

object OutdoorActivityMath {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun totalDistanceMeters(points: List<RoutePoint>): Double {
        if (points.size <= 1) return 0.0
        var total = 0.0
        for (i in 1 until points.size) {
            total += haversineMeters(
                lat1 = points[i - 1].latitude, lon1 = points[i - 1].longitude,
                lat2 = points[i].latitude, lon2 = points[i].longitude
            )
        }
        return total
    }

    /**
     * Noise-filtered elevation gain: GPS altitude readings jitter by several
     * metres even standing still, so a naive sum of every positive delta wildly
     * overcounts. Only deltas past [minimumDeltaMeters] between a point and the
     * last point that cleared the threshold count as real gain — a simple
     * hysteresis filter, the same technique (and the same 3.0m default) used on
     * iOS for this exact problem.
     */
    fun elevationGainMeters(points: List<RoutePoint>, minimumDeltaMeters: Double = 3.0): Double {
        if (points.size <= 1) return 0.0
        var gain = 0.0
        var reference = points[0].altitudeMeters
        for (point in points.drop(1)) {
            val delta = point.altitudeMeters - reference
            when {
                delta >= minimumDeltaMeters -> {
                    gain += delta
                    reference = point.altitudeMeters
                }
                delta <= -minimumDeltaMeters -> {
                    reference = point.altitudeMeters
                }
            }
        }
        return gain
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = lat1 * Math.PI / 180
        val phi2 = lat2 * Math.PI / 180
        val deltaPhi = (lat2 - lat1) * Math.PI / 180
        val deltaLambda = (lon2 - lon1) * Math.PI / 180
        val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
            cos(phi1) * cos(phi2) * sin(deltaLambda / 2) * sin(deltaLambda / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }
}

/** Mirrors [WeightUnit]'s absence on Android deliberately — see plan Global Constraints: miles only, no toggle. */
private const val METERS_PER_MILE = 1609.344

fun Double.metersToMiles(): Double = this / METERS_PER_MILE
