package com.dugcanlift.liftkit.link

/**
 * LIFT Link — the direct Bluetooth LE channel between LIFT Android (phone) and the LIFT Wear OS
 * watch. See `docs/LINK-PROTOCOL.md` for the full specification; this file is the normative list
 * of constants that document quotes.
 *
 * SHARED SOURCE. Every file in this package is byte-identical in two repositories:
 *
 *   canonical  dugcanlift-lift-watch : android/liftkit/src/main/kotlin/com/dugcanlift/liftkit/link/
 *   mirror     dugcanlift-lift       : link/src/main/kotlin/com/dugcanlift/liftkit/link/
 *
 * Change the canonical copy and copy it across; never edit only one. `LinkWireFixtureTest` in each
 * repo encodes the same canonical objects and asserts the bytes equal `fixtures/link-wire.txt`,
 * which is the same file in both — so a copy that drifts fails its own repo's tests rather than
 * producing a link that half works. The long-term home is `dugcanlift-kit-android`'s `:liftcore`,
 * which both apps already depend on; moving it there is a follow-up, and until then the fixture is
 * what keeps the two honest.
 *
 * Nothing in this package imports anything from Android, and nothing imports a JSON library: the
 * wire is bytes with explicit presence bits, so an absent field is physically absent rather than a
 * sentinel that some later reader turns into a zero.
 */
object LinkProtocol {
    /**
     * The version this build speaks, and the oldest it can still talk to.
     *
     * **Byte 0 of every frame is the version, and version 1's header shape is frozen for ever.**
     * That is the whole point of putting it first: a build that only knows version 1 can always
     * read byte 0 of anything that arrives, recognise a version it does not know, and refuse
     * politely instead of parsing a later layout as if it were this one. A version bump that
     * changes the header is therefore legal, because no old reader will ever get past byte 0.
     */
    const val VERSION = 2
    const val MIN_SUPPORTED_VERSION = 1

    /**
     * What version 2 added, and nothing else: a coach's sides on a plan — `eachSide` on an exercise
     * and a named `side` on a prescribed set (PLAN-FORMAT.md "Sides"). Both are presence bits in
     * masks version 1 already reserved, so **a plan with no sides encodes to exactly version 1's
     * bytes** and `fixtures/link-wire.txt`'s `plan` line does not move.
     *
     * A version-1 reader would refuse those bits outright rather than ignore them — every decoder
     * here is strict on purpose — so [LinkSession.pushPlan] strips sides when the link settled on
     * version 1, and frames are written at the **negotiated** version (see [LinkCodec.version]).
     * That is what makes a version-2 phone and a version-1 watch a working link that is missing one
     * label, instead of two devices refusing to speak.
     */
    const val VERSION_WITH_PLAN_SIDES = 2

    /**
     * An [MessageType.ERROR] is always sent at version 1, whatever version the link settled on,
     * for the same reason: version 1 is the one frame shape every build that has ever existed can
     * parse, so a refusal is always legible to the peer being refused.
     */
    const val ERROR_VERSION = 1

    /** 128-bit UUIDs, generated once and fixed. The service is what the watch advertises. */
    const val SERVICE_UUID = "6f1e2d40-9c3b-4a51-8f7a-2b5d1c0e7a10"

    /** Central → peripheral. Write with response: the response is the flow control. */
    const val RX_CHARACTERISTIC_UUID = "6f1e2d41-9c3b-4a51-8f7a-2b5d1c0e7a10"

    /** Peripheral → central, by notification. */
    const val TX_CHARACTERISTIC_UUID = "6f1e2d42-9c3b-4a51-8f7a-2b5d1c0e7a10"

    /** The Bluetooth SIG's Client Characteristic Configuration descriptor, for enabling notifies. */
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    /**
     * Requested ATT MTU. 247 is what both Android peers will usually agree, leaving 244 bytes of
     * ATT payload; the code never assumes it got it, because the guaranteed floor is 23 (20 bytes
     * of payload, 12 after this protocol's header) and fragmentation covers the difference.
     */
    const val REQUESTED_MTU = 247

    /** Bytes of ATT overhead on a write or a notification, subtracted from the negotiated MTU. */
    const val ATT_OVERHEAD = 3

    /** Usable frame bytes for a negotiated [mtu]. Never returns less than the BLE floor allows. */
    fun frameBudget(mtu: Int): Int = (mtu - ATT_OVERHEAD).coerceAtLeast(23 - ATT_OVERHEAD)
}

/**
 * Message opcodes. The names mirror `shared/contracts/workout-sync.schema.json`'s envelope events
 * wherever an event means the same thing, so the Apple and Wear channels stay legible to each
 * other: [PLAN_PUSHED], [PLAN_REQUEST], [SET_LOGGED] and [SESSION_FINISHED] are those events, and
 * [ACK] is the schema's `WORKOUT_SYNC_ACK`. The handshake types have no envelope equivalent
 * because WatchConnectivity does its own pairing and this link has to do its own.
 */
enum class MessageType(val code: Int) {
    HELLO(0x01),
    HELLO_ACK(0x02),
    PAIR_CONFIRM(0x03),
    PAIR_RESULT(0x04),
    PLAN_PUSHED(0x10),
    PLAN_REQUEST(0x11),
    SET_LOGGED(0x20),
    SESSION_FINISHED(0x21),
    ACK(0x30),
    ERROR(0x3F);

    companion object {
        private val byCode = entries.associateBy { it.code }

        /** null for an opcode this build does not know — which is refused, never guessed at. */
        fun from(code: Int): MessageType? = byCode[code]
    }
}

/** Why a peer refused something. Carried by [MessageType.ERROR] with optional free text. */
enum class LinkError(val code: Int) {
    UNSUPPORTED_VERSION(1),
    MALFORMED_FRAME(2),
    NOT_PAIRED(3),
    UNEXPECTED_MESSAGE(4),
    PAYLOAD_TOO_LARGE(5),
    UNKNOWN_TYPE(6),
    PAIRING_REJECTED(7);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun from(code: Int): LinkError? = byCode[code]
    }
}

/**
 * What a receiver did with something it stored, mirroring the reconciliation outcomes
 * `docs/ARCHITECTURE.md` already defines for the Apple side, so both channels acknowledge in the
 * same words.
 */
enum class AckOutcome(val code: Int) {
    INSERTED(0), ACCEPTED(1), IGNORED(2), IDEMPOTENT(3);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun from(code: Int): AckOutcome? = byCode[code]
    }
}
