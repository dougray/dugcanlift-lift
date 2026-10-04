package com.dugcanlift.macrocalc.watchlink

import com.dugcanlift.liftkit.link.AckOutcome
import com.dugcanlift.liftkit.link.FinishedSession
import com.dugcanlift.liftkit.link.LastPerformed
import com.dugcanlift.liftkit.link.LinkPayloads
import com.dugcanlift.liftkit.link.LogSide
import com.dugcanlift.liftkit.link.Plan
import com.dugcanlift.liftkit.link.PlanExercise
import com.dugcanlift.liftkit.link.PlanSource
import com.dugcanlift.liftkit.link.PrescribedSet
import com.dugcanlift.macrocalc.data.LoggedExercise
import com.dugcanlift.macrocalc.data.PrescribedSet as AppPrescribedSet
import com.dugcanlift.macrocalc.data.Routine
import com.dugcanlift.macrocalc.data.RoutineExercise
import com.dugcanlift.macrocalc.data.SetSide
import com.dugcanlift.macrocalc.data.WorkoutSession
import com.dugcanlift.macrocalc.data.WorkoutSet
import com.dugcanlift.macrocalc.data.historyFor
import java.util.UUID
import kotlin.math.roundToLong

/**
 * Between this app's stores and LIFT Link's wire types. No Android in it, so every rule that
 * decides a number on a lifter's wrist is a plain function with a plain test.
 *
 * **Pounds here, kilograms on the wire.** This app stores `weightLb`; LIFT Link carries kilograms,
 * as `workout-sync.schema.json`'s `weightKg` does. The conversion happens in this file and nowhere
 * else, and a missing one is silent and 2.2x wrong on somebody's wrist — `WatchPlanMapperTest`
 * pins both directions.
 *
 * **Blank stays blank.** A routine with no target weight sends no weight; it does not send zero.
 * A value the wire cannot carry — an RPE of 11, reps of 0 — is sent as absent rather than invented
 * or clamped, for the same reason.
 */
object WatchPlanMapper {
    /** The international avoirdupois pound, exactly. Not an approximation of one. */
    const val KG_PER_LB = 0.45359237

    fun lbToKg(lb: Double): Double = lb * KG_PER_LB

    /**
     * Back to pounds, rounded to the hundredth. A weight crosses the wire as whole grams, so 185 lb
     * comes back as 185.0009; storing that would put a number in the log nobody lifted. A hundredth
     * of a pound is below any plate there is.
     */
    fun kgToLb(kg: Double): Double = (kg / KG_PER_LB * 100.0).roundToLong() / 100.0

    /**
     * A stable identity for "this routine, on this day", so pushing the same day twice is the same
     * plan rather than a second one. The schema's `workoutId` is a UUID; a name-based one gives the
     * same answer every time without anything to store.
     */
    fun planId(routineId: String, day: String?): String =
        UUID.nameUUIDFromBytes("lift-link-plan|$routineId|${day ?: ""}".toByteArray()).toString()

    fun plan(
        routine: Routine,
        day: String?,
        source: PlanSource,
        history: List<WorkoutSession>,
        revision: Int,
    ): Plan = Plan(
        planId = planId(routine.id, day),
        revision = revision,
        name = routine.name.ifBlank { "Workout" },
        source = source,
        scheduledFor = day,
        exercises = routine.exercises.map { exercise(it, history) },
    )

    private fun exercise(template: RoutineExercise, history: List<WorkoutSession>): PlanExercise {
        // A routine that prescribes set by set sends its rows as they are -- a ramp, a named side, an
        // extra set on one limb. `template.prescribed` is non-null exactly when the flattened targets
        // cannot say what the coach wrote (`Prescription`, applied by `PlanImporter`), so this is the
        // same question `Routine.toSession` asks when the phone starts one.
        //
        // Before LIFT Link version 2 this always flattened, which sent a coach's "3 x 8 each side" to
        // the watch as three ordinary sets with no sides: half the work asked for, and no mention of
        // the limbs. A missing side is silent, which is why it lasted.
        //
        // No rest time either way: this app does not store one, and the wire's absent restSeconds
        // means "the watch's own default", which is the honest thing to send.
        val sets = template.prescribed
            ?.map(::prescribedSet)
            ?.takeIf { it.isNotEmpty() }
            ?: List(template.targetSets.coerceAtLeast(1)) {
                PrescribedSet(
                    weightKg = template.targetWeightLb?.takeIf { w -> w.isFinite() && w >= 0.0 }?.let(::lbToKg),
                    reps = template.targetReps?.takeIf { r -> r >= 1 },
                    rpe = template.targetRpe?.takeIf { r -> r.isFinite() && r in 1.0..10.0 },
                )
            }
        return PlanExercise(
            name = template.name,
            equipment = template.equipment.takeIf { it.isNotBlank() },
            note = template.note.takeIf { it.isNotBlank() },
            sets = sets,
            lastPerformed = lastPerformed(template, history),
            // PLAN-FORMAT's `b: 1`. False writes no presence bit at all, so a routine with no sides
            // encodes to exactly the bytes version 1 wrote.
            eachSide = template.eachSide,
        )
    }

    /**
     * One prescribed row, phone to wire.
     *
     * **Pounds to kilograms**, as everywhere this app meets the wire: a missing conversion here is
     * silent and 2.2x wrong on a lifter's wrist. Duration and distance have nowhere to go -- the
     * wire's prescription carries neither -- so a conditioning row reaches the watch as a set with no
     * numbers, which is what it already did when every row was flattened.
     */
    private fun prescribedSet(set: AppPrescribedSet): PrescribedSet = PrescribedSet(
        weightKg = set.weightLb?.takeIf { it.isFinite() && it >= 0.0 }?.let(::lbToKg),
        reps = set.reps?.takeIf { it >= 1 },
        rpe = set.rpe?.takeIf { it.isFinite() && it in 1.0..10.0 },
        side = when (set.side) {
            SetSide.LEFT -> LogSide.LEFT
            SetSide.RIGHT -> LogSide.RIGHT
            null -> null
        },
    )

    /**
     * "last: 185x5 @8". The top set of the most recent time this lift — name *and* equipment — was
     * trained, with its day. Heaviest rather than last, because the last set of a session is often
     * a back-off, and a lifter glancing at a wrist wants the number that mattered.
     */
    internal fun lastPerformed(template: RoutineExercise, history: List<WorkoutSession>): LastPerformed? {
        val (day, exercise) = history.historyFor(template.name, template.equipment).lastOrNull() ?: return null
        val top = topSet(exercise) ?: return null
        val last = LastPerformed(
            weightKg = top.weightLb?.takeIf { it.isFinite() && it >= 0.0 }?.let(::lbToKg),
            reps = top.reps?.takeIf { it >= 1 },
            rpe = top.rpe?.takeIf { it.isFinite() && it in 1.0..10.0 },
            performedOn = day.takeIf { it.isNotBlank() },
        )
        return last.takeUnless { it.weightKg == null && it.reps == null && it.rpe == null }
    }

    private fun topSet(exercise: LoggedExercise): WorkoutSet? =
        exercise.sets.filter { !it.isEmpty }.maxWithOrNull(
            compareBy<WorkoutSet> { it.weightLb ?: Double.NEGATIVE_INFINITY }.thenBy { it.reps ?: 0 }
        )

    /** A session the watch ran, as this app stores one. */
    fun session(finished: FinishedSession): WorkoutSession = WorkoutSession(
        id = finished.sessionId,
        date = finished.day,
        name = finished.name,
        startedAt = finished.startedAtEpochSeconds * 1000,
        exercises = finished.exercises.mapIndexed { exerciseIndex, exercise ->
            LoggedExercise(
                // Deterministic ids, so the same revision mapped twice is the same object — which
                // is what lets reconcile() tell "unchanged since we stored it" from "edited here".
                // By position, not name: a session may train the same lift twice.
                id = stableId(finished.sessionId, exerciseIndex),
                name = exercise.name,
                equipment = exercise.equipment ?: "",
                note = exercise.note ?: "",
                sets = exercise.sets.mapIndexed { index, set ->
                    WorkoutSet(
                        id = stableId(finished.sessionId, exerciseIndex, index),
                        weightLb = set.weightKg?.let(::kgToLb),
                        reps = set.reps,
                        rpe = set.rpe,
                        durationSec = set.durationSeconds,
                        distanceMeters = set.distanceMetres,
                        side = when (set.side) {
                            LogSide.LEFT -> SetSide.LEFT
                            LogSide.RIGHT -> SetSide.RIGHT
                            null -> null   // both, as it always means
                        },
                    )
                },
            )
        },
    )

    private fun stableId(vararg parts: Any): String =
        UUID.nameUUIDFromBytes(("lift-link-session|" + parts.joinToString("|")).toByteArray()).toString()

    /**
     * What storing a session the watch sent should do. The conflict rule is the schema's —
     * revision wins, the phone reconciles — plus the one it states in words: **the watch never
     * silently overwrites newer phone edits.**
     *
     * [applied] is what this phone last stored for that session, if anything: the revision and the
     * hash of the exact object written. [onPhone] is what the log holds now.
     *
     * - never seen: [AckOutcome.INSERTED].
     * - seen, same revision: [AckOutcome.IDEMPOTENT], nothing written.
     * - seen, older revision: [AckOutcome.IGNORED].
     * - seen, newer revision, and the phone's copy is still exactly what was stored:
     *   [AckOutcome.ACCEPTED].
     * - seen, newer revision, but the lifter has since **edited or deleted** it here:
     *   [AckOutcome.IGNORED]. Their edit is newer than anything the watch knows about, and a
     *   deleted session does not come back because a wrist remembered it.
     */
    fun reconcile(applied: AppliedRevision?, incomingRevision: Int, onPhone: WorkoutSession?): AckOutcome {
        if (applied == null) return AckOutcome.INSERTED
        return when {
            incomingRevision == applied.revision -> AckOutcome.IDEMPOTENT
            incomingRevision < applied.revision -> AckOutcome.IGNORED
            onPhone == null -> AckOutcome.IGNORED
            fingerprint(onPhone) != applied.storedHash -> AckOutcome.IGNORED
            else -> AckOutcome.ACCEPTED
        }
    }

    /**
     * A content hash of a stored session that is the same in every process. Not `hashCode()`: a
     * data class's hash includes its enums', and an enum's hash is its identity, which changes on
     * every launch — so a session with a left or right set would look "edited" the day after it
     * was stored, and every later revision from the watch would be refused.
     */
    fun fingerprint(session: WorkoutSession): Int = buildString {
        append(session.id).append('\u0000').append(session.date).append('\u0000')
        append(session.name).append('\u0000').append(session.note).append('\u0000').append(session.startedAt)
        session.exercises.forEach { e ->
            append('\u0001').append(e.id).append('\u0000').append(e.name).append('\u0000')
            append(e.equipment).append('\u0000').append(e.note)
            e.sets.forEach { s ->
                append('\u0002').append(s.id).append('\u0000').append(s.weightLb).append('\u0000')
                append(s.reps).append('\u0000').append(s.rpe).append('\u0000').append(s.durationSec)
                append('\u0000').append(s.distanceMeters).append('\u0000').append(s.side?.wire)
            }
        }
    }.hashCode()

    /** The same, for a plan: its encoded bytes, with the revision held still so it does not count
     *  as content. Bytes, because the encoding is the one thing guaranteed identical run to run. */
    fun fingerprint(plan: Plan): Int =
        LinkPayloads.encodePlan(plan.copy(revision = 1)).contentHashCode()

    /** A plan's revision only moves when its content does, so re-sending an unchanged day is
     *  idempotent on the watch rather than a new revision every time the button is pressed. */
    fun nextPlanRevision(previous: AppliedRevision?, contentHash: Int): Int = when {
        previous == null -> 1
        previous.storedHash == contentHash -> previous.revision
        else -> previous.revision + 1
    }
}

/** A revision this phone stored, and the hash of what it stored, for [WatchPlanMapper.reconcile]. */
data class AppliedRevision(val revision: Int, val storedHash: Int)
