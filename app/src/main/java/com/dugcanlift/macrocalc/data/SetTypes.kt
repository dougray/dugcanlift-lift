package com.dugcanlift.macrocalc.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * How a set follows the one before it. PLAN-FORMAT "Set types": flags bits 3-4, `1` drop, `2`
 * rest-pause; BACKUP-FORMAT: `kind: "drop" | "restPause"`, omitted when straight.
 *
 * There is no `STRAIGHT` entry, for the reason [SetSide] has no `BOTH`: absent is straight, and it
 * is the only thing a set written before set types can mean. A constant for it would invite a
 * non-null default.
 *
 * [wire] is BACKUP-FORMAT's spelling. [label] is the word a set's text ends with, after its side:
 * "30 x 12 L drop" -- Coach web's words and order, so a coach and their client read one sentence.
 */
enum class SetKind(val wire: String, val label: String, internal val bits: Int) {
    DROP("drop", "drop", 1),
    REST_PAUSE("restPause", "rest-pause", 2);

    companion object {
        /**
         * Null for absent, blank, `"straight"`, or anything this build does not recognise: all of
         * them mean straight, the only safe reading of a set nobody described. Case and surrounding
         * space are forgiven, as they are for a side. Only a string is read -- a number, a boolean
         * or an object in `kind` is junk another app wrote, not a spelling.
         */
        fun fromWire(value: Any?): SetKind? {
            val text = (value as? String)?.trim() ?: return null
            return entries.firstOrNull { it.wire.equals(text, ignoreCase = true) }
        }

        /**
         * The kind bits 3-4 of a flags value say, or null for straight.
         *
         * **Masked, never compared.** A left-side drop set is `10` -- side 1 in bits 1-2, kind 1 in
         * bits 3-4 -- and a reader testing `flags == 8` would call it straight. `3` in those bits is
         * never written and reads as straight, as `3` in the side bits reads as both.
         */
        fun fromFlags(flags: Int?): SetKind? = when (((flags ?: 0) shr KIND_SHIFT) and KIND_MASK) {
            DROP.bits -> DROP
            REST_PAUSE.bits -> REST_PAUSE
            else -> null
        }

        private const val KIND_SHIFT = 3
        private const val KIND_MASK = 3
    }
}

/**
 * Set types: supersets and circuits, drop sets and rest-pause.
 *
 * `coach/PLAN-FORMAT.md` "Set types" has the rules, Coach web's `coach/prescriptions.js` is the
 * reference implementation, and LIFT web's `lift/set-types.js` is the port of its reader's half --
 * this is that port again, function for function, so a coach and their client read the same groups
 * and the same words on every build. The rule changes in `prescriptions.js` first and is ported
 * again; it is never improved here.
 *
 * Two things, each an optional field omitted when it does not apply, the shape sides already have:
 *
 * - **a group**: exercises done round by round -- one set of each, then the rest. Two make a
 *   superset, three or more a circuit. `group: <positive integer>` on each member
 *   ([RoutineExercise.group], [LoggedExercise.group]); `g` on a plan link. **Adjacency makes the
 *   group, not the number**: `g: 1` on exercises one and three with another between them is two
 *   groups of one, and a group of one is an ordinary exercise -- so storage never keeps one.
 * - **a kind**: [SetKind], on a set ([PrescribedSet.kind], [WorkoutSet.kind]). An exercise's first
 *   set has no set before it, so it is always straight, whatever a file says.
 *
 * Where they live here:
 *
 * - **a coach's plan**: read off the link's own JSON by [planTypes], because the pinned kit models
 *   neither (`g` and bits 3-4 are app-side, the way `rf` and the coach's `n` are). A group goes on
 *   the [RoutineExercise], a kind on its [PrescribedSet] -- which makes the exercise one that is
 *   kept set by set, since a kind on a later set makes it differ from the first ([Prescription]).
 * - **a started day**: [Routine.toSession] copies the group onto the [LoggedExercise] and each kind
 *   onto the pre-filled [WorkoutSet] (and, inside a sided prescription, onto its [PrescribedSet]),
 *   so the session can label them. Informational: SHARE-FORMAT carries no set type and
 *   `CoachShare` writes a logged set field by field, so a drop set logged here reaches a coach as
 *   an ordinary set with its real numbers.
 * - **your routines**: `group`, set with "Superset with next" ([setGroupedWithNext]). A routine of
 *   your own stores a set count ([RoutineExercise.targetSets]), not a list of sets, so its kinds are
 *   a list beside that count, by position ([RoutineExercise.setKinds], [routineSetKinds]), set from
 *   the routine's Exercises. An exercise holding a coach's sets keeps its kinds on them instead.
 *
 * Counted and shown, never graded. Nothing here says whether a superset was done as one, and
 * the imbalance figure and volume read the log, never a plan. The next weight ([ProgressionRule])
 * reads straight sets only: a drop set is lighter on purpose and a rest-pause set short on purpose.
 *
 * Pure and free of Android, like [Prescription] and [SideBalance].
 */
object SetTypes {

    /* ---------------- set kinds ---------------- */

    /**
     * The kind of set [index] of a list as it should be shown and stored: the first set is straight
     * whatever it says, because there is nothing before it to drop from or pause after -- which can
     * happen in a log, when a set before a drop set is deleted.
     */
    fun <T> kindAt(sets: List<T>, index: Int, kind: (T) -> SetKind?): SetKind? =
        if (index > 0) sets.getOrNull(index)?.let(kind) else null

    /**
     * A routine of your own's kinds as they are stored ([RoutineExercise.setKinds]): by set position,
     * set 1 straight whatever the list says, nothing past [targetSets], trailing straights trimmed,
     * and null -- not a list of nulls -- when every set is straight, so a routine without kinds is
     * the object it always was. Applied on every write and every read.
     */
    fun routineSetKinds(kinds: List<SetKind?>?, targetSets: Int): List<SetKind?>? {
        if (kinds == null) return null
        val kept = kinds.take(targetSets.coerceAtLeast(0)).mapIndexed { i, kind -> if (i == 0) null else kind }
        val last = kept.indexOfLast { it != null }
        return if (last < 0) null else kept.subList(0, last + 1).toList()
    }

    /**
     * BACKUP-FORMAT's `setKinds`, read leniently: anything but an array is none, an entry that is not
     * `"drop"` or `"restPause"` (case and space forgiven, as [SetKind.fromWire] forgives them) is
     * straight, and the result is [routineSetKinds]'s.
     */
    fun routineSetKindsOf(value: Any?, targetSets: Int): List<SetKind?>? {
        val array = value as? JSONArray ?: return null
        return routineSetKinds((0 until array.length()).map { SetKind.fromWire(array.opt(it)) }, targetSets)
    }

    /** " drop", " rest-pause", or nothing: said after a set's side. */
    fun suffix(kind: SetKind?): String = kind?.let { " ${it.label}" } ?: ""

    /* ---------------- groups ---------------- */

    /**
     * A group number as stored or sent: a JSON positive integer, or null.
     *
     * `2.0` is 2 -- JSON has one number type, and a file another app wrote may spell it so. `0`, a
     * fraction, a negative, a string, a boolean, a number past [Int.MAX_VALUE] or anything else is
     * not grouped.
     */
    fun groupOf(value: Any?): Int? {
        if (value !is Number) return null
        val d = value.toDouble()
        if (!d.isFinite() || d != Math.floor(d) || d < 1.0 || d > Int.MAX_VALUE) return null
        return d.toInt()
    }

    /**
     * The group each position belongs to, from the raw group numbers in order: runs of two or more
     * adjacent equal numbers become 1, 2, 3... in order of appearance, and everything else -- a lone
     * number, a number its neighbours do not share, no number at all -- is null.
     *
     * So `[7, 7, 3, 3, 3]` is `[1, 1, 2, 2, 2]`, and `[1, null, 1]` is three ungrouped exercises:
     * the number only has to differ from its neighbours.
     */
    fun runs(numbers: List<Int?>): List<Int?> {
        val out = MutableList<Int?>(numbers.size) { null }
        var next = 1
        var i = 0
        while (i < numbers.size) {
            val g = numbers[i]?.takeIf { it > 0 }
            var j = i + 1
            if (g != null) while (j < numbers.size && numbers[j] == g) j += 1
            if (g != null && j - i >= 2) {
                for (k in i until j) out[k] = next
                next += 1
            }
            i = j
        }
        return out
    }

    /** True when exercise [index] and the one after it are in the same group -- the "Superset with next" chip's state. */
    fun groupedWithNext(groups: List<Int?>, index: Int): Boolean {
        val runs = runs(groups)
        val a = runs.getOrNull(index) ?: return false
        return index + 1 < runs.size && runs[index + 1] == a
    }

    /**
     * Join exercise [index] to the next, or split them. Joining takes the whole group the next
     * exercise is in, so "Superset with next" on the exercise before a superset makes a circuit of
     * three. Splitting keeps the exercises before the split in their group and gives the later ones
     * a group of their own, so splitting a circuit of three after its first leaves a superset of the
     * last two -- and splitting a superset leaves no group at all. Either way the result is
     * renumbered as the wire would number it, and a group left with one member is gone.
     *
     * Coach web's `setGroupedWithNext`, rule for rule.
     */
    fun setGroupedWithNext(groups: List<Int?>, index: Int, on: Boolean): List<Int?> {
        if (index < 0 || index + 1 >= groups.size) return runs(groups)
        val list = runs(groups).toMutableList()
        val a = list[index]
        val b = list[index + 1]
        val fresh = (list.maxOfOrNull { it ?: 0 } ?: 0) + 1
        if (on) {
            if (a != null && a == b) return list
            val target = a ?: b ?: fresh
            list[index] = target
            list[index + 1] = target
            var j = index + 2
            while (b != null && j < list.size && list[j] == b) { list[j] = target; j += 1 }
        } else {
            if (a == null || a != b) return list
            var j = index + 1
            while (j < list.size && list[j] == a) { list[j] = fresh; j += 1 }
        }
        return runs(list)
    }

    /** Spreadsheet letters: 0 is A, 25 Z, 26 AA. A day with 27 groups is not likely, but a label must never be blank. */
    fun letters(n: Int): String {
        val out = StringBuilder()
        var x = n + 1
        while (x > 0) {
            out.insert(0, ('A' + (x - 1) % 26))
            x = (x - 1) / 26
        }
        return out.toString()
    }

    /**
     * What an exercise in a group is labelled. [group] is "Superset A" for two, "Circuit A" for three
     * or more, said once above the group's [first] member; [member] is "A1", "A2", said before each
     * member's name.
     */
    data class GroupLabel(val group: String, val member: String, val first: Boolean, val size: Int)

    /**
     * Each exercise's label, or null when it is not grouped. The letters run A, B, C... within the
     * day and members are numbered from 1. Coach web's labels, so a coach and their client read the
     * same words.
     */
    fun labels(groups: List<Int?>): List<GroupLabel?> {
        val runs = runs(groups)
        val sizes = runs.filterNotNull().groupingBy { it }.eachCount()
        val seen = mutableMapOf<Int, Int>()
        return runs.map { g ->
            if (g == null) return@map null
            val letter = letters(g - 1)
            val n = (seen[g] ?: 0) + 1
            seen[g] = n
            val size = sizes.getValue(g)
            GroupLabel(
                group = (if (size >= 3) "Circuit " else "Superset ") + letter,
                member = "$letter$n",
                first = n == 1,
                size = size
            )
        }
    }

    /** An exercise's heading with its place in a group: "A1 · Dumbbell Bench Press (Dumbbell)". */
    fun heading(title: String, label: GroupLabel?): String =
        if (label == null) title else "${label.member} · $title"

    /* ---------------- a coach's plan ---------------- */

    /** One workout's set types, as a plan link sent them: each exercise's group, and each set's kind. */
    data class WorkoutTypes(val groups: List<Int?>, val kinds: List<List<SetKind?>>)

    /**
     * Each workout's set types, read straight off a plan's own JSON, in the order the kit decodes
     * the workouts -- or an empty list when the plan is not readable.
     *
     * The pinned kit (1.8.0) models neither `g` nor bits 3-4, so they are read here, the way
     * `RoadPicks.fromPlan` reads `rf`. **The walk is the kit's own**: a workout or exercise that is
     * not an object, and a set that is not an array, is skipped exactly as `PlanLinkCodec` skips it,
     * so position *n* here is position *n* in the decoded payload. A flags value is read the way the
     * kit reads it (`optDouble`, then truncated), so the side the kit found and the kind found here
     * come from one number.
     *
     * Groups are [runs], renumbered 1, 2, 3... with a group of one removed; an exercise's first set
     * is straight whatever it says.
     */
    fun planTypes(rawJson: String): List<WorkoutTypes> {
        val root = try { JSONObject(rawJson) } catch (e: Exception) { return emptyList() }
        return objects(root.optJSONArray("w")).map { workout ->
            val exercises = objects(workout.optJSONArray("e"))
            WorkoutTypes(
                groups = runs(exercises.map { groupOf(it.opt("g")) }),
                kinds = exercises.map { exercise ->
                    val sets = exercise.optJSONArray("s")?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optJSONArray(it) }
                    }.orEmpty()
                    sets.mapIndexed { i, tuple -> if (i == 0) null else SetKind.fromFlags(flagsOf(tuple)) }
                }
            )
        }
    }

    /** The sixth tuple position as `PlanLinkCodec.parseSet` reads it. */
    private fun flagsOf(tuple: JSONArray): Int? {
        if (tuple.length() <= 5 || tuple.isNull(5)) return null
        val v = tuple.optDouble(5)
        return if (v.isNaN()) null else v.toInt()
    }

    private fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}
