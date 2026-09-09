package com.dugcanlift.macrocalc.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OutdoorActivityRepositoryTest {

    // Robolectric reuses its classloader's cached singleton `instance` field across test
    // methods in this class, so leftover state from one test would otherwise leak into the
    // next. Reset it before each test.
    @Before
    fun resetSingleton() {
        val field = OutdoorActivityRepository::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

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

    // Test 3: Save logic - appends new and replaces existing, exercised against the real repository
    @Test
    fun `repository save appends on new id and replaces on existing id`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo = OutdoorActivityRepository.get(context)

        val activity1 = OutdoorActivity(
            id = "activity-1",
            activityType = OutdoorActivityType.RUN,
            startedAtEpochMs = 1000L,
            distanceMeters = 1000.0
        )
        repo.save(activity1)
        assertEquals(1, repo.activities.value.size)
        assertEquals("activity-1", repo.activities.value[0].id)
        assertEquals(1000.0, repo.activities.value[0].distanceMeters, 0.001)

        val activity2 = OutdoorActivity(
            id = "activity-2",
            activityType = OutdoorActivityType.HIKE,
            startedAtEpochMs = 2000L,
            distanceMeters = 2000.0
        )
        repo.save(activity2)
        assertEquals(2, repo.activities.value.size)
        assertTrue(repo.activities.value.any { it.id == "activity-1" })
        assertTrue(repo.activities.value.any { it.id == "activity-2" })

        val activity1Updated = activity1.copy(distanceMeters = 1500.0)
        repo.save(activity1Updated)
        assertEquals(2, repo.activities.value.size) // still 2, not 3 — replaced, not duplicated
        assertEquals(1500.0, repo.activities.value.first { it.id == "activity-1" }.distanceMeters, 0.001)
    }

    // Test 4: Delete logic - removes matching activity, exercised against the real repository
    @Test
    fun `repository delete removes matching activity and leaves others untouched`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo = OutdoorActivityRepository.get(context)

        repo.save(OutdoorActivity(id = "del-activity-1", activityType = OutdoorActivityType.RUN, startedAtEpochMs = 1000L))
        repo.save(OutdoorActivity(id = "del-activity-2", activityType = OutdoorActivityType.HIKE, startedAtEpochMs = 2000L))
        repo.save(OutdoorActivity(id = "del-activity-3", activityType = OutdoorActivityType.RUN, startedAtEpochMs = 3000L))
        assertEquals(3, repo.activities.value.size)

        repo.delete("del-activity-2")
        assertEquals(2, repo.activities.value.size)
        assertTrue(repo.activities.value.any { it.id == "del-activity-1" })
        assertTrue(repo.activities.value.any { it.id == "del-activity-3" })
        assertTrue(repo.activities.value.none { it.id == "del-activity-2" })
    }
}
