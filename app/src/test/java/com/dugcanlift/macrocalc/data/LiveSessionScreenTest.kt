package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * The screen stays on while someone is training, and never because of a session they forgot to end.
 * A port of LIFT iPhone's `LiveSessionScreenTests`, case for case, plus the live session's own
 * record ([LiveSession]).
 */
class LiveSessionScreenTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    @Test fun `a live session holds the screen on`() {
        assertTrue(LiveSessionScreen.holdsScreenOn(listOf(now - 40 * 60_000L), enabled = true, nowMs = now))
    }

    @Test fun `no live session lets it sleep`() {
        assertFalse(LiveSessionScreen.holdsScreenOn(emptyList(), enabled = true, nowMs = now))
    }

    @Test fun `the setting turns it off`() {
        assertFalse(LiveSessionScreen.holdsScreenOn(listOf(now - 60_000L), enabled = false, nowMs = now))
    }

    /** A session left running overnight must not keep a phone awake on the nightstand. */
    @Test fun `a session left running stops holding after eight hours`() {
        assertTrue(LiveSessionScreen.holdsScreenOn(listOf(now - (8 * hour - 1000)), enabled = true, nowMs = now))
        assertFalse(LiveSessionScreen.holdsScreenOn(listOf(now - 8 * hour), enabled = true, nowMs = now))
    }

    /** Past midnight the session belongs to yesterday's day, and is still on. */
    @Test fun `a session that crosses midnight still holds`() {
        val midnight = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertTrue(LiveSessionScreen.holdsScreenOn(listOf(midnight - 20 * 60_000L), enabled = true, nowMs = midnight + 30 * 60_000L))
    }

    @Test fun `one recent session among stale ones holds`() {
        assertTrue(LiveSessionScreen.holdsScreenOn(listOf(now - 3 * 24 * hour, now - 600_000L), enabled = true, nowMs = now))
    }

    @Test fun `it is on unless someone turns it off`() {
        assertTrue(LiveSessionScreen.DEFAULT_ENABLED)
    }

    /** The screen is held awake, so nothing else would ask again at the eight-hour mark: the root waits for this moment. */
    @Test fun `the next cutoff is the earliest still ahead`() {
        val older = now - 7 * hour
        val newer = now - hour
        assertEquals(older + 8 * hour, LiveSessionScreen.nextCutoff(listOf(newer, older), now))
        assertNull(LiveSessionScreen.nextCutoff(listOf(now - 9 * hour), now))
        assertNull(LiveSessionScreen.nextCutoff(emptyList(), now))
    }

    /* ---------- the live session's record, on this phone only ---------- */

    @Test fun `ticks round-trip and unticking takes one back`() {
        val live = LiveSession("s1", now).ticked("a", true).ticked("b", true).ticked("a", false)
        assertEquals(setOf("b"), live.done)
        assertEquals(live, LiveSession.fromJson(live.toJson()))
    }

    @Test fun `an unreadable record is no live session`() {
        assertNull(LiveSession.fromJson(null))
        assertNull(LiveSession.fromJson("{}"))
        assertNull(LiveSession.fromJson("nonsense"))
    }
}
