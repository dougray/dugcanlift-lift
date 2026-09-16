package com.dugcanlift.macrocalc

import com.dugcanlift.macrocalc.data.RoutePoint
import org.junit.Assert.assertEquals
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

    // MARK: - A box wider than it is tall (Train's Last route card)

    private val wideWidth = 1000f
    private val wideHeight = 450f

    private fun assertInWideBox(points: List<RoutePoint>) {
        projectRoutePoints(points, wideWidth, wideHeight).forEach { offset ->
            assertTrue("x=${offset.x} out of [0, $wideWidth]", offset.x in 0f..wideWidth)
            assertTrue("y=${offset.y} out of [0, $wideHeight]", offset.y in 0f..wideHeight)
        }
    }

    @Test
    fun `every route shape stays inside a wide box`() {
        assertInWideBox(listOf(point(40.0, -74.0), point(40.0001, -74.1)))
        assertInWideBox(listOf(point(40.0, -74.0), point(40.2, -74.0001)))
        assertInWideBox(listOf(point(40.0, -74.0), point(40.01, -74.01)))
    }

    @Test
    fun `a route is centred and never stretched in a wide box`() {
        // A north-south line: tall and thin, so height decides the scale and
        // the spare width is split evenly either side.
        val offsets = projectRoutePoints(
            listOf(point(40.0, -74.0), point(40.1, -74.0)), wideWidth, wideHeight
        )
        assertEquals(wideWidth / 2, offsets[0].x, 0.5f)
        assertEquals(wideWidth / 2, offsets[1].x, 0.5f)
        assertTrue("north is up", offsets[1].y < offsets[0].y)

        // A square loop keeps equal sides once longitude is scaled by cos(lat).
        val lonSpan = 0.01 / kotlin.math.cos(Math.toRadians(40.005))
        val square = projectRoutePoints(
            listOf(point(40.0, -74.0), point(40.0, -74.0 + lonSpan), point(40.01, -74.0 + lonSpan)),
            wideWidth, wideHeight
        )
        val side1 = square[1].x - square[0].x
        val side2 = square[1].y - square[2].y
        assertEquals(side1, side2, 1f)
    }

    @Test
    fun `the square overload still matches a square box`() {
        val points = listOf(point(40.0, -74.0), point(40.01, -74.02), point(40.02, -74.0))
        assertEquals(
            projectRoutePoints(points, drawableDimension, drawableDimension),
            projectRoutePoints(points, drawableDimension)
        )
    }
}
