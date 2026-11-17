package com.dugcanlift.macrocalc

import com.dugcanlift.macrocalc.data.AddedHeartRateDevice
import com.dugcanlift.macrocalc.data.HeartRateSetting
import com.dugcanlift.macrocalc.watchlink.WatchHeartRateLink
import java.lang.ref.WeakReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The radio as the run uses it: what was asked, and a probe that can be held back. */
class FakeCentral(var inRangeAnswer: List<AddedHeartRateDevice> = emptyList()) : HeartRateConnecting {
    val inRangeCalls = ArrayList<List<AddedHeartRateDevice>>()
    val connects = ArrayList<String>()
    val allowedSeen = ArrayList<List<AddedHeartRateDevice>?>()
    var disconnects = 0
    var onReading: ((Int, Long) -> Unit)? = null
    var probe: CompletableDeferred<Unit>? = null

    override suspend fun inRange(added: List<AddedHeartRateDevice>, withinMs: Long): List<AddedHeartRateDevice> {
        inRangeCalls.add(added)
        probe?.await()
        return inRangeAnswer
    }

    override fun connect(address: String, allowed: List<AddedHeartRateDevice>?, onReading: (Int, Long) -> Unit) {
        connects.add(address)
        allowedSeen.add(allowed)
        this.onReading = onReading
    }

    override fun disconnect() {
        disconnects++
        onReading = null
    }

    fun deliver(bpm: Int, at: Long) = onReading?.invoke(bpm, at)
}

/** One source per run, picked once; what the live card reads; and what Finish may take. */
@OptIn(ExperimentalCoroutinesApi::class)
class RunHeartRateControllerTest {
    private val polar = AddedHeartRateDevice("AA:BB:CC:DD:EE:01", "Polar H10")
    private val wahoo = AddedHeartRateDevice("AA:BB:CC:DD:EE:02", "Wahoo Tickr")
    private val stranger = AddedHeartRateDevice("AA:BB:CC:DD:EE:09", "A stranger's strap")
    private val t0 = 5_000_000L

    private fun controller(
        scope: CoroutineScope,
        central: FakeCentral,
        setting: HeartRateSetting = HeartRateSetting.Automatic,
        added: List<AddedHeartRateDevice> = listOf(polar, wahoo),
        watch: WatchHeartRateLink = WatchHeartRateLink.None,
    ) = RunHeartRateController(central, { setting }, { added }, watch, scope)

    @Test
    fun `the source is picked once at start from the added devices and nothing wider`() = runTest {
        val central = FakeCentral(inRangeAnswer = listOf(wahoo, polar).sortedBy { it.id }) // in range, any order
        val c = controller(backgroundScope, central)
        c.onRunStarted(t0)
        runCurrent()
        assertEquals(listOf(listOf(polar, wahoo)), central.inRangeCalls) // only what was added
        assertEquals(listOf(polar.id), central.connects) // the first added in range, picker output only
        assertEquals(listOf<List<AddedHeartRateDevice>?>(listOf(polar, wahoo)), central.allowedSeen)
        // The stranger was never offered to the radio or the picker.
        assertFalse(central.inRangeCalls.flatten().contains(stranger))
    }

    @Test
    fun `automatic with nothing added never touches the radio`() = runTest {
        val central = FakeCentral()
        val c = controller(backgroundScope, central, added = emptyList())
        c.onRunStarted(t0)
        runCurrent()
        assertTrue(central.inRangeCalls.isEmpty())
        assertTrue(central.connects.isEmpty())
        assertNull(c.liveHeartRate(t0 + 1_000))
    }

    @Test
    fun `Off and Watch never touch the radio and show nothing in this build`() = runTest {
        for (setting in listOf(HeartRateSetting.Off, HeartRateSetting.Watch)) {
            val central = FakeCentral(listOf(polar))
            val c = controller(backgroundScope, central, setting = setting)
            c.onRunStarted(t0)
            runCurrent()
            assertTrue(central.inRangeCalls.isEmpty())
            assertTrue(central.connects.isEmpty())
            assertNull(c.liveHeartRate(t0 + 1_000))
        }
    }

    @Test
    fun `a chosen device is looked for first and out of range is no source, never another strap`() = runTest {
        val central = FakeCentral(inRangeAnswer = listOf(wahoo)) // the chosen Polar is not nearby
        val c = controller(backgroundScope, central, setting = HeartRateSetting.Device(polar.id))
        c.onRunStarted(t0)
        runCurrent()
        assertEquals(1, central.inRangeCalls.size)
        assertTrue(central.connects.isEmpty())
        assertNull(c.liveHeartRate(t0))
    }

    @Test
    fun `a chosen device in range is connected after the look`() = runTest {
        val central = FakeCentral(inRangeAnswer = listOf(polar, wahoo))
        val c = controller(backgroundScope, central, setting = HeartRateSetting.Device(wahoo.id))
        c.onRunStarted(t0)
        runCurrent()
        assertEquals(listOf(wahoo.id), central.connects)
    }

    @Test
    fun `a strap that connects after the window is not used live`() = runTest {
        val central = FakeCentral(inRangeAnswer = emptyList()) // nothing nearby during the 3 s look
        val c = controller(backgroundScope, central)
        c.onRunStarted(t0)
        runCurrent()
        // The strap switches on a minute later: nothing re-picks, nothing connects.
        central.inRangeAnswer = listOf(polar)
        assertTrue(central.connects.isEmpty())
        assertNull(c.liveHeartRate(t0 + 60_000))
        assertNull(c.beginFinish(t0)?.sourceName) // Finish decides from Health Connect
    }

    @Test
    fun `a dropped source is reconnected not swapped through the pipeline`() = runTest {
        val central = FakeCentral(inRangeAnswer = listOf(polar, wahoo))
        val c = controller(backgroundScope, central)
        c.onRunStarted(t0)
        runCurrent()
        central.deliver(140, t0 + 1_000)
        assertEquals("140 bpm", c.liveHeartRate(t0 + 2_000)?.number)
        assertEquals("from Polar H10", c.liveHeartRate(t0 + 2_000)?.source)
        // The strap drops for more than ten seconds; the other strap comes into range meanwhile.
        assertEquals("—", c.liveHeartRate(t0 + 12_000)?.number)
        assertEquals("from Polar H10", c.liveHeartRate(t0 + 12_000)?.source)
        // The same strap resumes through the same callback.
        central.deliver(150, t0 + 20_000)
        assertEquals("150 bpm", c.liveHeartRate(t0 + 21_000)?.number)
        // One probe, one connect; never Health Connect, never the other strap, never a re-pick.
        assertEquals(1, central.inRangeCalls.size)
        assertEquals(listOf(polar.id), central.connects)
        assertEquals("Polar H10", c.beginFinish(t0)?.sourceName)
    }

    @Test
    fun `the live number disappears at ten seconds with nothing else changing`() = runTest {
        val central = FakeCentral(inRangeAnswer = listOf(polar))
        val c = controller(backgroundScope, central)
        c.onRunStarted(t0)
        runCurrent()
        central.deliver(142, t0)
        assertEquals("142 bpm", c.liveHeartRate(t0 + 9_900)?.number)
        assertEquals("142 bpm", c.liveHeartRate(t0 + 10_000)?.number) // exactly ten seconds still shows
        assertEquals("—", c.liveHeartRate(t0 + 10_100)?.number)
        assertEquals("from Polar H10", c.liveHeartRate(t0 + 10_100)?.source) // the source line stays
    }

    @Test
    fun `before the first reading the card shows a dash and the source`() = runTest {
        val central = FakeCentral(inRangeAnswer = listOf(polar))
        val c = controller(backgroundScope, central)
        c.onRunStarted(t0)
        runCurrent()
        assertEquals("—", c.liveHeartRate(t0 + 1_000)?.number)
    }

    @Test
    fun `a Finish before the source was picked connects nothing late`() = runTest {
        val central = FakeCentral(inRangeAnswer = listOf(polar)).apply { probe = CompletableDeferred() }
        val c = controller(backgroundScope, central)
        c.onRunStarted(t0)
        runCurrent()
        assertNotNull(c.beginFinish(t0)) // Finish begins while the probe still looks
        central.probe!!.complete(Unit)
        runCurrent()
        assertTrue(central.connects.isEmpty())
    }

    @Test
    fun `a run ended during the probe connects to nothing`() = runTest {
        val central = FakeCentral(inRangeAnswer = listOf(polar)).apply { probe = CompletableDeferred() }
        val c = controller(backgroundScope, central)
        c.onRunStarted(t0)
        runCurrent()
        c.onRunStopped(t0) // Cancel
        central.probe!!.complete(Unit)
        runCurrent()
        assertTrue(central.connects.isEmpty())
        assertNull(c.liveHeartRate(t0 + 1))
    }

    @Test
    fun `a stop for another run changes nothing`() = runTest {
        val central = FakeCentral(inRangeAnswer = listOf(polar))
        val c = controller(backgroundScope, central)
        c.onRunStarted(t0)
        runCurrent()
        c.onRunStopped(t0 - 1)
        assertEquals(0, central.disconnects)
        central.deliver(140, t0)
        assertEquals("140 bpm", c.liveHeartRate(t0)?.number)
    }

    @Test
    fun `ending a run lets the strap go and a late reading is dropped`() = runTest {
        val central = FakeCentral(inRangeAnswer = listOf(polar))
        val c = controller(backgroundScope, central)
        c.onRunStarted(t0)
        runCurrent()
        central.deliver(140, t0)
        val callback = central.onReading!!
        c.onRunStopped(t0)
        assertEquals(1, central.disconnects)
        callback(190, t0 + 5)
        assertNull(c.liveHeartRate(t0 + 5))
    }

    @Test
    fun `a new run replaces the old run's session and starts its own pick`() = runTest {
        val central = FakeCentral(inRangeAnswer = listOf(polar))
        val c = controller(backgroundScope, central)
        c.onRunStarted(t0)
        runCurrent()
        central.deliver(140, t0)
        c.onRunStarted(t0 + 100_000)
        runCurrent()
        assertEquals(2, central.connects.size)
        assertEquals("—", c.liveHeartRate(t0 + 100_001)?.number) // nothing carried across runs
    }

    @Test
    fun `a second finish while one waits is refused until the first ends`() = runTest {
        val c = controller(backgroundScope, FakeCentral())
        c.onRunStarted(t0)
        runCurrent()
        assertNotNull(c.beginFinish(t0))
        assertNull(c.beginFinish(t0))
        c.endFinish(t0)
        assertNotNull(c.beginFinish(t0))
    }

    @Test
    fun `the finish gate belongs to a run, so an old wait never blocks or clears the next run's`() = runTest {
        val c = controller(backgroundScope, FakeCentral())
        c.onRunStarted(t0)
        runCurrent()
        assertNotNull(c.beginFinish(t0)) // run A's Finish waits on a slow Health Connect
        c.onRunStopped(t0) // discarded
        c.onRunStarted(t0 + 50_000) // run B
        runCurrent()
        assertNotNull(c.beginFinish(t0 + 50_000)) // B can finish while A's wait is still open
        assertNull(c.beginFinish(t0 + 50_000)) // and B's own double tap is still refused
        c.endFinish(t0) // A's wait ends...
        assertNull(c.beginFinish(t0 + 50_000)) // ...which does not clear B's gate
        c.endFinish(t0 + 50_000)
        assertNotNull(c.beginFinish(t0 + 50_000))
    }

    @Test
    fun `the Finish button state follows each run's own wait`() = runTest {
        val c = controller(backgroundScope, FakeCentral())
        c.onRunStarted(t0)
        runCurrent()
        assertTrue(c.finishingRuns.value.isEmpty()) // enabled before Finish
        c.beginFinish(t0)
        assertEquals(setOf(t0), c.finishingRuns.value) // disabled while it waits
        c.onRunStopped(t0)
        c.onRunStarted(t0 + 50_000) // the next run is not held by the old wait
        runCurrent()
        assertFalse((t0 + 50_000) in c.finishingRuns.value)
        c.endFinish(t0)
        assertTrue(c.finishingRuns.value.isEmpty()) // enabled again when it ends or fails
        c.beginFinish(t0 + 50_000)
        c.endFinish(t0 + 50_000)
        assertTrue(c.finishingRuns.value.isEmpty())
    }

    @Test
    fun `an old runs Finish does not stop the next runs strap being picked`() = runTest {
        val central = FakeCentral(inRangeAnswer = listOf(polar))
        val c = controller(backgroundScope, central)
        c.onRunStarted(t0)
        runCurrent()
        assertNotNull(c.beginFinish(t0)) // A's wait stays open
        c.onRunStopped(t0)
        central.probe = CompletableDeferred() // B's three second look
        c.onRunStarted(t0 + 50_000)
        runCurrent()
        central.probe!!.complete(Unit)
        runCurrent()
        assertEquals(listOf(polar.id, polar.id), central.connects) // B connected
        central.deliver(150, t0 + 51_000)
        assertEquals("150 bpm", c.liveHeartRate(t0 + 52_000)?.number)
    }

    @Test
    fun `finish takes the readings without ending the session`() = runTest {
        val central = FakeCentral(inRangeAnswer = listOf(polar))
        val c = controller(backgroundScope, central)
        c.onRunStarted(t0)
        runCurrent()
        central.deliver(140, t0)
        central.deliver(160, t0 + 1_000)
        val figures = c.beginFinish(t0)!!
        assertEquals(listOf(140, 160), figures.readings)
        assertEquals("Polar H10", figures.sourceName)
        // A failed save keeps the run going: the session still listens.
        central.deliver(150, t0 + 2_000)
        assertEquals("150 bpm", c.liveHeartRate(t0 + 2_500)?.number)
    }

    @Test
    fun `the reading callback does not keep the run's owner alive`() {
        // The radio holds the callback for the whole run. It may hold the session; it must not
        // hold the controller (and through it anything a screen gave it).
        val central = FakeCentral(inRangeAnswer = listOf(polar))
        val scope = TestScope()
        var c: RunHeartRateController? = controller(scope, central)
        c!!.onRunStarted(t0)
        scope.runCurrent()
        assertTrue(central.onReading != null)
        val ref = WeakReference(c)
        c = null
        var cleared = false
        for (i in 0 until 20) {
            System.gc()
            if (ref.get() == null) { cleared = true; break }
            Thread.sleep(25)
        }
        assertTrue("the callback kept the controller alive", cleared)
        // The callback is still safe to call: it reaches only its own session.
        central.deliver(120, t0)
    }
}
