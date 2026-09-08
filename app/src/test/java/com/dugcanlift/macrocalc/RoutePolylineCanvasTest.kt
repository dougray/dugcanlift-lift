package com.dugcanlift.macrocalc

import com.dugcanlift.macrocalc.data.RoutePoint
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [projectRoutePoints], the pure Canvas-projection math behind
 * [RoutePolylineCanvas]. This is the exact surface that shipped a sign error
 * (C-1 in the final-review fix wave) undetected — the centering offset was
 * subtracted instead of added, which only produces an in-bounds result for a
 * route whose east-west and north-south extents happen to be equal. These
 * fixtures cover the three shapes that would have caught it: an east-west
 * route (`spanX` >> `spanY`), a north-south route (`spanY` >> `spanX`), and a
 * "square" loop (`spanX` ≈ `spanY`).
 */
class RoutePolylineCanvasTest {

    private val drawableDimension = 1000f

    private fun point(lat: Double, lon: Double) =
        RoutePoint(
            latitude = lat,
            longitude = lon,
            altitudeMeters = 0.0,
            recordedAtEpochMs = 0L,
            horizontalAccuracyMeters = 5.0,
            verticalAccuracyMeters = 5.0
        )

    private fun assertAllOffsetsInBounds(points: List<RoutePoint>) {
        val offsets = projectRoutePoints(points, drawableDimension)
        offsets.forEach { offset ->
            assertTrue(
                "x=${offset.x} out of bounds [0, $drawableDimension]",
                offset.x in 0f..drawableDimension
            )
            assertTrue(
                "y=${offset.y} out of bounds [0, $drawableDimension]",
                offset.y in 0f..drawableDimension
            )
        }
    }

    @Test
    fun `east-west out-and-back route projects within canvas bounds`() {
        // spanX (longitude) is far larger than spanY (latitude).
        val points = listOf(
            point(lat = 40.0000, lon = -74.0000),
            point(lat = 40.0001, lon = -74.0500),
            point(lat = 40.0000, lon = -74.1000),
            point(lat = 40.0001, lon = -74.0500),
            point(lat = 40.0000, lon = -74.0000)
        )
        assertAllOffsetsInBounds(points)
    }

    @Test
    fun `north-south route projects within canvas bounds`() {
        // spanY (latitude) is far larger than spanX (longitude).
        val points = listOf(
            point(lat = 40.0000, lon = -74.0000),
            point(lat = 40.0500, lon = -74.0001),
            point(lat = 40.1000, lon = -74.0000),
            point(lat = 40.1500, lon = -74.0001),
            point(lat = 40.2000, lon = -74.0000)
        )
        assertAllOffsetsInBounds(points)
    }

    @Test
    fun `square loop with roughly equal spans projects within canvas bounds`() {
        // spanX ~= spanY.
        val points = listOf(
            point(lat = 40.0000, lon = -74.0000),
            point(lat = 40.0000, lon = -74.0100),
            point(lat = 40.0100, lon = -74.0100),
            point(lat = 40.0100, lon = -74.0000),
            point(lat = 40.0000, lon = -74.0000)
        )
        assertAllOffsetsInBounds(points)
    }
}
