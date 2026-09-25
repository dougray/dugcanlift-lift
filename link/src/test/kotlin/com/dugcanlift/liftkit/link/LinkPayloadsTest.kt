package com.dugcanlift.liftkit.link

import org.junit.Assert.*
import org.junit.Test

class LinkPayloadsTest {

    // ---- the rule the whole encoding exists for ------------------------------------------

    @Test fun `a prescription of reps alone comes back as reps alone, with no weight`() {
        // PLAN-FORMAT's `[null, 5]`: five reps, you pick the weight.
        val set = LinkPayloads.decodePlan(
            LinkPayloads.encodePlan(onePrescribed(PrescribedSet(reps = 5)))
        ).exercises.single().sets.single()
        assertEquals(5, set.reps)
        assertNull(set.weightKg)
        assertNull(set.rpe)
        assertNull(set.restSeconds)
    }

    @Test fun `a set that prescribes nothing is legal and stays empty`() {
        val set = LinkPayloads.decodePlan(
            LinkPayloads.encodePlan(onePrescribed(PrescribedSet()))
        ).exercises.single().sets.single()
        assertEquals(PrescribedSet(), set)
    }

    @Test fun `an absent field costs one presence byte and no value bytes`() {
        val empty = LinkPayloads.encodePlan(onePrescribed(PrescribedSet()))
        val withReps = LinkPayloads.encodePlan(onePrescribed(PrescribedSet(reps = 5)))
        // Two bytes of difference, which is the u16 reps and nothing else: an absent field is
        // physically absent on the wire, not a zero waiting to be read as one.
        assertEquals(2, withReps.size - empty.size)
    }

    @Test fun `absent rest seconds is not zero rest`() {
        val set = LinkPayloads.decodePlan(
            LinkPayloads.encodePlan(onePrescribed(PrescribedSet(reps = 5, restSeconds = null)))
        ).exercises.single().sets.single()
        assertNull(set.restSeconds)
    }

    @Test fun `zero rest seconds cannot be encoded at all`() {
        assertThrows(IllegalArgumentException::class.java) {
            LinkPayloads.encodePlan(onePrescribed(PrescribedSet(restSeconds = 0)))
        }
    }

    @Test fun `zero reps cannot be encoded at all`() {
        assertThrows(IllegalArgumentException::class.java) {
            LinkPayloads.encodePlan(onePrescribed(PrescribedSet(reps = 0)))
        }
    }

    @Test fun `a logged set with no weight keeps no weight`() {
        val session = FinishedSession(
            "s", 1, "2026-09-21", "Upper A", 0,
            listOf(FinishedExercise("Pull-up", sets = listOf(LoggedSet(reps = 8))))
        )
        val set = LinkPayloads.decodeSession(LinkPayloads.encodeSession(session)).exercises.single().sets.single()
        assertNull(set.weightKg)
        assertEquals(8, set.reps)
    }

    // ---- weights and RPE --------------------------------------------------------------------

    @Test fun `weight travels as whole grams and comes back to the gram`() {
        assertEquals(83915L, LinkPayloads.gramsOf(83.915))
        assertEquals(83.915, LinkPayloads.kgOf(83915L), 0.0)
        val set = LinkPayloads.decodePlan(
            LinkPayloads.encodePlan(onePrescribed(PrescribedSet(weightKg = 83.915)))
        ).exercises.single().sets.single()
        assertEquals(83.915, set.weightKg!!, 1e-9)
    }

    @Test fun `a bodyweight zero is a weight and survives`() {
        val set = LinkPayloads.decodePlan(
            LinkPayloads.encodePlan(onePrescribed(PrescribedSet(weightKg = 0.0, reps = 10)))
        ).exercises.single().sets.single()
        assertEquals(0.0, set.weightKg!!, 0.0)   // present and zero is not the same as absent
    }

    @Test fun `a negative or non-finite weight is refused at the encoder`() {
        assertThrows(IllegalArgumentException::class.java) { LinkPayloads.gramsOf(-1.0) }
        assertThrows(IllegalArgumentException::class.java) { LinkPayloads.gramsOf(Double.NaN) }
    }

    @Test fun `RPE travels in tenths and keeps a half`() {
        assertEquals(85, LinkPayloads.rpeTenths(8.5))
        val set = LinkPayloads.decodePlan(
            LinkPayloads.encodePlan(onePrescribed(PrescribedSet(rpe = 8.5)))
        ).exercises.single().sets.single()
        assertEquals(8.5, set.rpe!!, 1e-9)
    }

    @Test fun `an RPE outside one to ten is refused`() {
        assertThrows(IllegalArgumentException::class.java) { LinkPayloads.rpeTenths(0.0) }
        assertThrows(IllegalArgumentException::class.java) { LinkPayloads.rpeTenths(10.5) }
    }

    // ---- identity and sides -----------------------------------------------------------------

    @Test fun `blank equipment is absent in both directions`() {
        val plan = LinkPayloads.decodePlan(LinkPayloads.encodePlan(onePrescribed(PrescribedSet(reps = 1), equipment = "")))
        assertNull(plan.exercises.single().equipment)
    }

    @Test fun `equipment is carried, because a cable pulldown is not a machine pulldown`() {
        val plan = LinkPayloads.decodePlan(
            LinkPayloads.encodePlan(onePrescribed(PrescribedSet(reps = 1), equipment = "Cable"))
        )
        assertEquals("Cable", plan.exercises.single().equipment)
    }

    @Test fun `a set with no side comes back with no side, which means both`() {
        val session = FinishedSession(
            "s", 1, "2026-09-21", "", 0,
            listOf(FinishedExercise("Row", sets = listOf(LoggedSet(reps = 8))))
        )
        assertNull(LinkPayloads.decodeSession(LinkPayloads.encodeSession(session)).exercises.single().sets.single().side)
    }

    @Test fun `left and right survive and are not merged`() {
        val decoded = LinkPayloads.decodeSession(LinkPayloads.encodeSession(LinkFixtures.session))
        val sides = decoded.exercises[1].sets.map { it.side }
        assertEquals(listOf(LogSide.LEFT, LogSide.RIGHT), sides)
    }

    // ---- whole-payload round trips ----------------------------------------------------------

    @Test fun `the canonical plan round-trips exactly`() {
        assertEquals(LinkFixtures.plan, LinkPayloads.decodePlan(LinkPayloads.encodePlan(LinkFixtures.plan)))
    }

    @Test fun `the canonical session round-trips exactly`() {
        assertEquals(LinkFixtures.session, LinkPayloads.decodeSession(LinkPayloads.encodeSession(LinkFixtures.session)))
    }

    @Test fun `the canonical set report round-trips exactly`() {
        assertEquals(LinkFixtures.report, LinkPayloads.decodeLoggedSet(LinkPayloads.encodeLoggedSet(LinkFixtures.report)))
    }

    @Test fun `hello round-trips, and a HELLO_ACK names the chosen version`() {
        assertEquals(LinkFixtures.hello, LinkPayloads.decodeHello(LinkPayloads.encodeHello(LinkFixtures.hello)))
        assertNull(LinkPayloads.decodeHello(LinkPayloads.encodeHello(LinkFixtures.hello)).chosenVersion)
        assertEquals(1, LinkPayloads.decodeHello(LinkPayloads.encodeHello(LinkFixtures.helloAck)).chosenVersion)
    }

    @Test fun `ack and error round-trip`() {
        assertEquals(LinkFixtures.ack, LinkPayloads.decodeAck(LinkPayloads.encodeAck(LinkFixtures.ack)))
        assertEquals(LinkFixtures.error, LinkPayloads.decodeError(LinkPayloads.encodeError(LinkFixtures.error)))
    }

    @Test fun `an error code this build does not know is still reported, with the peer's number`() {
        val raw = byteArrayOf(99, 0, 4) + "oops".toByteArray()
        val decoded = LinkPayloads.decodeError(raw)
        assertEquals(LinkError.MALFORMED_FRAME, decoded.error)
        assertTrue(decoded.detail, decoded.detail.contains("99"))
        assertTrue(decoded.detail, decoded.detail.contains("oops"))
    }

    // ---- strictness -------------------------------------------------------------------------

    @Test fun `a trailing byte is refused rather than ignored`() {
        val bytes = LinkPayloads.encodeSession(LinkFixtures.session) + byteArrayOf(0)
        assertThrows(LinkDecodeException::class.java) { LinkPayloads.decodeSession(bytes) }
    }

    @Test fun `a truncated payload is refused rather than half-read`() {
        val bytes = LinkPayloads.encodePlan(LinkFixtures.plan)
        assertThrows(LinkDecodeException::class.java) { LinkPayloads.decodePlan(bytes.copyOf(bytes.size - 3)) }
    }

    @Test fun `a reserved presence bit is refused, because a peer that sets one is a peer from another version`() {
        val bytes = LinkPayloads.encodeSession(
            FinishedSession("s", 1, "2026-09-21", "", 0, listOf(FinishedExercise("Row", sets = listOf(LoggedSet(reps = 8)))))
        )
        // The logged set's presence byte is the last-but-two byte: mask, then the u16 reps.
        val maskAt = bytes.size - 3
        assertEquals(0x02, bytes[maskAt].toInt())
        bytes[maskAt] = (bytes[maskAt].toInt() or 0x80).toByte()
        assertThrows(LinkDecodeException::class.java) { LinkPayloads.decodeSession(bytes) }
    }

    @Test fun `a revision of zero is refused, because revisions are compared`() {
        val bytes = LinkPayloads.encodeSession(LinkFixtures.session)
        // revision is the u32 straight after the session id's u16 length and bytes.
        val at = 2 + LinkFixtures.SESSION_ID.length
        for (i in at until at + 4) bytes[i] = 0
        assertThrows(LinkDecodeException::class.java) { LinkPayloads.decodeSession(bytes) }
    }

    @Test fun `a decision byte that is neither accept nor reject is refused`() {
        assertTrue(LinkPayloads.decodeDecision(LinkPayloads.encodeDecision(true)))
        assertFalse(LinkPayloads.decodeDecision(LinkPayloads.encodeDecision(false)))
        assertThrows(LinkDecodeException::class.java) { LinkPayloads.decodeDecision(byteArrayOf(2)) }
    }

    @Test fun `a plan with no exercises is legal`() {
        val plan = Plan("p", 1, "Rest day", PlanSource.ROUTINE)
        assertEquals(plan, LinkPayloads.decodePlan(LinkPayloads.encodePlan(plan)))
    }

    @Test fun `a plan tied to no date carries no date`() {
        val plan = Plan("p", 1, "Upper A", PlanSource.COACH_PLAN, scheduledFor = null)
        assertNull(LinkPayloads.decodePlan(LinkPayloads.encodePlan(plan)).scheduledFor)
    }

    private fun onePrescribed(set: PrescribedSet, equipment: String? = null) = Plan(
        planId = "p", revision = 1, name = "Upper A", source = PlanSource.ROUTINE,
        exercises = listOf(PlanExercise("Bench Press", equipment = equipment, sets = listOf(set))),
    )
}
