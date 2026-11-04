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
 * Version 4's whole content: heart rate on what the watch sends home — the highest bpm around a
 * logged set, and the session's average and maximum. What this pins first is that **heart rate costs
 * nothing when it is absent**, then that a zero is never a heart rate, and then the rule that matters
 * most: a phone that settled below version 4 still gets the whole session, without the heart rate.
 */
class LinkHeartRateTest {
    private val plain = FinishedSession(
        "s", 1, "2026-11-02", "Push", 1_762_000_000L,
        listOf(
            FinishedExercise("Bench Press", "Barbell", sets = listOf(LoggedSet(weightKg = 60.0, reps = 8), LoggedSet(reps = 8))),
            FinishedExercise("Row", sets = listOf(LoggedSet(weightKg = 50.0, reps = 10, side = LogSide.LEFT))),
        ),
    )

    private val measured = plain.copy(
        exercises = plain.exercises.mapIndexed { i, exercise ->
            exercise.copy(sets = exercise.sets.mapIndexed { j, set -> if (j == 0) set.copy(maxBpm = 140 + i) else set })
        },
        averageBpm = 118,
        maxBpm = 156,
    )

    private fun roundTrip(session: FinishedSession) = LinkPayloads.decodeSession(LinkPayloads.encodeSession(session))

    // ---- what a heart rate costs on the wire ---------------------------------------------------

    @Test fun `a set's heart rate is one presence bit and exactly one byte`() {
        val one = plain.copy(exercises = listOf(plain.exercises[0].copy(sets = listOf(LoggedSet(reps = 8, maxBpm = 142)))))
        val none = plain.copy(exercises = listOf(plain.exercises[0].copy(sets = listOf(LoggedSet(reps = 8)))))
        assertEquals(LinkPayloads.encodeSession(none).size + 1, LinkPayloads.encodeSession(one).size)
    }

    @Test fun `the session's pair is one mask byte and a byte for each number present`() {
        val base = LinkPayloads.encodeSession(plain).size
        assertEquals(base + 3, LinkPayloads.encodeSession(plain.copy(averageBpm = 118, maxBpm = 156)).size)
        assertEquals(base + 2, LinkPayloads.encodeSession(plain.copy(maxBpm = 156)).size)
        assertEquals(base + 2, LinkPayloads.encodeSession(plain.copy(averageBpm = 118)).size)
    }

    @Test fun `a session with no heart rate is the bytes it was before heart rate existed`() {
        assertFalse(plain.recordsHeartRate)
        assertEquals(plain, plain.withoutHeartRate())
        assertEquals(
            LinkPayloads.encodeSession(plain).toList(),
            LinkPayloads.encodeSession(plain.withoutHeartRate()).toList(),
        )
    }

    // ---- the round trip ------------------------------------------------------------------------

    @Test fun `heart rates survive the round trip, and absent stays absent`() {
        val back = roundTrip(measured)
        assertEquals(measured, back)
        assertEquals(140, back.exercises[0].sets[0].maxBpm)
        assertNull(back.exercises[0].sets[1].maxBpm)
        // The bpm rides beside the side on the same set, and neither eats the other.
        assertEquals(LogSide.LEFT, back.exercises[1].sets[0].side)
        assertEquals(141, back.exercises[1].sets[0].maxBpm)
        assertEquals(118, back.averageBpm)
        assertEquals(156, back.maxBpm)
    }

    @Test fun `either half of the session's pair can travel alone`() {
        assertEquals(plain.copy(maxBpm = 156), roundTrip(plain.copy(maxBpm = 156)))
        assertEquals(plain.copy(averageBpm = 118), roundTrip(plain.copy(averageBpm = 118)))
    }

    @Test fun `a set report carries its heart rate under the same bit`() {
        val report = LoggedSetReport("s", 1, "Bench Press", "Barbell", 0, LoggedSet(weightKg = 60.0, reps = 8, maxBpm = 150), 1L)
        assertEquals(report, LinkPayloads.decodeLoggedSet(LinkPayloads.encodeLoggedSet(report)))
    }

    // ---- zero is not a heart rate --------------------------------------------------------------

    @Test fun `a heart rate the byte cannot carry is left off, and the session still goes`() {
        val odd = plain.copy(
            exercises = listOf(plain.exercises[0].copy(sets = listOf(LoggedSet(reps = 8, maxBpm = 0), LoggedSet(reps = 8, maxBpm = 300)))),
            averageBpm = 0,
            maxBpm = -1,
        )
        val back = roundTrip(odd)
        assertEquals(listOf<Int?>(null, null), back.exercises[0].sets.map { it.maxBpm })
        assertEquals(listOf<Int?>(8, 8), back.exercises[0].sets.map { it.reps })
        assertNull(back.averageBpm)
        assertNull(back.maxBpm)
        assertEquals(
            LinkPayloads.encodeSession(odd.withoutHeartRate()).toList(),
            LinkPayloads.encodeSession(odd).toList(),
        )
    }

    @Test fun `a set's heart rate of zero is refused`() {
        val bytes = LinkPayloads.encodeSession(
            plain.copy(exercises = listOf(FinishedExercise("Row", sets = listOf(LoggedSet(reps = 8, maxBpm = 142)))))
        )
        assertEquals(142, bytes.last().toInt() and 0xFF)
        bytes[bytes.size - 1] = 0
        assertThrows(LinkDecodeException::class.java) { LinkPayloads.decodeSession(bytes) }
    }

    @Test fun `a session's average or maximum of zero is refused`() {
        val bytes = LinkPayloads.encodeSession(plain.copy(averageBpm = 118, maxBpm = 156))
        assertEquals(0x03, bytes[bytes.size - 3].toInt())
        val zeroAverage = bytes.copyOf().also { it[it.size - 2] = 0 }
        val zeroMax = bytes.copyOf().also { it[it.size - 1] = 0 }
        assertThrows(LinkDecodeException::class.java) { LinkPayloads.decodeSession(zeroAverage) }
        assertThrows(LinkDecodeException::class.java) { LinkPayloads.decodeSession(zeroMax) }
    }

    @Test fun `a trailer that says nothing, or sets a reserved bit, is refused`() {
        val bytes = LinkPayloads.encodeSession(plain)
        assertThrows(LinkDecodeException::class.java) { LinkPayloads.decodeSession(bytes + byteArrayOf(0)) }
        assertThrows(LinkDecodeException::class.java) { LinkPayloads.decodeSession(bytes + byteArrayOf(0x04, 100)) }
        // And a trailer is still the last thing: one byte past it is refused like any other.
        val full = LinkPayloads.encodeSession(plain.copy(maxBpm = 156))
        assertThrows(LinkDecodeException::class.java) { LinkPayloads.decodeSession(full + byteArrayOf(1)) }
    }

    // ---- stripping, for a phone below version 4 ------------------------------------------------

    private val phoneNonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val watchNonce = byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16)

    /** A watch whose handshake settled at [peerVersion]: the phone nobody has updated. */
    private fun peripheral(peerVersion: Int): LinkSession {
        val watch = LinkSession(LinkRole.PERIPHERAL, "Watch", watchNonce, alreadyPaired = true)
        watch.start()
        watch.receive(LinkMessage(MessageType.HELLO, LinkPayloads.encodeHello(Hello(1, peerVersion, null, "Pixel 8", phoneNonce))))
        return watch
    }

    @Test fun `a version-4 link carries the heart rate`() {
        val watch = peripheral(peerVersion = 4)
        assertEquals(4, watch.negotiatedVersion)
        assertEquals(measured, LinkPayloads.decodeSession(watch.finishSession(measured).payload))
    }

    @Test fun `a version-3 link gets the whole session without its heart rate, rather than no session at all`() {
        for (version in 1..3) {
            val watch = peripheral(peerVersion = version)
            assertEquals(version, watch.negotiatedVersion)
            val sent = watch.finishSession(measured).payload
            // Exactly the bytes the same session with no heart rate makes: what version 3 wrote.
            assertEquals(LinkPayloads.encodeSession(plain).toList(), sent.toList())
            val back = LinkPayloads.decodeSession(sent)
            assertFalse(back.recordsHeartRate)
            assertEquals(listOf(60.0, null), back.exercises[0].sets.map { it.weightKg })
            assertEquals(listOf(8, 8), back.exercises[0].sets.map { it.reps })
        }
    }

    @Test fun `a set report is stripped below version 4 as the session is`() {
        val report = LoggedSetReport("s", 1, "Bench Press", "Barbell", 0, LoggedSet(weightKg = 60.0, reps = 8, maxBpm = 150), 1L)
        val stripped = LinkPayloads.decodeLoggedSet(peripheral(peerVersion = 3).reportSet(report).payload)
        assertNull(stripped.set.maxBpm)
        assertEquals(8, stripped.set.reps)
        assertEquals(150, LinkPayloads.decodeLoggedSet(peripheral(peerVersion = 4).reportSet(report).payload).set.maxBpm)
    }

    @Test fun `this build speaks version 4 and frames at it once settled`() {
        assertEquals(4, LinkProtocol.VERSION)
        assertEquals(4, LinkProtocol.VERSION_WITH_HEART_RATE)
        assertTrue(LinkProtocol.VERSION_WITH_HEART_RATE > LinkProtocol.VERSION_WITH_SET_TYPES)
        val codec = LinkCodec().apply { version = 4 }
        assertEquals(4, codec.frames(LinkMessage(MessageType.PLAN_REQUEST))[0][0].toInt())
    }
}
