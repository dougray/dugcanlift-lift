package com.dugcanlift.macrocalc.data

import java.util.Locale

private const val PLACEHOLDER = "—"

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
    val metersPerMile = 1.0 / 1.0.metersToMiles()
    val secondsPerMile = (secondsPerMeter * metersPerMile).toLong()
    val minutes = secondsPerMile / 60
    val seconds = secondsPerMile % 60
    return String.format(Locale.US, "%d:%02d /mi", minutes, seconds)
}
