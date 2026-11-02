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
 * Version 3's whole content: a coach's set types on a plan (PLAN-FORMAT.md "Set types"). As with
 * sides, what this pins first is that **they cost nothing when they are absent**, and then the
 * reader's rules: adjacency makes a group, an exercise's first set is always straight, and a kind
 * this build does not know is a straight set rather than a refused plan.
 */
class LinkPlanSetTypesTest {
    private val plain = Plan(
        "p", 1, "Push", PlanSource.ROUTINE, null,
        listOf(
            PlanExercise("Bench Press", sets = listOf(PrescribedSet(reps = 6), PrescribedSet(reps = 6))),
            PlanExercise("Row", sets = listOf(PrescribedSet(reps = 8))),
        ),
    )

    private fun grouped(vararg groups: Int?) = plain.copy(
        exercises = groups.mapIndexed { i, g -> PlanExercise("Lift $i", sets = listOf(PrescribedSet(reps = 5)), group = g) }
    )

    // ---- what a set type costs on the wire -----------------------------------------------------

    @Test fun `a group is one presence bit and two bytes`() {
        val superset = plain.copy(exercises = plain.exercises.map { it.copy(group = 1) })
        assertEquals(LinkPayloads.encodePlan(plain).size + 2 * 2, LinkPayloads.encodePlan(superset).size)
    }

    @Test fun `a kind is one presence bit and exactly one byte`() {
        val drop = plain.copy(exercises = plain.exercises.mapIndexed { i, ex ->
            if (i == 0) ex.copy(sets = listOf(ex.sets[0], ex.sets[1].copy(kind = SetKind.DROP))) else ex
        })
        assertEquals(LinkPayloads.encodePlan(plain).size + 1, LinkPayloads.encodePlan(drop).size)
    }

    @Test fun `a plan with no set types is the bytes it was before set types existed`() {
        assertFalse(plain.prescribesSetTypes)
        assertEquals(plain, plain.withoutSetTypes())
        assertEquals(
            LinkPayloads.encodePlan(plain).toList(),
            LinkPayloads.encodePlan(plain.withoutSetTypes()).toList(),
        )
    }

    // ---- the round trip ----------------------------------------------------------------------

    @Test fun `groups and kinds survive the round trip, and absent stays absent`() {
        val typed = Plan(
            "p", 2, "Upper B", PlanSource.COACH_PLAN, "2026-11-02",
            listOf(
                PlanExercise("Bench", "Dumbbell", sets = listOf(PrescribedSet(reps = 10)), group = 1),
                PlanExercise("Row", "Cable", sets = listOf(PrescribedSet(reps = 12)), group = 1),
                PlanExercise("Fly", sets = listOf(PrescribedSet(reps = 15)), group = 1),
                PlanExercise(
                    "Pushdown",
                    sets = listOf(
                        PrescribedSet(weightKg = 30.0, reps = 12),
                        PrescribedSet(reps = 10, kind = SetKind.DROP),
                        PrescribedSet(reps = 10, rpe = 9.0, restSeconds = 15, side = LogSide.RIGHT, kind = SetKind.REST_PAUSE),
                    ),
                ),
                PlanExercise("Curl", sets = listOf(PrescribedSet(reps = 8)), group = 2),
                PlanExercise("Hammer Curl", sets = listOf(PrescribedSet(reps = 8)), group = 2),
            ),
        )
        val back = LinkPayloads.decodePlan(LinkPayloads.encodePlan(typed))
        assertEquals(typed, back)
        assertEquals(listOf(1, 1, 1, null, 2, 2), back.exercises.map { it.group })
        assertNull(back.exercises[3].kindOf(0))
        assertEquals(SetKind.DROP, back.exercises[3].kindOf(1))
        assertEquals(SetKind.REST_PAUSE, back.exercises[3].kindOf(2))
        // The side rides beside the kind on the same set, and neither eats the other.
        assertEquals(LogSide.RIGHT, back.exercises[3].sets[2].side)
        // The blank weight is still blank.
        assertNull(back.exercises[3].sets[1].weightKg)
    }

    // ---- the reader's rules --------------------------------------------------------------------

    @Test fun `adjacency makes the group, not the number`() {
        assertEquals(listOf(1, 1, 2, 2, 2), ExerciseGroups.runs(listOf(7, 7, 3, 3, 3)))
        assertEquals(listOf(null, null, null), ExerciseGroups.runs(listOf(1, null, 1)))
        assertEquals(listOf(null, 1, 1, null), ExerciseGroups.runs(listOf(4, 9, 9, 4)))
        assertEquals(listOf(null, null), ExerciseGroups.runs(listOf(0, 0)))
        assertEquals(emptyList<Int?>(), ExerciseGroups.runs(emptyList()))
    }

    @Test fun `a group of one is written as nothing at all`() {
        val lone = grouped(1, null, 1)
        assertFalse(lone.prescribesSetTypes)
        assertEquals(
            LinkPayloads.encodePlan(grouped(null, null, null)).toList(),
            LinkPayloads.encodePlan(lone).toList(),
        )
    }

    @Test fun `groups go out renumbered in order of appearance`() {
        val back = LinkPayloads.decodePlan(LinkPayloads.encodePlan(grouped(7, 7, 3, 3, 3)))
        assertEquals(listOf(1, 1, 2, 2, 2), back.exercises.map { it.group })
    }

    @Test fun `an exercise's first set is always straight, on the way out and on the way in`() {
        val firstDrop = plain.copy(exercises = plain.exercises.map { ex ->
            ex.copy(sets = listOf(ex.sets[0].copy(kind = SetKind.DROP)) + ex.sets.drop(1))
        })
        assertFalse(firstDrop.prescribesSetTypes)
        assertNull(firstDrop.exercises[0].kindOf(0))
        // Never written: the bytes are a plan with no kinds.
        assertEquals(LinkPayloads.encodePlan(plain).toList(), LinkPayloads.encodePlan(firstDrop).toList())

        // Never read either, even from a peer that wrote one: set the kind bit on the first set by
        // hand. Walking back from the end: Row's one set (3), its set count (2), mask (1) and name
        // (2 + 3), then Bench's two sets (3 each).
        val bytes = LinkPayloads.encodePlan(plain).toMutableList()
        val firstSetMaskAt = bytes.size - (3 + 2 + 1 + 5) - 3 - 3
        assertEquals(0x02, bytes[firstSetMaskAt].toInt())
        bytes[firstSetMaskAt] = 0x22
        bytes.add(firstSetMaskAt + 3, SetKind.DROP.code.toByte())
        val read = LinkPayloads.decodePlan(bytes.toByteArray())
        assertNull(read.exercises[0].sets[0].kind)
        assertEquals(6, read.exercises[0].sets[0].reps)
        assertEquals(6, read.exercises[0].sets[1].reps)
    }

    @Test fun `a kind this build does not know reads as straight, and the plan still arrives`() {
        val drop = plain.copy(exercises = listOf(
            PlanExercise("Curl", sets = listOf(PrescribedSet(reps = 8), PrescribedSet(reps = 4, kind = SetKind.DROP)))
        ))
        val bytes = LinkPayloads.encodePlan(drop)
        assertEquals(SetKind.DROP.code, bytes.last().toInt())
        bytes[bytes.size - 1] = 3      // PLAN-FORMAT: `3` in bits 3-4 is never written and reads as straight
        val read = LinkPayloads.decodePlan(bytes)
        assertNull(read.exercises[0].sets[1].kind)
        assertEquals(4, read.exercises[0].sets[1].reps)
    }

    @Test fun `a group number of zero reads as not grouped`() {
        val bytes = LinkPayloads.encodePlan(grouped(1, 1))
        // Each exercise: name (2 + 6), mask, group u16, set count u16, one set (mask + reps u16).
        val firstGroupAt = bytes.size - 2 * (2 + 6 + 1 + 2 + 2 + 3) + (2 + 6 + 1)
        assertEquals(0, bytes[firstGroupAt].toInt())
        assertEquals(1, bytes[firstGroupAt + 1].toInt())
        bytes[firstGroupAt + 1] = 0
        val read = LinkPayloads.decodePlan(bytes)
        assertEquals(listOf(null, null), read.exercises.map { it.group })
    }

    @Test fun `a plan with set types is still refused for a single trailing byte`() {
        val bytes = LinkPayloads.encodePlan(grouped(1, 1)) + byteArrayOf(0)
        assertThrows(LinkDecodeException::class.java) { LinkPayloads.decodePlan(bytes) }
    }

    // ---- stripping, for a peer below version 3 -------------------------------------------------

    @Test fun `stripping gives exactly the bytes of the same plan without set types`() {
        val typed = plain.copy(exercises = plain.exercises.mapIndexed { i, ex ->
            ex.copy(
                group = 1,
                sets = ex.sets.mapIndexed { j, set -> if (i == 0 && j == 1) set.copy(kind = SetKind.REST_PAUSE) else set },
            )
        })
        assertTrue(typed.prescribesSetTypes)
        assertEquals(
            LinkPayloads.encodePlan(plain).toList(),
            LinkPayloads.encodePlan(typed.withoutSetTypes()).toList(),
        )
        // The numbers are all still there. It is the set types that came off, not the prescription.
        assertEquals(6, typed.withoutSetTypes().exercises[0].sets[1].reps)
    }

    private val phoneNonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val watchNonce = byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16)

    /** A central whose handshake settled at [peerVersion]: the version-2 watch nobody has updated. */
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

    private val typedAndSided = Plan(
        "p", 1, "Legs", PlanSource.COACH_PLAN, null,
        listOf(
            PlanExercise(
                "Split Squat",
                sets = listOf(PrescribedSet(reps = 8), PrescribedSet(reps = 6, side = LogSide.LEFT, kind = SetKind.DROP)),
                eachSide = true, group = 1,
            ),
            PlanExercise("Box Jump", sets = listOf(PrescribedSet(reps = 5)), group = 1),
        ),
    )

    @Test fun `a version-3 link carries the coach's set types and sides`() {
        val phone = central(peerVersion = 3)
        assertEquals(3, phone.negotiatedVersion)
        val pushed = LinkPayloads.decodePlan(phone.pushPlan(typedAndSided).payload)
        assertEquals(typedAndSided, pushed)
    }

    @Test fun `a version-2 link gets the sides without the set types, rather than no plan at all`() {
        val phone = central(peerVersion = 2)
        val pushed = LinkPayloads.decodePlan(phone.pushPlan(typedAndSided).payload)
        assertFalse(pushed.prescribesSetTypes)
        assertTrue(pushed.exercises[0].eachSide)
        assertEquals(LogSide.LEFT, pushed.exercises[0].sets[1].side)
        assertEquals(6, pushed.exercises[0].sets[1].reps)
        assertEquals(listOf("Split Squat", "Box Jump"), pushed.exercises.map { it.name })
    }

    @Test fun `a version-1 link gets neither, and still the numbers`() {
        val phone = central(peerVersion = 1)
        val pushed = LinkPayloads.decodePlan(phone.pushPlan(typedAndSided).payload)
        assertFalse(pushed.prescribesSetTypes)
        assertFalse(pushed.prescribesSides)
        assertEquals(6, pushed.exercises[0].sets[1].reps)
    }

    @Test fun `this build speaks version 3 and frames at it once settled`() {
        assertEquals(3, LinkProtocol.VERSION)
        assertEquals(3, LinkProtocol.VERSION_WITH_SET_TYPES)
        val codec = LinkCodec().apply { version = 3 }
        assertEquals(3, codec.frames(LinkMessage(MessageType.PLAN_REQUEST))[0][0].toInt())
    }
}
