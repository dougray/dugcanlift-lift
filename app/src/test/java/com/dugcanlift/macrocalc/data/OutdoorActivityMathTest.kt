package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OutdoorActivityMathTest {

    private fun point(lat: Double, lon: Double, alt: Double = 0.0, t: Long = 0) =
        RoutePoint(latitude = lat, longitude = lon, altitudeMeters = alt, recordedAtEpochMs = t,
            horizontalAccuracyMeters = 5.0, verticalAccuracyMeters = 5.0)

    @Test
    fun `zero or one point has zero distance`() {
        assertEquals(0.0, OutdoorActivityMath.totalDistanceMeters(emptyList()), 0.001)
        assertEquals(0.0, OutdoorActivityMath.totalDistanceMeters(listOf(point(40.0, -74.0))), 0.001)
    }

    @Test
    fun `distance between two known points matches expected haversine result`() {
        // Roughly 1 degree of longitude at the equator is ~111,320 metres.
        val points = listOf(point(0.0, 0.0), point(0.0, 1.0))
        val distance = OutdoorActivityMath.totalDistanceMeters(points)
        assertEquals(111_320.0, distance, 200.0) // generous tolerance, exact value depends on the formula's precision
    }

    @Test
    fun `elevation gain ignores jitter below the threshold`() {
        val points = listOf(
            point(0.0, 0.0, alt = 100.0),
            point(0.0, 0.0, alt = 101.5), // +1.5, below 3.0 threshold
            point(0.0, 0.0, alt = 99.0),  // -1.0 from reference (100.0, unchanged since 101.5 didn't clear the threshold), below threshold
            point(0.0, 0.0, alt = 100.5)  // +0.5 from 100.0, still below threshold
        )
        assertEquals(0.0, OutdoorActivityMath.elevationGainMeters(points), 0.001)
    }

    @Test
    fun `elevation gain counts a real climb past the threshold`() {
        val points = listOf(
            point(0.0, 0.0, alt = 100.0),
            point(0.0, 0.0, alt = 104.0), // +4.0, past threshold — counts, reference becomes 104.0
            point(0.0, 0.0, alt = 108.0)  // +4.0 from 104.0 — counts again
        )
        assertEquals(8.0, OutdoorActivityMath.elevationGainMeters(points), 0.001)
    }

    @Test
    fun `metersToMiles matches the known conversion factor`() {
        assertEquals(1.0, 1609.344.metersToMiles(), 0.0001)
        assertEquals(26.2, 42_164.86.metersToMiles(), 0.01) // marathon distance, sanity check
    }
}
