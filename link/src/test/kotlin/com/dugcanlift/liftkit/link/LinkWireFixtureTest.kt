package com.dugcanlift.liftkit.link

import org.junit.Assert.*
import org.junit.Test

/**
 * The anti-drift test, and the only one here that is about more than this repo agreeing with
 * itself.
 *
 * This package is compiled into two applications from two repositories (see [LinkProtocol]). Both
 * carry this test and the same `fixtures/link-wire.txt`, so a change to either copy that alters a
 * single byte on the wire fails in the repo where it was made — before a phone and a watch meet
 * and half understand each other.
 *
 * **Do not regenerate the fixture to make this pass.** Regenerating it from the code under test is
 * exactly the move that would turn this into another round trip against itself. If the bytes must
 * change, that is a protocol version bump, and both repos change together.
 */
class LinkWireFixtureTest {
    private val fixture: Map<String, ByteArray> by lazy {
        javaClass.getResourceAsStream("/fixtures/link-wire.txt")!!
            .bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val (name, hex) = line.split(Regex("\\s+"), limit = 2)
                name to LinkFixtures.unhex(hex)
            }
    }

    private fun assertBytes(name: String, actual: ByteArray) =
        assertEquals("$name differs", LinkFixtures.hex(fixture.getValue(name)), LinkFixtures.hex(actual))

    @Test fun `the frame header this build writes is the one in the fixture`() {
        val frame = LinkFrame(
            LinkProtocol.VERSION, MessageType.PLAN_REQUEST.code,
            LinkFrame.FLAG_FIRST or LinkFrame.FLAG_LAST, 0, ByteArray(0),
        )
        assertBytes("frame.planRequest", LinkFrames.encode(frame))
    }

    @Test fun `hello encodes to the fixture's bytes`() = assertBytes("hello", LinkPayloads.encodeHello(LinkFixtures.hello))

    @Test fun `hello ack encodes to the fixture's bytes`() =
        assertBytes("helloAck", LinkPayloads.encodeHello(LinkFixtures.helloAck))

    @Test fun `the plan encodes to the fixture's bytes`() = assertBytes("plan", LinkPayloads.encodePlan(LinkFixtures.plan))

    @Test fun `the session encodes to the fixture's bytes`() =
        assertBytes("session", LinkPayloads.encodeSession(LinkFixtures.session))

    @Test fun `the set report encodes to the fixture's bytes`() =
        assertBytes("setLogged", LinkPayloads.encodeLoggedSet(LinkFixtures.report))

    @Test fun `ack and error encode to the fixture's bytes`() {
        assertBytes("ack", LinkPayloads.encodeAck(LinkFixtures.ack))
        assertBytes("error", LinkPayloads.encodeError(LinkFixtures.error))
    }

    @Test fun `the fixture's bytes decode to the canonical objects`() {
        assertEquals(LinkFixtures.hello, LinkPayloads.decodeHello(fixture.getValue("hello")))
        assertEquals(LinkFixtures.helloAck, LinkPayloads.decodeHello(fixture.getValue("helloAck")))
        assertEquals(LinkFixtures.plan, LinkPayloads.decodePlan(fixture.getValue("plan")))
        assertEquals(LinkFixtures.session, LinkPayloads.decodeSession(fixture.getValue("session")))
        assertEquals(LinkFixtures.report, LinkPayloads.decodeLoggedSet(fixture.getValue("setLogged")))
        assertEquals(LinkFixtures.ack, LinkPayloads.decodeAck(fixture.getValue("ack")))
        assertEquals(LinkFixtures.error, LinkPayloads.decodeError(fixture.getValue("error")))
    }

    @Test fun `the fixture says out loud that a blank field is absent`() {
        // The second prescribed set is `[null, 5]`. Its presence byte is 0x02 and it is followed
        // immediately by the two-byte reps — no weight bytes, not even zeroed ones. Reading it out
        // of the committed bytes rather than out of the encoder is the point.
        val plan = LinkPayloads.decodePlan(fixture.getValue("plan"))
        assertNull(plan.exercises.first().sets[1].weightKg)
        assertEquals(5, plan.exercises.first().sets[1].reps)
        assertEquals(PrescribedSet(), plan.exercises.first().sets[2])
    }
}
