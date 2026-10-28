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
    val targetRpe: Double? = null,
    val targetDurationSec: Int? = null,
    val targetDistanceMeters: Double? = null,
    val note: String = "",
    /**
     * A coach's sets one by one, kept whenever the targets above cannot say
     * what the coach wrote: a ramp (60/60/70), a set whose weight was left to
     * the lifter, each side, or a set naming one (PLAN-FORMAT "Sides"). The
     * targets flatten a prescription to one set and a count, which says
     * neither 60/60/70 nor "plus one more on the left". [Prescription] is the
     * rule; null when the targets say the whole of it, and they stay what they
     * always were either way.
     */
    val prescribed: List<PrescribedSet>? = null,
    /** PLAN-FORMAT's `b: 1`: every set in [prescribed] is done on both sides. */
    val eachSide: Boolean = false,
    /**
     * The superset or circuit this exercise is in: adjacent exercises sharing a positive integer are
     * one group, done round by round (PLAN-FORMAT "Set types", `g`). Null is not grouped. Stored
     * renumbered 1, 2, 3... with a group of one removed -- [SetTypes] is the rule. Set by a coach's
     * plan or by "Superset with next".
     */
    val group: Int? = null,
    /**
     * Drop sets and rest-pause on a routine of your own, **by set position**: index 0 is set 1, which
     * is always straight, so it is always null. A routine of your own is a set count, not a list of
     * sets, so the kinds are a list beside that count -- never longer than [targetSets], trailing
     * straights trimmed, and null when every set is straight, so a routine without kinds is stored
     * and backed up as the object it always was. [SetTypes.routineSetKinds] is the rule, applied on
     * every read and write; [withSetKind] and [withTargetSets] keep it in memory.
     *
     * Only for an exercise without [prescribed]: a coach's sets carry their own kinds, on
     * [PrescribedSet.kind], and this is never written beside them. BACKUP-FORMAT
     * `"setKinds": [null, "drop", "restPause"]`, LIFT web's spelling.
     */
    val setKinds: List<SetKind?>? = null
) {
    val displayName: String
        get() = if (equipment.isBlank()) name else "$name ($equipment)"

    /**
     * "3 x 8 @ 185 lb", skipping whatever wasn't specified — and a coach's
     * sets one by one when the targets cannot say them ("60 x 8, 60 x 8,
     * 70 x 6, each side"). Summarising a ramp as its most common set would be
     * the flattening [prescribed] exists to undo, in words.
     */
    val summary: String
        get() {
            prescribed?.let { sets ->
                return sets.joinToString(", ") { it.summary } + if (eachSide) ", each side" else ""
            }
            val parts = mutableListOf<String>()
            parts += "$targetSets sets"
            targetReps?.let { parts += "x $it" }
            targetWeightLb?.let { parts += "@ ${it.toInt()} lb" }
            targetRpe?.let { parts += "RPE ${it}" }
            targetDurationSec?.let { parts += "${it}s" }
            targetDistanceMeters?.let { parts += "${it.toInt()}m" }
            // "4 sets x 8 @ 185 lb · set 3 drop, set 4 rest-pause": the kinded sets, by number.
            val kinded = (1 until targetSets).mapNotNull { i -> setKind(i)?.let { "set ${i + 1} ${it.label}" } }
            return parts.joinToString(" ") + if (kinded.isEmpty()) "" else " · " + kinded.joinToString(", ")
        }

    /**
     * The kind of set [index] (0 is set 1) of a routine of your own, or null for straight: set 1, a
     * set past [targetSets] and an exercise holding a coach's [prescribed] sets all are.
     */
    fun setKind(index: Int): SetKind? =
        if (prescribed != null || index <= 0 || index >= targetSets) null else setKinds?.getOrNull(index)

    /**
     * The exercise with set [index] made [kind] (null is straight). Nothing changes for set 1, a set
     * past [targetSets], or an exercise holding a coach's sets, whose kinds live on [prescribed].
     */
    fun withSetKind(index: Int, kind: SetKind?): RoutineExercise {
        if (prescribed != null || index <= 0 || index >= targetSets) return this
        val kinds = MutableList(targetSets) { setKind(it) }
        kinds[index] = kind
        return copy(setKinds = SetTypes.routineSetKinds(kinds, targetSets))
    }

    /** The exercise with [sets] sets, its kinds trimmed to fit: a kind on a set that is gone goes with it. */
    fun withTargetSets(sets: Int): RoutineExercise =
        copy(targetSets = sets, setKinds = SetTypes.routineSetKinds(setKinds, sets))
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

/** Whether exercise [index] is done round by round with the next -- the "Superset with next" chip. */
fun Routine.groupedWithNext(index: Int): Boolean = SetTypes.groupedWithNext(exercises.map { it.group }, index)

/**
 * The routine with exercise [index] joined to the next, or split from it -- [SetTypes.setGroupedWithNext],
 * Coach web's rules: joining onto a superset makes a circuit, and a split keeps the earlier
 * exercises grouped and gives the later ones a group of their own (or none, if one is left).
 */
fun Routine.withGroupedWithNext(index: Int, on: Boolean): Routine {
    val groups = SetTypes.setGroupedWithNext(exercises.map { it.group }, index, on)
    return copy(exercises = exercises.mapIndexed { i, e -> e.copy(group = groups[i]) })
}

/* ---------- JSON ---------- */

internal fun RoutineExercise.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("equipment", equipment)
    put("targetSets", targetSets)
    targetReps?.let { put("targetReps", it) }
    targetWeightLb?.let { put("targetWeightLb", it) }
    targetRpe?.let { put("targetRpe", it) }
    targetDurationSec?.let { put("targetDurationSec", it) }
    targetDistanceMeters?.let { put("targetDistanceMeters", it) }
    put("note", note)
    putPrescription(prescribed, eachSide)
    // BACKUP-FORMAT's `group`, omitted when not grouped -- so a routine without one writes the
    // object it always did.
    group?.let { put("group", it) }
    // BACKUP-FORMAT's `setKinds`, omitted when every set is straight and never beside a coach's
    // sets -- so a routine without kinds writes the object it always did.
    if (prescribed == null) SetTypes.routineSetKinds(setKinds, targetSets)?.let { kinds ->
        put("setKinds", JSONArray().also { array -> kinds.forEach { array.put(it?.wire ?: JSONObject.NULL) } })
    }
}

internal fun routineExerciseFromJson(o: JSONObject): RoutineExercise {
    val targetSets = o.finiteInt("targetSets", 3)
    val prescribed = o.prescribedOrNull()
    return RoutineExercise(
    id = o.optString("id", UUID.randomUUID().toString()),
    name = o.optString("name", ""),
    equipment = o.optString("equipment", ""),
    targetSets = targetSets,
    targetReps = o.finiteIntOrNull("targetReps"),
    targetWeightLb = o.finiteDoubleOrNull("targetWeightLb"),
    targetRpe = o.finiteDoubleOrNull("targetRpe"),
    targetDurationSec = o.finiteIntOrNull("targetDurationSec"),
    targetDistanceMeters = o.finiteDoubleOrNull("targetDistanceMeters"),
    note = o.optString("note", ""),
    prescribed = prescribed,
    eachSide = o.eachSideFlag(),
    // Lenient: anything but a positive integer is not grouped. [routineFromJson] renumbers the
    // routine's runs, so a group of one is none.
    group = SetTypes.groupOf(o.opt("group")),
    // Lenient, and never beside a coach's sets: see [RoutineExercise.setKinds].
    setKinds = if (prescribed != null) null else SetTypes.routineSetKindsOf(o.opt("setKinds"), targetSets)
    )
}

internal fun Routine.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("folder", folder)
    put("createdAt", createdAt)
    put("exercises", JSONArray().also { array ->
        exercises.regrouped({ group }) { copy(group = it) }.forEach { array.put(it.toJson()) }
    })
}

internal fun routineFromJson(o: JSONObject): Routine {
    val array = o.optJSONArray("exercises")
    val exercises = if (array == null) emptyList() else
        (0 until array.length()).map { routineExerciseFromJson(array.getJSONObject(it)) }
            .regrouped({ group }) { copy(group = it) }
    return Routine(
        id = o.optString("id", UUID.randomUUID().toString()),
        name = o.optString("name", ""),
        folder = o.optString("folder", ""),
        exercises = exercises,
        createdAt = o.finiteLong("createdAt", 0L)
    )
}

/* ---------- conversions ---------- */

/**
 * The sets this exercise asks for, **one by one**.
 *
 * [RoutineExercise.prescribed] where a coach's sets were kept, and the flattened targets repeated
 * [RoutineExercise.targetSets] times where they say the whole of it — a routine saved from a
 * workout, a starter split and every routine an older build wrote are nothing but targets, and
 * [Prescription] is the rule for which is which.
 *
 * One function, because two readers need the same answer: [toSession] lays a session out from it,
 * and [PlanLog]'s week card prints what a booked day asked for from it. Reading `targetReps` and
 * `targetWeightLb` instead would print a ramp of 225/225/245 as three 225s — the flattening
 * [RoutineExercise.prescribed] exists to undo.
 */
val RoutineExercise.askedSets: List<PrescribedSet>
    get() = prescribed ?: List(targetSets.coerceAtLeast(1)) {
        PrescribedSet(
            weightLb = targetWeightLb,
            reps = targetReps,
            rpe = targetRpe,
            durationSec = targetDurationSec,
            distanceMeters = targetDistanceMeters,
            // A routine of your own's drop sets and rest-pause, by position ([RoutineExercise.setKinds]).
            kind = setKind(it)
        )
    }

/**
 * Builds a session from a routine, with the sets already laid out so you tick
 * through them rather than adding each one by hand.
 *
 * A coach's sets are laid out **one by one, each with its own numbers**, when
 * [RoutineExercise.prescribed] kept them: a ramp is 60/60/70, and a weight the
 * coach left blank stays blank rather than borrowing the one beside it. Where
 * a prescription says nothing the targets cannot -- and for every routine
 * saved from a workout, added from a starter split or written by an older
 * build -- the targets are repeated [RoutineExercise.targetSets] times, which
 * is the same list.
 *
 * A prescription with sides in it also goes onto the exercise, so the header
 * counts against it and Add set offers the next set it asks for. Its sided
 * sets are logged one at a time, as they are done, rather than pre-filled: a
 * pre-filled left set is a claim nobody has made yet. Its two-sided sets -- on
 * an exercise that is not each side -- are laid out as always. LIFT web's
 * `startPrescribed`, rule for rule.
 */
fun Routine.toSession(date: String): WorkoutSession = WorkoutSession(
    date = date,
    name = name,
    exercises = exercises.map { template ->
        val prescribed = template.prescribed
        if (prescribed != null) LoggedExercise(
            name = template.name,
            equipment = template.equipment,
            note = template.note,
            // Carried onto the session only when it says something about
            // sides: that is what the header counts against and what Add set
            // offers a side from. A ramp has nothing left to say once its sets
            // are rows, and carrying it would write a key into every session
            // file that reads it back as nothing.
            prescribed = prescribed.takeIf { PerSideLogging.prescribesSides(it, template.eachSide) },
            eachSide = template.eachSide,
            group = template.group,
            sets = prescribed.filter { !template.eachSide && it.side == null }.map {
                WorkoutSet(
                    weightLb = it.weightLb,
                    reps = it.reps,
                    rpe = it.rpe,
                    durationSec = it.durationSec,
                    distanceMeters = it.distanceMeters,
                    // What the coach asked this set to be, so its row can say "drop". It never
                    // reaches the coach: SHARE-FORMAT has no set type. See SetTypes.
                    kind = it.kind
                )
            }
        ) else LoggedExercise(
            name = template.name,
            equipment = template.equipment,
            note = template.note,
            // A superset stays one on the day, so the session can label it as the routine does.
            group = template.group,
            // The targets, repeated -- [askedSets], which is the one place that knows how a
            // prescription with none of its own expands.
            sets = template.askedSets.map {
                WorkoutSet(
                    weightLb = it.weightLb,
                    reps = it.reps,
                    rpe = it.rpe,
                    durationSec = it.durationSec,
                    distanceMeters = it.distanceMeters,
                    // A drop set or rest-pause you set on the routine, so its row can say so. Never
                    // sent: SHARE-FORMAT has no set type.
                    kind = it.kind
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
            note = logged.note,
            // A superset done today is kept as one, and each set's kind by its position -- except
            // for a lift logged a side at a time, where one position is two logged sets. LIFT web's
            // `fromSession`, rule for rule.
            group = logged.group,
            setKinds = if (logged.sets.any { it.side != null }) null else
                SetTypes.routineSetKinds(logged.sets.map { it.kind }, logged.sets.size.coerceAtLeast(1))
        )
    }.regrouped({ group }) { copy(group = it) }
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
        // Case-insensitive: see backupIdKey. distinctBy so a file listing the
        // same record twice adds it once.
        val known = existing.map { backupIdKey(it.id) }.toSet()
        val fresh = incoming.filter { backupIdKey(it.id) !in known }.distinctBy { backupIdKey(it.id) }
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
