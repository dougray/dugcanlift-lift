package com.dugcanlift.liftkit.link

/** Which end of the link this is. The watch is the peripheral; see `docs/LINK-PROTOCOL.md`. */
enum class LinkRole { CENTRAL, PERIPHERAL }

enum class LinkState {
    /** Nothing has happened yet. */
    IDLE,

    /** HELLO is in flight, or awaited. */
    HANDSHAKING,

    /** Versions agreed and a confirmation code is on both screens, waiting for the humans. */
    CONFIRMING,

    /** Paired and open for business. */
    READY,

    /** Refused, rejected or broken. A failed session is never reused; the caller makes a new one. */
    FAILED,
}

sealed interface LinkEvent {
    /** Show this number and ask. Both ends raise it with the same [code]. */
    data class ConfirmationCode(val code: String, val peerName: String) : LinkEvent

    data class Paired(val peerName: String) : LinkEvent

    /** One of the two humans said no. Nothing is remembered. */
    data object PairingRejected : LinkEvent

    data class PlanReceived(val plan: Plan) : LinkEvent
    data object PlanRequested : LinkEvent
    data class SetLogged(val report: LoggedSetReport) : LinkEvent
    data class SessionFinished(val session: FinishedSession) : LinkEvent
    data class Acknowledged(val ack: LinkAck) : LinkEvent

    /** The peer refused something while the link stayed up. Worth a log, not a teardown. */
    data class Refused(val error: LinkErrorMessage) : LinkEvent

    /** The link is over. [state] is [LinkState.FAILED] and this session is spent. */
    data class Failed(val error: LinkErrorMessage) : LinkEvent
}

/** What one step produced: frames to write, things to tell the app, and where the link now is. */
data class Reaction(
    val send: List<LinkMessage> = emptyList(),
    val events: List<LinkEvent> = emptyList(),
    val state: LinkState,
)

/**
 * The protocol's rules, with no radio in them.
 *
 * Everything about *when* a message is legal lives here and is unit tested: which end may send
 * what, what happens to a message that arrives before pairing, and how a version disagreement is
 * refused. The Android layer on each side only moves bytes.
 *
 * A session is single-use. It is created when a connection opens and discarded when it closes or
 * fails, so there is no half-torn-down state to reason about.
 */
class LinkSession(
    val role: LinkRole,
    /** What the peer will show the user: "Pixel 8", "Galaxy Watch6". */
    private val deviceName: String,
    /** [Hello.NONCE_BYTES] random bytes, fresh for this connection. */
    private val nonce: ByteArray,
    /**
     * True when this peer is already in our bond record. A remembered watch reconnects without
     * asking anybody anything — that is the whole point of remembering it — so the confirmation
     * code is skipped and the link goes straight to [LinkState.READY].
     */
    private val alreadyPaired: Boolean = false,
) {
    init {
        require(nonce.size == Hello.NONCE_BYTES) { "nonce must be ${Hello.NONCE_BYTES} bytes" }
    }

    var state: LinkState = LinkState.IDLE
        private set

    var negotiatedVersion: Int? = null
        private set

    var peerName: String? = null
        private set

    /** Non-null once both nonces are known; null again once pairing is settled. */
    var confirmationCode: String? = null
        private set

    private var localDecision: Boolean? = null
    private var peerDecision: Boolean? = null

    /** The central opens; the peripheral waits. Call once, when the connection is usable. */
    fun start(): Reaction {
        check(state == LinkState.IDLE) { "already started" }
        state = LinkState.HANDSHAKING
        return if (role == LinkRole.CENTRAL) {
            Reaction(
                send = listOf(
                    LinkMessage(
                        MessageType.HELLO,
                        LinkPayloads.encodeHello(
                            Hello(LinkProtocol.MIN_SUPPORTED_VERSION, LinkProtocol.VERSION, null, deviceName, nonce)
                        )
                    )
                ),
                state = state,
            )
        } else {
            Reaction(state = state)
        }
    }

    /** The local user answered the confirmation prompt. */
    fun confirm(accepted: Boolean): Reaction {
        check(state == LinkState.CONFIRMING) { "nothing to confirm in state $state" }
        localDecision = accepted
        val type = if (role == LinkRole.CENTRAL) MessageType.PAIR_CONFIRM else MessageType.PAIR_RESULT
        val outgoing = listOf(LinkMessage(type, LinkPayloads.encodeDecision(accepted)))
        return Reaction(send = outgoing, events = settle(), state = state)
    }

    fun receive(message: LinkMessage): Reaction {
        val type = message.known
            ?: return refuse(LinkError.UNKNOWN_TYPE, "opcode 0x${message.type.toString(16)}", fatal = false)
        return try {
            handle(type, message.payload)
        } catch (e: LinkDecodeException) {
            refuse(LinkError.MALFORMED_FRAME, e.message ?: "undecodable", fatal = state != LinkState.READY)
        }
    }

    // ---- outbound, once READY. The role rules are here rather than in two Android files. ----

    fun pushPlan(plan: Plan): LinkMessage {
        requireReady(LinkRole.CENTRAL, "push a plan")
        return LinkMessage(MessageType.PLAN_PUSHED, LinkPayloads.encodePlan(plan))
    }

    fun requestPlan(): LinkMessage {
        requireReady(LinkRole.PERIPHERAL, "request a plan")
        return LinkMessage(MessageType.PLAN_REQUEST)
    }

    fun reportSet(report: LoggedSetReport): LinkMessage {
        requireReady(LinkRole.PERIPHERAL, "report a set")
        return LinkMessage(MessageType.SET_LOGGED, LinkPayloads.encodeLoggedSet(report))
    }

    fun finishSession(session: FinishedSession): LinkMessage {
        requireReady(LinkRole.PERIPHERAL, "finish a session")
        return LinkMessage(MessageType.SESSION_FINISHED, LinkPayloads.encodeSession(session))
    }

    fun acknowledge(ack: LinkAck): LinkMessage {
        check(state == LinkState.READY) { "cannot acknowledge in state $state" }
        return LinkMessage(MessageType.ACK, LinkPayloads.encodeAck(ack))
    }

    private fun requireReady(expected: LinkRole, what: String) {
        check(state == LinkState.READY) { "cannot $what in state $state" }
        check(role == expected) { "only the ${expected.name.lowercase()} may $what" }
    }

    // ---- inbound ---------------------------------------------------------------------------

    private fun handle(type: MessageType, payload: ByteArray): Reaction = when (type) {
        MessageType.HELLO -> onHello(payload)
        MessageType.HELLO_ACK -> onHelloAck(payload)
        MessageType.PAIR_CONFIRM -> onPeerDecision(type, payload, from = LinkRole.CENTRAL)
        MessageType.PAIR_RESULT -> onPeerDecision(type, payload, from = LinkRole.PERIPHERAL)
        MessageType.PLAN_PUSHED -> data(LinkRole.PERIPHERAL) { LinkEvent.PlanReceived(LinkPayloads.decodePlan(payload)) }
        MessageType.PLAN_REQUEST -> data(LinkRole.CENTRAL) { LinkEvent.PlanRequested }
        MessageType.SET_LOGGED -> data(LinkRole.CENTRAL) { LinkEvent.SetLogged(LinkPayloads.decodeLoggedSet(payload)) }
        MessageType.SESSION_FINISHED -> data(LinkRole.CENTRAL) { LinkEvent.SessionFinished(LinkPayloads.decodeSession(payload)) }
        MessageType.ACK -> data(null) { LinkEvent.Acknowledged(LinkPayloads.decodeAck(payload)) }
        MessageType.ERROR -> onError(payload)
    }

    private fun onHello(payload: ByteArray): Reaction {
        if (role != LinkRole.PERIPHERAL) return refuse(LinkError.UNEXPECTED_MESSAGE, "HELLO to a central")
        if (state != LinkState.IDLE && state != LinkState.HANDSHAKING) {
            return refuse(LinkError.UNEXPECTED_MESSAGE, "HELLO in state $state")
        }
        val hello = LinkPayloads.decodeHello(payload)
        val chosen = minOf(hello.maxVersion, LinkProtocol.VERSION)
        val floor = maxOf(hello.minVersion, LinkProtocol.MIN_SUPPORTED_VERSION)
        if (chosen < floor) {
            return refuse(
                LinkError.UNSUPPORTED_VERSION,
                "peer speaks ${hello.minVersion}..${hello.maxVersion}, this build " +
                    "${LinkProtocol.MIN_SUPPORTED_VERSION}..${LinkProtocol.VERSION}",
            )
        }
        negotiatedVersion = chosen
        peerName = hello.deviceName
        val ack = LinkMessage(
            MessageType.HELLO_ACK,
            LinkPayloads.encodeHello(
                Hello(LinkProtocol.MIN_SUPPORTED_VERSION, LinkProtocol.VERSION, chosen, deviceName, nonce)
            ),
        )
        return Reaction(send = listOf(ack), events = afterHandshake(hello.nonce, hello.deviceName), state = state)
    }

    private fun onHelloAck(payload: ByteArray): Reaction {
        if (role != LinkRole.CENTRAL) return refuse(LinkError.UNEXPECTED_MESSAGE, "HELLO_ACK to a peripheral")
        if (state != LinkState.HANDSHAKING) return refuse(LinkError.UNEXPECTED_MESSAGE, "HELLO_ACK in state $state")
        val hello = LinkPayloads.decodeHello(payload)
        val chosen = hello.chosenVersion
            ?: return refuse(LinkError.UNSUPPORTED_VERSION, "peer named no version")
        if (chosen < LinkProtocol.MIN_SUPPORTED_VERSION || chosen > LinkProtocol.VERSION) {
            return refuse(LinkError.UNSUPPORTED_VERSION, "peer chose $chosen")
        }
        negotiatedVersion = chosen
        peerName = hello.deviceName
        return Reaction(events = afterHandshake(hello.nonce, hello.deviceName), state = state)
    }

    /** Versions are agreed. A remembered peer is in; a new one needs the humans. */
    private fun afterHandshake(peerNonce: ByteArray, peerDeviceName: String): List<LinkEvent> {
        val code = PairingCode.of(nonce, peerNonce)
        return if (alreadyPaired) {
            state = LinkState.READY
            listOf(LinkEvent.Paired(peerDeviceName))
        } else {
            confirmationCode = code
            state = LinkState.CONFIRMING
            listOf(LinkEvent.ConfirmationCode(code, peerDeviceName))
        }
    }

    private fun onPeerDecision(type: MessageType, payload: ByteArray, from: LinkRole): Reaction {
        if (role == from) return refuse(LinkError.UNEXPECTED_MESSAGE, "$type from the wrong end")
        if (state != LinkState.CONFIRMING) return refuse(LinkError.UNEXPECTED_MESSAGE, "$type in state $state")
        peerDecision = LinkPayloads.decodeDecision(payload)
        return Reaction(events = settle(), state = state)
    }

    /**
     * Pairing completes only when **both** humans have said yes, in either order. The phone's user
     * reads the code off the watch and confirms; the watch's user reads it off the phone and
     * accepts. Either one saying no ends it, and nothing is remembered.
     */
    private fun settle(): List<LinkEvent> {
        val mine = localDecision
        val theirs = peerDecision
        if (mine == false || theirs == false) {
            state = LinkState.FAILED
            confirmationCode = null
            return listOf(LinkEvent.PairingRejected)
        }
        if (mine != true || theirs != true) return emptyList()
        state = LinkState.READY
        confirmationCode = null
        return listOf(LinkEvent.Paired(peerName ?: ""))
    }

    private fun data(allowedReceiver: LinkRole?, decode: () -> LinkEvent): Reaction {
        if (allowedReceiver != null && role != allowedReceiver) {
            return refuse(LinkError.UNEXPECTED_MESSAGE, "wrong direction for this end")
        }
        // The app layer's own guard, behind the encrypted characteristics rather than instead of
        // them: nothing carrying a training log is handed up before the link is paired.
        if (state != LinkState.READY) return refuse(LinkError.NOT_PAIRED, "state $state")
        return Reaction(events = listOf(decode()), state = state)
    }

    private fun onError(payload: ByteArray): Reaction {
        val error = LinkPayloads.decodeError(payload)
        return if (state == LinkState.READY) {
            // A refusal of one message is not a broken link. A peer that does not know an opcode
            // says so and carries on, the same way the sync schema has receivers ignore an event
            // they do not know rather than failing.
            Reaction(events = listOf(LinkEvent.Refused(error)), state = state)
        } else {
            state = LinkState.FAILED
            confirmationCode = null
            Reaction(events = listOf(LinkEvent.Failed(error)), state = state)
        }
    }

    private fun refuse(error: LinkError, detail: String, fatal: Boolean = error.isFatal()): Reaction {
        val message = LinkMessage(MessageType.ERROR, LinkPayloads.encodeError(LinkErrorMessage(error, detail)))
        val events = if (fatal) {
            state = LinkState.FAILED
            confirmationCode = null
            listOf(LinkEvent.Failed(LinkErrorMessage(error, detail)))
        } else {
            emptyList()
        }
        return Reaction(send = listOf(message), events = events, state = state)
    }

    /**
     * Which refusals end the link. A version disagreement does — there is nothing else to say —
     * and so does anything that arrives out of order during the handshake. An unknown opcode, or a
     * message refused while the link is up, does not: a newer peer talking about something this
     * build has never heard of is a peer worth staying connected to.
     */
    private fun LinkError.isFatal(): Boolean = when (this) {
        LinkError.UNSUPPORTED_VERSION, LinkError.PAIRING_REJECTED -> true
        LinkError.UNEXPECTED_MESSAGE -> state != LinkState.READY
        LinkError.MALFORMED_FRAME, LinkError.NOT_PAIRED, LinkError.PAYLOAD_TOO_LARGE,
        LinkError.UNKNOWN_TYPE -> false
    }
}
