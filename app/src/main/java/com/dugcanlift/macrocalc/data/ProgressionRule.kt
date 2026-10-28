package com.dugcanlift.macrocalc.data

import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * What to lift next time, from what was lifted last time: double progression, the rule most
 * programmes write down.
 *
 * - Every working set at last session's top weight reached the routine's reps, and there were as
 *   many of them as the routine asks for: **up** one step.
 * - Short, but not twice running at that weight: **the same weight again**.
 * - Short at the same weight two sessions running: **back off** about 10%, rounded down to a step,
 *   and build up again.
 *
 * It says why, every time, in the line it gives. It is a suggestion shown beside the sets, never
 * written into them unless "Use" is tapped, and it only touches sets not yet logged ([open]).
 *
 * No suggestion at all where there is nothing honest to say: no rep target, no earlier session of
 * this lift, or no weight (a bodyweight lift has no step to take). A routine a coach scheduled gets
 * none either, because the coach programmes those numbers and a second voice on the same set is a
 * contradiction, not help.
 *
 * A port of LIFT iPhone's `ProgressionRule`, function for function and word for word. Where this
 * build's data differs, the port follows the data:
 *
 * - **Pounds, always.** This app has no unit setting (see "Weights never convert anywhere" in
 *   CLAUDE.md), so the step is the iPhone's pound step, 5 lb, and every line says "lb".
 * - **No plate data.** The iPhone makes a bar lift's step two of the smallest plate the lifter
 *   owns; nothing here records plates, so every lift steps 5 lb.
 * - **No warmup flag.** Every logged straight set is a working set, as [PlanLog] already says.
 * - **Drop sets and rest-pause are not working sets.** A drop set is lighter on purpose and a
 *   rest-pause set short on purpose, so neither is asked for ([target]), counted ([sessions]) or
 *   filled by "Use" ([open]) -- otherwise a four-set routine with two drops could never go up. The
 *   kind is the one the set was laid out with: from a coach's sets or the routine's own
 *   [RoutineExercise.setKinds].
 * - **No "done" tick.** A routine lays its sets out as rows the moment it starts, and a row is
 *   logged from then on. So "a set not done" is a row still exactly as the routine laid it out
 *   ([open]); a set lifted at exactly those numbers cannot be told from one not lifted yet.
 * - **Which routine a session came from** is [RoutineRotation]'s reading -- a recorded booking, or
 *   the session's name -- and the exercise within it is matched by name and equipment, because a
 *   started session's exercises carry fresh ids. A session whose name matches two routines cannot
 *   be told, and gets no suggestion.
 */
object ProgressionRule {
    const val ENABLED_KEY = "suggests_next_weight"

    /** The smallest change of load: the iPhone's pound step. See the class comment. */
    const val STEP_LB = 5.0

    /** One working set as it was logged. */
    data class LoggedSet(val weight: Double, val reps: Int)

    /** One earlier day of this lift: its working sets. Sessions are handed in newest day first. */
    data class Session(val date: String, val sets: List<LoggedSet>)

    sealed interface Next {
        val weight: Double

        data class Up(val to: Double, val from: Double, val reps: Int, val date: String) : Next {
            override val weight: Double get() = to
        }

        data class Same(override val weight: Double, val reps: List<Int>, val target: Int, val date: String) : Next

        data class BackOff(val to: Double, val from: Double, val target: Int) : Next {
            override val weight: Double get() = to
        }
    }

    /**
     * [target] is the routine's reps, [setCount] how many sets it asks for, [step] the smallest
     * change of load.
     */
    fun next(target: Int, setCount: Int, sessions: List<Session>, step: Double): Next? {
        if (target <= 0 || step <= 0) return null
        val last = sessions.firstOrNull() ?: return null
        val top = topWeight(last) ?: return null
        if (top <= 0) return null
        val atTop = last.sets.filter { it.weight == top }

        if (atTop.size >= maxOf(setCount, 1) && atTop.all { it.reps >= target }) {
            return Next.Up(to = rounded(top + step, step), from = top, reps = target, date = last.date)
        }
        if (sessions.size > 1 && topWeight(sessions[1]) == top &&
            !sessions[1].sets.filter { it.weight == top }.all { it.reps >= target }
        ) {
            val lighter = floor(top * 0.9 / step) * step
            if (lighter > 0 && lighter < top) {
                return Next.BackOff(to = rounded(lighter, step), from = top, target = target)
            }
        }
        return Next.Same(top, reps = atTop.map { it.reps }, target = target, date = last.date)
    }

    private fun topWeight(session: Session): Double? = session.sets.maxOfOrNull { it.weight }

    /** Swift's `.rounded()`: half away from zero, which for a weight is half up. */
    private fun roundHalfUp(value: Double): Double = Math.round(value).toDouble()

    /** A weight that has been through arithmetic comes back as 189.99999999999997. */
    fun rounded(value: Double, step: Double): Double {
        val hundredths = roundHalfUp(value * 100) / 100
        val steps = roundHalfUp(hundredths / step)
        return if (abs(steps * step - hundredths) < 0.01) steps * step else hundredths
    }

    /** The step for any lift on this build: no unit to choose and no plates to count. */
    fun step(): Double = STEP_LB

    /* ---------- words ---------- */

    /** "185", "82.5", "2.75": the iPhone's `PlateMath.text`. */
    fun weightText(value: Double): String {
        if (value == roundHalfUp(value)) return value.toLong().toString()
        return String.format(Locale.ROOT, "%.2f", value).trimEnd('0')
    }

    fun line(next: Next, locale: Locale = Locale.getDefault()): String {
        val u = "lb"
        return when (next) {
            is Next.Up ->
                "Next: ${weightText(next.to)} $u. Every set reached ${next.reps} at ${weightText(next.from)} " +
                    "last time (${day(next.date, locale)})."
            is Next.Same -> {
                val done = next.reps.joinToString(", ")
                "Next: ${weightText(next.weight)} $u again. $done at ${weightText(next.weight)} " +
                    "last time (${day(next.date, locale)}), aiming for ${next.target}."
            }
            is Next.BackOff ->
                "Next: ${weightText(next.to)} $u. Short of ${next.target} at ${weightText(next.from)} " +
                    "two sessions running, so back off and build up again."
        }
    }

    /** "Use 190 lb". */
    fun useLabel(next: Next): String = "Use ${weightText(next.weight)} lb"

    /** "Mon, Oct 26": the iPhone's abbreviated weekday, day and month. A key that is not a date is printed as it is. */
    fun day(date: String, locale: Locale = Locale.getDefault()): String = try {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(date)
        if (parsed == null) date else SimpleDateFormat("EEE, MMM d", locale).format(parsed)
    } catch (e: Exception) {
        date
    }

    const val SETTINGS_TEXT =
        "For an exercise from one of your own routines, LIFT suggests the next weight from your last session: " +
            "up a step when every set reached its reps, the same again when one didn't, and a little lighter " +
            "after two short sessions in a row."

    /* ---------- from the stores ---------- */

    /**
     * The routine a session was started from: the one a booking recorded starting it, or else the one
     * routine with its name. Null when none matches, or when its name matches more than one -- then
     * which routine's reps to aim for cannot be told.
     */
    fun routineOf(session: WorkoutSession, routines: List<Routine>, bookings: List<ScheduledSession>): Routine? {
        bookings.firstOrNull { it.startedSessionId == session.id }?.let { booking ->
            return routines.firstOrNull { it.id == booking.routineId }
        }
        val key = RoutineRotation.nameKey(session.name)
        if (key.isEmpty()) return null
        return routines.filter { RoutineRotation.nameKey(it.name) == key }.singleOrNull()
    }

    private fun matchKey(name: String, equipment: String): String =
        "${name.trim()}|${equipment.trim()}".lowercase(Locale.US)

    /** The routine's own exercise for a logged one, by name and equipment. */
    fun routineExercise(exercise: LoggedExercise, routine: Routine): RoutineExercise? =
        routine.exercises.firstOrNull { matchKey(it.name, it.equipment) == exercise.matchKey }

    /** The routine's reps -- the most any straight set asks for -- and how many straight sets ask for reps. */
    data class Target(val reps: Int, val sets: Int)

    fun target(routineExercise: RoutineExercise): Target? {
        // Straight sets only. A drop set is lighter on purpose and a rest-pause set short on purpose,
        // so counting them would ask for sets at the top weight that the routine never meant to be
        // there: a four-set routine with two drops could never go up.
        val reps = routineExercise.askedSets.filter { it.kind == null }.mapNotNull { it.reps }
        val most = reps.maxOrNull() ?: return null
        return Target(most, reps.size)
    }

    /**
     * The working sets of this lift on each earlier day, newest first. A day this lift was logged a
     * side at a time is left out: its sides are two lifts, and one number for both would be wrong for
     * each. A day is every session logged on that date, as the iPhone's `WorkoutDay` is.
     */
    fun sessions(exercise: LoggedExercise, before: String, all: List<WorkoutSession>): List<Session> =
        all.filter { it.date < before }
            .groupBy { it.date }
            .entries
            .sortedByDescending { it.key }
            .mapNotNull { (date, list) ->
                val sets = list.sortedBy { it.startedAt }
                    .flatMap { it.exercises }
                    .filter { it.id != exercise.id && it.matchKey == exercise.matchKey }
                    .flatMap { it.sets }
                if (sets.any { it.side != null }) return@mapNotNull null
                val working = sets.mapNotNull { set ->
                    // A drop or rest-pause set is not a working set: see [target].
                    if (set.kind != null) return@mapNotNull null
                    val weight = set.weightLb ?: return@mapNotNull null
                    val reps = set.reps ?: return@mapNotNull null
                    if (weight > 0 && reps > 0) LoggedSet(weight, reps) else null
                }
                if (working.isEmpty()) null else Session(date, working)
            }

    /**
     * The next weight for one exercise of one session, or null -- every gate in one place, so the
     * screen has nothing to decide. Only today's session, only the lifter's own routine (none the
     * coach booked), and none for a lift logged a side at a time.
     */
    fun suggestion(
        enabled: Boolean,
        perSide: Boolean,
        exercise: LoggedExercise,
        session: WorkoutSession,
        today: String,
        all: List<WorkoutSession>,
        routines: List<Routine>,
        bookings: List<ScheduledSession>,
        step: Double = step()
    ): Suggestion? {
        if (!enabled || perSide || exercise.hasPerSideSets || session.date != today) return null
        val routine = routineOf(session, routines, bookings) ?: return null
        if (bookings.any { it.routineId == routine.id }) return null
        val prescribed = routineExercise(exercise, routine) ?: return null
        val target = target(prescribed) ?: return null
        val next = next(target.reps, target.sets, sessions(exercise, session.date, all), step) ?: return null
        return Suggestion(next, prescribed.askedSets)
    }

    /** A next weight, with the sets the routine laid out, so "Use" knows which rows are still its. */
    data class Suggestion(val next: Next, val laidOut: List<PrescribedSet>)

    /**
     * The sets "Use" fills: two-sided straight rows still exactly as the routine laid them out, whose
     * weight is not already the one. A drop or rest-pause row is never filled: it keeps its own weight. A row the lifter added is a set they logged; this build has no tick
     * to tell a laid-out row done from one not done, so a laid-out row is taken as not done.
     */
    fun open(sets: List<WorkoutSet>, laidOut: List<PrescribedSet>, weight: Double): List<WorkoutSet> =
        sets.filter { set ->
            // A drop set keeps its own, lighter weight, and a rest-pause set's is the lifter's call.
            set.side == null && set.kind == null &&
                laidOut.any { it.laysOut(set) } &&
                (set.weightLb?.let { abs(it - weight) > 0.001 } ?: true)
        }

    private fun PrescribedSet.laysOut(set: WorkoutSet): Boolean =
        side == null && set.weightLb == weightLb && set.reps == reps && set.rpe == rpe &&
            set.durationSec == durationSec && set.distanceMeters == distanceMeters && set.kind == kind

    /** The exercise with the suggestion's weight in its [open] sets, and nothing else changed. */
    fun use(exercise: LoggedExercise, suggestion: Suggestion): LoggedExercise {
        val ids = open(exercise.sets, suggestion.laidOut, suggestion.next.weight).map { it.id }.toSet()
        if (ids.isEmpty()) return exercise
        return exercise.copy(sets = exercise.sets.map { if (it.id in ids) it.copy(weightLb = suggestion.next.weight) else it })
    }
}
