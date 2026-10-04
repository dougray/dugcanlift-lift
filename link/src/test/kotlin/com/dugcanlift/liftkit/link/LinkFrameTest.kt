package com.dugcanlift.liftkit.link

import org.junit.Assert.*
import org.junit.Test

class LinkFrameTest {
    private fun frame(payload: ByteArray = byteArrayOf(9, 9), type: Int = MessageType.HELLO.code) =
        LinkFrame(LinkProtocol.VERSION, type, LinkFrame.FLAG_FIRST or LinkFrame.FLAG_LAST, 7, payload)

    @Test fun `the header is eight bytes in the documented order`() {
        val bytes = LinkFrames.encode(frame())
        assertEquals(LinkFrame.HEADER_BYTES + 2, bytes.size)
        assertEquals(LinkProtocol.VERSION, bytes[0].toInt())        // version first, always
        assertEquals(MessageType.HELLO.code, bytes[1].toInt())      // type
        assertEquals(0x03, bytes[2].toInt())                        // FIRST | LAST
        assertEquals(7, bytes[3].toInt())                           // seq
        assertEquals(0, bytes[4].toInt())                           // length, big-endian
        assertEquals(2, bytes[5].toInt())
    }

    @Test fun `a frame round-trips`() {
        val original = frame(ByteArray(300) { (it and 0xFF).toByte() })
        val decoded = LinkFrames.decode(LinkFrames.encode(original))
        assertEquals(FrameDecode.Ok(original), decoded)
    }

    @Test fun `an empty payload is legal`() {
        val decoded = LinkFrames.decode(LinkFrames.encode(frame(ByteArray(0), MessageType.PLAN_REQUEST.code)))
        assertTrue(decoded is FrameDecode.Ok)
        assertEquals(0, (decoded as FrameDecode.Ok).frame.payload.size)
    }

    @Test fun `CRC-16 CCITT-FALSE matches its published check value`() {
        // The standard check: "123456789" is 0x29B1 under CRC-16/CCITT-FALSE. If this drifts, so
        // has every frame, and no amount of round-tripping against ourselves would show it.
        assertEquals(0x29B1, LinkFrames.crc16("123456789".toByteArray()))
    }

    @Test fun `a flipped payload byte fails the CRC`() {
        val bytes = LinkFrames.encode(frame(byteArrayOf(1, 2, 3, 4)))
        bytes[LinkFrame.HEADER_BYTES + 2] = (bytes[LinkFrame.HEADER_BYTES + 2] + 1).toByte()
        assertTrue(LinkFrames.decode(bytes) is FrameDecode.Malformed)
    }

    @Test fun `a truncated frame is malformed, not half-read`() {
        val bytes = LinkFrames.encode(frame(byteArrayOf(1, 2, 3, 4)))
        assertTrue(LinkFrames.decode(bytes.copyOf(bytes.size - 1)) is FrameDecode.Malformed)
        assertTrue(LinkFrames.decode(ByteArray(3)) is FrameDecode.Malformed)
    }

    @Test fun `extra bytes appended to a frame are refused`() {
        val bytes = LinkFrames.encode(frame(byteArrayOf(1, 2)))
        assertTrue(LinkFrames.decode(bytes + byteArrayOf(0)) is FrameDecode.Malformed)
    }

    @Test fun `a newer version is refused by name, not misread`() {
        val bytes = LinkFrames.encode(frame())
        bytes[0] = (LinkProtocol.VERSION + 1).toByte()
        val decoded = LinkFrames.decode(bytes)
        assertEquals(FrameDecode.UnsupportedVersion(LinkProtocol.VERSION + 1), decoded)
    }

    @Test fun `version zero is refused`() {
        val bytes = LinkFrames.encode(frame())
        bytes[0] = 0
        assertEquals(FrameDecode.UnsupportedVersion(0), LinkFrames.decode(bytes))
    }

    @Test fun `the version is read before the CRC, so a future layout is refused politely`() {
        // A frame from a build whose header is not this shape will not check out under this CRC.
        // Reading byte 0 first is what turns that from "corrupt, retry" into "newer, refuse".
        val bytes = ByteArray(LinkFrame.HEADER_BYTES) { 0x5A }
        bytes[0] = 99
        assertEquals(FrameDecode.UnsupportedVersion(99), LinkFrames.decode(bytes))
    }

    @Test fun `a reserved flag bit is refused rather than masked off`() {
        val bytes = LinkFrames.encode(
            LinkFrame(LinkProtocol.VERSION, MessageType.HELLO.code, LinkFrame.FLAG_FIRST or 0x40, 0, byteArrayOf(1))
        )
        assertTrue(LinkFrames.decode(bytes) is FrameDecode.Malformed)
    }

    @Test fun `frames compare by their bytes, not by array identity`() {
        assertEquals(frame(byteArrayOf(1, 2)), frame(byteArrayOf(1, 2)))
        assertNotEquals(frame(byteArrayOf(1, 2)), frame(byteArrayOf(1, 3)))
    }

    @Test fun `the frame budget never drops below what BLE guarantees`() {
        assertEquals(20, LinkProtocol.frameBudget(23))
        assertEquals(20, LinkProtocol.frameBudget(0))
        assertEquals(244, LinkProtocol.frameBudget(LinkProtocol.REQUESTED_MTU))
    }
}
