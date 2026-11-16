package com.dugcanlift.macrocalc.watchlink

import com.dugcanlift.liftkit.link.Hello
import com.dugcanlift.liftkit.link.LinkError
import com.dugcanlift.liftkit.link.LinkEvent
import com.dugcanlift.liftkit.link.LinkMessage
import com.dugcanlift.liftkit.link.LinkPayloads
import com.dugcanlift.liftkit.link.LinkRole
import com.dugcanlift.liftkit.link.LinkSession
import com.dugcanlift.liftkit.link.LinkState
import com.dugcanlift.liftkit.link.MessageType
import com.dugcanlift.liftkit.link.RunHeartRate
import com.dugcanlift.macrocalc.data.LiveHeartRateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's side of LIFT Link version 5, with real [LinkSession]s and no radio: what is sent to a
 * watch, when, and to which links. A version-4 watch is a peer whose HELLO_ACK names 4, exactly as an
 * old build sends it.
 */
class WatchLinkHeartRateTest {
    private val phoneNonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val watchNonce = byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16)

    /** A phone and a v5 watch that already know each other. */
    private fun phoneLinkedAt5(): LinkSession {
        val phone = LinkSession(LinkRole.CENTRAL, "Pixel 8", phoneNonce, alreadyPaired = true)
        val watch = LinkSession(LinkRole.PERIPHERAL, "Pixel Watch 2", watchNonce, alreadyPaired = true)
        watch.start()
        phone.receive(watch.receive(phone.start().send.single()).send.single())
        assertEquals(LinkState.READY, phone.state)
        assertEquals(5, phone.negotiatedVersion)
        return phone
    }

    /** A phone whose watch is an older build: the handshake settles on [watchMax]. */
    private fun phoneLinkedBelow(watchMax: Int): LinkSession {
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

    private class Rig(var session: LinkSession?, var name: String? = "Pixel Watch 2") {
        val sent = ArrayList<LinkMessage>()
        val relay = WatchHeartRateRelay({ session }, { sent.add(it) }, { name })
        val types: List<MessageType?> get() = sent.map { it.known }
    }

    // ---- who counts as a source ---------------------------------------------------------------

    @Test
    fun `aV4WatchIsNotAHeartRateSource`() {
        val rig = Rig(phoneLinkedBelow(4))
        // No watch row: settled below 5, the watch is not offered.
        assertNull(rig.relay.linkedName())
        assertNull(WatchHeartRateRules.settingsName(paired = true, name = "Old watch", lastLinkVersion = 4))
        // And no RUN_HR_START is sent, whatever the run asks.
        assertFalse(rig.relay.begin { _, _ -> })
        assertFalse(rig.relay.refresh())
        rig.relay.onLinkReady()
        rig.relay.end()
        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun `a v5 link is a source and carries the paired watch's name`() {
        val rig = Rig(phoneLinkedAt5())
        assertEquals("Pixel Watch 2", rig.relay.linkedName())
    }

    @Test
    fun `no link, a link not yet ready, or no watch name still never lists a watch that is not linked`() {
        val rig = Rig(null)
        assertNull(rig.relay.linkedName())
        val connecting = LinkSession(LinkRole.CENTRAL, "Pixel 8", phoneNonce, alreadyPaired = true).also { it.start() }
        rig.session = connecting
        assertNull(rig.relay.linkedName())
        assertFalse(rig.relay.begin { _, _ -> })
        rig.session = phoneLinkedAt5()
        rig.name = "  "
        assertEquals(WatchHeartRateRules.FALLBACK_NAME, rig.relay.linkedName())
    }

    @Test
    fun `the settings row needs a paired watch whose link last settled at 5 or later`() {
        assertEquals("Pixel Watch 2", WatchHeartRateRules.settingsName(true, "Pixel Watch 2", 5))
        assertEquals("Pixel Watch 2", WatchHeartRateRules.settingsName(true, "Pixel Watch 2", 6))
        assertNull(WatchHeartRateRules.settingsName(true, "Pixel Watch 2", 4))
        assertNull(WatchHeartRateRules.settingsName(true, "Pixel Watch 2", null)) // never linked since the update
        assertNull(WatchHeartRateRules.settingsName(false, "Pixel Watch 2", 5)) // forgotten
        assertEquals("Wear watch", WatchHeartRateRules.settingsName(true, null, 5))
        assertEquals("Wear watch", WatchHeartRateRules.settingsName(true, " ", 5))
    }

    // ---- sending ---------------------------------------------------------------------------------

    @Test
    fun `begin sends one empty RUN_HR_START and end sends one RUN_HR_STOP`() {
        val rig = Rig(phoneLinkedAt5())
        assertTrue(rig.relay.begin { _, _ -> })
        assertEquals(listOf(MessageType.RUN_HR_START), rig.types)
        assertEquals(0, rig.sent.single().payload.size)
        rig.relay.end()
        assertEquals(listOf(MessageType.RUN_HR_START, MessageType.RUN_HR_STOP), rig.types)
    }

    @Test
    fun `stop is idempotent and a stop with nothing begun sends nothing`() {
        val rig = Rig(phoneLinkedAt5())
        rig.relay.end()
        assertTrue(rig.sent.isEmpty())
        rig.relay.begin { _, _ -> }
        rig.relay.end()
        rig.relay.end()
        rig.relay.end()
        assertEquals(listOf(MessageType.RUN_HR_START, MessageType.RUN_HR_STOP), rig.types)
    }

    @Test
    fun `a refresh asks again while a run wants it, and never after it ended`() {
        val rig = Rig(phoneLinkedAt5())
        assertFalse(rig.relay.refresh()) // nothing wants it yet
        rig.relay.begin { _, _ -> }
        assertTrue(rig.relay.refresh())
        assertTrue(rig.relay.refresh())
        assertEquals(List(3) { MessageType.RUN_HR_START }, rig.types)
        rig.relay.end()
        assertFalse(rig.relay.refresh())
        assertEquals(1, rig.types.count { it == MessageType.RUN_HR_STOP })
        assertEquals(4, rig.sent.size)
    }

    @Test
    fun `a reconnect asks again while the run wants heart rate`() {
        val rig = Rig(phoneLinkedAt5())
        rig.relay.begin { _, _ -> }
        // The link drops: the session is gone, and a refresh in the meantime says nothing and breaks nothing.
        rig.session = null
        assertFalse(rig.relay.refresh())
        assertNull(rig.relay.linkedName())
        assertEquals(1, rig.sent.size)
        // The link comes back (a new session, READY again): the transport tells the relay.
        rig.session = phoneLinkedAt5()
        rig.relay.onLinkReady()
        assertEquals(listOf(MessageType.RUN_HR_START, MessageType.RUN_HR_START), rig.types)
    }

    @Test
    fun `a reconnect with no run wanting heart rate asks nothing`() {
        val rig = Rig(phoneLinkedAt5())
        rig.relay.onLinkReady() // never begun
        rig.relay.begin { _, _ -> }
        rig.relay.end()
        rig.relay.onLinkReady() // ended
        assertEquals(listOf(MessageType.RUN_HR_START, MessageType.RUN_HR_STOP), rig.types)
    }

    @Test
    fun `a reconnect that settles below 5 sends nothing, and the run keeps its source`() {
        val rig = Rig(phoneLinkedAt5())
        rig.relay.begin { _, _ -> }
        rig.session = phoneLinkedBelow(4) // the watch was downgraded
        rig.relay.onLinkReady()
        assertEquals(1, rig.sent.size)
        rig.relay.end() // the run ends: no STOP to a link that has never heard of one
        assertEquals(1, rig.sent.size)
    }

    @Test
    fun `ending while the link is down clears the wish, so a later reconnect does not start a finished run`() {
        val rig = Rig(phoneLinkedAt5())
        rig.relay.begin { _, _ -> }
        rig.session = null
        rig.relay.end()
        rig.session = phoneLinkedAt5()
        rig.relay.onLinkReady()
        assertEquals(listOf(MessageType.RUN_HR_START), rig.types)
    }

    @Test
    fun `a start that cannot be sent holds no wish, no callback and no later start`() {
        val rig = Rig(null)
        assertFalse(rig.relay.begin { _, _ -> error("never called") })
        rig.session = phoneLinkedAt5()
        rig.relay.onLinkReady()
        rig.relay.onReading(RunHeartRate(140, 1_762_800_000L))
        assertTrue(rig.sent.isEmpty())
    }

    // ---- readings --------------------------------------------------------------------------------

    @Test
    fun `readings reach the run while it wants them and not after it ended`() {
        val rig = Rig(phoneLinkedAt5())
        val got = ArrayList<Pair<Int, Long>>()
        rig.relay.begin { bpm, at -> got.add(bpm to at) }
        rig.relay.onReading(RunHeartRate(142, 1_762_800_000L))
        rig.relay.end()
        rig.relay.onReading(RunHeartRate(150, 1_762_800_005L))
        assertEquals(listOf(142 to 1_762_800_000L), got)
    }

    @Test
    fun `a garbled reading is a refusal and the link stays up`() {
        val phone = phoneLinkedAt5()
        val reaction = phone.receive(LinkMessage(MessageType.RUN_HR, byteArrayOf(0, 0, 1)))
        assertEquals(LinkError.MALFORMED_FRAME, LinkPayloads.decodeError(reaction.send.single().payload).error)
        assertEquals(LinkState.READY, phone.state)
        assertTrue(reaction.events.none { it is LinkEvent.Failed })
    }

    @Test
    fun `a reading is delivered from the link as a RunHeartRateReceived event`() {
        val phone = phoneLinkedAt5()
        val wire = LinkMessage(MessageType.RUN_HR, LinkPayloads.encodeRunHeartRate(RunHeartRate(142, 1_762_800_000L)))
        assertEquals(listOf(LinkEvent.RunHeartRateReceived(RunHeartRate(142, 1_762_800_000L))), phone.receive(wire).events)
    }

    // ---- the rules ---------------------------------------------------------------------------------

    @Test
    fun `the keepalive is well inside the watch's five minutes and at least every two`() {
        assertTrue(WatchHeartRateRules.KEEPALIVE_MS <= 2 * 60_000L)
        assertTrue(WatchHeartRateRules.KEEPALIVE_MS > 0)
    }

    @Test
    fun `a reading keeps its own time, in milliseconds`() {
        val now = 1_762_800_010_000L
        assertEquals(WatchHeartRateRules.Reading(142, 1_762_800_004_000L), WatchHeartRateRules.reading(142, 1_762_800_004L, now))
    }

    @Test
    fun `a reading from a watch clock ahead of the phone is clamped to now, so a dead one cannot stay fresh`() {
        val now = 1_762_800_010_000L
        assertEquals(now, WatchHeartRateRules.reading(142, 1_762_800_000L + 3_600, now)!!.atEpochMs)
        assertEquals(now, WatchHeartRateRules.reading(142, Long.MAX_VALUE, now)!!.atEpochMs)
    }

    @Test
    fun `an old reading stays old, so the stale rule hides it`() {
        val now = 1_762_800_010_000L
        assertEquals(1_762_799_000_000L, WatchHeartRateRules.reading(142, 1_762_799_000L, now)!!.atEpochMs)
    }

    @Test
    fun `a value that is not a pulse is no reading`() {
        val now = 1_762_800_010_000L
        assertNull(WatchHeartRateRules.reading(0, 1_762_800_000L, now))
        assertNull(WatchHeartRateRules.reading(-1, 1_762_800_000L, now))
        assertNull(WatchHeartRateRules.reading(65_535, 1_762_800_000L, now))
        assertEquals(300, WatchHeartRateRules.reading(300, 1_762_800_000L, now)!!.bpm)
    }

    @Test
    fun `a start that could not be sent leaves the run with no live source, and never another one`() {
        val watch = LiveHeartRateSource.Watch
        assertEquals(watch, WatchHeartRateRules.sourceAfterStart(watch, requestSent = true))
        assertNull(WatchHeartRateRules.sourceAfterStart(watch, requestSent = false))
        val strap = LiveHeartRateSource.Device("AA:BB", "Polar H10")
        assertEquals(strap, WatchHeartRateRules.sourceAfterStart(strap, requestSent = false)) // not the watch's business
        assertNull(WatchHeartRateRules.sourceAfterStart(null, requestSent = false))
    }
}
