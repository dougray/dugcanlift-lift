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

/**
 * A coach-scheduled reference to a routine on a date — not a pre-created
 * workout day. Starting one runs the same manual "start a routine" path any
 * routine already uses; this only says which day it was meant for.
 */
data class ScheduledSession(
    val id: String = UUID.randomUUID().toString(),
    val routineId: String,
    val routineName: String,
    /** "yyyy-MM-dd", same key format the food/workout logs use. */
    val date: String,
    /**
     * The coach who sent this booking, as the plan link's `n` gave it — null for a booking a plan
     * carried no name for, and for every booking written before this field existed.
     *
     * It is stored on the booking because that is where LIFT web keeps it (`training[].fromCoach`,
     * under this same spelling) and because a booking is the only record of a plan that never leaves
     * the device: `WorkoutSession` travels in the backup and in the share link the lifter sends
     * back, and somebody else's name has no business riding in either for the sake of one muted line
     * on Train. Read by [PlanLog.sentBy] and nowhere else — the card signs the week once and names
     * nobody anywhere below that.
     */
    val fromCoach: String? = null
)

/**
 * A booking as JSON. [ScheduledSession.fromCoach] is **omitted when null**, so a device that has
 * never been sent a name writes byte for byte the object it always wrote — and an older build, which
 * reads keys by name and ignores the rest, loads a file this one writes with every field it knows
 * about intact. `ScheduledSessionTest` pins both directions.
 */
internal fun ScheduledSession.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("routineId", routineId)
    .put("routineName", routineName)
    .put("date", date)
    .also { o -> fromCoach?.let { o.put("fromCoach", it) } }

internal fun scheduledSessionFromJson(o: JSONObject) = ScheduledSession(
    id = o.optString("id", UUID.randomUUID().toString()),
    routineId = o.optString("routineId", ""),
    routineName = o.optString("routineName", ""),
    date = o.optString("date", ""),
    // A real string or nothing. `optString` would read a JSON `true` — which is what LIFT web
    // stores for "a coach, name unknown" — as the name "true", and print it.
    fromCoach = (o.opt("fromCoach") as? String)?.trim()?.ifBlank { null }
)

fun List<ScheduledSession>.onDate(date: String): List<ScheduledSession> = filter { it.date == date }

class ScheduledSessionRepository private constructor(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    private val _sessions = MutableStateFlow<List<ScheduledSession>>(emptyList())
    val sessions: StateFlow<List<ScheduledSession>> = _sessions.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _sessions.value = read()
    }

    suspend fun add(session: ScheduledSession) = withContext(Dispatchers.IO) {
        val updated = read() + session
        write(updated)
        _sessions.value = updated
    }

    private fun read(): List<ScheduledSession> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { scheduledSessionFromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun write(sessions: List<ScheduledSession>) {
        val array = JSONArray()
        sessions.forEach { array.put(it.toJson()) }
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(array.toString())
        temp.renameTo(file)
    }

    companion object {
        private const val FILE_NAME = "scheduled_sessions.json"

        @Volatile
        private var instance: ScheduledSessionRepository? = null

        fun get(context: Context): ScheduledSessionRepository =
            instance ?: synchronized(this) {
                instance ?: ScheduledSessionRepository(context).also { instance = it }
            }
    }
}
