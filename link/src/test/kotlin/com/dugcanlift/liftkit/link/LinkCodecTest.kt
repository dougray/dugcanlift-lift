package com.dugcanlift.liftkit.link

import org.junit.Assert.*
import org.junit.Test

class LinkCodecTest {
    private fun codec(mtu: Int = 23) = LinkCodec().apply { frameBudget = LinkProtocol.frameBudget(mtu) }

    private fun deliver(from: LinkCodec, to: LinkCodec, message: LinkMessage): CodecEvent {
        var last: CodecEvent = CodecEvent.Incomplete
        from.frames(message).forEach { last = to.accept(it) }
        return last
    }

    @Test fun `a message that fits one frame is FIRST and LAST together`() {
        val frames = codec(LinkProtocol.REQUESTED_MTU).frames(LinkMessage(MessageType.HELLO, ByteArray(10)))
        assertEquals(1, frames.size)
        val frame = (LinkFrames.decode(frames[0]) as FrameDecode.Ok).frame
        assertTrue(frame.isFirst && frame.isLast)
    }

    @Test fun `an empty payload still produces exactly one frame`() {
        assertEquals(1, codec().frames(LinkMessage(MessageType.PLAN_REQUEST)).size)
    }

    @Test fun `a long message survives the smallest MTU BLE guarantees`() {
        val payload = ByteArray(2000) { (it * 7 and 0xFF).toByte() }
        val sender = codec(23)
        val receiver = codec(23)
        val frames = sender.frames(LinkMessage(MessageType.PLAN_PUSHED, payload))
        // 20 bytes per write, 8 of them header: 12 bytes of payload each.
        assertEquals(167, frames.size)
        frames.forEach { assertTrue("${it.size} > 20", it.size <= 20) }
        var completed: LinkMessage? = null
        frames.forEach { f -> (receiver.accept(f) as? CodecEvent.Complete)?.let { completed = it.message } }
        assertArrayEquals(payload, completed!!.payload)
        assertEquals(MessageType.PLAN_PUSHED.code, completed!!.type)
    }

    @Test fun `a larger MTU means fewer frames and the same message`() {
        val payload = ByteArray(2000) { (it * 7 and 0xFF).toByte() }
        val sender = codec(LinkProtocol.REQUESTED_MTU)
        val receiver = codec(LinkProtocol.REQUESTED_MTU)
        assertEquals(9, sender.frames(LinkMessage(MessageType.PLAN_PUSHED, payload)).size)
        val event = deliver(sender, receiver, LinkMessage(MessageType.PLAN_PUSHED, payload))
        assertArrayEquals(payload, (event as CodecEvent.Complete).message.payload)
    }

    @Test fun `every frame of one message carries the same sequence number, and the next message a new one`() {
        val sender = codec(23)
        val seqA = sender.frames(LinkMessage(MessageType.PLAN_PUSHED, ByteArray(100)))
            .map { (LinkFrames.decode(it) as FrameDecode.Ok).frame.seq }
        val seqB = sender.frames(LinkMessage(MessageType.PLAN_PUSHED, ByteArray(100)))
            .map { (LinkFrames.decode(it) as FrameDecode.Ok).frame.seq }
        assertEquals(1, seqA.toSet().size)
        assertEquals(1, seqB.toSet().size)
        assertNotEquals(seqA.first(), seqB.first())
    }

    @Test fun `the sequence number wraps rather than overflowing the byte`() {
        val sender = codec(LinkProtocol.REQUESTED_MTU)
        val seqs = (0 until 258).map {
            (LinkFrames.decode(sender.frames(LinkMessage(MessageType.ACK, ByteArray(1)))[0]) as FrameDecode.Ok).frame.seq
        }
        assertEquals(0, seqs.first())
        assertEquals(255, seqs[255])
        assertEquals(0, seqs[256])
        assertEquals(1, seqs[257])
    }

    @Test fun `a continuation with no start is refused`() {
        val continuation = LinkFrames.encode(
            LinkFrame(LinkProtocol.VERSION, MessageType.PLAN_PUSHED.code, 0, 4, byteArrayOf(1, 2))
        )
        val event = codec().accept(continuation)
        assertEquals(LinkError.MALFORMED_FRAME, (event as CodecEvent.Refused).error)
    }

    @Test fun `a continuation of a different message is refused, not spliced in`() {
        val receiver = codec(23)
        val first = LinkFrames.encode(
            LinkFrame(LinkProtocol.VERSION, MessageType.PLAN_PUSHED.code, LinkFrame.FLAG_FIRST, 4, byteArrayOf(1))
        )
        val stranger = LinkFrames.encode(
            LinkFrame(LinkProtocol.VERSION, MessageType.PLAN_PUSHED.code, LinkFrame.FLAG_LAST, 9, byteArrayOf(2))
        )
        assertEquals(CodecEvent.Incomplete, receiver.accept(first))
        assertEquals(LinkError.MALFORMED_FRAME, (receiver.accept(stranger) as CodecEvent.Refused).error)
    }

    @Test fun `a new message abandons an unfinished one, and says how many it abandoned`() {
        val receiver = codec(23)
        val sender = codec(23)
        val abandoned = sender.frames(LinkMessage(MessageType.PLAN_PUSHED, ByteArray(100)))
        receiver.accept(abandoned.first())
        assertEquals(0, receiver.abandonedPartials)
        val event = deliver(sender, receiver, LinkMessage(MessageType.ACK, byteArrayOf(1)))
        assertTrue(event is CodecEvent.Complete)
        assertEquals(1, receiver.abandonedPartials)
    }

    @Test fun `a message over the cap is refused out loud rather than truncated`() {
        val small = LinkCodec(maxMessageBytes = 32).apply { frameBudget = LinkProtocol.frameBudget(23) }
        val sender = codec(23)
        var refused: CodecEvent.Refused? = null
        sender.frames(LinkMessage(MessageType.PLAN_PUSHED, ByteArray(200))).forEach { f ->
            (small.accept(f) as? CodecEvent.Refused)?.let { if (refused == null) refused = it }
        }
        assertEquals(LinkError.PAYLOAD_TOO_LARGE, refused!!.error)
    }

    @Test fun `an unknown opcode reaches the session layer rather than being dropped here`() {
        val receiver = codec(23)
        val unknown = LinkFrames.encode(
            LinkFrame(LinkProtocol.VERSION, 0x7E, LinkFrame.FLAG_FIRST or LinkFrame.FLAG_LAST, 0, byteArrayOf(1))
        )
        val event = receiver.accept(unknown) as CodecEvent.Complete
        assertEquals(0x7E, event.message.type)
        assertNull(event.message.known)
    }

    @Test fun `a version the codec does not know is refused as a version problem`() {
        val bytes = LinkFrames.encode(
            LinkFrame(LinkProtocol.VERSION, MessageType.HELLO.code, 0x03, 0, byteArrayOf(1))
        )
        bytes[0] = 4
        assertEquals(LinkError.UNSUPPORTED_VERSION, (codec().accept(bytes) as CodecEvent.Refused).error)
    }

    @Test fun `a frame budget with no room for a payload is rejected at the source`() {
        assertThrows(IllegalArgumentException::class.java) { LinkCodec().frameBudget = LinkFrame.HEADER_BYTES }
    }
}
