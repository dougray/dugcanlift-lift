package com.dugcanlift.macrocalc.data

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers what does not require a real Health Connect provider: the
 * permission sets requested, and [buildExerciseSessionRecord]'s handling of
 * the two real edge cases in the underlying library's own validation —
 * duplicate route-point timestamps and a route point landing on or after the
 * session's own end time. Both classes involved ([ExerciseSessionRecord],
 * [ExerciseRoute]) are plain Kotlin data classes, so this runs as an
 * ordinary JVM test with no Robolectric/Android dependency, same as
 * [OutdoorActivityMathTest].
 */
class HealthConnectManagerTest {

    private fun point(
        lat: Double = 40.0,
        lon: Double = -74.0,
        alt: Double = 10.0,
        t: Long
    ) = RoutePoint(
        latitude = lat,
        longitude = lon,
        altitudeMeters = alt,
        recordedAtEpochMs = t,
        horizontalAccuracyMeters = 5.0,
        verticalAccuracyMeters = 5.0
    )

    private fun activity(
        type: OutdoorActivityType = OutdoorActivityType.RUN,
        start: Long = 1_000L,
        end: Long? = 10_000L,
        points: List<RoutePoint> = emptyList()
    ) = OutdoorActivity(
        activityType = type,
        startedAtEpochMs = start,
        endedAtEpochMs = end,
        routePoints = points
    )

    @Test
    fun `write permissions cover exercise session and exercise route separately`() {
        assertEquals(
            setOf(
                HealthPermission.getWritePermission(ExerciseSessionRecord::class),
                HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE
            ),
            HealthConnectManager.writePermissions
        )
    }

    @Test
    fun `permissions to request bundle read, write, and history permissions`() {
        val requested = HealthConnectManager.permissionsToRequest
        assertTrue(requested.containsAll(HealthConnectManager.permissions))
        assertTrue(requested.containsAll(HealthConnectManager.writePermissions))
        assertTrue(requested.contains(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY))
    }

    @Test
    fun `run maps to running exercise type and hike to hiking`() {
        assertEquals(
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            buildExerciseSessionRecord(activity(type = OutdoorActivityType.RUN)).exerciseType
        )
        assertEquals(
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING,
            buildExerciseSessionRecord(activity(type = OutdoorActivityType.HIKE)).exerciseType
        )
    }

    @Test
    fun `record has no route when activity recorded no points`() {
        val record = buildExerciseSessionRecord(activity(points = emptyList()))
        assertEquals(ExerciseRouteResult.NoData(), record.exerciseRouteResult)
    }

    @Test
    fun `duplicate timestamps among route points are deduped before building the route`() {
        // Two fixes landing on the same millisecond would otherwise make
        // ExerciseRoute's own init block throw.
        val points = listOf(point(t = 2_000L), point(t = 2_000L), point(t = 3_000L))

        val record = buildExerciseSessionRecord(activity(points = points))

        val route = (record.exerciseRouteResult as ExerciseRouteResult.Data).exerciseRoute
        assertEquals(2, route.route.size)
    }

    @Test
    fun `session end time is pushed past a route point that lands on or after it`() {
        // The last GPS fix commonly coincides with endedAtEpochMs, which
        // ExerciseSessionRecord's init block rejects (route max time must be
        // strictly before the session end time).
        val points = listOf(point(t = 2_000L), point(t = 10_000L))

        val record = buildExerciseSessionRecord(activity(end = 10_000L, points = points))

        assertTrue(record.endTime.toEpochMilli() > 10_000L)
        val route = (record.exerciseRouteResult as ExerciseRouteResult.Data).exerciseRoute
        assertTrue(route.route.last().time.isBefore(record.endTime))
    }

    @Test
    fun `session start time is pulled earlier to cover a route point recorded before it`() {
        val points = listOf(point(t = 500L), point(t = 2_000L))

        val record = buildExerciseSessionRecord(activity(start = 1_000L, points = points))

        assertEquals(500L, record.startTime.toEpochMilli())
    }

    @Test
    fun `title is the activity type's display name`() {
        assertEquals("Run", buildExerciseSessionRecord(activity(type = OutdoorActivityType.RUN)).title)
        assertEquals("Hike", buildExerciseSessionRecord(activity(type = OutdoorActivityType.HIKE)).title)
    }
}
