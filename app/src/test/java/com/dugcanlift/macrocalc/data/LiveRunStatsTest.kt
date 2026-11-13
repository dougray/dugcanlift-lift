package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRunStatsTest {

    private fun point(lat: Double, lon: Double = -122.0, at: Long = 0L) = RoutePoint(
        latitude = lat, longitude = lon, altitudeMeters = 0.0,
        recordedAtEpochMs = at, horizontalAccuracyMeters = 5.0, verticalAccuracyMeters = 5.0
    )

    @Test
    fun `no fix yet is zero time zero distance and no pace`() {
        val stats = LiveRunStats.compute(emptyList(), startedAtEpochMs = 1_000_000L, nowEpochMs = 1_000_000L)
        assertEquals(0L, stats.elapsedMs)
        assertEquals(0.0, stats.distanceMeters, 0.0)
        assertNull(stats.paceSecondsPerMeter)
        assertEquals("0:00", stats.elapsedText())
        assertEquals("—", stats.paceText())
        assertEquals("0.00 mi", stats.distanceText())
    }

    @Test
    fun `a clock that reads before the start never goes negative`() {
        val stats = LiveRunStats.compute(emptyList(), startedAtEpochMs = 5_000L, nowEpochMs = 1_000L)
        assertEquals(0L, stats.elapsedMs)
        assertEquals("0:00", stats.elapsedText())
    }

    @Test
    fun `no start time is the empty run`() {
        val stats = LiveRunStats.compute(emptyList(), startedAtEpochMs = null, nowEpochMs = 9_000L)
        assertEquals(0L, stats.elapsedMs)
        assertNull(stats.paceSecondsPerMeter)
    }

    @Test
    fun `a single fix has no distance and no pace`() {
        val stats = LiveRunStats.compute(listOf(point(37.0)), startedAtEpochMs = 0L, nowEpochMs = 60_000L)
        assertEquals(60_000L, stats.elapsedMs)
        assertEquals(0.0, stats.distanceMeters, 0.0)
        assertNull(stats.paceSecondsPerMeter)
        assertEquals("1:00", stats.elapsedText())
    }

    @Test
    fun `pace is seconds per meter once a run has gone ten meters`() {
        // About 1000 m of latitude: 1000 / 111_194.9 degrees.
        val points = listOf(point(37.0), point(37.0 + 1000.0 / 111_194.9))
        val stats = LiveRunStats.compute(points, startedAtEpochMs = 0L, nowEpochMs = 300_000L)
        assertEquals(1000.0, stats.distanceMeters, 1.0)
        assertEquals(0.3, stats.paceSecondsPerMeter!!, 0.001)
    }

    @Test
    fun `under ten meters there is no pace`() {
        val points = listOf(point(37.0), point(37.0 + 5.0 / 111_194.9))
        val stats = LiveRunStats.compute(points, startedAtEpochMs = 0L, nowEpochMs = 30_000L)
        assertTrue(stats.distanceMeters in 4.0..6.0)
        assertNull(stats.paceSecondsPerMeter)
    }

    @Test
    fun `an hour and more reads with hours`() {
        val stats = LiveRunStats.compute(emptyList(), startedAtEpochMs = 0L, nowEpochMs = 3_725_000L)
        assertEquals("1:02:05", stats.elapsedText())
    }
}
