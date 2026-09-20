package com.dugcanlift.macrocalc.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Which number summarises a session under a given focus. */
enum class FocusSummary { VOLUME, TOP_SET, WORK, DISTANCE }

/** Which series the exercise progression chart plots under a given focus. */
enum class FocusChart { STRENGTH, VOLUME, WORK, PACE }

/**
 * Training style.
 *
 * This used to control only which fields the UI showed, and Bodybuilding and
 * Powerlifting carried identical flags — so picking between the two most
 * likely options changed nothing whatsoever. It now also decides what a new
 * set starts at, which number summarises a session, and what the progression
 * chart plots, because those are the things that actually differ between a
 * hypertrophy block and a strength block.
 *
 * The visibility flags stay, because they still matter: an endurance set has
 * no business asking for reps.
 *
 * `defaultReps` seeds the FIRST set of an exercise only. Every set after it
 * copies the one before, which is a better guess than any constant. Null means
 * the focus has no opinion.
 *
 * Every set stores every field regardless of focus, so switching focus — or
 * opening a log written on a device set to another focus — never loses data.
 */
enum class TrainingFocus(
    val label: String,
    val showWeight: Boolean,
    val showReps: Boolean,
    val showRpe: Boolean,
    val showTime: Boolean,
    val showDistance: Boolean,
    val defaultReps: Int?,
    val summary: FocusSummary,
    val chart: FocusChart
) {
    BODYBUILDING("Bodybuilding", true, true, true, false, false,
        defaultReps = 10, summary = FocusSummary.VOLUME, chart = FocusChart.VOLUME),
    POWERLIFTING("Powerlifting", true, true, true, false, false,
        defaultReps = 5, summary = FocusSummary.TOP_SET, chart = FocusChart.STRENGTH),
    CROSSFIT("CrossFit", true, true, false, true, false,
        defaultReps = null, summary = FocusSummary.WORK, chart = FocusChart.WORK),
    HYROX("Hyrox", true, true, false, true, true,
        defaultReps = null, summary = FocusSummary.DISTANCE, chart = FocusChart.PACE),
    ENDURANCE("Endurance", false, false, true, true, true,
        defaultReps = null, summary = FocusSummary.DISTANCE, chart = FocusChart.PACE),
    EVERYTHING("Everything", true, true, true, true, true,
        defaultReps = 8, summary = FocusSummary.VOLUME, chart = FocusChart.STRENGTH)
}

/**
 * Which limb performed a set.
 *
 * There is no `BOTH` entry on purpose: absent is both, and it is the only thing
 * every set ever written by an older build can mean. A `BOTH` constant would
 * invite a non-null default, and a default of either side would be a guess
 * about training that nobody made.
 *
 * [wire] is BACKUP-FORMAT's spelling — a named field, so a file stays readable
 * by a human and by a platform that has never heard of sides. [short] is the
 * one letter the log shows.
 */
enum class SetSide(val wire: String, val short: String, val label: String) {
    LEFT("left", "L", "Left"),
    RIGHT("right", "R", "Right");

    companion object {
        /** Null for absent, blank, or anything this build does not recognise: all of them mean both. */
        fun fromWire(value: String?): SetSide? =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * One set. Every field is optional because a set means different things
 * depending on the movement — 185 lb x 5 for a squat, 400 m in 90 s for a
 * sled push, just 12 reps for a bodyweight movement.
 *
 * Distance is stored in metres as the canonical unit and converted for
 * display, so changing display units later can't corrupt stored data.
 *
 * [side] is the same shape: null means both, which is what every set written
 * before per-limb tracking means and what every set of a two-sided lift means
 * now. Nothing ever fills it in by guessing.
 */
data class WorkoutSet(
    val id: String = UUID.randomUUID().toString(),
    val weightLb: Double? = null,
    val reps: Int? = null,
    val rpe: Double? = null,
    val durationSec: Int? = null,
    val distanceMeters: Double? = null,
    val side: SetSide? = null
) {
    /** Weight moved, for the sets where that means something. */
    val volumeLb: Double
        get() = if (weightLb != null && reps != null) weightLb * reps else 0.0

    /**
     * A side alone is not a set. The L/R control has a value from the moment
     * the form opens, so counting it here would let an empty form be saved.
     */
    val isEmpty: Boolean
        get() = weightLb == null && reps == null && rpe == null &&
            durationSec == null && distanceMeters == null

    /** Epley, as [estimatedOneRepMax] takes it per exercise. Null unless this set is weight x reps. */
    val estimatedOneRepMax: Double?
        get() {
            val weight = weightLb ?: return null
            val reps = reps ?: return null
            return if (reps <= 0) null else weight * (1.0 + reps / 30.0)
        }
}

/**
 * Equipment is a separate field rather than part of the name, because
 * "Lat Pulldown (Cable)" and "Lat Pulldown (Machine)" are different lifts with
 * different numbers — and keeping them apart means history matches correctly.
 *
 * Stored as free text rather than an enum: the UI suggests the common ones,
 * but Hyrox and CrossFit need Sled, Ski Erg, Wall Ball and the rest, which a
 * closed list would flatten into "Other".
 */
data class LoggedExercise(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val equipment: String = "",
    val sets: List<WorkoutSet> = emptyList(),
    val note: String = ""
) {
    val volumeLb: Double get() = sets.sumOf { it.volumeLb }

    val displayName: String
        get() = if (equipment.isBlank()) name else "$name ($equipment)"

    /**
     * What history lookups match on — the exercise's identity, which is still
     * name and equipment. A side is a property of a *set*, not of the lift
     * being trained, so a day's left and right sets stay in the one exercise
     * and only the series drawn from them are keyed per side ([sideKey]).
     */
    val matchKey: String
        get() = "${name.trim()}|${equipment.trim()}".lowercase(java.util.Locale.US)

    /** This exercise's sets for one side. Null asks for the both-sided ones. */
    fun sets(side: SetSide?): List<WorkoutSet> = sets.filter { it.side == side }

    /** How many sets are logged per side, for the "L 3 · R 3" line. */
    fun setCount(side: SetSide?): Int = sets.count { it.side == side }

    /** True once any set of this exercise names a side. */
    val hasPerSideSets: Boolean get() = sets.any { it.side != null }
}

/**
 * The key a chart or a grouping uses: name, equipment **and** side.
 *
 * Same reasoning as equipment. A left-arm row and a right-arm row are not the
 * same lift, and averaging them hides exactly the thing being looked for — the
 * way one line once merged a cable pulldown with a machine pulldown.
 */
fun sideKey(matchKey: String, side: SetSide?): String = "$matchKey|${side?.wire ?: ""}"

/** [sideKey] for a whole exercise's sets on one side. */
fun LoggedExercise.sideKey(side: SetSide?): String = sideKey(matchKey, side)

val COMMON_EQUIPMENT = listOf(
    "Barbell", "Dumbbell", "Machine", "Cable", "Smith Machine",
    "Kettlebell", "Bodyweight", "Band", "Sled", "Rower",
    "Ski Erg", "Assault Bike", "Wall Ball", "Sandbag"
)

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

/**
 * BACKUP-FORMAT's `side`: `"left"` or `"right"`, **omitted when both** — this
 * is the file the web build and the iPhone read, and a named field is what
 * survives a platform that has never heard of it. Omitting it also means a
 * phone with no per-limb sets writes byte for byte the file it wrote before.
 */
internal fun WorkoutSet.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    weightLb?.let { put("weightLb", it) }
    reps?.let { put("reps", it) }
    rpe?.let { put("rpe", it) }
    durationSec?.let { put("durationSec", it) }
    distanceMeters?.let { put("distanceMeters", it) }
    side?.let { put("side", it.wire) }
}

internal fun workoutSetFromJson(o: JSONObject): WorkoutSet = WorkoutSet(
    id = o.optString("id", UUID.randomUUID().toString()),
    weightLb = o.finiteDoubleOrNull("weightLb"),
    reps = o.finiteIntOrNull("reps"),
    rpe = o.finiteDoubleOrNull("rpe"),
    durationSec = o.finiteIntOrNull("durationSec"),
    distanceMeters = o.finiteDoubleOrNull("distanceMeters"),
    // No `side`, or a spelling this build does not know, is both. A backup
    // written before per-limb tracking restores exactly as it always did.
    side = SetSide.fromWire(if (o.isNull("side")) null else o.optString("side", ""))
)

internal fun LoggedExercise.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("equipment", equipment)
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
        equipment = o.optString("equipment", ""),
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
        startedAt = o.finiteLong("startedAt", 0L)
    )
}
