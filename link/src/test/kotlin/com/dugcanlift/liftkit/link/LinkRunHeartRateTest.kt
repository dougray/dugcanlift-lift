package com.dugcanlift.liftkit.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SHARED SOURCE — identical in `dugcanlift-lift-watch` and `dugcanlift-lift`. See [LinkProtocol]'s
 * file comment.
 *
 * Version 5's whole content: heart rate during a run. The phone asks the watch to start measuring
 * (`RUN_HR_START`) and stop (`RUN_HR_STOP`), both with an empty payload; the watch sends its readings
 * (`RUN_HR`: a `u16` bpm then a `u32` of seconds since the epoch, big-endian like every other number
 * on this link). What this pins: the bytes; that a zero is never a heart rate; that a build which
 * has never heard of these opcodes treats them as any other unknown message; and that a link that
 * settled below 5 carries none of them in either direction.
 */
class LinkRunHeartRateTest {
    private val phoneNonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val watchNonce = byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16)
    private val reading = RunHeartRate(bpm = 142, atEpochSec = 1_762_800_000L)

    /** Two remembered peers, so the handshake settles on the highest version both speak. */
    private fun linked(): Pair<LinkSession, LinkSession> {
        val phone = LinkSession(LinkRole.CENTRAL, "Pixel 8", phoneNonce, alreadyPaired = true)
        val watch = LinkSession(LinkRole.PERIPHERAL, "Galaxy Watch6", watchNonce, alreadyPaired = true)
        watch.start()
        phone.receive(watch.receive(phone.start().send.single()).send.single())
        assertEquals(LinkState.READY, phone.state)
        assertEquals(LinkState.READY, watch.state)
        return phone to watch
    }

    /** A watch whose peer is a phone that still speaks only up to [peerMax]. */
    private fun watchBelow(peerMax: Int): LinkSession {
        val watch = LinkSession(LinkRole.PERIPHERAL, "Galaxy Watch6", watchNonce, alreadyPaired = true)
        watch.start()
        watch.receive(
            LinkMessage(
                MessageType.HELLO,
                LinkPayloads.encodeHello(Hello(1, peerMax, null, "Old phone", phoneNonce)),
            )
        )
        assertEquals(peerMax, watch.negotiatedVersion)
        assertEquals(LinkState.READY, watch.state)
        return watch
    }

    private fun phoneBelow(watchMax: Int): LinkSession {
        val phone = LinkSession(LinkRole.CENTRAL, "Pixel 8", phoneNonce, alreadyPaired = true)
        phone.start()
        phone.receive(
            LinkMessage(
                MessageType.HELLO_ACK,
                LinkPayloads.encodeHello(Hello(1, watchMax, watchMax, "Old watch", watchNonce)),
            )
        )
        assertEquals(watchMax, phone.negotiatedVersion)
        assertEquals(LinkState.READY, phone.state)
        return phone
    }

    // ---- the bytes ------------------------------------------------------------------------------

    @Test fun `the opcodes are the ones the protocol document states`() {
        assertEquals(0x12, MessageType.RUN_HR_START.code)
        assertEquals(0x13, MessageType.RUN_HR_STOP.code)
        assertEquals(0x22, MessageType.RUN_HR.code)
        assertEquals(5, LinkProtocol.VERSION)
        assertEquals(5, LinkProtocol.VERSION_WITH_RUN_HEART_RATE)
    }

    @Test fun `a reading of 142 at 1762800000 is a big-endian u16 then a big-endian u32`() {
        // 142 = 0x008e; 1_762_800_000 = 0x69123180. Written by hand from the document, not read back
        // out of the encoder.
        assertEquals("008e69123180", LinkFixtures.hex(LinkPayloads.encodeRunHeartRate(reading)))
    }

    @Test fun `a reading decodes back to what was encoded`() {
        assertEquals(reading, LinkPayloads.decodeRunHeartRate(LinkPayloads.encodeRunHeartRate(reading)))
        assertEquals(reading, LinkPayloads.decodeRunHeartRate(LinkFixtures.unhex("008e69123180")))
    }

    @Test fun `the u16 and the u32 hold their extremes`() {
        val widest = RunHeartRate(bpm = 65_535, atEpochSec = 0xFFFFFFFFL)
        assertEquals(widest, LinkPayloads.decodeRunHeartRate(LinkPayloads.encodeRunHeartRate(widest)))
        val narrowest = RunHeartRate(bpm = 1, atEpochSec = 0)
        assertEquals(narrowest, LinkPayloads.decodeRunHeartRate(LinkPayloads.encodeRunHeartRate(narrowest)))
    }

    @Test fun `a zero is never a heart rate, in either direction`() {
        assertThrows(IllegalArgumentException::class.java) { RunHeartRate(0, 1_762_800_000L) }
        assertThrows(IllegalArgumentException::class.java) { RunHeartRate(-3, 1_762_800_000L) }
        assertThrows(LinkDecodeException::class.java) {
            LinkPayloads.decodeRunHeartRate(LinkFixtures.unhex("000069123180"))
        }
    }

    @Test fun `a short or long payload is refused like every other payload here`() {
        assertThrows(LinkDecodeException::class.java) { LinkPayloads.decodeRunHeartRate(LinkFixtures.unhex("008e691231")) }
        assertThrows(LinkDecodeException::class.java) { LinkPayloads.decodeRunHeartRate(LinkFixtures.unhex("008e6912318000")) }
        assertThrows(LinkDecodeException::class.java) { LinkPayloads.decodeRunHeartRate(ByteArray(0)) }
    }

    // ---- a build that has never heard of them -----------------------------------------------------

    @Test fun `version 4 does not know the new opcodes and version 5 does`() {
        listOf(0x12, 0x13, 0x22).forEach { code ->
            assertNull("0x${code.toString(16)} at v4", MessageType.from(code, version = 4))
            assertEquals("0x${code.toString(16)} at v5", code, MessageType.from(code, version = 5)!!.code)
        }
        // And everything v4 had it still has.
        MessageType.entries.filter { it.since <= 4 }.forEach {
            assertEquals(it, MessageType.from(it.code, version = 4))
        }
    }

    @Test fun `a link settled on version 4 treats a RUN_HR as it treats any unknown message`() {
        val watch = watchBelow(peerMax = 4)
        val reaction = watch.receive(LinkMessage(MessageType.RUN_HR, LinkPayloads.encodeRunHeartRate(reading)))
        val error = LinkPayloads.decodeError(reaction.send.single().payload)
        assertEquals(LinkError.UNKNOWN_TYPE, error.error)
        assertEquals("opcode 0x22", error.detail)
        assertEquals(LinkState.READY, watch.state)          // no crash, the link stays up
        assertTrue(reaction.events.isEmpty())               // and nothing is handed to the app
    }

    @Test fun `a link settled on version 4 will not start or stop a run on the watch either`() {
        val watch = watchBelow(peerMax = 4)
        listOf(MessageType.RUN_HR_START, MessageType.RUN_HR_STOP).forEach { type ->
            val reaction = watch.receive(LinkMessage(type))
            assertEquals(LinkError.UNKNOWN_TYPE, LinkPayloads.decodeError(reaction.send.single().payload).error)
            assertTrue(reaction.events.isEmpty())
        }
    }

    @Test fun `an opcode this build has never heard of still gets the same answer`() {
        val (_, watch) = linked()
        val reaction = watch.receive(LinkMessage(0x7E, byteArrayOf(1)))
        assertEquals(LinkError.UNKNOWN_TYPE, LinkPayloads.decodeError(reaction.send.single().payload).error)
        assertEquals(LinkState.READY, watch.state)
    }

    // ---- the session rules ------------------------------------------------------------------------

    @Test fun `the phone asks, the watch hears, and the readings come back`() {
        val (phone, watch) = linked()
        assertTrue(phone.supportsRunHeartRate)

        val start = phone.startRunHeartRate()!!
        assertEquals(MessageType.RUN_HR_START, start.known)
        assertEquals(0, start.payload.size)
        assertEquals(listOf(LinkEvent.RunHeartRateStartRequested), watch.receive(start).events)

        val sent = watch.reportRunHeartRate(reading)!!
        assertEquals(MessageType.RUN_HR, sent.known)
        assertEquals(listOf(LinkEvent.RunHeartRateReceived(reading)), phone.receive(sent).events)

        val stop = phone.stopRunHeartRate()!!
        assertEquals(MessageType.RUN_HR_STOP, stop.known)
        assertEquals(0, stop.payload.size)
        assertEquals(listOf(LinkEvent.RunHeartRateStopRequested), watch.receive(stop).events)
    }

    @Test fun `only the phone may ask and only the watch may report`() {
        val (phone, watch) = linked()
        assertThrows(IllegalStateException::class.java) { watch.startRunHeartRate() }
        assertThrows(IllegalStateException::class.java) { watch.stopRunHeartRate() }
        assertThrows(IllegalStateException::class.java) { phone.reportRunHeartRate(reading) }
        // And the receiving end refuses a message from the wrong direction.
        val wrongWay = phone.receive(LinkMessage(MessageType.RUN_HR_START))
        assertEquals(LinkError.UNEXPECTED_MESSAGE, LinkPayloads.decodeError(wrongWay.send.single().payload).error)
        assertTrue(wrongWay.events.isEmpty())
        val reversed = watch.receive(LinkMessage(MessageType.RUN_HR, LinkPayloads.encodeRunHeartRate(reading)))
        assertEquals(LinkError.UNEXPECTED_MESSAGE, LinkPayloads.decodeError(reversed.send.single().payload).error)
    }

    @Test fun `nothing about a run is accepted before the link is paired`() {
        val watch = LinkSession(LinkRole.PERIPHERAL, "Galaxy Watch6", watchNonce)
        watch.start()
        val reaction = watch.receive(LinkMessage(MessageType.RUN_HR_START))
        assertEquals(LinkError.NOT_PAIRED, LinkPayloads.decodeError(reaction.send.single().payload).error)
        assertTrue(reaction.events.isEmpty())
    }

    @Test fun `a phone on a version 4 watch sends nothing, and says so`() {
        val phone = phoneBelow(watchMax = 4)
        assertFalse(phone.supportsRunHeartRate)
        assertNull(phone.startRunHeartRate())
        assertNull(phone.stopRunHeartRate())
    }

    @Test fun `a watch on a version 4 phone sends nothing, and says so`() {
        val watch = watchBelow(peerMax = 4)
        assertFalse(watch.supportsRunHeartRate)
        assertNull(watch.reportRunHeartRate(reading))
    }

    @Test fun `a garbled reading is refused without ending a working link`() {
        val (phone, _) = linked()
        val reaction = phone.receive(LinkMessage(MessageType.RUN_HR, byteArrayOf(0, 0, 1)))
        assertEquals(LinkError.MALFORMED_FRAME, LinkPayloads.decodeError(reaction.send.single().payload).error)
        assertEquals(LinkState.READY, phone.state)
    }

    @Test fun `the new messages travel in frames at the negotiated version`() {
        val (phone, _) = linked()
        val codec = LinkCodec().apply { version = phone.negotiatedVersion!! }
        assertEquals(5, codec.version)
        val frames = codec.frames(phone.startRunHeartRate()!!)
        assertEquals(1, frames.size)
        assertEquals(5, frames.single()[0].toInt())
        assertEquals(0x12, frames.single()[1].toInt())
    }
}
