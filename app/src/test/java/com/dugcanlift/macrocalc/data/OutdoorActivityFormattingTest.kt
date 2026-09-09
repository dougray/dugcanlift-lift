package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OutdoorActivityFormattingTest {

    private fun activity(
        distanceMeters: Double = 0.0,
        startedAtEpochMs: Long = 0L,
        endedAtEpochMs: Long? = null,
        elevationGainMeters: Double = 0.0
    ) = OutdoorActivity(
        activityType = OutdoorActivityType.RUN,
        startedAtEpochMs = startedAtEpochMs,
        endedAtEpochMs = endedAtEpochMs,
        distanceMeters = distanceMeters,
        elevationGainMeters = elevationGainMeters
    )

    // formattedDuration()

    @Test
    fun `formattedDuration under an hour formats as mm ss`() {
        // 42 minutes 7 seconds
        val durationMs = (42 * 60 + 7) * 1000L
        val a = activity(startedAtEpochMs = 0L, endedAtEpochMs = durationMs)
        assertEquals("42:07", a.formattedDuration())
    }

    @Test
    fun `formattedDuration an hour or more formats as h mm ss`() {
        // 1 hour 5 minutes 3 seconds
        val durationMs = (60 * 60 + 5 * 60 + 3) * 1000L
        val a = activity(startedAtEpochMs = 0L, endedAtEpochMs = durationMs)
        assertEquals("1:05:03", a.formattedDuration())
    }

    @Test
    fun `formattedDuration returns placeholder when still recording`() {
        val a = activity(startedAtEpochMs = 0L, endedAtEpochMs = null)
        assertEquals("—", a.formattedDuration())
    }

    // formattedPace()

    @Test
    fun `formattedPace formats minutes and seconds per mile with zero padding`() {
        // 8 minutes 15 seconds per mile.
        // secondsPerMile = 8*60 + 15 = 495. metersPerMile = 1609.344.
        val secondsPerMeter = 495.0 / 1609.344
        val distanceMeters = 1609.344 // exactly one mile
        val durationMs = (secondsPerMeter * distanceMeters * 1000.0).toLong()
        val a = activity(distanceMeters = distanceMeters, startedAtEpochMs = 0L, endedAtEpochMs = durationMs)
        assertEquals("8:15 /mi", a.formattedPace())
    }

    @Test
    fun `formattedPace zero pads single digit seconds`() {
        // 8 minutes 5 seconds per mile.
        val secondsPerMeter = 485.0 / 1609.344
        val distanceMeters = 1609.344
        val durationMs = (secondsPerMeter * distanceMeters * 1000.0).toLong()
        val a = activity(distanceMeters = distanceMeters, startedAtEpochMs = 0L, endedAtEpochMs = durationMs)
        assertEquals("8:05 /mi", a.formattedPace())
    }

    @Test
    fun `formattedPace returns placeholder when distance is zero`() {
        // Duration exists but distance is zero, so averagePaceSecondsPerMeter is null per the model.
        val a = activity(distanceMeters = 0.0, startedAtEpochMs = 0L, endedAtEpochMs = 60_000L)
        assertEquals("—", a.formattedPace())
    }

    @Test
    fun `formattedPace returns placeholder when still recording`() {
        // No duration yet (still recording), even though distance is nonzero.
        val a = activity(distanceMeters = 1000.0, startedAtEpochMs = 0L, endedAtEpochMs = null)
        assertEquals("—", a.formattedPace())
    }

    // formattedDistanceMiles()

    @Test
    fun `formattedDistanceMiles formats a normal value to two decimal places`() {
        val distanceMeters = 3.14 * 1609.344
        val a = activity(distanceMeters = distanceMeters)
        assertEquals("3.14 mi", a.formattedDistanceMiles())
    }

    @Test
    fun `formattedDistanceMiles formats zero distance as a real value not a placeholder`() {
        val a = activity(distanceMeters = 0.0)
        assertEquals("0.00 mi", a.formattedDistanceMiles())
    }

    // formattedElevationGainFeet()

    @Test
    fun `formattedElevationGainFeet converts meters to feet and rounds to a whole number`() {
        // 100m * 3.280839895 ft/m = 328.0839895 ft
        val a = activity(elevationGainMeters = 100.0)
        assertEquals("328 ft gain", a.formattedElevationGainFeet())
    }

    @Test
    fun `formattedElevationGainFeet formats zero gain as a real value not a placeholder`() {
        val a = activity(elevationGainMeters = 0.0)
        assertEquals("0 ft gain", a.formattedElevationGainFeet())
    }
}
