package com.dugcanlift.macrocalc.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.meters
import java.time.Instant
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Health Connect access: reads step counts, and (as of the outdoor-activity
 * feature) writes finished [OutdoorActivity] sessions with their GPS route.
 * Mirrors the read side of HealthKitManager on iOS; the write side has no iOS
 * equivalent to mirror since HealthKit writes are handled separately there.
 *
 * Idempotency for the write side lives on the caller: [OutdoorActivity]
 * carries its own [OutdoorActivity.healthConnectRecordId], and a caller must
 * check that it is still null before invoking [exportOutdoorActivity] —
 * this function always attempts an insert and never checks for a prior
 * export itself.
 */
object HealthConnectManager {

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class)
    )

    /**
     * Write permissions needed to export an [OutdoorActivity] as an exercise
     * session with its route, distance, and elevation gain attached. Route
     * data requires its own grant (PERMISSION_WRITE_EXERCISE_ROUTE) on top of
     * the exercise-session write permission — Health Connect treats "log a
     * workout" and "log where the user was" as separate consents, and
     * rejects the entire insert if the route permission is not granted.
     * Distance and elevation gain are likewise their own record types
     * ([DistanceRecord], [ElevationGainedRecord]) with their own write
     * permissions, since [ExerciseSessionRecord] itself carries neither
     * field.
     */
    val writePermissions: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE,
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(ElevationGainedRecord::class)
    )

    /**
     * What the grant sheet asks for at the read-only step-count call site
     * ([DashboardScreen]'s first-launch prompt). Deliberately does NOT
     * include [writePermissions]: those cover write-exercise-route consent,
     * which must only ever be requested at the point of use
     * (`OutdoorReviewScreen`'s on-demand request), never on an unrelated
     * first launch with no user gesture behind it (I-6 in the final-review
     * fix wave — this was accidentally widened to include write permissions
     * during Task 5). History is bundled in but deliberately kept out of
     * [permissions]: it is what lets a step history reach further back than
     * 30 days, and someone who declines it should still count as connected
     * rather than being nagged forever.
     */
    val permissionsToRequest: Set<String> =
        permissions + HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

    fun isAvailable(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermission(context: Context): Boolean {
        if (!isAvailable(context)) return false
        val client = HealthConnectClient.getOrCreate(context)
        return client.permissionController.getGrantedPermissions().containsAll(permissions)
    }

    suspend fun hasWritePermission(context: Context): Boolean {
        if (!isAvailable(context)) return false
        val client = HealthConnectClient.getOrCreate(context)
        return client.permissionController.getGrantedPermissions().containsAll(writePermissions)
    }

    /**
     * Exports a finished [OutdoorActivity] to Health Connect as an
     * [ExerciseSessionRecord] with its GPS route, distance, and elevation
     * gain attached, and returns the session record's id.
     *
     * Distance and elevation gain have no home on [ExerciseSessionRecord]
     * itself (that type carries neither field), so a companion
     * [DistanceRecord] and [ElevationGainedRecord] covering the same
     * start/end window are inserted in the same [HealthConnectClient.insertRecords]
     * call — matching what iOS writes today. The session record is always
     * first in that list, so its id is always `recordIdsList.first()`; the
     * companion records' ids are not returned since nothing on
     * [OutdoorActivity] tracks them (only the session id backs
     * [OutdoorActivity.healthConnectRecordId]'s idempotency check).
     *
     * Returns null on any failure: Health Connect unavailable on this
     * device, [writePermissions] not granted, or the insert call itself
     * throwing. Requesting the missing permission is not done here — Health
     * Connect only exposes that as an [androidx.activity.result.ActivityResultContract]
     * that must be launched from an Activity/Compose caller (see
     * [PermissionController.createRequestPermissionResultContract], already
     * used for the read-only step permission at the [DashboardScreen] call
     * site) — so a caller lacking the write grant should route the user
     * through that launcher first, then retry the export.
     *
     * Guards [OutdoorActivity.healthConnectRecordId] internally rather than
     * relying solely on the caller to check first (matching
     * `HealthKitManager.swift`'s internal guard) — cheap defense in depth
     * against a double-export.
     */
    suspend fun exportOutdoorActivity(context: Context, activity: OutdoorActivity): String? {
        if (activity.healthConnectRecordId != null) return null
        if (activity.endedAtEpochMs == null) return null
        if (!isAvailable(context)) return null
        if (!hasWritePermission(context)) return null

        val client = HealthConnectClient.getOrCreate(context)
        val records = try {
            buildExerciseRecords(activity)
        } catch (e: IllegalArgumentException) {
            // Malformed activity data (e.g. a start/end pair that can't form
            // a valid interval) must not crash the export flow.
            return null
        }

        return try {
            // records[0] is always the ExerciseSessionRecord — see buildExerciseRecords.
            client.insertRecords(records).recordIdsList.firstOrNull()
        } catch (e: Exception) {
            // SecurityException (permission revoked between the check above
            // and the call), RemoteException, IOException, IllegalStateException
            // (Health Connect service unavailable) — all failures here mean
            // "not exported", never a crash.
            null
        }
    }

    /**
     * Sum of step records from midnight to now, from any source (phone,
     * watch, or a third-party app) — whatever Health Connect itself
     * considers today's total.
     */
    suspend fun todaysStepCount(context: Context): Long {
        if (!isAvailable(context)) return 0
        val client = HealthConnectClient.getOrCreate(context)

        val startOfDay = LocalDateTime.now(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay()
        val now = Instant.now()

        return try {
            val response = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(
                        startOfDay.atZone(ZoneId.systemDefault()).toInstant(),
                        now
                    )
                )
            )
            response[StepsRecord.COUNT_TOTAL] ?: 0
        } catch (e: SecurityException) {
            0
        }
    }

    /**
     * Steps per day for the last [days] days, keyed "yyyy-MM-dd", ending
     * today. Days Health Connect has nothing for are absent rather than zero —
     * a coach reading a chart needs "no data" and "did not move" to look
     * different.
     *
     * Without READ_HEALTH_DATA_HISTORY this quietly returns only the last 30
     * days, which is Health Connect's own limit and not something to treat as
     * an error.
     */
    suspend fun dailyStepCounts(context: Context, days: Int): Map<String, Long> {
        if (!isAvailable(context) || days <= 0) return emptyMap()
        val client = HealthConnectClient.getOrCreate(context)

        val zone = ZoneId.systemDefault()
        val endOfToday = LocalDateTime.now(zone).toLocalDate().plusDays(1).atStartOfDay()
        val start = endOfToday.minusDays(days.toLong())

        return try {
            val response = client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, endOfToday),
                    timeRangeSlicer = Period.ofDays(1)
                )
            )
            response.mapNotNull { bucket ->
                val count = bucket.result[StepsRecord.COUNT_TOTAL] ?: return@mapNotNull null
                bucket.startTime.toLocalDate().format(DAY_FORMAT) to count
            }.toMap()
        } catch (e: SecurityException) {
            emptyMap()
        } catch (e: Exception) {
            // A failed history read must not stop someone sending their log.
            emptyMap()
        }
    }

    private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
}

/**
 * Builds the [ExerciseSessionRecord] for [activity], including its route.
 * Pulled out of [HealthConnectManager.exportOutdoorActivity] so it can be
 * unit-tested without a real Health Connect client — [ExerciseSessionRecord]
 * and [ExerciseRoute] are plain data classes with real validation in their
 * `init` blocks, and that validation is exactly what has sharp edges here:
 *
 * - [ExerciseRoute] rejects two points sharing a timestamp (its `init`
 *   requires each point's time to be strictly before the next once sorted),
 *   which real GPS fixes occasionally do.
 * - [ExerciseSessionRecord] rejects a route whose last point is not strictly
 *   before the session's end time, and [OutdoorActivity.endedAtEpochMs] is
 *   commonly set from that same last GPS fix.
 *
 * Both are handled defensively below rather than trusted to never happen.
 */
internal fun buildExerciseSessionRecord(activity: OutdoorActivity): ExerciseSessionRecord {
    val endedAtEpochMs = requireNotNull(activity.endedAtEpochMs) {
        "activity must be finished (endedAtEpochMs set) before it can be exported"
    }

    val exerciseType = when (activity.activityType) {
        OutdoorActivityType.RUN -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
        OutdoorActivityType.WALK -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
        OutdoorActivityType.HIKE -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
    }

    // Dedupe by timestamp (keeping first-seen) and sort — ExerciseRoute
    // requires strictly increasing times once sorted.
    val dedupedPoints = activity.routePoints
        .sortedBy { it.recordedAtEpochMs }
        .distinctBy { it.recordedAtEpochMs }

    var startInstant = Instant.ofEpochMilli(activity.startedAtEpochMs)
    var endInstant = Instant.ofEpochMilli(endedAtEpochMs)

    val route = dedupedPoints.takeIf { it.isNotEmpty() }?.let { points ->
        val firstPointInstant = Instant.ofEpochMilli(points.first().recordedAtEpochMs)
        val lastPointInstant = Instant.ofEpochMilli(points.last().recordedAtEpochMs)

        // A route point can't precede the session's own start.
        if (firstPointInstant.isBefore(startInstant)) {
            startInstant = firstPointInstant
        }
        // A route point's time must be strictly before the session's end;
        // nudge the end forward by 1ms rather than drop the point.
        if (!lastPointInstant.isBefore(endInstant)) {
            endInstant = lastPointInstant.plusMillis(1)
        }

        ExerciseRoute(
            points.map { point ->
                ExerciseRoute.Location(
                    time = Instant.ofEpochMilli(point.recordedAtEpochMs),
                    latitude = point.latitude,
                    longitude = point.longitude,
                    horizontalAccuracy = point.horizontalAccuracyMeters.meters,
                    verticalAccuracy = point.verticalAccuracyMeters.meters,
                    altitude = point.altitudeMeters.meters
                )
            }
        )
    }

    val zone = ZoneId.systemDefault()
    val startZoneOffset = zone.rules.getOffset(startInstant)
    val endZoneOffset = zone.rules.getOffset(endInstant)

    return ExerciseSessionRecord(
        startTime = startInstant,
        startZoneOffset = startZoneOffset,
        endTime = endInstant,
        endZoneOffset = endZoneOffset,
        metadata = Metadata.activelyRecorded(Device(type = Device.TYPE_PHONE)),
        exerciseType = exerciseType,
        title = activity.activityType.displayName,
        exerciseRoute = route
    )
}

/**
 * Builds every Health Connect record [HealthConnectManager.exportOutdoorActivity]
 * inserts for [activity] in one batch: the [ExerciseSessionRecord] (always
 * first — callers rely on that ordering to recover the session's own record
 * id from `insertRecords(...).recordIdsList`), plus a companion
 * [DistanceRecord] and [ElevationGainedRecord] (I-5 in the final-review fix
 * wave — [ExerciseSessionRecord] itself has no distance/elevation fields).
 *
 * The companion records reuse the session record's own (possibly
 * route-nudged) start/end instants and zone offsets rather than recomputing
 * them, so all three records describe exactly the same time window and their
 * `startTime.isBefore(endTime)` validation is automatically satisfied by
 * [ExerciseSessionRecord]'s own identical requirement.
 */
internal fun buildExerciseRecords(activity: OutdoorActivity): List<Record> {
    val session = buildExerciseSessionRecord(activity)
    val metadata = Metadata.activelyRecorded(Device(type = Device.TYPE_PHONE))

    val distance = DistanceRecord(
        startTime = session.startTime,
        startZoneOffset = session.startZoneOffset,
        endTime = session.endTime,
        endZoneOffset = session.endZoneOffset,
        distance = activity.distanceMeters.meters,
        metadata = metadata
    )
    val elevationGained = ElevationGainedRecord(
        startTime = session.startTime,
        startZoneOffset = session.startZoneOffset,
        endTime = session.endTime,
        endZoneOffset = session.endZoneOffset,
        elevation = activity.elevationGainMeters.meters,
        metadata = metadata
    )

    return listOf<Record>(session, distance, elevationGained)
}
