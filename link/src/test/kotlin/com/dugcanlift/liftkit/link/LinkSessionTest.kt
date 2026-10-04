package com.dugcanlift.liftkit.link

import org.junit.Assert.*
import org.junit.Test

class LinkSessionTest {
    private val phoneNonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val watchNonce = byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16)

    private fun phone(paired: Boolean = false) =
        LinkSession(LinkRole.CENTRAL, "Pixel 8", phoneNonce, paired)

    private fun watch(paired: Boolean = false) =
        LinkSession(LinkRole.PERIPHERAL, "Galaxy Watch6", watchNonce, paired)

    private fun errorIn(reaction: Reaction): LinkErrorMessage =
        LinkPayloads.decodeError(reaction.send.single { it.known == MessageType.ERROR }.payload)

    // ---- the handshake ----------------------------------------------------------------------

    @Test fun `the central opens and the peripheral answers`() {
        val phone = phone()
        val watch = watch()
        watch.start()
        val hello = phone.start().send.single()
        assertEquals(MessageType.HELLO, hello.known)

        val answer = watch.receive(hello)
        assertEquals(MessageType.HELLO_ACK, answer.send.single().known)
        assertEquals(LinkState.CONFIRMING, watch.state)
        assertEquals("Pixel 8", watch.peerName)

        phone.receive(answer.send.single())
        assertEquals(LinkState.CONFIRMING, phone.state)
        assertEquals("Galaxy Watch6", phone.peerName)
    }

    @Test fun `both ends show the same confirmation code, and neither sent it`() {
        val phone = phone()
        val watch = watch()
        watch.start()
        val watchReaction = watch.receive(phone.start().send.single())
        val phoneReaction = phone.receive(watchReaction.send.single())

        val onWatch = watchReaction.events.filterIsInstance<LinkEvent.ConfirmationCode>().single()
        val onPhone = phoneReaction.events.filterIsInstance<LinkEvent.ConfirmationCode>().single()
        assertEquals(onWatch.code, onPhone.code)
        assertEquals(PairingCode.of(phoneNonce, watchNonce), onPhone.code)
        assertEquals("Galaxy Watch6", onPhone.peerName)
        assertEquals("Pixel 8", onWatch.peerName)
    }

    @Test fun `pairing needs both humans, and completes in either order`() {
        forBothConfirmOrders { phone, watch ->
            assertEquals(LinkState.READY, phone.state)
            assertEquals(LinkState.READY, watch.state)
            assertNull(phone.confirmationCode)
            assertNull(watch.confirmationCode)
        }
    }

    @Test fun `one human confirming is not enough`() {
        val (phone, watch) = handshaken()
        phone.confirm(true)
        assertEquals(LinkState.CONFIRMING, phone.state)
        assertEquals(LinkState.CONFIRMING, watch.state)
    }

    @Test fun `a rejection on the watch ends it for both, and nothing is remembered`() {
        val (phone, watch) = handshaken()
        val confirm = phone.confirm(true)
        watch.receive(confirm.send.single())
        val rejection = watch.confirm(false)
        assertEquals(LinkState.FAILED, watch.state)
        assertTrue(rejection.events.contains(LinkEvent.PairingRejected))

        val onPhone = phone.receive(rejection.send.single())
        assertEquals(LinkState.FAILED, phone.state)
        assertTrue(onPhone.events.contains(LinkEvent.PairingRejected))
        assertNull(phone.confirmationCode)
    }

    @Test fun `a remembered watch reconnects with no code and nobody tapping anything`() {
        val phone = phone(paired = true)
        val watch = watch(paired = true)
        watch.start()
        val watchReaction = watch.receive(phone.start().send.single())
        val phoneReaction = phone.receive(watchReaction.send.single())
        assertEquals(LinkState.READY, watch.state)
        assertEquals(LinkState.READY, phone.state)
        assertTrue(watchReaction.events.any { it is LinkEvent.Paired })
        assertTrue(phoneReaction.events.any { it is LinkEvent.Paired })
        assertTrue(watchReaction.events.none { it is LinkEvent.ConfirmationCode })
        assertNull(phone.confirmationCode)
    }

    // ---- versions ---------------------------------------------------------------------------

    @Test fun `a watch that only speaks a later version is refused politely, not misread`() {
        val watch = watch()
        watch.start()
        val futureHello = LinkMessage(
            MessageType.HELLO,
            LinkPayloads.encodeHello(Hello(9, 9, null, "Watch from 2030", phoneNonce)),
        )
        val reaction = watch.receive(futureHello)
        assertEquals(LinkError.UNSUPPORTED_VERSION, errorIn(reaction).error)
        assertEquals(LinkState.FAILED, watch.state)
        assertTrue(reaction.events.any { it is LinkEvent.Failed })
    }

    @Test fun `a phone told an impossible version refuses it`() {
        val phone = phone()
        phone.start()
        val bad = LinkMessage(
            MessageType.HELLO_ACK,
            LinkPayloads.encodeHello(Hello(1, 9, 9, "Watch from 2030", watchNonce)),
        )
        val reaction = phone.receive(bad)
        assertEquals(LinkError.UNSUPPORTED_VERSION, errorIn(reaction).error)
        assertEquals(LinkState.FAILED, phone.state)
    }

    @Test fun `the version both ends settle on is the highest they share`() {
        val (phone, watch) = handshaken()
        assertEquals(LinkProtocol.VERSION, phone.negotiatedVersion)
        assertEquals(LinkProtocol.VERSION, watch.negotiatedVersion)
    }

    @Test fun `an error during the handshake ends the link`() {
        val phone = phone()
        phone.start()
        val reaction = phone.receive(
            LinkMessage(MessageType.ERROR, LinkPayloads.encodeError(LinkErrorMessage(LinkError.UNSUPPORTED_VERSION, "nope")))
        )
        assertEquals(LinkState.FAILED, phone.state)
        assertTrue(reaction.events.single() is LinkEvent.Failed)
    }

    // ---- the guard in front of a training log ------------------------------------------------

    @Test fun `a plan pushed before pairing is refused and never handed up`() {
        val watch = watch()
        watch.start()
        val reaction = watch.receive(LinkMessage(MessageType.PLAN_PUSHED, LinkPayloads.encodePlan(LinkFixtures.plan)))
        assertEquals(LinkError.NOT_PAIRED, errorIn(reaction).error)
        assertTrue(reaction.events.isEmpty())
        assertNotEquals(LinkState.READY, watch.state)
    }

    @Test fun `a finished session sent before pairing is refused and never handed up`() {
        val phone = phone()
        phone.start()
        val reaction = phone.receive(LinkMessage(MessageType.SESSION_FINISHED, LinkPayloads.encodeSession(LinkFixtures.session)))
        assertEquals(LinkError.NOT_PAIRED, errorIn(reaction).error)
        assertTrue(reaction.events.isEmpty())
    }

    @Test fun `a message travelling the wrong way is refused`() {
        val (phone, watch) = paired()
        // Only the phone pushes plans; a watch that pushed one would be a watch pretending to be
        // a phone, which is a thing to refuse rather than to accommodate.
        val reaction = phone.receive(LinkMessage(MessageType.PLAN_PUSHED, LinkPayloads.encodePlan(LinkFixtures.plan)))
        assertEquals(LinkError.UNEXPECTED_MESSAGE, errorIn(reaction).error)
        assertEquals(LinkState.READY, phone.state)
        assertEquals(LinkState.READY, watch.state)
    }

    @Test fun `the roles are enforced on the way out too`() {
        val (phone, watch) = paired()
        assertThrows(IllegalStateException::class.java) { watch.pushPlan(LinkFixtures.plan) }
        assertThrows(IllegalStateException::class.java) { phone.requestPlan() }
        assertThrows(IllegalStateException::class.java) { phone.finishSession(LinkFixtures.session) }
    }

    @Test fun `nothing may be sent before the link is ready`() {
        val phone = phone()
        assertThrows(IllegalStateException::class.java) { phone.pushPlan(LinkFixtures.plan) }
        phone.start()
        assertThrows(IllegalStateException::class.java) { phone.pushPlan(LinkFixtures.plan) }
    }

    // ---- forward compatibility ---------------------------------------------------------------

    @Test fun `an opcode this build has never heard of is refused without dropping the link`() {
        val (phone, _) = paired()
        val reaction = phone.receive(LinkMessage(0x7E, byteArrayOf(1, 2, 3)))
        assertEquals(LinkError.UNKNOWN_TYPE, errorIn(reaction).error)
        assertEquals(LinkState.READY, phone.state)
        assertTrue(reaction.events.isEmpty())
    }

    @Test fun `a refusal from the peer while the link is up is reported, not fatal`() {
        val (phone, _) = paired()
        val reaction = phone.receive(
            LinkMessage(MessageType.ERROR, LinkPayloads.encodeError(LinkErrorMessage(LinkError.UNKNOWN_TYPE, "0x7e")))
        )
        assertEquals(LinkState.READY, phone.state)
        assertEquals(LinkError.UNKNOWN_TYPE, (reaction.events.single() as LinkEvent.Refused).error.error)
    }

    @Test fun `a payload that will not decode is refused without ending a working link`() {
        val (phone, _) = paired()
        val reaction = phone.receive(LinkMessage(MessageType.SESSION_FINISHED, byteArrayOf(0, 1)))
        assertEquals(LinkError.MALFORMED_FRAME, errorIn(reaction).error)
        assertEquals(LinkState.READY, phone.state)
        assertTrue(reaction.events.isEmpty())
    }

    // ---- what flows once it is up --------------------------------------------------------------

    @Test fun `the plan the phone pushes is the plan the watch reads`() {
        val (phone, watch) = paired()
        val event = watch.receive(phone.pushPlan(LinkFixtures.plan)).events.single()
        assertEquals(LinkFixtures.plan, (event as LinkEvent.PlanReceived).plan)
    }

    @Test fun `the watch can ask for the plan, and report sets and a session back`() {
        val (phone, watch) = paired()
        assertEquals(LinkEvent.PlanRequested, phone.receive(watch.requestPlan()).events.single())
        assertEquals(
            LinkFixtures.report,
            (phone.receive(watch.reportSet(LinkFixtures.report)).events.single() as LinkEvent.SetLogged).report,
        )
        assertEquals(
            LinkFixtures.session,
            (phone.receive(watch.finishSession(LinkFixtures.session)).events.single() as LinkEvent.SessionFinished).session,
        )
    }

    @Test fun `an acknowledgement names what was stored and what happened to it`() {
        val (phone, watch) = paired()
        val ack = phone.acknowledge(LinkAck(MessageType.SESSION_FINISHED, LinkFixtures.SESSION_ID, 4, AckOutcome.INSERTED))
        val event = watch.receive(ack).events.single() as LinkEvent.Acknowledged
        assertEquals(AckOutcome.INSERTED, event.ack.outcome)
        assertEquals(LinkFixtures.SESSION_ID, event.ack.id)
        assertEquals(4, event.ack.revision)
    }

    @Test fun `a session is single use`() {
        val phone = phone()
        phone.start()
        assertThrows(IllegalStateException::class.java) { phone.start() }
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun handshaken(): Pair<LinkSession, LinkSession> {
        val phone = phone()
        val watch = watch()
        watch.start()
        val answer = watch.receive(phone.start().send.single())
        phone.receive(answer.send.single())
        return phone to watch
    }

    private fun paired(): Pair<LinkSession, LinkSession> {
        val (phone, watch) = handshaken()
        val confirm = phone.confirm(true)
        watch.receive(confirm.send.single())
        val result = watch.confirm(true)
        phone.receive(result.send.single())
        return phone to watch
    }

    /** Whoever taps first, the other end still completes: there is no privileged order. */
    private fun forBothConfirmOrders(check: (LinkSession, LinkSession) -> Unit) {
        run {
            val (phone, watch) = handshaken()
            watch.receive(phone.confirm(true).send.single())
            phone.receive(watch.confirm(true).send.single())
            check(phone, watch)
        }
        run {
            val (phone, watch) = handshaken()
            val result = watch.confirm(true)
            phone.receive(result.send.single())
            watch.receive(phone.confirm(true).send.single())
            check(phone, watch)
        }
    }
}
