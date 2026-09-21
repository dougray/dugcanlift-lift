package com.dugcanlift.liftkit.link

import org.junit.Assert.*
import org.junit.Test

/**
 * Two ends of the protocol talking to each other through an in-memory pipe: codec, framing,
 * fragmentation and state machine together, with nothing stubbed out but the radio.
 *
 * This is not a substitute for a real link — a BLE stack has its own opinions about MTUs, bonding
 * and when a write completes, and only hardware settles those. What it does prove is that the two
 * implementations of these rules agree with each other end to end, including at the 23-byte MTU
 * every BLE device is required to accept, where a whole training plan becomes a hundred writes.
 */
class LinkLoopbackTest {

    /** One end: a session, a codec, and an outbox of encoded frames waiting to be delivered. */
    private class Peer(role: LinkRole, name: String, nonce: ByteArray, paired: Boolean, mtu: Int) {
        val session = LinkSession(role, name, nonce, paired)
        val codec = LinkCodec().apply { frameBudget = LinkProtocol.frameBudget(mtu) }
        val outbox = ArrayDeque<ByteArray>()
        val events = mutableListOf<LinkEvent>()

        fun queue(reaction: Reaction) {
            events += reaction.events
            reaction.send.forEach { message -> codec.frames(message).forEach(outbox::addLast) }
        }

        fun queue(message: LinkMessage) {
            codec.frames(message).forEach(outbox::addLast)
        }
    }

    private class Pipe(mtu: Int, paired: Boolean = false) {
        val phone = Peer(LinkRole.CENTRAL, "Pixel 8", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), paired, mtu)
        val watch = Peer(LinkRole.PERIPHERAL, "Galaxy Watch6", byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16), paired, mtu)
        var framesDelivered = 0
            private set

        /** Drains both outboxes until neither end has anything left to say. */
        fun settle() {
            var moved = true
            while (moved) {
                moved = false
                if (deliver(phone, watch)) moved = true
                if (deliver(watch, phone)) moved = true
            }
        }

        private fun deliver(from: Peer, to: Peer): Boolean {
            val bytes = from.outbox.removeFirstOrNull() ?: return false
            framesDelivered++
            when (val event = to.codec.accept(bytes)) {
                is CodecEvent.Incomplete -> Unit
                is CodecEvent.Complete -> to.queue(to.session.receive(event.message))
                is CodecEvent.Refused -> to.queue(
                    Reaction(
                        send = listOf(LinkMessage(MessageType.ERROR, LinkPayloads.encodeError(LinkErrorMessage(event.error, event.detail)))),
                        state = to.session.state,
                    )
                )
            }
            return true
        }

        fun pair() {
            watch.session.start()
            phone.queue(phone.session.start())
            settle()
            phone.queue(phone.session.confirm(true))
            settle()
            watch.queue(watch.session.confirm(true))
            settle()
        }
    }

    @Test fun `a phone and a watch pair, and both end up ready`() {
        val pipe = Pipe(mtu = 23)
        pipe.pair()
        assertEquals(LinkState.READY, pipe.phone.session.state)
        assertEquals(LinkState.READY, pipe.watch.session.state)
        assertTrue(pipe.phone.events.any { it is LinkEvent.Paired })
        assertTrue(pipe.watch.events.any { it is LinkEvent.Paired })
    }

    @Test fun `the code both ends showed was the same number`() {
        val pipe = Pipe(mtu = 23)
        pipe.watch.session.start()
        pipe.phone.queue(pipe.phone.session.start())
        pipe.settle()
        val onPhone = pipe.phone.events.filterIsInstance<LinkEvent.ConfirmationCode>().single().code
        val onWatch = pipe.watch.events.filterIsInstance<LinkEvent.ConfirmationCode>().single().code
        assertEquals(onWatch, onPhone)
    }

    @Test fun `a whole plan crosses at the smallest MTU BLE guarantees, unchanged`() {
        val pipe = Pipe(mtu = 23)
        pipe.pair()
        pipe.phone.queue(pipe.phone.session.pushPlan(LinkFixtures.plan))
        pipe.settle()
        val received = pipe.watch.events.filterIsInstance<LinkEvent.PlanReceived>().single().plan
        assertEquals(LinkFixtures.plan, received)
        // The prescriptions that are the point of the exercise, checked after the round trip
        // rather than only at the encoder: reps with no weight, and a set that says nothing.
        val sets = received.exercises.first().sets
        assertNull(sets[1].weightKg)
        assertEquals(5, sets[1].reps)
        assertEquals(PrescribedSet(), sets[2])
    }

    @Test fun `a plan that takes many frames at 23 takes few at 247, and arrives the same`() {
        val small = Pipe(mtu = 23).also { it.pair() }
        val large = Pipe(mtu = LinkProtocol.REQUESTED_MTU).also { it.pair() }
        val before = small.framesDelivered
        small.phone.queue(small.phone.session.pushPlan(LinkFixtures.plan))
        small.settle()
        val smallFrames = small.framesDelivered - before

        val beforeLarge = large.framesDelivered
        large.phone.queue(large.phone.session.pushPlan(LinkFixtures.plan))
        large.settle()
        val largeFrames = large.framesDelivered - beforeLarge

        assertTrue("$smallFrames should exceed $largeFrames", smallFrames > largeFrames)
        assertEquals(
            small.watch.events.filterIsInstance<LinkEvent.PlanReceived>().single().plan,
            large.watch.events.filterIsInstance<LinkEvent.PlanReceived>().single().plan,
        )
    }

    @Test fun `a full session comes back the other way, sides and blanks intact`() {
        val pipe = Pipe(mtu = 23)
        pipe.pair()
        pipe.watch.queue(pipe.watch.session.reportSet(LinkFixtures.report))
        pipe.watch.queue(pipe.watch.session.finishSession(LinkFixtures.session))
        pipe.settle()
        assertEquals(LinkFixtures.report, pipe.phone.events.filterIsInstance<LinkEvent.SetLogged>().single().report)
        val session = pipe.phone.events.filterIsInstance<LinkEvent.SessionFinished>().single().session
        assertEquals(LinkFixtures.session, session)
        assertEquals(listOf(LogSide.LEFT, LogSide.RIGHT), session.exercises[1].sets.map { it.side })
        assertNull(session.exercises[0].sets[1].weightKg)
    }

    @Test fun `the watch asks and the phone answers, over the same one channel`() {
        val pipe = Pipe(mtu = 23)
        pipe.pair()
        pipe.watch.queue(pipe.watch.session.requestPlan())
        pipe.settle()
        assertTrue(pipe.phone.events.contains(LinkEvent.PlanRequested))
        pipe.phone.queue(pipe.phone.session.pushPlan(LinkFixtures.plan))
        pipe.settle()
        assertEquals(LinkFixtures.plan, pipe.watch.events.filterIsInstance<LinkEvent.PlanReceived>().single().plan)
    }

    @Test fun `a remembered pair needs no taps at all`() {
        val pipe = Pipe(mtu = LinkProtocol.REQUESTED_MTU, paired = true)
        pipe.watch.session.start()
        pipe.phone.queue(pipe.phone.session.start())
        pipe.settle()
        assertEquals(LinkState.READY, pipe.phone.session.state)
        assertEquals(LinkState.READY, pipe.watch.session.state)
        assertTrue(pipe.phone.events.none { it is LinkEvent.ConfirmationCode })
    }

    @Test fun `a corrupted frame mid-plan is refused and the link stays up`() {
        val pipe = Pipe(mtu = 23)
        pipe.pair()
        pipe.phone.queue(pipe.phone.session.pushPlan(LinkFixtures.plan))
        // Flip a byte in the middle of the plan's frames, as a stack handing up a short write would.
        val victim = pipe.phone.outbox.elementAt(3)
        victim[LinkFrame.HEADER_BYTES] = (victim[LinkFrame.HEADER_BYTES].toInt() xor 0xFF).toByte()
        pipe.settle()
        assertTrue(pipe.watch.events.none { it is LinkEvent.PlanReceived })
        assertEquals(LinkState.READY, pipe.watch.session.state)
        assertEquals(LinkState.READY, pipe.phone.session.state)

        // …and the phone can simply send it again.
        pipe.phone.queue(pipe.phone.session.pushPlan(LinkFixtures.plan))
        pipe.settle()
        assertEquals(LinkFixtures.plan, pipe.watch.events.filterIsInstance<LinkEvent.PlanReceived>().single().plan)
    }

    @Test fun `a watch speaking a version this phone does not know is refused, and neither pairs`() {
        val pipe = Pipe(mtu = 23)
        pipe.watch.session.start()
        // A HELLO from a build that only speaks version 9, handed straight to the real watch end.
        pipe.phone.queue(
            LinkMessage(MessageType.HELLO, LinkPayloads.encodeHello(Hello(9, 9, null, "Phone from 2030", byteArrayOf(1, 1, 1, 1, 1, 1, 1, 1))))
        )
        pipe.settle()
        assertEquals(LinkState.FAILED, pipe.watch.session.state)
        assertTrue(pipe.watch.events.filterIsInstance<LinkEvent.Failed>().single().error.error == LinkError.UNSUPPORTED_VERSION)
    }
}
