package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The rest between sets. A port of LIFT iPhone's `RestTimerTests`, case for case, plus the rest
 * default from its `WatchPlanBuilderTests` ("The rest default").
 */
class RestTimerTest {
    private val now = 1_800_000_000_000L

    private fun at(seconds: Int) = now + seconds * 1000L

    /* ---------- the countdown ---------- */

    @Test fun `a countdown counts down`() {
        val rest = RestCountdown.start(90.0, null, "Bench Press", now)
        assertEquals(90.0, rest.remaining(now), 0.0)
        assertEquals(60.0, rest.remaining(at(30)), 0.0)
        assertFalse(rest.isOver(at(89)))
        assertTrue(rest.isOver(at(90)))
        assertEquals(0.0, rest.remaining(at(200)), 0.0)
    }

    @Test fun `fifteen seconds more or less`() {
        val rest = RestCountdown.start(90.0, null, null, now)
        val later = at(30)
        assertEquals(75.0, rest.adjusted(15.0, later).remaining(later), 0.0)
        assertEquals(45.0, rest.adjusted(-15.0, later).remaining(later), 0.0)
        // The start stays where it was.
        assertEquals(now, rest.adjusted(15.0, later).startedAtMs)
    }

    @Test fun `taking time off cannot go below nothing`() {
        val rest = RestCountdown.start(30.0, null, null, now)
        val later = at(20)
        val shorter = rest.adjusted(-15.0, later)
        assertEquals(0.0, shorter.remaining(later), 0.0)
        assertTrue(shorter.isOver(later))
    }

    @Test fun `a rest is never longer than fifteen minutes`() {
        var rest = RestCountdown.start(300.0, null, null, now)
        repeat(100) { rest = rest.adjusted(15.0, now) }
        assertEquals(RestCountdown.LONGEST_SECONDS, rest.remaining(now), 0.0)
    }

    @Test fun `adding to an over rest starts from now`() {
        val rest = RestCountdown.start(60.0, null, null, now)
        val late = at(100)
        assertEquals(15.0, rest.adjusted(15.0, late).remaining(late), 0.0)
    }

    @Test fun `the clock`() {
        assertEquals("1:35", RestCountdown.clock(95.0))
        assertEquals("0:09", RestCountdown.clock(9.0))
        assertEquals("0:00", RestCountdown.clock(0.0))
        // Rounded up, so it never shows 0:00 while there is still time left.
        assertEquals("0:01", RestCountdown.clock(0.2))
    }

    /* ---------- the timer ---------- */

    private class Outlets : RestOutlets {
        val events = mutableListOf<String>()
        override fun restStarted(rest: RestCountdown) { events += "start ${rest.remaining(rest.startedAtMs).toInt()}" }
        override fun restChanged(rest: RestCountdown) { events += "change ${(rest.endsAtMs - rest.startedAtMs) / 1000}" }
        override fun restCleared() { events += "clear" }
        override fun sessionEnded() { events += "session ended" }
    }

    private class Storage : RestStorage {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String?) { this.value = value }
    }

    private fun id() = UUID.randomUUID().toString()

    @Test fun `ticking a set starts the rest from Settings`() {
        val outlets = Outlets()
        val timer = RestTimer(outlets, Storage())
        val set = id()
        timer.setDone(set, "Squat", 120, now)
        assertEquals(set, timer.current.value?.setId)
        assertEquals(120.0, timer.current.value!!.remaining(now), 0.0)
        assertEquals(listOf("start 120"), outlets.events)
    }

    /** The "no rest" setting: a stored non-positive value is null. */
    @Test fun `no rest time means no rest`() {
        val outlets = Outlets()
        val timer = RestTimer(outlets, Storage())
        timer.setDone(id(), null, null, now)
        assertNull(timer.current.value)
        assertEquals(emptyList<String>(), outlets.events)
    }

    /** A second set ticked mid-rest starts a fresh rest; the old one is not left counting behind it. */
    @Test fun `the next set restarts the rest`() {
        val outlets = Outlets()
        val timer = RestTimer(outlets, Storage())
        timer.setDone(id(), "Squat", 90, now)
        val second = id()
        timer.setDone(second, "Squat", 90, at(40))
        assertEquals(second, timer.current.value?.setId)
        assertEquals(90.0, timer.current.value!!.remaining(at(40)), 0.0)
        assertEquals(listOf("start 90", "start 90"), outlets.events)
    }

    /** Unticking the set a rest belongs to takes the rest back too; unticking another one leaves it alone. */
    @Test fun `unticking the set cancels its rest`() {
        val outlets = Outlets()
        val timer = RestTimer(outlets, Storage())
        val set = id()
        timer.setDone(set, null, 90, now)
        timer.setUndone(id())
        assertNotNull(timer.current.value)
        timer.setUndone(set)
        assertNull(timer.current.value)
        assertEquals(listOf("start 90", "clear"), outlets.events)
    }

    @Test fun `adjust and skip`() {
        val outlets = Outlets()
        val timer = RestTimer(outlets, Storage())
        timer.setDone(id(), null, 90, now)
        timer.adjust(15.0, now)
        assertEquals(105.0, timer.current.value!!.remaining(now), 0.0)
        timer.skip()
        assertNull(timer.current.value)
        assertEquals(listOf("start 90", "change 105", "clear"), outlets.events)
        // Nothing to adjust or skip says nothing.
        timer.adjust(15.0, now)
        timer.skip()
        assertEquals(listOf("start 90", "change 105", "clear"), outlets.events)
    }

    /** Android can end LIFT mid-rest. Coming back, a rest still running is still on screen; one that ended a while ago is not. */
    @Test fun `a rest survives a relaunch until it is long over`() {
        val storage = Storage()
        RestTimer(Outlets(), storage).setDone(id(), "Row", 90, now)

        val relaunched = RestTimer(Outlets(), storage)
        relaunched.restore(at(30))
        assertEquals("Row", relaunched.current.value?.exerciseName)

        val outlets = Outlets()
        val muchLater = RestTimer(outlets, storage)
        muchLater.restore(at(90) + RestTimer.KEEPS_OVER_FOR_MS + 1000)
        assertNull(muchLater.current.value)
        assertEquals(listOf("clear"), outlets.events)
    }

    @Test fun `ending the session ends the rest`() {
        val outlets = Outlets()
        val timer = RestTimer(outlets, Storage())
        timer.setDone(id(), null, 60, now)
        timer.sessionEnded()
        assertNull(timer.current.value)
        assertEquals(listOf("start 60", "clear", "session ended"), outlets.events)
    }

    /* ---------- set types (SessionRest) ---------- */

    /** Inside a superset round, a tick starts no rest and ends the one still running: the next set has begun. */
    @Test fun `a tick with no rest ends the running rest and says what is next`() {
        val outlets = Outlets()
        val timer = RestTimer(outlets, Storage())
        timer.setDone(id(), "Bench", 90, now)
        val a1 = id()
        timer.setDone(a1, SessionRest.After.NoRest("Next: Seated Cable Rows"), "Bench", now)
        assertNull(timer.current.value)
        assertEquals(RestTimer.Cue(a1, "Next: Seated Cable Rows"), timer.cue.value)
        assertEquals(listOf("start 90", "clear"), outlets.events)

        // The round's last tick starts the full rest, and the cue goes.
        val b1 = id()
        timer.setDone(b1, SessionRest.After.Rest(90, "Bench (Dumbbell)"), "Rows", now)
        assertNull(timer.cue.value)
        assertEquals("Bench (Dumbbell)", timer.current.value?.exerciseName)
        assertEquals(listOf("start 90", "clear", "start 90"), outlets.events)
    }

    @Test fun `unticking the set takes its cue back and Done dismisses one`() {
        val outlets = Outlets()
        val timer = RestTimer(outlets, Storage())
        val set = id()
        timer.setDoneNoRest(set, "Drop set next")
        timer.setUndone(id())
        assertNotNull(timer.cue.value)
        timer.setUndone(set)
        assertNull(timer.cue.value)
        timer.setDoneNoRest(set, "Drop set next")
        timer.skip()
        assertNull(timer.cue.value)
        assertEquals("a cue is not a rest: nothing in the notification shade", emptyList<String>(), outlets.events)
    }

    @Test fun `rest-pause is a fifteen-second rest`() {
        val outlets = Outlets()
        val timer = RestTimer(outlets, Storage())
        timer.setDone(id(), SessionRest.After.Rest(SessionRest.REST_PAUSE_SECONDS, null), "Barbell Curl", now)
        assertEquals(15.0, timer.current.value!!.remaining(now), 0.0)
        assertEquals("Barbell Curl", timer.current.value?.exerciseName)
        timer.setDone(id(), SessionRest.After.Nothing, "Barbell Curl", now)
        assertEquals("rest turned off starts nothing, as before", listOf("start 15"), outlets.events)
    }

    @Test fun `ending the session takes the cue too`() {
        val timer = RestTimer(Outlets(), Storage())
        timer.setDoneNoRest(id(), "Next: Rows")
        timer.sessionEnded()
        assertNull(timer.cue.value)
    }

    /** With no rest running, ending the session still clears the notification shade. */
    @Test fun `ending the session with no rest still tells the outlets`() {
        val outlets = Outlets()
        RestTimer(outlets, Storage()).sessionEnded()
        assertEquals(listOf("session ended"), outlets.events)
    }

    /** Not in the iPhone's tests: what Android hands back after process death must not crash the app. */
    @Test fun `an unreadable stored rest is no rest`() {
        val storage = Storage().apply { value = "nonsense" }
        assertNull(RestTimer(Outlets(), storage).current.value)
    }

    /* ---------- the rest default ---------- */

    @Test fun `a phone that has never opened Settings still rests`() {
        assertEquals(90, RestBetweenSets.resolved(null))
    }

    @Test fun `a stored rest is used and a stored zero means no rest`() {
        assertEquals(150, RestBetweenSets.resolved(150))
        assertNull(RestBetweenSets.resolved(0))
    }
}
