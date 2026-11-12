package com.dugcanlift.macrocalc

import com.dugcanlift.macrocalc.data.OutdoorActivity
import com.dugcanlift.macrocalc.data.OutdoorActivityType
import com.dugcanlift.macrocalc.data.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Maps card's "Download streets around your last run" starts where the newest finished route started. */
class LastRunStartTest {

    private fun point(lat: Double, lon: Double) = RoutePoint(lat, lon, 0.0, 0L, 5.0, 5.0)

    private fun run(startedAt: Long, endedAt: Long?, vararg points: RoutePoint) = OutdoorActivity(
        activityType = OutdoorActivityType.entries.first(),
        startedAtEpochMs = startedAt,
        endedAtEpochMs = endedAt,
        routePoints = points.toList(),
    )

    @Test
    fun `no runs, no start`() = assertNull(lastRunStart(emptyList()))

    @Test
    fun `the newest finished route with points wins`() {
        val older = run(1_000, 2_000, point(1.0, 2.0))
        val newer = run(5_000, 6_000, point(3.0, 4.0), point(9.0, 9.0))
        assertEquals(3.0 to 4.0, lastRunStart(listOf(newer, older)))
    }

    @Test
    fun `a recording still in progress and a run with no route are skipped`() {
        val finished = run(1_000, 2_000, point(1.0, 2.0))
        val inProgress = run(5_000, null, point(7.0, 7.0))
        val noRoute = run(6_000, 7_000)
        assertEquals(1.0 to 2.0, lastRunStart(listOf(inProgress, noRoute, finished)))
    }
}
