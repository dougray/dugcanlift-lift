package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OutdoorActivityRepositoryTest {

    // Test 1: JSON round-trip with route points intact
    @Test
    fun `json round trip preserves all fields including route points`() {
        val points = listOf(
            RoutePoint(
                latitude = 40.7128,
                longitude = -74.0060,
                altitudeMeters = 10.0,
                recordedAtEpochMs = 1000L,
                horizontalAccuracyMeters = 5.0,
                verticalAccuracyMeters = 3.0
            ),
            RoutePoint(
                latitude = 40.7129,
                longitude = -74.0061,
                altitudeMeters = 12.0,
                recordedAtEpochMs = 2000L,
                horizontalAccuracyMeters = 5.5,
                verticalAccuracyMeters = 3.5
            )
        )

        val activity = OutdoorActivity(
            id = "test-id-123",
            activityType = OutdoorActivityType.RUN,
            startedAtEpochMs = 1000L,
            endedAtEpochMs = 3000L,
            distanceMeters = 1234.5,
            elevationGainMeters = 56.7,
            activeCalories = 250.0,
            routePoints = points,
            healthConnectRecordId = "hc-record-456"
        )

        val json = activity.toJson()
        val restored = outdoorActivityFromJson(json)

        assertEquals("test-id-123", restored.id)
        assertEquals(OutdoorActivityType.RUN, restored.activityType)
        assertEquals(1000L, restored.startedAtEpochMs)
        assertEquals(3000L, restored.endedAtEpochMs)
        assertEquals(1234.5, restored.distanceMeters, 0.001)
        assertEquals(56.7, restored.elevationGainMeters, 0.001)
        assertEquals(250.0, restored.activeCalories!!, 0.001)
        assertEquals("hc-record-456", restored.healthConnectRecordId)

        // Verify route points
        assertEquals(2, restored.routePoints.size)
        assertEquals(40.7128, restored.routePoints[0].latitude, 0.0001)
        assertEquals(-74.0060, restored.routePoints[0].longitude, 0.0001)
        assertEquals(10.0, restored.routePoints[0].altitudeMeters, 0.001)
        assertEquals(1000L, restored.routePoints[0].recordedAtEpochMs)
        assertEquals(5.0, restored.routePoints[0].horizontalAccuracyMeters, 0.001)
        assertEquals(3.0, restored.routePoints[0].verticalAccuracyMeters, 0.001)

        assertEquals(40.7129, restored.routePoints[1].latitude, 0.0001)
        assertEquals(-74.0061, restored.routePoints[1].longitude, 0.0001)
        assertEquals(12.0, restored.routePoints[1].altitudeMeters, 0.001)
        assertEquals(2000L, restored.routePoints[1].recordedAtEpochMs)
        assertEquals(5.5, restored.routePoints[1].horizontalAccuracyMeters, 0.001)
        assertEquals(3.5, restored.routePoints[1].verticalAccuracyMeters, 0.001)
    }

    // Test 2: Backward compatibility with missing fields
    @Test
    fun `old format json without optional fields loads with null values`() {
        // Create a JSON object missing healthConnectRecordId, activeCalories, and endedAtEpochMs
        val oldJson = JSONObject().apply {
            put("id", "old-activity-1")
            put("activityType", "HIKE")
            put("startedAtEpochMs", 5000L)
            put("distanceMeters", 2500.0)
            put("elevationGainMeters", 150.0)
            put("routePoints", JSONArray())
            // Intentionally omit: healthConnectRecordId, activeCalories, endedAtEpochMs
        }

        val restored = outdoorActivityFromJson(oldJson)

        assertEquals("old-activity-1", restored.id)
        assertEquals(OutdoorActivityType.HIKE, restored.activityType)
        assertEquals(5000L, restored.startedAtEpochMs)
        assertNull(restored.endedAtEpochMs)
        assertEquals(2500.0, restored.distanceMeters, 0.001)
        assertEquals(150.0, restored.elevationGainMeters, 0.001)
        assertNull(restored.activeCalories)
        assertNull(restored.healthConnectRecordId)
        assertEquals(0, restored.routePoints.size)
    }

    // Test 3: Save logic - appends new and replaces existing (simulating repository behavior with JSON)
    @Test
    fun `repository save logic appends on new id and replaces on existing id`() {
        val activity1 = OutdoorActivity(
            id = "activity-1",
            activityType = OutdoorActivityType.RUN,
            startedAtEpochMs = 1000L,
            distanceMeters = 1000.0
        )

        val activity2 = OutdoorActivity(
            id = "activity-2",
            activityType = OutdoorActivityType.HIKE,
            startedAtEpochMs = 2000L,
            distanceMeters = 2000.0
        )

        // Simulate initial list
        var activities = emptyList<OutdoorActivity>()

        // Simulate save(activity1) - new id, should append
        activities = if (activities.any { it.id == activity1.id }) {
            activities.map { if (it.id == activity1.id) activity1 else it }
        } else {
            activities + activity1
        }
        assertEquals(1, activities.size)
        assertEquals("activity-1", activities[0].id)
        assertEquals(1000.0, activities[0].distanceMeters, 0.001)

        // Simulate save(activity2) - different id, should append
        activities = if (activities.any { it.id == activity2.id }) {
            activities.map { if (it.id == activity2.id) activity2 else it }
        } else {
            activities + activity2
        }
        assertEquals(2, activities.size)
        assertTrue(activities.any { it.id == "activity-1" })
        assertTrue(activities.any { it.id == "activity-2" })

        // Simulate save(activity1Updated) - same id, should replace not duplicate
        val activity1Updated = activity1.copy(distanceMeters = 1500.0)
        activities = if (activities.any { it.id == activity1Updated.id }) {
            activities.map { if (it.id == activity1Updated.id) activity1Updated else it }
        } else {
            activities + activity1Updated
        }
        assertEquals(2, activities.size) // Still 2, not 3
        val updated = activities.first { it.id == "activity-1" }
        assertEquals(1500.0, updated.distanceMeters, 0.001)

        // Verify JSON round-trip preserves the updated state
        val json = JSONArray().apply {
            activities.forEach { put(it.toJson()) }
        }
        val restored = (0 until json.length()).map { outdoorActivityFromJson(json.getJSONObject(it)) }
        assertEquals(2, restored.size)
        assertEquals(1500.0, restored.first { it.id == "activity-1" }.distanceMeters, 0.001)
    }

    // Test 4: Delete logic - removes matching activity (simulating repository behavior)
    @Test
    fun `repository delete logic removes matching activity and leaves others untouched`() {
        val activity1 = OutdoorActivity(
            id = "del-activity-1",
            activityType = OutdoorActivityType.RUN,
            startedAtEpochMs = 1000L
        )

        val activity2 = OutdoorActivity(
            id = "del-activity-2",
            activityType = OutdoorActivityType.HIKE,
            startedAtEpochMs = 2000L
        )

        val activity3 = OutdoorActivity(
            id = "del-activity-3",
            activityType = OutdoorActivityType.RUN,
            startedAtEpochMs = 3000L
        )

        // Simulate initial list with all three
        var activities = listOf(activity1, activity2, activity3)
        assertEquals(3, activities.size)

        // Simulate delete("del-activity-2")
        activities = activities.filterNot { it.id == "del-activity-2" }
        assertEquals(2, activities.size)
        assertTrue(activities.any { it.id == "del-activity-1" })
        assertTrue(activities.any { it.id == "del-activity-3" })
        assertTrue(activities.none { it.id == "del-activity-2" })

        // Verify JSON round-trip after delete
        val json = JSONArray().apply {
            activities.forEach { put(it.toJson()) }
        }
        val restored = (0 until json.length()).map { outdoorActivityFromJson(json.getJSONObject(it)) }
        assertEquals(2, restored.size)
        assertTrue(restored.any { it.id == "del-activity-1" })
        assertTrue(restored.any { it.id == "del-activity-3" })
    }
}
