package com.dugcanlift.macrocalc.data

import java.util.Locale

private const val PLACEHOLDER = "—"
private const val METERS_TO_FEET = 3.280839895

/** Same constant as [metersToMiles]'s private divisor — kept here too since a
 *  reciprocal-of-a-reciprocal round trip through that function is needlessly
 *  indirect for a plain unit conversion. */
private const val METERS_PER_MILE = 1609.344

fun OutdoorActivity.formattedDistanceMiles(): String =
    String.format(Locale.US, "%.2f mi", distanceMeters.metersToMiles())

fun OutdoorActivity.formattedDuration(): String {
    val duration = durationMs ?: return PLACEHOLDER
    val totalSeconds = duration / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

fun OutdoorActivity.formattedPace(): String {
    val secondsPerMeter = averagePaceSecondsPerMeter ?: return PLACEHOLDER
    val secondsPerMile = (secondsPerMeter * METERS_PER_MILE).toLong()
    val minutes = secondsPerMile / 60
    val seconds = secondsPerMile % 60
    return String.format(Locale.US, "%d:%02d /mi", minutes, seconds)
}

fun OutdoorActivity.formattedElevationGainFeet(): String {
    val feet = elevationGainMeters * METERS_TO_FEET
    return String.format(Locale.US, "%.0f ft gain", feet)
}
