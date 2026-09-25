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
 * Version 2's whole content: a coach's sides on a plan. What this pins is not that the bits exist
 * but that **they cost nothing when they are absent** — a plan that says nothing about sides is the
 * bytes version 1 wrote, which is why this is a version a version-1 peer can still be talked to
 * rather than a break.
 */
class LinkPlanSidesTest {
    private val plain = Plan(
        "p", 1, "Push", PlanSource.ROUTINE, null,
        listOf(PlanExercise("Bench Press", sets = listOf(PrescribedSet(reps = 6)))),
    )

    // ---- what a side costs on the wire --------------------------------------------------------

    @Test fun `each side is one presence bit and not one byte more`() {
        val eachSide = plain.copy(
            exercises = plain.exercises.map { it.copy(eachSide = true) }
        )
        val before = LinkPayloads.encodePlan(plain)
        val after = LinkPayloads.encodePlan(eachSide)
        assertEquals(before.size, after.size)
        // The exercise's presence byte is the only difference, and only by EX_EACH_SIDE (0x08).
        val differing = before.indices.filter { before[it] != after[it] }
        assertEquals(1, differing.size)
        assertEquals(0x08, (after[differing.single()].toInt() xor before[differing.single()].toInt()))
    }

    @Test fun `a named side is one presence bit and exactly one byte`() {
        val sided = plain.copy(
            exercises = plain.exercises.map { ex ->
                ex.copy(sets = ex.sets.map { it.copy(side = LogSide.RIGHT) })
            }
        )
        assertEquals(LinkPayloads.encodePlan(plain).size + 1, LinkPayloads.encodePlan(sided).size)
    }

    @Test fun `sides survive the round trip, and absent stays absent`() {
        val sided = Plan(
            "p", 2, "Legs", PlanSource.COACH_PLAN, "2026-09-25",
            listOf(
                PlanExercise(
                    "Split Squat",
                    sets = listOf(
                        PrescribedSet(reps = 8),
                        PrescribedSet(reps = 8, side = LogSide.LEFT),
                        PrescribedSet(weightKg = 20.0, reps = 8, rpe = 8.5, restSeconds = 90, side = LogSide.RIGHT),
                    ),
                    eachSide = true,
                ),
                PlanExercise("Leg Press", sets = listOf(PrescribedSet(reps = 12))),
            ),
        )
        val back = LinkPayloads.decodePlan(LinkPayloads.encodePlan(sided))
        assertEquals(sided, back)
        assertTrue(back.exercises[0].eachSide)
        assertNull(back.exercises[0].sets[0].side)
        assertEquals(LogSide.LEFT, back.exercises[0].sets[1].side)
        assertEquals(LogSide.RIGHT, back.exercises[0].sets[2].side)
        assertFalse(back.exercises[1].eachSide)
        // The blank weight is still blank. A sided plan is exactly where a zero would creep in.
        assertNull(back.exercises[0].sets[1].weightKg)
    }

    @Test fun `a side code that is neither left nor right is refused, never guessed`() {
        val bytes = LinkPayloads.encodePlan(
            plain.copy(exercises = plain.exercises.map { ex ->
                ex.copy(sets = ex.sets.map { it.copy(side = LogSide.LEFT) })
            })
        )
        bytes[bytes.size - 1] = 3
        assertThrows(LinkDecodeException::class.java) { LinkPayloads.decodePlan(bytes) }
    }

    @Test fun `the bits above the new ones are still reserved and still refused`() {
        // Prescribed set: 0x10 is now the side, so 0x20 must still be refused.
        val prescribed = LinkPayloads.encodePlan(plain)
        val setMaskAt = prescribed.size - 3
        assertEquals(0x02, prescribed[setMaskAt].toInt())
        prescribed[setMaskAt] = (prescribed[setMaskAt].toInt() or 0x20).toByte()
        assertThrows(LinkDecodeException::class.java) { LinkPayloads.decodePlan(prescribed) }

        // Exercise: 0x08 is now each-side, so 0x10 must still be refused.
        val exercise = LinkPayloads.encodePlan(plain)
        val exerciseMaskAt = exercise.size - 6
        assertEquals(0x00, exercise[exerciseMaskAt].toInt())
        exercise[exerciseMaskAt] = 0x10
        assertThrows(LinkDecodeException::class.java) { LinkPayloads.decodePlan(exercise) }
    }

    // ---- stripping, for a peer that speaks version 1 -------------------------------------------

    @Test fun `a plan with no sides is unchanged by stripping, and says so about itself`() {
        assertFalse(plain.prescribesSides)
        assertEquals(plain, plain.withoutSides())
    }

    @Test fun `stripping a sided plan gives exactly the bytes a plan without sides has`() {
        val sided = plain.copy(
            exercises = plain.exercises.map { ex ->
                ex.copy(eachSide = true, sets = ex.sets.map { it.copy(side = LogSide.LEFT) })
            }
        )
        assertTrue(sided.prescribesSides)
        assertEquals(
            LinkPayloads.encodePlan(plain).toList(),
            LinkPayloads.encodePlan(sided.withoutSides()).toList(),
        )
        // The numbers are all still there. It is the sides that came off, not the prescription.
        assertEquals(6, sided.withoutSides().exercises[0].sets[0].reps)
    }

    // ---- the version the link settled on -------------------------------------------------------

    private val phoneNonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val watchNonce = byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16)

    /**
     * A central that has finished its handshake with a peripheral topping out at [peerVersion]. The
     * HELLO_ACK is written by hand rather than produced by a second [LinkSession], because the point
     * is a peer from a build that does not exist here: the version-1 watch someone has not updated.
     */
    private fun central(peerVersion: Int): LinkSession {
        val phone = LinkSession(LinkRole.CENTRAL, "Pixel 8", phoneNonce, alreadyPaired = true)
        phone.start()
        phone.receive(
            LinkMessage(
                MessageType.HELLO_ACK,
                LinkPayloads.encodeHello(Hello(1, peerVersion, peerVersion, "Watch", watchNonce)),
            )
        )
        return phone
    }

    @Test fun `two builds of this version settle on it`() {
        val phone = LinkSession(LinkRole.CENTRAL, "Pixel 8", phoneNonce, alreadyPaired = true)
        val watch = LinkSession(LinkRole.PERIPHERAL, "Watch", watchNonce, alreadyPaired = true)
        watch.start()
        val answer = watch.receive(phone.start().send.single())
        phone.receive(answer.send.single())
        assertEquals(LinkProtocol.VERSION, watch.negotiatedVersion)
        assertEquals(LinkProtocol.VERSION, phone.negotiatedVersion)
        assertEquals(LinkState.READY, phone.state)
    }

    @Test fun `a peer that only speaks version 1 settles on version 1, and the link still comes up`() {
        val watch = LinkSession(LinkRole.PERIPHERAL, "Watch", watchNonce, alreadyPaired = true)
        watch.start()
        // An old phone: its HELLO proposes 1..1.
        val answer = watch.receive(
            LinkMessage(MessageType.HELLO, LinkPayloads.encodeHello(Hello(1, 1, null, "Pixel 8", phoneNonce)))
        )
        assertEquals(1, watch.negotiatedVersion)
        assertEquals(LinkState.READY, watch.state)
        assertEquals(1, LinkPayloads.decodeHello(answer.send.single().payload).chosenVersion)
    }

    @Test fun `a version-2 link carries the coach's sides`() {
        val phone = central(peerVersion = 2)
        assertEquals(2, phone.negotiatedVersion)
        val sided = plain.copy(exercises = plain.exercises.map { it.copy(eachSide = true) })
        val pushed = LinkPayloads.decodePlan(phone.pushPlan(sided).payload)
        assertTrue(pushed.exercises[0].eachSide)
    }

    @Test fun `a version-1 link gets the plan without its sides rather than no plan at all`() {
        val phone = central(peerVersion = 1)
        assertEquals(1, phone.negotiatedVersion)
        val sided = plain.copy(
            exercises = plain.exercises.map { ex ->
                ex.copy(eachSide = true, sets = ex.sets.map { it.copy(side = LogSide.LEFT) })
            }
        )
        val pushed = LinkPayloads.decodePlan(phone.pushPlan(sided).payload)
        assertFalse(pushed.exercises[0].eachSide)
        assertNull(pushed.exercises[0].sets[0].side)
        assertEquals(6, pushed.exercises[0].sets[0].reps)
    }

    // ---- frames go out at the negotiated version -----------------------------------------------

    @Test fun `a codec frames at version 1 until the handshake says otherwise`() {
        val codec = LinkCodec()
        assertEquals(1, codec.frames(LinkMessage(MessageType.PLAN_REQUEST))[0][0].toInt())
        codec.version = 2
        assertEquals(2, codec.frames(LinkMessage(MessageType.PLAN_REQUEST))[0][0].toInt())
    }

    @Test fun `an error is always legible to the peer being refused`() {
        val codec = LinkCodec().apply { version = 2 }
        val error = LinkMessage(MessageType.ERROR, LinkPayloads.encodeError(LinkErrorMessage(LinkError.NOT_PAIRED)))
        assertEquals(LinkProtocol.ERROR_VERSION, codec.frames(error)[0][0].toInt())
    }

    @Test fun `a codec refuses to frame at a version this build cannot write`() {
        assertThrows(IllegalArgumentException::class.java) { LinkCodec().version = 0 }
        assertThrows(IllegalArgumentException::class.java) { LinkCodec().version = LinkProtocol.VERSION + 1 }
    }
}
