package com.dugcanlift.liftkit.link

/**
 * What travels over the link, as value types with no platform in them.
 *
 * **Every prescription and every logged field is nullable, and null is absent.** It is not zero, it
 * is not a default, and nothing here may turn it into one: `PrescribedSet(reps = 5)` is
 * PLAN-FORMAT's `[null, 5]` — "five reps, you pick the weight" — and a receiver that renders a
 * blank weight as 0 has told the lifter something the coach did not say. The wire carries presence
 * bits precisely so an absent field is physically absent rather than a sentinel someone has to
 * remember to check.
 *
 * **Weights are kilograms**, as `workout-sync.schema.json`'s `weightKg` is, and for the same
 * reason: the unit is in the field name rather than left to a convention that can be forgotten.
 * LIFT Android stores pounds and converts at its own mapping layer, never here.
 */

/** Where a plan came from, so the watch can say so. Matches the schema's `plan.source`. */
enum class PlanSource(val code: Int) {
    ROUTINE(0), COACH_PLAN(1);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun from(code: Int): PlanSource? = byCode[code]
    }
}

/**
 * Which limb performed a set. **Absent means both**, which is what every set of a two-sided lift
 * means and what every set logged before per-limb sets existed means — so there is deliberately no
 * `BOTH` constant to accidentally write. The codes are SHARE-FORMAT's flags bits 1-2, so nothing
 * needs a translation table.
 */
enum class LogSide(val code: Int) {
    LEFT(1), RIGHT(2);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun from(code: Int): LogSide? = byCode[code]
    }
}

/** A prescription. All five fields optional; a set that prescribes nothing at all is legal. */
data class PrescribedSet(
    val weightKg: Double? = null,
    val reps: Int? = null,
    val rpe: Double? = null,
    /** How long to rest *after* this set. Absent means the sender has no opinion and the watch
     *  uses its own default — it does not mean rest for zero seconds. */
    val restSeconds: Int? = null,
    /**
     * The one limb this set is for (PLAN-FORMAT.md "Sides": the set tuple's sixth position).
     * **Absent is both**, and a both-sides set writes no presence bit, so a plan that says nothing
     * about sides is byte for byte what version 1 wrote.
     *
     * [LogSide] rather than a second enum of its own: the codes are SHARE-FORMAT's flags bits
     * either way, and a prescription's "left" and a logged set's "left" are the same word about
     * the same limb. Two enums would only be two things to keep in step.
     *
     * A named side on an exercise that is not [PlanExercise.eachSide] is legal and means that one
     * set is single-limb.
     */
    val side: LogSide? = null,
)

/** What the lifter actually did last time, for the "last: 185x5 @8" line beside a prescription. */
data class LastPerformed(
    val weightKg: Double? = null,
    val reps: Int? = null,
    val rpe: Double? = null,
    /** The local calendar day, `yyyy-MM-dd`. Local, as every day key in LIFT is. */
    val performedOn: String? = null,
)

data class PlanExercise(
    val name: String,
    /** Absent, never empty, when the exercise has none. A lift's identity is name and equipment. */
    val equipment: String? = null,
    val note: String? = null,
    val sets: List<PrescribedSet> = emptyList(),
    val lastPerformed: LastPerformed? = null,
    /**
     * **Every prescribed set is done on both sides** (PLAN-FORMAT.md "Sides", `b: 1`): "3 x 8 each
     * side" stays three prescribed rows and is six sets, three a side. The coach's own flag —
     * neither end guesses it from the exercise's name.
     *
     * `false` writes **no presence bit**, never a zero byte, so a plan with no sides encodes to
     * exactly the bytes version 1 wrote and `fixtures/link-wire.txt` does not move.
     */
    val eachSide: Boolean = false,
)

/**
 * The workout the watch should run. [planId] and [revision] are the envelope's own `workoutId` and
 * `revision`, so the schema's conflict rule applies unchanged: a receiver ignores a plan whose
 * revision it has already seen for that id, and accepts a newer one.
 */
data class Plan(
    val planId: String,
    val revision: Int,
    val name: String,
    val source: PlanSource,
    /** The local calendar day this plan is for, `yyyy-MM-dd`. Absent when it is not tied to one. */
    val scheduledFor: String? = null,
    val exercises: List<PlanExercise> = emptyList(),
) {
    /** Whether anything in this plan says anything about sides at all. */
    val prescribesSides: Boolean
        get() = exercises.any { it.eachSide || it.sets.any { set -> set.side != null } }

    /**
     * This plan with every side stripped, for a peer that negotiated version 1 and would refuse
     * the presence bits outright (`LinkPayloads` reserves nothing it does not understand).
     * [LinkSession.pushPlan] applies it, so no caller can send sides down a version-1 link by
     * forgetting to ask. Losing a side costs the lifter a label; losing the frame costs them the
     * whole plan.
     */
    fun withoutSides(): Plan =
        if (!prescribesSides) this
        else copy(exercises = exercises.map { exercise ->
            exercise.copy(eachSide = false, sets = exercise.sets.map { it.copy(side = null) })
        })
}

/** One performed set. Every field optional, for the reason a prescription's are. */
data class LoggedSet(
    val weightKg: Double? = null,
    val reps: Int? = null,
    val rpe: Double? = null,
    /** Absent means both limbs. */
    val side: LogSide? = null,
    val durationSeconds: Int? = null,
    val distanceMetres: Double? = null,
)

/**
 * Streamed while a session is in progress, so a phone on the bench stays in step. Optional: the
 * session is not lost if none of these arrive, because [FinishedSession] remains the source of
 * truth, exactly as the schema says.
 */
data class LoggedSetReport(
    val sessionId: String,
    val revision: Int,
    val exerciseName: String,
    val equipment: String? = null,
    /** The set's position within that exercise, so a repeat is recognised rather than appended. */
    val setIndex: Int,
    val set: LoggedSet,
    val loggedAtEpochSeconds: Long,
)

data class FinishedExercise(
    val name: String,
    val equipment: String? = null,
    val note: String? = null,
    val sets: List<LoggedSet> = emptyList(),
)

/** The source of truth for a session the watch ran. */
data class FinishedSession(
    val sessionId: String,
    val revision: Int,
    /** Local calendar day, `yyyy-MM-dd`. */
    val day: String,
    val name: String,
    val startedAtEpochSeconds: Long,
    val exercises: List<FinishedExercise> = emptyList(),
)

/**
 * The handshake. [chosenVersion] is null in a HELLO — the central proposes a range — and set in a
 * HELLO_ACK, where the peripheral names the version both ends will use.
 */
data class Hello(
    val minVersion: Int,
    val maxVersion: Int,
    val chosenVersion: Int?,
    val deviceName: String,
    /** Eight random bytes, fresh per handshake. Both nonces together make the confirmation code. */
    val nonce: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is Hello && minVersion == other.minVersion &&
            maxVersion == other.maxVersion && chosenVersion == other.chosenVersion &&
            deviceName == other.deviceName && nonce.contentEquals(other.nonce))

    override fun hashCode(): Int =
        (((minVersion * 31 + maxVersion) * 31 + (chosenVersion ?: -1)) * 31 +
            deviceName.hashCode()) * 31 + nonce.contentHashCode()

    companion object {
        const val NONCE_BYTES = 8
    }
}

/**
 * An acknowledgement, carrying what was stored rather than which frame carried it: reconciliation
 * clears an outbox entry by id and revision, and a frame number means nothing a week later. The
 * outcomes are `docs/ARCHITECTURE.md`'s, so both channels acknowledge in the same words.
 */
data class LinkAck(
    val ackType: MessageType,
    val id: String,
    val revision: Int,
    val outcome: AckOutcome,
)

/** A refusal. [detail] is free text for a log, never for a decision. */
data class LinkErrorMessage(val error: LinkError, val detail: String = "")
