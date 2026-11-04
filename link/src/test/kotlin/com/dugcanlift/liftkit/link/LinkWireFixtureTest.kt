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
        // Built at an explicit 1 rather than at LinkProtocol.VERSION: version 1's header shape is
        // frozen for ever (that is the whole point of the version being byte 0), and this line is
        // what pins it. A version bump must not move it -- if it did, the guarantee would be a
        // comment rather than a test.
        fun planRequest(version: Int) = LinkFrames.encode(
            LinkFrame(
                version, MessageType.PLAN_REQUEST.code,
                LinkFrame.FLAG_FIRST or LinkFrame.FLAG_LAST, 0, ByteArray(0),
            )
        )
        assertBytes("frame.planRequest", planRequest(LinkProtocol.MIN_SUPPORTED_VERSION))
        assertBytes("frame.planRequestV2", planRequest(LinkProtocol.VERSION_WITH_PLAN_SIDES))
        assertBytes("frame.planRequestV3", planRequest(LinkProtocol.VERSION_WITH_SET_TYPES))
        assertBytes("frame.planRequestV4", planRequest(LinkProtocol.VERSION_WITH_HEART_RATE))
    }

    @Test fun `hello encodes to the fixture's bytes`() = assertBytes("hello", LinkPayloads.encodeHello(LinkFixtures.hello))

    @Test fun `hello ack encodes to the fixture's bytes`() =
        assertBytes("helloAck", LinkPayloads.encodeHello(LinkFixtures.helloAck))

    @Test fun `the plan encodes to the fixture's bytes`() = assertBytes("plan", LinkPayloads.encodePlan(LinkFixtures.plan))

    @Test fun `a plan carrying the coach's sides encodes to the fixture's bytes`() =
        assertBytes("plan.sides", LinkPayloads.encodePlan(LinkFixtures.sidedPlan))

    @Test fun `a plan carrying the coach's set types encodes to the fixture's bytes`() =
        assertBytes("plan.setTypes", LinkPayloads.encodePlan(LinkFixtures.setTypesPlan))

    /**
     * The reason version 3 is a bump and not a break: the `plan` and `plan.sides` lines predate set
     * types, and both still come out of the encoder unchanged -- and stripping set types from the
     * version-3 plan leaves no version-3 bit set anywhere in it.
     */
    @Test fun `a plan with no set types is byte for byte what version 2 wrote`() {
        assertEquals(false, LinkFixtures.plan.prescribesSetTypes)
        assertEquals(false, LinkFixtures.sidedPlan.prescribesSetTypes)
        assertBytes("plan", LinkPayloads.encodePlan(LinkFixtures.plan.withoutSetTypes()))
        assertBytes("plan.sides", LinkPayloads.encodePlan(LinkFixtures.sidedPlan.withoutSetTypes()))
        assertEquals(true, LinkFixtures.setTypesPlan.prescribesSetTypes)
        val stripped = LinkPayloads.decodePlan(LinkPayloads.encodePlan(LinkFixtures.setTypesPlan.withoutSetTypes()))
        assertEquals(false, stripped.prescribesSetTypes)
    }

    /**
     * The reason version 2 is a bump and not a break: a plan that says nothing about sides is the
     * same bytes it always was. Read out of the committed fixture rather than out of the encoder,
     * because that is the claim -- the `plan` line predates sides entirely.
     */
    @Test fun `a plan with no sides is byte for byte what version 1 wrote`() {
        assertBytes("plan", LinkPayloads.encodePlan(LinkFixtures.plan))
        assertEquals(false, LinkFixtures.plan.prescribesSides)
        assertEquals(
            LinkFixtures.hex(fixture.getValue("plan")),
            LinkFixtures.hex(LinkPayloads.encodePlan(LinkFixtures.plan.withoutSides())),
        )
    }

    @Test fun `the session encodes to the fixture's bytes`() =
        assertBytes("session", LinkPayloads.encodeSession(LinkFixtures.session))

    @Test fun `a session carrying heart rate encodes to the fixture's bytes`() =
        assertBytes("session.heartRate", LinkPayloads.encodeSession(LinkFixtures.heartRateSession))

    /**
     * The reason version 4 is a bump and not a break: the `session` line predates heart rate and
     * still comes out of the encoder unchanged, and stripping heart rate from the version-4 session
     * gives exactly the bytes of the same session with none -- which is what a version-3 phone gets.
     */
    @Test fun `a session with no heart rate is byte for byte what version 3 wrote`() {
        assertEquals(false, LinkFixtures.session.recordsHeartRate)
        assertBytes("session", LinkPayloads.encodeSession(LinkFixtures.session))
        assertBytes("session", LinkPayloads.encodeSession(LinkFixtures.session.withoutHeartRate()))
        assertEquals(true, LinkFixtures.heartRateSession.recordsHeartRate)
        assertEquals(
            LinkFixtures.hex(LinkPayloads.encodeSession(LinkFixtures.session.copy(revision = 2))),
            LinkFixtures.hex(LinkPayloads.encodeSession(LinkFixtures.heartRateSession.withoutHeartRate())),
        )
    }

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
        assertEquals(LinkFixtures.sidedPlan, LinkPayloads.decodePlan(fixture.getValue("plan.sides")))
        assertEquals(LinkFixtures.setTypesPlan, LinkPayloads.decodePlan(fixture.getValue("plan.setTypes")))
        assertEquals(LinkFixtures.session, LinkPayloads.decodeSession(fixture.getValue("session")))
        assertEquals(LinkFixtures.heartRateSession, LinkPayloads.decodeSession(fixture.getValue("session.heartRate")))
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
