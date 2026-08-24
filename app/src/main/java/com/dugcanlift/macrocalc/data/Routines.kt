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
 * A saved routine — a template, not a record. "Push A: bench 3x8, overhead
 * press 3x10." Starting one creates a fresh WorkoutSession with the exercises
 * already in place and the target numbers prefilled, which you then overwrite
 * with what actually happened.
 */
data class RoutineExercise(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val equipment: String = "",
    val targetSets: Int = 3,
    val targetReps: Int? = null,
    val targetWeightLb: Double? = null,
    val note: String = ""
) {
    val displayName: String
        get() = if (equipment.isBlank()) name else "$name ($equipment)"

    /** "3 x 8 @ 185 lb", skipping whatever wasn't specified. */
    val summary: String
        get() {
            val parts = mutableListOf<String>()
            parts += "$targetSets sets"
            targetReps?.let { parts += "x $it" }
            targetWeightLb?.let { parts += "@ ${it.toInt()} lb" }
            return parts.joinToString(" ")
        }
}

data class Routine(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val folder: String = "",
    val exercises: List<RoutineExercise> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
) {
    /** The exercise list, for the one-line preview under the routine name. */
    val preview: String
        get() = exercises.joinToString(", ") { it.displayName }
}

/* ---------- JSON ---------- */

internal fun RoutineExercise.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("equipment", equipment)
    put("targetSets", targetSets)
    targetReps?.let { put("targetReps", it) }
    targetWeightLb?.let { put("targetWeightLb", it) }
    put("note", note)
}

internal fun routineExerciseFromJson(o: JSONObject) = RoutineExercise(
    id = o.optString("id", UUID.randomUUID().toString()),
    name = o.optString("name", ""),
    equipment = o.optString("equipment", ""),
    targetSets = o.optInt("targetSets", 3),
    targetReps = if (o.has("targetReps") && !o.isNull("targetReps")) o.optInt("targetReps") else null,
    targetWeightLb = if (o.has("targetWeightLb") && !o.isNull("targetWeightLb"))
        o.optDouble("targetWeightLb") else null,
    note = o.optString("note", "")
)

internal fun Routine.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("folder", folder)
    put("createdAt", createdAt)
    put("exercises", JSONArray().also { array -> exercises.forEach { array.put(it.toJson()) } })
}

internal fun routineFromJson(o: JSONObject): Routine {
    val array = o.optJSONArray("exercises")
    val exercises = if (array == null) emptyList() else
        (0 until array.length()).map { routineExerciseFromJson(array.getJSONObject(it)) }
    return Routine(
        id = o.optString("id", UUID.randomUUID().toString()),
        name = o.optString("name", ""),
        folder = o.optString("folder", ""),
        exercises = exercises,
        createdAt = o.optLong("createdAt", 0L)
    )
}

/* ---------- conversions ---------- */

/**
 * Builds a session from a routine, with the target sets already laid out so
 * you tick through them rather than adding each one by hand.
 */
fun Routine.toSession(date: String): WorkoutSession = WorkoutSession(
    date = date,
    name = name,
    exercises = exercises.map { template ->
        LoggedExercise(
            name = template.name,
            equipment = template.equipment,
            note = template.note,
            sets = List(template.targetSets.coerceAtLeast(1)) {
                WorkoutSet(
                    weightLb = template.targetWeightLb,
                    reps = template.targetReps
                )
            }
        )
    }
)

/** Turns a workout you just did into a reusable routine. */
fun WorkoutSession.toRoutine(routineName: String, folder: String = ""): Routine = Routine(
    name = routineName.ifBlank { name.ifBlank { "Routine" } },
    folder = folder,
    exercises = exercises.map { logged ->
        RoutineExercise(
            name = logged.name,
            equipment = logged.equipment,
            targetSets = logged.sets.size.coerceAtLeast(1),
            // Most common reps across the sets, so one heavy single doesn't
            // become the template for everything.
            targetReps = logged.sets.mapNotNull { it.reps }
                .groupingBy { it }.eachCount()
                .maxByOrNull { it.value }?.key,
            targetWeightLb = logged.sets.mapNotNull { it.weightLb }.maxOrNull(),
            note = logged.note
        )
    }
)

/* ---------- storage ---------- */

class RoutineRepository private constructor(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    private val _routines = MutableStateFlow<List<Routine>>(emptyList())
    val routines: StateFlow<List<Routine>> = _routines.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _routines.value = read()
    }

    suspend fun save(routine: Routine) = withContext(Dispatchers.IO) {
        val existing = read()
        val updated = if (existing.any { it.id == routine.id }) {
            existing.map { if (it.id == routine.id) routine else it }
        } else {
            existing + routine
        }
        write(updated)
        _routines.value = updated
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val updated = read().filterNot { it.id == id }
        write(updated)
        _routines.value = updated
    }

    @Synchronized
    /**
     * Adds only routines this device has never seen, matched on id, and
     * returns how many landed. Never overwrites one already here.
     */
    fun restoreMissing(incoming: List<Routine>): Int {
        val existing = read()
        val known = existing.map { it.id }.toSet()
        val fresh = incoming.filter { it.id !in known }
        if (fresh.isEmpty()) return 0
        val updated = existing + fresh
        write(updated)
        _routines.value = updated
        return fresh.size
    }

    private fun read(): List<Routine> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { routineFromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun write(routines: List<Routine>) {
        val array = JSONArray()
        routines.forEach { array.put(it.toJson()) }
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(array.toString())
        temp.renameTo(file)
    }

    companion object {
        private const val FILE_NAME = "routines.json"

        @Volatile
        private var instance: RoutineRepository? = null

        fun get(context: Context): RoutineRepository =
            instance ?: synchronized(this) {
                instance ?: RoutineRepository(context).also { instance = it }
            }
    }
}

/** Routines grouped by folder, with unfoldered ones under "My Routines". */
fun List<Routine>.byFolder(): Map<String, List<Routine>> =
    groupBy { it.folder.ifBlank { "My Routines" } }
