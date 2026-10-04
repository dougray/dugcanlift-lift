package com.dugcanlift.liftkit.link

import java.io.ByteArrayOutputStream

/**
 * A whole message: one opcode and its payload, however many frames it took to carry.
 *
 * [type] is the raw opcode rather than a [MessageType] on purpose. The codec must be able to hand
 * up something it does not recognise so the session layer can refuse it by name and keep the link
 * open, the way the sync schema already says a receiver treats an event it does not know.
 */
class LinkMessage(val type: Int, val payload: ByteArray) {
    constructor(type: MessageType, payload: ByteArray = ByteArray(0)) : this(type.code, payload)

    val known: MessageType? get() = MessageType.from(type)

    override fun toString(): String =
        "LinkMessage(${known?.name ?: "0x${type.toString(16)}"}, ${payload.size} bytes)"
}

/** What handing one inbound write to a [LinkCodec] produced. */
sealed interface CodecEvent {
    /** A fragment was taken; the message is not complete yet. */
    data object Incomplete : CodecEvent

    data class Complete(val message: LinkMessage) : CodecEvent

    /** The caller answers with an ERROR carrying [error]; nothing was delivered. */
    data class Refused(val error: LinkError, val detail: String) : CodecEvent
}

/**
 * Frames a message out and reassembles one in. One instance per link, per direction pair: it owns
 * the outgoing sequence counter and the single inbound reassembly buffer.
 *
 * The channel is strictly one message at a time in each direction — the sender does not start a
 * second message before the first one's last frame has gone out. That is what makes a single
 * reassembly buffer correct, and it is affordable because a write-with-response is already
 * serialised by ATT.
 */
class LinkCodec(
    /**
     * The largest message this side will reassemble. A plan for a full training day is on the
     * order of a kilobyte; the bound exists so a peer cannot make this side allocate without limit,
     * and it is refused out loud rather than truncated.
     */
    private val maxMessageBytes: Int = MAX_MESSAGE_BYTES,
) {
    /**
     * Usable bytes per frame, header included. Starts at the BLE floor and is raised once an MTU
     * is negotiated: starting high and hoping would silently truncate every frame until it was.
     */
    var frameBudget: Int = LinkProtocol.frameBudget(23)
        set(value) {
            require(value > LinkFrame.HEADER_BYTES) { "frame budget $value leaves no payload" }
            field = value
        }

    /**
     * The version written into byte 0 of every outgoing frame.
     *
     * Starts at [LinkProtocol.MIN_SUPPORTED_VERSION], not at [LinkProtocol.VERSION], and is raised
     * only once the handshake has settled on a version both ends read. That is what the version byte
     * is *for*: a build that speaks 2 must still be able to talk to one that speaks 1, and a peer
     * that refuses byte 0 refuses the HELLO too, so framing at this build's own newest version would
     * mean no link at all rather than a link without the newest field.
     *
     * An [MessageType.ERROR] ignores this and always goes at [LinkProtocol.ERROR_VERSION], because a
     * refusal has to be legible to the peer being refused — which `LINK-PROTOCOL.md` has always
     * said and nothing implemented until frames stopped being written at one fixed version.
     */
    var version: Int = LinkProtocol.MIN_SUPPORTED_VERSION
        set(value) {
            require(value in LinkProtocol.MIN_SUPPORTED_VERSION..LinkProtocol.VERSION) {
                "cannot frame at version $value"
            }
            field = value
        }

    private var nextSeq = 0
    private var pendingType = -1
    private var pendingSeq = -1
    private var pending: ByteArrayOutputStream? = null

    /**
     * Partial messages abandoned because a new one started before they finished. A count rather
     * than silence: a link that keeps dropping halves of plans should be able to say so.
     */
    var abandonedPartials: Int = 0
        private set

    /** The frames to write, in order. A message that fits in one frame is FIRST and LAST together. */
    fun frames(message: LinkMessage): List<ByteArray> {
        val seq = nextSeq
        nextSeq = (nextSeq + 1) and 0xFF
        val frameVersion =
            if (message.known == MessageType.ERROR) LinkProtocol.ERROR_VERSION else version
        val perFrame = frameBudget - LinkFrame.HEADER_BYTES
        val payload = message.payload
        if (payload.isEmpty()) {
            return listOf(
                LinkFrames.encode(
                    LinkFrame(frameVersion, message.type, LinkFrame.FLAG_FIRST or LinkFrame.FLAG_LAST, seq, ByteArray(0))
                )
            )
        }
        val out = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + perFrame, payload.size)
            var flags = 0
            if (offset == 0) flags = flags or LinkFrame.FLAG_FIRST
            if (end == payload.size) flags = flags or LinkFrame.FLAG_LAST
            out += LinkFrames.encode(
                LinkFrame(frameVersion, message.type, flags, seq, payload.copyOfRange(offset, end))
            )
            offset = end
        }
        return out
    }

    /** Version refusal is the caller's to answer: this only says the frame could not be taken. */
    fun accept(bytes: ByteArray): CodecEvent = when (val decoded = LinkFrames.decode(bytes)) {
        is FrameDecode.UnsupportedVersion -> {
            reset()
            CodecEvent.Refused(LinkError.UNSUPPORTED_VERSION, "version ${decoded.version}")
        }
        is FrameDecode.Malformed -> {
            reset()
            CodecEvent.Refused(LinkError.MALFORMED_FRAME, decoded.reason)
        }
        is FrameDecode.Ok -> take(decoded.frame)
    }

    private fun take(frame: LinkFrame): CodecEvent {
        if (frame.isFirst) {
            if (pending != null) abandonedPartials++
            pendingType = frame.type
            pendingSeq = frame.seq
            pending = ByteArrayOutputStream()
        } else if (pending == null) {
            return CodecEvent.Refused(LinkError.MALFORMED_FRAME, "continuation with no start")
        } else if (frame.type != pendingType || frame.seq != pendingSeq) {
            reset()
            return CodecEvent.Refused(LinkError.MALFORMED_FRAME, "continuation of a different message")
        }
        val buffer = pending!!
        if (buffer.size() + frame.payload.size > maxMessageBytes) {
            reset()
            return CodecEvent.Refused(LinkError.PAYLOAD_TOO_LARGE, "over $maxMessageBytes bytes")
        }
        buffer.write(frame.payload)
        if (!frame.isLast) return CodecEvent.Incomplete
        val message = LinkMessage(pendingType, buffer.toByteArray())
        reset()
        return CodecEvent.Complete(message)
    }

    private fun reset() {
        pending = null
        pendingType = -1
        pendingSeq = -1
    }

    companion object {
        const val MAX_MESSAGE_BYTES = 64 * 1024
    }
}
