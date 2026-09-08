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
    val date: String
)

internal fun ScheduledSession.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("routineId", routineId)
    .put("routineName", routineName)
    .put("date", date)

internal fun scheduledSessionFromJson(o: JSONObject) = ScheduledSession(
    id = o.optString("id", UUID.randomUUID().toString()),
    routineId = o.optString("routineId", ""),
    routineName = o.optString("routineName", ""),
    date = o.optString("date", "")
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
