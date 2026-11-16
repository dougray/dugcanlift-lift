package com.dugcanlift.macrocalc

import com.dugcanlift.macrocalc.data.AddedHeartRateDevice
import com.dugcanlift.macrocalc.data.HeartRateSetting
import com.dugcanlift.macrocalc.watchlink.WatchHeartRateLink
import com.dugcanlift.macrocalc.watchlink.WatchHeartRateRules
import java.lang.ref.WeakReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The watch link as a run uses it: what was asked, and a callback the test can fire. */
class FakeWatch(
    /** The name when linked (version 5, READY); null is "not a source". */
    var name: String? = "Pixel Watch 2",
    /** Whether a start can be sent at all right now. */
    var reachable: Boolean = true,
) : WatchHeartRateLink {
    var begins = 0
    var refreshes = 0
    var refreshAnswered = true
    var ends = 0
    var sink: ((Int, Long) -> Unit)? = null
    var order = ArrayList<String>()

    override fun linkedName(): String? = name

    override fun begin(onReading: (bpm: Int, atEpochSec: Long) -> Unit): Boolean {
        if (!reachable) return false
        begins++
        order.add("begin")
        sink = onReading
        return true
    }

    override fun refresh(): Boolean { refreshes++; order.add("refresh"); return refreshAnswered }

    override fun end() { ends++; order.add("end"); sink = null }

    fun deliver(bpm: Int, atEpochSec: Long) = sink?.invoke(bpm, atEpochSec)
}

/**
 * The watch (LIFT Link v5) as a run's one source: picked once, asked to start, kept awake, never
 * swapped for a strap or Health Connect, stopped once when the run ends, and never able to strand a
 * run or block Finish.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunHeartRateWatchTest {
    private val polar = AddedHeartRateDevice("AA:BB:CC:DD:EE:01", "Polar H10")
    private val t0 = 1_762_800_000_000L // epoch ms; the watch's seconds are t0 / 1000

    private var now = t0
    private fun sec(msAfterStart: Long) = (t0 + msAfterStart) / 1000

    private fun controller(
        scope: CoroutineScope,
        central: FakeCentral,
        watch: WatchHeartRateLink,
        setting: HeartRateSetting = HeartRateSetting.Automatic,
        added: List<AddedHeartRateDevice> = emptyList(),
    ) = RunHeartRateController(central, { setting }, { added }, watch, scope, clock = { now })

    @Test
    fun `automatic with no strap takes the watch, asks it once, and shows its name`() = runTest {
        val watch = FakeWatch()
        val c = controller(backgroundScope, FakeCentral(), watch)
        c.onRunStarted(t0)
        runCurrent()
        assertEquals(1, watch.begins)
        assertEquals("—", c.liveHeartRate(t0 + 1_000)?.number)
        assertEquals("from Pixel Watch 2", c.liveHeartRate(t0 + 1_000)?.source)
        now = t0 + 2_000
        watch.deliver(142, sec(2_000))
        assertEquals("142 bpm", c.liveHeartRate(t0 + 2_500)?.number)
        assertEquals("Pixel Watch 2", c.beginFinish(t0)?.sourceName) // stored as the run's source
    }

    @Test
    fun `a chosen Watch setting asks the watch and never looks for a strap`() = runTest {
        val watch = FakeWatch()
        val central = FakeCentral(listOf(polar))
        val c = controller(backgroundScope, central, watch, setting = HeartRateSetting.Watch, added = listOf(polar))
        c.onRunStarted(t0)
        runCurrent()
        assertEquals(1, watch.begins)
        assertTrue(central.inRangeCalls.isEmpty())
        assertTrue(central.connects.isEmpty())
    }

    @Test
    fun `automatic prefers an added strap in range, and then never asks the watch`() = runTest {
        val watch = FakeWatch()
        val central = FakeCentral(listOf(polar))
        val c = controller(backgroundScope, central, watch, added = listOf(polar))
        c.onRunStarted(t0)
        runCurrent()
        assertEquals(listOf(polar.id), central.connects)
        assertEquals(0, watch.begins)
        advanceTimeBy(5 * WatchHeartRateRules.KEEPALIVE_MS)
        assertEquals(0, watch.refreshes)
        c.onRunStopped(t0)
        assertEquals(0, watch.ends) // never asked, so nothing to stop
    }

    @Test
    fun `automatic with a strap added but out of range falls to the watch`() = runTest {
        val watch = FakeWatch()
        val central = FakeCentral(emptyList())
        val c = controller(backgroundScope, central, watch, added = listOf(polar))
        c.onRunStarted(t0)
        runCurrent()
        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(1, watch.begins)
        assertTrue(central.connects.isEmpty())
    }

    @Test
    fun `Off and a watch that is not a source never ask the watch`() = runTest {
        val off = FakeWatch()
        controller(backgroundScope, FakeCentral(), off, setting = HeartRateSetting.Off).also {
            it.onRunStarted(t0); runCurrent()
        }
        assertEquals(0, off.begins)
        val v4 = FakeWatch(name = null) // linked below 5, or not linked: no name
        for (setting in listOf(HeartRateSetting.Automatic, HeartRateSetting.Watch)) {
            val c = controller(backgroundScope, FakeCentral(), v4, setting = setting)
            c.onRunStarted(t0)
            runCurrent()
            assertNull(c.liveHeartRate(t0 + 1_000))
        }
        assertEquals(0, v4.begins)
    }

    @Test
    fun `the number hides at ten seconds, returns when readings resume, and the source never changes`() = runTest {
        val watch = FakeWatch()
        val central = FakeCentral(emptyList())
        val c = controller(backgroundScope, central, watch)
        c.onRunStarted(t0)
        runCurrent()
        now = t0 + 1_000
        watch.deliver(140, sec(1_000))
        assertEquals("140 bpm", c.liveHeartRate(t0 + 10_900)?.number) // 9.9 s old still shows
        assertEquals("—", c.liveHeartRate(t0 + 11_100)?.number) // 10.1 s hides
        assertEquals("from Pixel Watch 2", c.liveHeartRate(t0 + 11_100)?.source)
        // The link was down; it comes back and the watch resumes through the same callback.
        now = t0 + 40_000
        watch.deliver(150, sec(40_000))
        assertEquals("150 bpm", c.liveHeartRate(t0 + 41_000)?.number)
        assertEquals(0, central.connects.size) // never a strap
        assertEquals(1, watch.begins) // never re-picked
        assertEquals("Pixel Watch 2", c.beginFinish(t0)?.sourceName)
    }

    @Test
    fun `a watch clock ahead of the phone cannot keep a dead reading alive`() = runTest {
        val watch = FakeWatch()
        val c = controller(backgroundScope, FakeCentral(), watch)
        c.onRunStarted(t0)
        runCurrent()
        now = t0 + 5_000
        watch.deliver(140, sec(5_000) + 3_600) // an hour ahead
        assertEquals("140 bpm", c.liveHeartRate(t0 + 14_900)?.number)
        assertEquals("—", c.liveHeartRate(t0 + 15_100)?.number) // ten seconds after it arrived, not an hour
    }

    @Test
    fun `an old reading is kept in the run but hidden`() = runTest {
        val watch = FakeWatch()
        val c = controller(backgroundScope, FakeCentral(), watch)
        c.onRunStarted(t0)
        runCurrent()
        now = t0 + 60_000
        watch.deliver(140, sec(1_000)) // sampled a minute ago, delivered late
        assertEquals("—", c.liveHeartRate(t0 + 60_000)?.number)
        assertEquals(listOf(140), c.beginFinish(t0)!!.readings)
    }

    @Test
    fun `a value that is not a pulse is dropped`() = runTest {
        val watch = FakeWatch()
        val c = controller(backgroundScope, FakeCentral(), watch)
        c.onRunStarted(t0)
        runCurrent()
        watch.deliver(65_535, sec(0))
        assertEquals("—", c.liveHeartRate(t0)?.number)
        assertTrue(c.beginFinish(t0)!!.readings.isEmpty())
    }

    // ---- the keepalive -------------------------------------------------------------------------------

    @Test
    fun `the watch is asked again every minute, and the asks stop when the run ends`() = runTest {
        val watch = FakeWatch()
        val c = controller(backgroundScope, FakeCentral(), watch)
        c.onRunStarted(t0)
        runCurrent()
        val every = WatchHeartRateRules.KEEPALIVE_MS
        advanceTimeBy(every - 1)
        assertEquals(0, watch.refreshes)
        advanceTimeBy(2)
        assertEquals(1, watch.refreshes)
        advanceTimeBy(every)
        assertEquals(2, watch.refreshes)
        c.onRunStopped(t0)
        advanceTimeBy(10 * every)
        assertEquals(2, watch.refreshes) // no leaked timer
        assertEquals(1, watch.ends)
    }

    @Test
    fun `the keepalive keeps going through a link that is down, and never swaps the source`() = runTest {
        val watch = FakeWatch().apply { refreshAnswered = false }
        val central = FakeCentral(emptyList())
        val c = controller(backgroundScope, central, watch)
        c.onRunStarted(t0)
        runCurrent()
        advanceTimeBy(3 * WatchHeartRateRules.KEEPALIVE_MS + 1)
        assertEquals(3, watch.refreshes)
        assertEquals(0, central.connects.size)
        assertEquals("from Pixel Watch 2", c.liveHeartRate(t0 + 1)?.source)
    }

    @Test
    fun `Finish, Discard and Cancel all tell the watch to stop, once`() = runTest {
        for (end in 0..2) {
            val watch = FakeWatch()
            val c = controller(backgroundScope, FakeCentral(), watch)
            c.onRunStarted(t0)
            runCurrent()
            val figures = c.beginFinish(t0)
            assertNotNull(figures)
            if (end == 0) c.endFinish(t0) // a failed save: the run goes on, the watch is not stopped
            if (end == 0) assertEquals(0, watch.ends)
            c.onRunStopped(t0) // the tracker stops: Finish saved it, or Discard, or Cancel
            c.onRunStopped(t0)
            assertEquals("end $end", 1, watch.ends)
            assertEquals(listOf("begin", "end"), watch.order)
        }
    }

    @Test
    fun `a stop for another run changes nothing`() = runTest {
        val watch = FakeWatch()
        val c = controller(backgroundScope, FakeCentral(), watch)
        c.onRunStarted(t0)
        runCurrent()
        c.onRunStopped(t0 - 1)
        assertEquals(0, watch.ends)
        advanceTimeBy(WatchHeartRateRules.KEEPALIVE_MS + 1)
        assertEquals(1, watch.refreshes)
    }

    @Test
    fun `a new run stops the old run's watch before asking for its own`() = runTest {
        val watch = FakeWatch()
        val c = controller(backgroundScope, FakeCentral(), watch)
        c.onRunStarted(t0)
        runCurrent()
        c.onRunStarted(t0 + 100_000)
        runCurrent()
        assertEquals(listOf("begin", "end", "begin"), watch.order)
        // Only the new run's timer is alive: one refresh a minute, not two.
        advanceTimeBy(WatchHeartRateRules.KEEPALIVE_MS + 1)
        assertEquals(1, watch.refreshes)
        assertEquals("—", c.liveHeartRate(t0 + 100_001)?.number) // nothing carried across runs
    }

    // ---- a start that fails ----------------------------------------------------------------------------

    @Test
    fun `a watch that cannot be reached leaves the run with no live source and Finish is not blocked`() = runTest {
        val watch = FakeWatch(reachable = false) // linked when picked, gone when asked
        val central = FakeCentral(emptyList())
        val c = controller(backgroundScope, central, watch)
        c.onRunStarted(t0)
        runCurrent()
        assertNull(c.liveHeartRate(t0 + 1_000)) // no source, nothing shown
        advanceTimeBy(10 * WatchHeartRateRules.KEEPALIVE_MS)
        assertEquals(0, watch.refreshes) // no timer for a source it does not have
        val figures = c.beginFinish(t0)
        assertNotNull(figures) // Finish begins
        assertNull(figures!!.sourceName) // so Health Connect fills the summary
        assertTrue(figures.readings.isEmpty())
        c.endFinish(t0)
        c.onRunStopped(t0)
        assertEquals(0, watch.ends) // nothing was started, nothing to stop
        assertEquals(0, central.connects.size) // and never a strap in its place
    }

    // ---- a pick or start that comes too late -------------------------------------------------------------

    @Test
    fun `a start that completes after Finish began does nothing`() = runTest {
        val central = FakeCentral(emptyList()).apply { probe = CompletableDeferred() }
        val watch = FakeWatch()
        val c = controller(backgroundScope, central, watch, added = listOf(polar))
        c.onRunStarted(t0)
        runCurrent()
        assertNotNull(c.beginFinish(t0)) // Finish begins while the three second look still waits
        central.probe!!.complete(Unit)
        runCurrent()
        assertEquals(0, watch.begins)
        advanceTimeBy(5 * WatchHeartRateRules.KEEPALIVE_MS)
        assertEquals(0, watch.refreshes)
    }

    @Test
    fun `a start that completes after the run ended does nothing`() = runTest {
        val central = FakeCentral(emptyList()).apply { probe = CompletableDeferred() }
        val watch = FakeWatch()
        val c = controller(backgroundScope, central, watch, added = listOf(polar))
        c.onRunStarted(t0)
        runCurrent()
        c.onRunStopped(t0)
        central.probe!!.complete(Unit)
        runCurrent()
        assertEquals(0, watch.begins)
        assertEquals(0, watch.ends)
    }

    @Test
    fun `an old run's Finish wait does not stop the next run's watch being asked`() = runTest {
        val watch = FakeWatch()
        val c = controller(backgroundScope, FakeCentral(), watch)
        c.onRunStarted(t0)
        runCurrent()
        assertNotNull(c.beginFinish(t0)) // A's Finish still waits on Health Connect
        c.onRunStopped(t0)
        c.onRunStarted(t0 + 50_000)
        runCurrent()
        assertEquals(2, watch.begins)
        now = t0 + 51_000
        watch.deliver(150, sec(51_000))
        assertEquals("150 bpm", c.liveHeartRate(t0 + 52_000)?.number)
    }

    // ---- callbacks hold nothing they should not ------------------------------------------------------------

    @Test
    fun `the watch callback and the keepalive do not keep the run's owner alive`() {
        val watch = FakeWatch()
        val scope = TestScope()
        var c: RunHeartRateController? = controller(scope, FakeCentral(), watch)
        c!!.onRunStarted(t0)
        scope.runCurrent()
        assertTrue(watch.sink != null)
        val ref = WeakReference(c)
        c = null
        var cleared = false
        for (i in 0 until 20) {
            System.gc()
            if (ref.get() == null) { cleared = true; break }
            Thread.sleep(25)
        }
        assertTrue("the watch callback or the timer kept the controller alive", cleared)
        watch.deliver(120, sec(0)) // still safe: it reaches only its own session
        scope.advanceTimeBy(5 * WatchHeartRateRules.KEEPALIVE_MS) // and the timer ends itself
        assertEquals(0, watch.refreshes)
    }
}
