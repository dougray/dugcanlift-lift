package com.dugcanlift.macrocalc.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Read-only Health Connect access for today's step count. Mirrors the read
 * side of HealthKitManager on iOS — same idempotency non-issue here, since
 * this app never writes to Health Connect, only reads.
 */
object HealthConnectManager {

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class)
    )

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
}
