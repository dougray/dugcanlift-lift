package com.dugcanlift.liftkit.link

import java.io.ByteArrayOutputStream

/** A payload this build could not read. Always answered with an ERROR, never guessed past. */
class LinkDecodeException(message: String) : Exception(message)

internal class ByteWriter {
    private val out = ByteArrayOutputStream()

    fun u8(value: Int) = apply {
        require(value in 0..0xFF) { "u8 out of range: $value" }
        out.write(value)
    }

    fun u16(value: Int) = apply {
        require(value in 0..0xFFFF) { "u16 out of range: $value" }
        out.write(value ushr 8); out.write(value and 0xFF)
    }

    fun u32(value: Long) = apply {
        require(value in 0..0xFFFFFFFFL) { "u32 out of range: $value" }
        for (shift in intArrayOf(24, 16, 8, 0)) out.write(((value ushr shift) and 0xFF).toInt())
    }

    fun i64(value: Long) = apply {
        for (shift in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) out.write(((value ushr shift) and 0xFF).toInt())
    }

    fun str(value: String) = apply {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "string of ${bytes.size} bytes exceeds $MAX_STRING_BYTES" }
        u16(bytes.size)
        out.write(bytes)
    }

    fun raw(bytes: ByteArray) = apply { out.write(bytes) }

    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        const val MAX_STRING_BYTES = 4096
    }
}

internal class ByteReader(private val source: ByteArray) {
    private var offset = 0
    val remaining: Int get() = source.size - offset

    private fun need(n: Int) {
        if (remaining < n) throw LinkDecodeException("wanted $n bytes, $remaining left")
    }

    fun u8(): Int { need(1); return source[offset++].toInt() and 0xFF }

    fun u16(): Int { need(2); return (u8() shl 8) or u8() }

    fun u32(): Long { need(4); var v = 0L; repeat(4) { v = (v shl 8) or u8().toLong() }; return v }

    fun i64(): Long { need(8); var v = 0L; repeat(8) { v = (v shl 8) or u8().toLong() }; return v }

    fun str(): String {
        val length = u16()
        if (length > ByteWriter.MAX_STRING_BYTES) throw LinkDecodeException("string of $length bytes")
        need(length)
        val s = String(source, offset, length, Charsets.UTF_8)
        offset += length
        return s
    }

    fun raw(n: Int): ByteArray { need(n); val b = source.copyOfRange(offset, offset + n); offset += n; return b }

    /** Trailing bytes mean the two sides disagree about the shape, which is exactly what must not
     *  pass silently: a longer payload from a peer that thinks it added a field is a version
     *  problem, and byte 0 is where version problems are answered. */
    fun end() { if (remaining != 0) throw LinkDecodeException("$remaining trailing bytes") }
}

/**
 * The binary payloads. No JSON and no dependency: presence bits make an absent field physically
 * absent, which is the only encoding of "blank" that a later reader cannot turn into a zero.
 *
 * Every decoder is strict. An out-of-range number, a reserved presence bit, or a single trailing
 * byte throws [LinkDecodeException] rather than being tidied up — the session answers with an
 * ERROR, and a link that half understands its peer is worse than one that says so.
 */
object LinkPayloads {
    // Presence masks. A bit outside a mask's own set is reserved: this build refuses it rather than
    // masking it off, because a peer setting one is a peer from a version this is not.
    private const val P_WEIGHT = 0x01
    private const val P_REPS = 0x02
    private const val P_RPE = 0x04

    private const val PRESCRIBED_REST = 0x08
    /** A named side, one byte of [LogSide.code]. Absent is both; see [PrescribedSet.side]. */
    private const val PRESCRIBED_SIDE = 0x10
    private const val PRESCRIBED_RESERVED = 0xE0

    private const val LOGGED_SIDE = 0x08
    private const val LOGGED_DURATION = 0x10
    private const val LOGGED_DISTANCE = 0x20
    private const val LOGGED_RESERVED = 0xC0

    private const val LAST_ON = 0x08
    private const val LAST_RESERVED = 0xF0

    private const val EX_EQUIPMENT = 0x01
    private const val EX_NOTE = 0x02
    private const val EX_LAST = 0x04
    /** A flag with no bytes behind it: the bit *is* the value. See [PlanExercise.eachSide]. */
    private const val EX_EACH_SIDE = 0x08
    private const val EX_RESERVED = 0xF0

    private const val FINISHED_EX_RESERVED = 0xFC

    private const val PLAN_SCHEDULED = 0x01
    private const val PLAN_RESERVED = 0xFE

    private const val REPORT_EQUIPMENT = 0x01
    private const val REPORT_RESERVED = 0xFE

    /** Weight travels as whole grams. An integer has one representation on both ends; a float has
     *  two roundings and a platform's opinion about which. */
    internal fun gramsOf(kg: Double): Long {
        require(kg.isFinite() && kg >= 0.0) { "weight $kg kg is not a weight" }
        return Math.round(kg * 1000.0)
    }

    internal fun kgOf(grams: Long): Double = grams / 1000.0

    /** RPE travels as tenths, 1.0..10.0 → 10..100, the range the schema allows and no other. */
    internal fun rpeTenths(rpe: Double): Int {
        require(rpe.isFinite() && rpe >= 1.0 && rpe <= 10.0) { "rpe $rpe outside 1..10" }
        return Math.round(rpe * 10.0).toInt()
    }

    private fun rpeOf(tenths: Int): Double {
        if (tenths !in 10..100) throw LinkDecodeException("rpe tenths $tenths outside 10..100")
        return tenths / 10.0
    }

    /** The schema's `revision` is an integer of at least 1, and the reconciliation rule compares
     *  them, so a zero or a value that wrapped to negative is refused rather than compared. */
    private fun revisionOf(value: Long): Int {
        if (value < 1 || value > Int.MAX_VALUE) throw LinkDecodeException("revision $value out of range")
        return value.toInt()
    }

    private fun repsOf(value: Int): Int {
        if (value < 1) throw LinkDecodeException("reps $value below 1")
        return value
    }

    /** Blank equipment and no equipment are the same lift, in both directions. */
    private fun normalised(text: String?): String? = text?.takeIf { it.isNotBlank() }

    private fun reserved(mask: Int, bits: Int, what: String) {
        if (mask and bits != 0) throw LinkDecodeException("reserved $what presence bits set: $mask")
    }

    // ---- HELLO / HELLO_ACK ----------------------------------------------------------------

    fun encodeHello(hello: Hello): ByteArray {
        require(hello.nonce.size == Hello.NONCE_BYTES) { "nonce must be ${Hello.NONCE_BYTES} bytes" }
        return ByteWriter()
            .u8(hello.minVersion)
            .u8(hello.maxVersion)
            .u8(hello.chosenVersion ?: 0)   // 0 is "not chosen": a HELLO proposes, a HELLO_ACK decides
            .str(hello.deviceName)
            .raw(hello.nonce)
            .toByteArray()
    }

    fun decodeHello(bytes: ByteArray): Hello {
        val r = ByteReader(bytes)
        val min = r.u8()
        val max = r.u8()
        val chosen = r.u8()
        val name = r.str()
        val nonce = r.raw(Hello.NONCE_BYTES)
        r.end()
        if (min > max) throw LinkDecodeException("version range $min..$max is empty")
        return Hello(min, max, chosen.takeIf { it != 0 }, name, nonce)
    }

    // ---- PAIR_CONFIRM / PAIR_RESULT -------------------------------------------------------

    fun encodeDecision(accepted: Boolean): ByteArray = ByteWriter().u8(if (accepted) 1 else 0).toByteArray()

    fun decodeDecision(bytes: ByteArray): Boolean {
        val r = ByteReader(bytes)
        val v = r.u8()
        r.end()
        return when (v) {
            0 -> false
            1 -> true
            else -> throw LinkDecodeException("decision byte $v is neither accept nor reject")
        }
    }

    // ---- PLAN_PUSHED ----------------------------------------------------------------------

    fun encodePlan(plan: Plan): ByteArray {
        val w = ByteWriter()
        w.str(plan.planId)
        w.u32(plan.revision.toLong())
        w.str(plan.name)
        w.u8(plan.source.code)
        w.u8(if (plan.scheduledFor != null) PLAN_SCHEDULED else 0)
        plan.scheduledFor?.let { w.str(it) }
        w.u16(plan.exercises.size)
        plan.exercises.forEach { exercise ->
            val equipment = normalised(exercise.equipment)
            val note = normalised(exercise.note)
            w.str(exercise.name)
            var mask = 0
            if (equipment != null) mask = mask or EX_EQUIPMENT
            if (note != null) mask = mask or EX_NOTE
            if (exercise.lastPerformed != null) mask = mask or EX_LAST
            if (exercise.eachSide) mask = mask or EX_EACH_SIDE
            w.u8(mask)
            equipment?.let { w.str(it) }
            note?.let { w.str(it) }
            w.u16(exercise.sets.size)
            exercise.sets.forEach { writePrescribed(w, it) }
            exercise.lastPerformed?.let { writeLastPerformed(w, it) }
        }
        return w.toByteArray()
    }

    fun decodePlan(bytes: ByteArray): Plan {
        val r = ByteReader(bytes)
        val planId = r.str()
        val revision = revisionOf(r.u32())
        val name = r.str()
        val source = PlanSource.from(r.u8()) ?: throw LinkDecodeException("unknown plan source")
        val planMask = r.u8()
        reserved(planMask, PLAN_RESERVED, "plan")
        val scheduledFor = if (planMask and PLAN_SCHEDULED != 0) r.str() else null
        val exercises = List(r.u16()) {
            val exName = r.str()
            val mask = r.u8()
            reserved(mask, EX_RESERVED, "exercise")
            val equipment = if (mask and EX_EQUIPMENT != 0) r.str() else null
            val note = if (mask and EX_NOTE != 0) r.str() else null
            val sets = List(r.u16()) { readPrescribed(r) }
            val last = if (mask and EX_LAST != 0) readLastPerformed(r) else null
            PlanExercise(
                exName, normalised(equipment), normalised(note), sets, last,
                eachSide = mask and EX_EACH_SIDE != 0,
            )
        }
        r.end()
        return Plan(planId, revision, name, source, scheduledFor, exercises)
    }

    private fun writePrescribed(w: ByteWriter, set: PrescribedSet) {
        var mask = 0
        if (set.weightKg != null) mask = mask or P_WEIGHT
        if (set.reps != null) mask = mask or P_REPS
        if (set.rpe != null) mask = mask or P_RPE
        if (set.restSeconds != null) mask = mask or PRESCRIBED_REST
        if (set.side != null) mask = mask or PRESCRIBED_SIDE
        w.u8(mask)
        set.weightKg?.let { w.u32(gramsOf(it)) }
        set.reps?.let { require(it >= 1) { "reps $it below 1" }; w.u16(it) }
        set.rpe?.let { w.u8(rpeTenths(it)) }
        set.restSeconds?.let { require(it >= 1) { "restSeconds $it below 1" }; w.u16(it) }
        set.side?.let { w.u8(it.code) }
    }

    private fun readPrescribed(r: ByteReader): PrescribedSet {
        val mask = r.u8()
        reserved(mask, PRESCRIBED_RESERVED, "prescribed set")
        return PrescribedSet(
            weightKg = if (mask and P_WEIGHT != 0) kgOf(r.u32()) else null,
            reps = if (mask and P_REPS != 0) repsOf(r.u16()) else null,
            rpe = if (mask and P_RPE != 0) rpeOf(r.u8()) else null,
            restSeconds = if (mask and PRESCRIBED_REST != 0) {
                val s = r.u16(); if (s < 1) throw LinkDecodeException("restSeconds $s below 1") else s
            } else null,
            side = if (mask and PRESCRIBED_SIDE != 0) {
                val code = r.u8()
                LogSide.from(code) ?: throw LinkDecodeException("side $code is neither left nor right")
            } else null,
        )
    }

    private fun writeLastPerformed(w: ByteWriter, last: LastPerformed) {
        var mask = 0
        if (last.weightKg != null) mask = mask or P_WEIGHT
        if (last.reps != null) mask = mask or P_REPS
        if (last.rpe != null) mask = mask or P_RPE
        if (last.performedOn != null) mask = mask or LAST_ON
        w.u8(mask)
        last.weightKg?.let { w.u32(gramsOf(it)) }
        last.reps?.let { require(it >= 1) { "reps $it below 1" }; w.u16(it) }
        last.rpe?.let { w.u8(rpeTenths(it)) }
        last.performedOn?.let { w.str(it) }
    }

    private fun readLastPerformed(r: ByteReader): LastPerformed {
        val mask = r.u8()
        reserved(mask, LAST_RESERVED, "last performed")
        return LastPerformed(
            weightKg = if (mask and P_WEIGHT != 0) kgOf(r.u32()) else null,
            reps = if (mask and P_REPS != 0) repsOf(r.u16()) else null,
            rpe = if (mask and P_RPE != 0) rpeOf(r.u8()) else null,
            performedOn = if (mask and LAST_ON != 0) r.str() else null,
        )
    }

    // ---- SET_LOGGED -----------------------------------------------------------------------

    fun encodeLoggedSet(report: LoggedSetReport): ByteArray {
        val w = ByteWriter()
        val equipment = normalised(report.equipment)
        w.str(report.sessionId)
        w.u32(report.revision.toLong())
        w.str(report.exerciseName)
        w.u8(if (equipment != null) REPORT_EQUIPMENT else 0)
        equipment?.let { w.str(it) }
        w.u16(report.setIndex)
        writeLogged(w, report.set)
        w.i64(report.loggedAtEpochSeconds)
        return w.toByteArray()
    }

    fun decodeLoggedSet(bytes: ByteArray): LoggedSetReport {
        val r = ByteReader(bytes)
        val sessionId = r.str()
        val revision = revisionOf(r.u32())
        val exerciseName = r.str()
        val mask = r.u8()
        reserved(mask, REPORT_RESERVED, "set report")
        val equipment = if (mask and REPORT_EQUIPMENT != 0) r.str() else null
        val setIndex = r.u16()
        val set = readLogged(r)
        val at = r.i64()
        r.end()
        return LoggedSetReport(sessionId, revision, exerciseName, normalised(equipment), setIndex, set, at)
    }

    // ---- SESSION_FINISHED -----------------------------------------------------------------

    fun encodeSession(session: FinishedSession): ByteArray {
        val w = ByteWriter()
        w.str(session.sessionId)
        w.u32(session.revision.toLong())
        w.str(session.day)
        w.str(session.name)
        w.i64(session.startedAtEpochSeconds)
        w.u16(session.exercises.size)
        session.exercises.forEach { exercise ->
            val equipment = normalised(exercise.equipment)
            val note = normalised(exercise.note)
            w.str(exercise.name)
            var mask = 0
            if (equipment != null) mask = mask or EX_EQUIPMENT
            if (note != null) mask = mask or EX_NOTE
            w.u8(mask)
            equipment?.let { w.str(it) }
            note?.let { w.str(it) }
            w.u16(exercise.sets.size)
            exercise.sets.forEach { writeLogged(w, it) }
        }
        return w.toByteArray()
    }

    fun decodeSession(bytes: ByteArray): FinishedSession {
        val r = ByteReader(bytes)
        val sessionId = r.str()
        val revision = revisionOf(r.u32())
        val day = r.str()
        val name = r.str()
        val startedAt = r.i64()
        val exercises = List(r.u16()) {
            val exName = r.str()
            val mask = r.u8()
            reserved(mask, FINISHED_EX_RESERVED, "finished exercise")
            val equipment = if (mask and EX_EQUIPMENT != 0) r.str() else null
            val note = if (mask and EX_NOTE != 0) r.str() else null
            val sets = List(r.u16()) { readLogged(r) }
            FinishedExercise(exName, normalised(equipment), normalised(note), sets)
        }
        r.end()
        return FinishedSession(sessionId, revision, day, name, startedAt, exercises)
    }

    /** One encoding for a performed set, used by both SET_LOGGED and SESSION_FINISHED, so the
     *  streamed set and the stored one can never come to mean different things. */
    private fun writeLogged(w: ByteWriter, set: LoggedSet) {
        var mask = 0
        if (set.weightKg != null) mask = mask or P_WEIGHT
        if (set.reps != null) mask = mask or P_REPS
        if (set.rpe != null) mask = mask or P_RPE
        if (set.side != null) mask = mask or LOGGED_SIDE
        if (set.durationSeconds != null) mask = mask or LOGGED_DURATION
        if (set.distanceMetres != null) mask = mask or LOGGED_DISTANCE
        w.u8(mask)
        set.weightKg?.let { w.u32(gramsOf(it)) }
        set.reps?.let { require(it >= 1) { "reps $it below 1" }; w.u16(it) }
        set.rpe?.let { w.u8(rpeTenths(it)) }
        set.side?.let { w.u8(it.code) }
        set.durationSeconds?.let { require(it >= 1) { "durationSeconds $it below 1" }; w.u32(it.toLong()) }
        set.distanceMetres?.let {
            require(it.isFinite() && it >= 0.0) { "distance $it m is not a distance" }
            w.u32(Math.round(it * 100.0))
        }
    }

    private fun readLogged(r: ByteReader): LoggedSet {
        val mask = r.u8()
        reserved(mask, LOGGED_RESERVED, "logged set")
        return LoggedSet(
            weightKg = if (mask and P_WEIGHT != 0) kgOf(r.u32()) else null,
            reps = if (mask and P_REPS != 0) repsOf(r.u16()) else null,
            rpe = if (mask and P_RPE != 0) rpeOf(r.u8()) else null,
            side = if (mask and LOGGED_SIDE != 0) {
                val code = r.u8()
                LogSide.from(code) ?: throw LinkDecodeException("side $code is neither left nor right")
            } else null,
            durationSeconds = if (mask and LOGGED_DURATION != 0) {
                val s = r.u32()
                if (s < 1 || s > Int.MAX_VALUE) throw LinkDecodeException("durationSeconds $s out of range")
                s.toInt()
            } else null,
            distanceMetres = if (mask and LOGGED_DISTANCE != 0) r.u32() / 100.0 else null,
        )
    }

    // ---- ACK / ERROR ----------------------------------------------------------------------

    fun encodeAck(ack: LinkAck): ByteArray = ByteWriter()
        .u8(ack.ackType.code)
        .str(ack.id)
        .u32(ack.revision.toLong())
        .u8(ack.outcome.code)
        .toByteArray()

    fun decodeAck(bytes: ByteArray): LinkAck {
        val r = ByteReader(bytes)
        val type = MessageType.from(r.u8()) ?: throw LinkDecodeException("ack of an unknown type")
        val id = r.str()
        val revision = revisionOf(r.u32())
        val outcome = AckOutcome.from(r.u8()) ?: throw LinkDecodeException("unknown ack outcome")
        r.end()
        return LinkAck(type, id, revision, outcome)
    }

    fun encodeError(error: LinkErrorMessage): ByteArray =
        ByteWriter().u8(error.error.code).str(error.detail).toByteArray()

    fun decodeError(bytes: ByteArray): LinkErrorMessage {
        val r = ByteReader(bytes)
        val code = r.u8()
        val detail = r.str()
        r.end()
        // An error whose code this build does not know is still an error: it is reported as
        // MALFORMED_FRAME with the peer's own code in the text, never swallowed.
        val known = LinkError.from(code)
        return if (known != null) LinkErrorMessage(known, detail)
        else LinkErrorMessage(LinkError.MALFORMED_FRAME, "peer error code $code: $detail")
    }
}
