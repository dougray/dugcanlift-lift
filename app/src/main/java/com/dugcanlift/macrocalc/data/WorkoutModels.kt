package com.dugcanlift.macrocalc.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Training style. This only controls which fields the UI shows — every set
 * stores every field regardless, so switching focus never loses data.
 */
enum class TrainingFocus(
    val label: String,
    val showWeight: Boolean,
    val showReps: Boolean,
    val showRpe: Boolean,
    val showTime: Boolean,
    val showDistance: Boolean
) {
    BODYBUILDING("Bodybuilding", true, true, true, false, false),
    POWERLIFTING("Powerlifting", true, true, true, false, false),
    CROSSFIT("CrossFit", true, true, false, true, false),
    HYROX("Hyrox", true, true, false, true, true),
    ENDURANCE("Endurance", false, false, true, true, true),
    EVERYTHING("Everything", true, true, true, true, true)
}

/**
 * One set. Every field is optional because a set means different things
 * depending on the movement — 185 lb x 5 for a squat, 400 m in 90 s for a
 * sled push, just 12 reps for a bodyweight movement.
 *
 * Distance is stored in metres as the canonical unit and converted for
 * display, so changing display units later can't corrupt stored data.
 */
data class WorkoutSet(
    val id: String = UUID.randomUUID().toString(),
    val weightLb: Double? = null,
    val reps: Int? = null,
    val rpe: Double? = null,
    val durationSec: Int? = null,
    val distanceMeters: Double? = null
) {
    /** Weight moved, for the sets where that means something. */
    val volumeLb: Double
        get() = if (weightLb != null && reps != null) weightLb * reps else 0.0

    val isEmpty: Boolean
        get() = weightLb == null && reps == null && rpe == null &&
            durationSec == null && distanceMeters == null
}

data class LoggedExercise(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sets: List<WorkoutSet> = emptyList(),
    val note: String = ""
) {
    val volumeLb: Double get() = sets.sumOf { it.volumeLb }
}

data class WorkoutSession(
    val id: String = UUID.randomUUID().toString(),
    val date: String = todayKey(),
    val name: String = "",
    val note: String = "",
    val exercises: List<LoggedExercise> = emptyList(),
    val startedAt: Long = System.currentTimeMillis()
) {
    val volumeLb: Double get() = exercises.sumOf { it.volumeLb }
    val setCount: Int get() = exercises.sumOf { it.sets.size }
}

/* ---------- JSON ---------- */

internal fun WorkoutSet.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    weightLb?.let { put("weightLb", it) }
    reps?.let { put("reps", it) }
    rpe?.let { put("rpe", it) }
    durationSec?.let { put("durationSec", it) }
    distanceMeters?.let { put("distanceMeters", it) }
}

internal fun workoutSetFromJson(o: JSONObject): WorkoutSet = WorkoutSet(
    id = o.optString("id", UUID.randomUUID().toString()),
    weightLb = o.optDoubleOrNull("weightLb"),
    reps = o.optIntOrNull("reps"),
    rpe = o.optDoubleOrNull("rpe"),
    durationSec = o.optIntOrNull("durationSec"),
    distanceMeters = o.optDoubleOrNull("distanceMeters")
)

internal fun LoggedExercise.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("note", note)
    put("sets", JSONArray().also { array -> sets.forEach { array.put(it.toJson()) } })
}

internal fun loggedExerciseFromJson(o: JSONObject): LoggedExercise {
    val setsArray = o.optJSONArray("sets")
    val sets = if (setsArray == null) emptyList() else
        (0 until setsArray.length()).map { workoutSetFromJson(setsArray.getJSONObject(it)) }
    return LoggedExercise(
        id = o.optString("id", UUID.randomUUID().toString()),
        name = o.optString("name", ""),
        sets = sets,
        note = o.optString("note", "")
    )
}

internal fun WorkoutSession.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("date", date)
    put("name", name)
    put("note", note)
    put("startedAt", startedAt)
    put("exercises", JSONArray().also { array -> exercises.forEach { array.put(it.toJson()) } })
}

internal fun workoutSessionFromJson(o: JSONObject): WorkoutSession {
    val exercisesArray = o.optJSONArray("exercises")
    val exercises = if (exercisesArray == null) emptyList() else
        (0 until exercisesArray.length()).map {
            loggedExerciseFromJson(exercisesArray.getJSONObject(it))
        }
    return WorkoutSession(
        id = o.optString("id", UUID.randomUUID().toString()),
        date = o.optString("date", todayKey()),
        name = o.optString("name", ""),
        note = o.optString("note", ""),
        exercises = exercises,
        startedAt = o.optLong("startedAt", 0L)
    )
}

/**
 * JSONObject has no nullable getters — optInt returns 0 for a missing key,
 * which would turn "no reps recorded" into "zero reps". These preserve null.
 */
private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null
