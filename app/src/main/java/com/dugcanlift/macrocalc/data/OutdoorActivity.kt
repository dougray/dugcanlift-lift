package com.dugcanlift.macrocalc.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class OutdoorActivity(
    val id: String = UUID.randomUUID().toString(),
    val activityType: OutdoorActivityType,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val distanceMeters: Double = 0.0,
    val elevationGainMeters: Double = 0.0,
    val activeCalories: Double? = null,
    val routePoints: List<RoutePoint> = emptyList(),
    /** Set only after a successful Health Connect export — never export twice. */
    val healthConnectRecordId: String? = null
) {
    val durationMs: Long? get() = endedAtEpochMs?.minus(startedAtEpochMs)

    /** Seconds per metre, or null while a duration isn't known yet. */
    val averagePaceSecondsPerMeter: Double?
        get() {
            val duration = durationMs ?: return null
            if (distanceMeters <= 0.0) return null
            return (duration / 1000.0) / distanceMeters
        }
}

internal fun OutdoorActivity.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("activityType", activityType.name)
    put("startedAtEpochMs", startedAtEpochMs)
    endedAtEpochMs?.let { put("endedAtEpochMs", it) }
    put("distanceMeters", distanceMeters)
    put("elevationGainMeters", elevationGainMeters)
    activeCalories?.let { put("activeCalories", it) }
    healthConnectRecordId?.let { put("healthConnectRecordId", it) }
    put("routePoints", JSONArray().also { array ->
        routePoints.forEach { p ->
            array.put(JSONObject()
                .put("latitude", p.latitude)
                .put("longitude", p.longitude)
                .put("altitudeMeters", p.altitudeMeters)
                .put("recordedAtEpochMs", p.recordedAtEpochMs)
                .put("horizontalAccuracyMeters", p.horizontalAccuracyMeters)
                .put("verticalAccuracyMeters", p.verticalAccuracyMeters))
        }
    })
}

internal fun outdoorActivityFromJson(o: JSONObject): OutdoorActivity {
    val pointsArray = o.optJSONArray("routePoints")
    val points = if (pointsArray == null) emptyList() else
        (0 until pointsArray.length()).mapNotNull { i ->
            val p = pointsArray.optJSONObject(i) ?: return@mapNotNull null
            RoutePoint(
                latitude = p.finiteDouble("latitude", 0.0),
                longitude = p.finiteDouble("longitude", 0.0),
                altitudeMeters = p.finiteDouble("altitudeMeters", 0.0),
                recordedAtEpochMs = p.finiteLong("recordedAtEpochMs", 0L),
                horizontalAccuracyMeters = p.finiteDouble("horizontalAccuracyMeters", 0.0),
                verticalAccuracyMeters = p.finiteDouble("verticalAccuracyMeters", 0.0)
            )
        }
    return OutdoorActivity(
        id = o.optString("id", UUID.randomUUID().toString()),
        activityType = runCatching { OutdoorActivityType.valueOf(o.optString("activityType", "RUN")) }
            .getOrDefault(OutdoorActivityType.RUN),
        startedAtEpochMs = o.finiteLong("startedAtEpochMs", 0L),
        endedAtEpochMs = o.finiteLongOrNull("endedAtEpochMs"),
        distanceMeters = o.finiteDouble("distanceMeters", 0.0),
        elevationGainMeters = o.finiteDouble("elevationGainMeters", 0.0),
        activeCalories = o.finiteDoubleOrNull("activeCalories"),
        routePoints = points,
        healthConnectRecordId = if (o.has("healthConnectRecordId") && !o.isNull("healthConnectRecordId"))
            o.optString("healthConnectRecordId") else null
    )
}

class OutdoorActivityRepository private constructor(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    private val _activities = MutableStateFlow<List<OutdoorActivity>>(emptyList())
    val activities: StateFlow<List<OutdoorActivity>> = _activities.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _activities.value = read()
    }

    suspend fun save(activity: OutdoorActivity) = withContext(Dispatchers.IO) {
        val existing = read()
        val updated = if (existing.any { it.id == activity.id }) {
            existing.map { if (it.id == activity.id) activity else it }
        } else {
            existing + activity
        }
        write(updated)
        _activities.value = updated
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val updated = read().filterNot { it.id == id }
        write(updated)
        _activities.value = updated
    }

    /** Finished activities only, read from the file rather than [activities],
     *  which is empty until a screen has called [load]. A recording still in
     *  progress is never written to a backup. */
    fun activitiesForBackup(): List<OutdoorActivity> = read().filter { it.endedAtEpochMs != null }

    /**
     * Adds only activities this device has never seen, matched on id
     * case-insensitively (see [backupIdKey]), and returns how many landed.
     * Never overwrites: restoring an old file must not undo a Health Connect
     * export recorded on the activity since.
     */
    @Synchronized
    fun restoreMissing(incoming: List<OutdoorActivity>): Int {
        val existing = read()
        val known = existing.map { backupIdKey(it.id) }.toSet()
        val fresh = incoming.filter { backupIdKey(it.id) !in known }.distinctBy { backupIdKey(it.id) }
        if (fresh.isEmpty()) return 0
        val updated = existing + fresh
        write(updated)
        _activities.value = updated
        return fresh.size
    }

    @Synchronized
    private fun read(): List<OutdoorActivity> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { outdoorActivityFromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun write(activities: List<OutdoorActivity>) {
        val array = JSONArray()
        activities.forEach { array.put(it.toJson()) }
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(array.toString())
        temp.renameTo(file)
    }

    companion object {
        private const val FILE_NAME = "outdoor_activities.json"
        @Volatile private var instance: OutdoorActivityRepository? = null
        fun get(context: Context): OutdoorActivityRepository =
            instance ?: synchronized(this) { instance ?: OutdoorActivityRepository(context).also { instance = it } }
    }
}
