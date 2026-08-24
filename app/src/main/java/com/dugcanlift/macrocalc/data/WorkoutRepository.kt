package com.dugcanlift.macrocalc.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.Locale

/**
 * Stores workout sessions as JSON on the device. Same approach as
 * FoodRepository — no database, no annotation processing, and callers only
 * ever touch this class so the storage can be swapped later.
 */
class WorkoutRepository private constructor(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    private val _sessions = MutableStateFlow<List<WorkoutSession>>(emptyList())
    val sessions: StateFlow<List<WorkoutSession>> = _sessions.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _sessions.value = read()
    }

    suspend fun save(session: WorkoutSession) = withContext(Dispatchers.IO) {
        val existing = read()
        val updated = if (existing.any { it.id == session.id }) {
            existing.map { if (it.id == session.id) session else it }
        } else {
            existing + session
        }
        write(updated)
        _sessions.value = updated
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val updated = read().filterNot { it.id == id }
        write(updated)
        _sessions.value = updated
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        write(emptyList())
        _sessions.value = emptyList()
    }

    /**
     * Adds only sessions this device has never seen, matched on id, and returns
     * how many landed. Never overwrites a session already here — see the note
     * on FoodRepository.restoreMissing.
     */
    fun restoreMissing(incoming: List<WorkoutSession>): Int {
        val existing = read()
        val known = existing.map { it.id }.toSet()
        val fresh = incoming.filter { it.id !in known }
        if (fresh.isEmpty()) return 0
        val updated = existing + fresh
        write(updated)
        _sessions.value = updated
        return fresh.size
    }


    /* ---------- file access ---------- */

    @Synchronized
    private fun read(): List<WorkoutSession> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { workoutSessionFromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            // Better to start empty than to crash on launch over a bad file.
            emptyList()
        }
    }

    @Synchronized
    private fun write(sessions: List<WorkoutSession>) {
        val array = JSONArray()
        sessions.forEach { array.put(it.toJson()) }
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(array.toString())
        temp.renameTo(file)
    }

    companion object {
        private const val FILE_NAME = "workout_log.json"

        @Volatile
        private var instance: WorkoutRepository? = null

        fun get(context: Context): WorkoutRepository =
            instance ?: synchronized(this) {
                instance ?: WorkoutRepository(context).also { instance = it }
            }
    }
}

/** Sessions for one day, most recent first. */
fun List<WorkoutSession>.sessionsForDate(date: String): List<WorkoutSession> =
    filter { it.date == date }.sortedByDescending { it.startedAt }

/**
 * Exercises used before, most recent first, as name+equipment pairs. Feeds
 * autocomplete so nobody retypes "Bench Press / Barbell" every week.
 */
fun List<WorkoutSession>.knownExercises(limit: Int = 30): List<LoggedExercise> =
    sortedByDescending { it.startedAt }
        .flatMap { it.exercises }
        .filter { it.name.isNotBlank() }
        .distinctBy { it.matchKey }
        .take(limit)

/** Equipment the person has actually used, so their own vocabulary comes first. */
fun List<WorkoutSession>.knownEquipment(): List<String> =
    sortedByDescending { it.startedAt }
        .flatMap { it.exercises }
        .map { it.equipment.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase(Locale.US) }

/**
 * The last time this exercise was performed, for showing previous numbers.
 * Matches on name AND equipment — a cable pulldown isn't a machine pulldown.
 */
fun List<WorkoutSession>.lastPerformed(name: String, equipment: String = ""): LoggedExercise? {
    val target = "${name.trim()}|${equipment.trim()}".lowercase(Locale.US)
    return sortedByDescending { it.startedAt }
        .flatMap { it.exercises }
        .firstOrNull { it.matchKey == target }
}


/**
 * Every time this exercise was performed, oldest first, paired with its date.
 *
 * Progression is plotted per occurrence rather than per calendar day: an
 * exercise trained twice a week would otherwise be five-sevenths empty space.
 */
fun List<WorkoutSession>.historyFor(
    name: String,
    equipment: String = ""
): List<Pair<String, LoggedExercise>> {
    val target = "${name.trim()}|${equipment.trim()}".lowercase(Locale.US)
    return sortedBy { it.startedAt }
        .flatMap { session -> session.exercises.map { session.date to it } }
        .filter { it.second.matchKey == target }
        .filter { it.second.sets.isNotEmpty() }
}

/** Heaviest set performed. */
fun LoggedExercise.topWeightLb(): Double? = sets.mapNotNull { it.weightLb }.maxOrNull()

/**
 * Estimated one-rep max via the Epley formula, taking the best set.
 *
 * It's an estimate, and it drifts at high rep counts — but it's the standard
 * way to compare 185x5 against 195x3, which raw top weight can't do.
 */
fun LoggedExercise.estimatedOneRepMax(): Double? = sets
    .mapNotNull { set ->
        val weight = set.weightLb ?: return@mapNotNull null
        val reps = set.reps ?: return@mapNotNull null
        if (reps <= 0) null else weight * (1.0 + reps / 30.0)
    }
    .maxOrNull()
