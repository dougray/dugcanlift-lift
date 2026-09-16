package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OutdoorRecordsTest {

    private val t0 = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun activity(
        type: OutdoorActivityType,
        dayOffset: Int = 0,
        meters: Double,
        seconds: Long?,
        points: Int = 2
    ): OutdoorActivity {
        val start = t0 + dayOffset * day
        return OutdoorActivity(
            activityType = type,
            startedAtEpochMs = start,
            endedAtEpochMs = seconds?.let { start + it * 1000 },
            distanceMeters = meters,
            routePoints = (0 until points).map {
                RoutePoint(30 + it * 0.001, -97.0, 150.0, start, 5.0, 5.0)
            }
        )
    }

    // MARK: - Last route

    @Test fun `the last route is the newest finished one`() {
        val older = activity(OutdoorActivityType.RUN, 0, 5_000.0, 1_500)
        val newer = activity(OutdoorActivityType.HIKE, 2, 8_000.0, 7_200)
        assertSame(newer, OutdoorRecords.lastRoute(listOf(newer, older)))
        assertSame("order of the input does not matter", newer, OutdoorRecords.lastRoute(listOf(older, newer)))
    }

    @Test fun `a recording still in progress is not the last route`() {
        val finished = activity(OutdoorActivityType.RUN, 0, 5_000.0, 1_500)
        val recording = activity(OutdoorActivityType.WALK, 1, 900.0, null)
        assertSame(finished, OutdoorRecords.lastRoute(listOf(finished, recording)))
    }

    @Test fun `an activity with one fix has no line to draw`() {
        val drawable = activity(OutdoorActivityType.RUN, 0, 5_000.0, 1_500)
        val oneFix = activity(OutdoorActivityType.RUN, 1, 0.0, 60, points = 1)
        assertSame(drawable, OutdoorRecords.lastRoute(listOf(drawable, oneFix)))
        assertNull(OutdoorRecords.lastRoute(listOf(oneFix)))
    }

    // MARK: - Bests

    @Test fun `bests are per type in start button order`() {
        val bests = OutdoorRecords.bests(listOf(
            activity(OutdoorActivityType.HIKE, meters = 9_000.0, seconds = 10_000),
            activity(OutdoorActivityType.RUN, meters = 5_000.0, seconds = 1_500)
        ))
        assertEquals("walk has nothing, so it has no row",
            listOf(OutdoorActivityType.RUN, OutdoorActivityType.HIKE), bests.map { it.type })
    }

    @Test fun `farthest and longest can come from different activities`() {
        val run = OutdoorRecords.bests(listOf(
            activity(OutdoorActivityType.RUN, meters = 10_000.0, seconds = 3_000),
            activity(OutdoorActivityType.RUN, meters = 6_000.0, seconds = 3_600)
        )).single()
        assertEquals(2, run.count)
        assertEquals(10_000.0, run.longestDistanceMeters!!, 0.0)
        assertEquals(3_600_000L, run.longestDurationMs)
        assertEquals(0.3, run.fastestPaceSecondsPerMeter!!, 1e-9)
    }

    @Test fun `a short blip cannot set the fastest pace`() {
        // 40 m in 5 s is 3:21 a mile. It is GPS drift, not a record.
        val run = OutdoorRecords.bests(listOf(
            activity(OutdoorActivityType.RUN, meters = 5_000.0, seconds = 1_500),
            activity(OutdoorActivityType.RUN, meters = 40.0, seconds = 5)
        )).single()
        assertEquals(0.3, run.fastestPaceSecondsPerMeter!!, 1e-9)
    }

    @Test fun `no pace until one recording is long enough`() {
        val walk = OutdoorRecords.bests(listOf(activity(OutdoorActivityType.WALK, meters = 800.0, seconds = 600))).single()
        assertNull(walk.fastestPaceSecondsPerMeter)
        assertEquals(800.0, walk.longestDistanceMeters!!, 0.0)
    }

    @Test fun `an unfinished recording counts for nothing`() {
        assertTrue(OutdoorRecords.bests(listOf(activity(OutdoorActivityType.RUN, meters = 42_000.0, seconds = null))).isEmpty())
    }

    @Test fun `blank stays blank`() {
        // Finished with no distance at all — GPS never locked. No farthest of 0.
        val hike = OutdoorRecords.bests(listOf(activity(OutdoorActivityType.HIKE, meters = 0.0, seconds = 900, points = 0))).single()
        assertNull(hike.longestDistanceMeters)
        assertEquals(900_000L, hike.longestDurationMs)
    }

    // MARK: - Formatting, identical to iOS

    @Test fun `duration text`() {
        assertEquals("28:40", OutdoorRecords.durationText(1_720_000))
        assertEquals("1:02:10", OutdoorRecords.durationText(3_730_000))
    }

    @Test fun `pace text`() {
        // 0.3 s/m is 8:03 a mile.
        assertEquals("8:03 /mi", OutdoorRecords.paceText(0.3))
    }

    @Test fun `walk is stored and read back by name`() {
        val stored = activity(OutdoorActivityType.WALK, meters = 1_000.0, seconds = 600).toJson()
        assertEquals(OutdoorActivityType.WALK, outdoorActivityFromJson(stored).activityType)
    }
}
