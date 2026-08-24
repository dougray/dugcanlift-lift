package com.dugcanlift.macrocalc.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Read-only Health Connect access to step counts, for today and for history.
 * Mirrors the read side of HealthKitManager on iOS — same idempotency
 * non-issue here, since this app never writes to Health Connect, only reads.
 */
object HealthConnectManager {

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class)
    )

    /**
     * What the grant sheet asks for. History is bundled in but deliberately
     * kept out of [permissions]: it is what lets a step history reach further
     * back than 30 days, and someone who declines it should still count as
     * connected rather than being nagged forever.
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
