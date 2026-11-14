package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bluetooth heart rate path that needs no radio. Twin of lift-ios's HeartRateCentralTests: the
 * radio itself (`HeartRateCentral`) is a thin shell over these. Plain JVM, no framework type.
 */
class HeartRateBluetoothTest {
    private val a = "AA:BB:CC:DD:EE:01"
    private val b = "AA:BB:CC:DD:EE:02"
    private val c = "AA:BB:CC:DD:EE:03"
    private val both = listOf(AddedHeartRateDevice(a, "A"), AddedHeartRateDevice(b, "B"))
    private val t0 = 1_800_000_000_000L

    // Scan results

    @Test
    fun `a scan result is kept once per address`() {
        var list = emptyList<DiscoveredHeartRateDevice>()
        list = HeartRateScan.merged(list, a, "Polar H10", -60)
        list = HeartRateScan.merged(list, b, "Wahoo TICKR", -70)
        list = HeartRateScan.merged(list, a, "Polar H10", -55)
        assertEquals(listOf(a, b), list.map { it.address })
        assertEquals(-55, list[0].rssi)
    }

    @Test
    fun `a missing or blank name reads Heart rate device`() {
        assertEquals("Heart rate device", HeartRateScan.displayName(null))
        assertEquals("Heart rate device", HeartRateScan.displayName(""))
        assertEquals("Heart rate device", HeartRateScan.displayName("   "))
        assertEquals("Polar H10", HeartRateScan.displayName(" Polar H10 "))
        assertEquals("Heart rate device", HeartRateScan.merged(emptyList(), a, null, null).first().name)
    }

    @Test
    fun `a real name replaces the fallback but never the other way round`() {
        var list = HeartRateScan.merged(emptyList(), a, null, -60)
        list = HeartRateScan.merged(list, a, "Polar H10", -60)
        assertEquals(listOf("Polar H10"), list.map { it.name })
        list = HeartRateScan.merged(list, a, null, -60)
        assertEquals(listOf("Polar H10"), list.map { it.name })
    }

    @Test
    fun `the list holds first seen order`() {
        var list = emptyList<DiscoveredHeartRateDevice>()
        for (id in listOf(c, a, b, a, c)) list = HeartRateScan.merged(list, id, "x", null)
        assertEquals(listOf(c, a, b), list.map { it.address })
    }

    // In range, in the order added

    @Test
    fun `in range is in the order added not the order seen`() {
        val added = listOf(AddedHeartRateDevice(a, "A"), AddedHeartRateDevice(b, "B"), AddedHeartRateDevice(c, "C"))
        assertEquals(listOf(b, c), HeartRateRange.inRange(added, setOf(c, b)).map { it.id })
    }

    @Test
    fun `in range never includes a device nobody added`() {
        assertEquals(emptyList<AddedHeartRateDevice>(), HeartRateRange.inRange(listOf(AddedHeartRateDevice(a, "A")), setOf(b, c)))
        assertEquals(emptyList<AddedHeartRateDevice>(), HeartRateRange.inRange(emptyList(), setOf(a)))
    }

    @Test
    fun `in range keeps an added device once even if listed twice`() {
        val added = listOf(AddedHeartRateDevice(a, "A"), AddedHeartRateDevice(a, "A again"))
        assertEquals(listOf("A"), HeartRateRange.inRange(added, setOf(a)).map { it.name })
    }

    @Test
    fun `in range feeds automatic in the order added`() {
        val near = HeartRateRange.inRange(both, setOf(b, a))
        assertEquals(
            LiveHeartRateSource.Device(a, "A"),
            HeartRateSourcePicker.pick(HeartRateSetting.Automatic, near, watchAvailable = true)
        )
    }

    // The list the person keeps

    @Test
    fun `adding the same device twice keeps one row in its first place`() {
        var list = listOf(AddedHeartRateDevice(a, "A"))
        list = HeartRateDevices.adding(AddedHeartRateDevice(b, "B"), list)
        list = HeartRateDevices.adding(AddedHeartRateDevice(a, "A renamed"), list)
        assertEquals(listOf(a, b), list.map { it.id })
        assertEquals("A renamed", list.first().name)
    }

    @Test
    fun `removing the chosen device falls back to automatic`() {
        assertEquals(HeartRateSetting.Automatic, HeartRateDevices.settingAfterRemoving(HeartRateSetting.Device(a), a))
        assertEquals(HeartRateSetting.Device(a), HeartRateDevices.settingAfterRemoving(HeartRateSetting.Device(a), b))
        assertEquals(HeartRateSetting.Off, HeartRateDevices.settingAfterRemoving(HeartRateSetting.Off, a))
    }

    @Test
    fun `a Bluetooth address is six colon separated bytes and nothing else`() {
        assertTrue(HeartRateDevices.isAddress("AA:BB:CC:DD:EE:01"))
        assertTrue(HeartRateDevices.isAddress("aa:bb:cc:dd:ee:01"))
        assertFalse(HeartRateDevices.isAddress(""))
        assertFalse(HeartRateDevices.isAddress("AA:BB:CC:DD:EE"))
        assertFalse(HeartRateDevices.isAddress("not an address"))
        assertFalse(HeartRateDevices.isAddress("AA:BB:CC:DD:EE:0G"))
    }

    // Reconnecting, on the same device only

    @Test
    fun `a drop while recording reconnects the same device at once`() {
        val reconnect = HeartRateReconnect()
        reconnect.begin(a, both)
        assertEquals(HeartRateReconnect.Attempt(a, delayMs = 0), reconnect.onDisconnect(a))
    }

    @Test
    fun `a drop of another device is left alone`() {
        val reconnect = HeartRateReconnect()
        reconnect.begin(a, both)
        assertNull(reconnect.onDisconnect(b))
    }

    @Test
    fun `nothing reconnects before a run begins or after it ends`() {
        val reconnect = HeartRateReconnect()
        assertNull(reconnect.onDisconnect(a))
        reconnect.begin(a, both)
        reconnect.end()
        assertNull(reconnect.onDisconnect(a))
        assertNull(reconnect.onFailure(a))
    }

    @Test
    fun `beginning another device mid run is refused`() {
        val reconnect = HeartRateReconnect()
        reconnect.begin(a, both)
        reconnect.begin(b, both)
        assertEquals(a, reconnect.target)
        assertNull(reconnect.onDisconnect(b))
        assertEquals(a, reconnect.onDisconnect(a)?.id)
    }

    @Test
    fun `a long drop hides the number while the reconnect keeps targeting the same device`() {
        val reconnect = HeartRateReconnect()
        reconnect.begin(a, both)
        val last = LiveHeartRate.Reading(142, t0)
        assertEquals(142, LiveHeartRate.shown(last, t0 + 5_000))
        // The strap drops and stays gone for 12 s: the number hides...
        assertNull(LiveHeartRate.shown(last, t0 + 12_000))
        // ...and every attempt, however late, is for the same strap.
        assertEquals(a, reconnect.onDisconnect(a)?.id)
        assertEquals(a, reconnect.onDisconnect(a)?.id)
        assertEquals(a, reconnect.target)
        // It comes back: the number shows again from the new reading.
        assertEquals(138, LiveHeartRate.shown(LiveHeartRate.Reading(138, t0 + 13_000), t0 + 14_000))
    }

    @Test
    fun `an address nobody added is never connected`() {
        val reconnect = HeartRateReconnect()
        reconnect.begin(c, both)
        assertNull(reconnect.target)
        assertNull(reconnect.onDisconnect(c))
        reconnect.begin(c, emptyList())
        assertNull(reconnect.target)
        // A refused address does not use up the run's one source.
        reconnect.begin(a, both)
        assertEquals(a, reconnect.target)
    }

    @Test
    fun `a failed attempt waits two seconds then five and stays on the same device`() {
        val reconnect = HeartRateReconnect()
        reconnect.begin(a, both)
        assertEquals(HeartRateReconnect.Attempt(a, 2_000), reconnect.onFailure(a))
        assertEquals(5_000L, reconnect.onFailure(a)?.delayMs)
        assertEquals(5_000L, reconnect.onFailure(a)?.delayMs)
        assertNull(reconnect.onFailure(b))
        reconnect.connected()
        assertEquals(2_000L, reconnect.onFailure(a)?.delayMs)
    }

    // What the add sheet says

    @Test
    fun `the sheet says why it is empty when the person can fix it`() {
        assertEquals("Bluetooth is off for LIFT. Turn it on in the Settings app.", HeartRateRadioState.Denied.emptyListMessage)
        assertEquals("Bluetooth is turned off. Turn it on to look for heart rate devices.", HeartRateRadioState.Off.emptyListMessage)
        assertEquals("This device can't look for Bluetooth heart rate devices.", HeartRateRadioState.Unsupported.emptyListMessage)
        assertEquals(
            "Android 11 and older need Location turned on to look for heart rate devices.",
            HeartRateRadioState.LocationOff.emptyListMessage
        )
        for (state in listOf(HeartRateRadioState.Denied, HeartRateRadioState.Off, HeartRateRadioState.Unsupported, HeartRateRadioState.LocationOff)) {
            assertFalse("$state is not looking", state.isLooking)
        }
    }

    @Test
    fun `a scan that could not start is said in words and is not looking`() {
        val failed = HeartRateRadioState.ScanFailed
        assertEquals("Couldn't look for heart rate devices right now. Try again in a minute.", failed.emptyListMessage)
        assertFalse(failed.isLooking)
        val usable = HeartRateRadioState.of(supported = true, permitted = true, enabled = true, locationOk = true, scanFailed = true)
        assertEquals(HeartRateRadioState.ScanFailed, usable)
        // What the person can fix comes first: a failed scan never hides "Bluetooth is turned off".
        assertEquals(HeartRateRadioState.Off, HeartRateRadioState.of(true, true, enabled = false, locationOk = true, scanFailed = true))
        assertEquals(HeartRateRadioState.Denied, HeartRateRadioState.of(true, permitted = false, enabled = true, locationOk = true, scanFailed = true))
        assertEquals(HeartRateRadioState.On, HeartRateRadioState.of(true, true, true, true, scanFailed = false))
    }

    @Test
    fun `an unsettled or working radio is still looking`() {
        for (state in listOf(HeartRateRadioState.On, HeartRateRadioState.Unknown)) {
            assertEquals("Looking for heart rate devices…", state.emptyListMessage)
            assertTrue(state.isLooking)
        }
    }

    @Test
    fun `the radio state is decided in a fixed order`() {
        fun of(supported: Boolean = true, permitted: Boolean = true, enabled: Boolean = true, locationOk: Boolean = true) =
            HeartRateRadioState.of(supported, permitted, enabled, locationOk)
        assertEquals(HeartRateRadioState.On, of())
        assertEquals(HeartRateRadioState.Unsupported, of(supported = false, permitted = false, enabled = false))
        assertEquals(HeartRateRadioState.Denied, of(permitted = false, enabled = false))
        assertEquals(HeartRateRadioState.Off, of(enabled = false, locationOk = false))
        assertEquals(HeartRateRadioState.LocationOff, of(locationOk = false))
    }

    // One radio, two users

    @Test
    fun `a range probe ending leaves the sheet's scan running`() {
        val demand = HeartRateScanDemand()
        assertTrue("the sheet starts the radio", demand.acquire(HeartRateScanDemand.Reason.Sheet))
        assertFalse("a probe shares the sheet's scan", demand.acquire(HeartRateScanDemand.Reason.Probe))
        assertFalse("the probe ending does not stop it", demand.release(HeartRateScanDemand.Reason.Probe))
        assertTrue(demand.wanted)
        assertTrue("the sheet closing does", demand.release(HeartRateScanDemand.Reason.Sheet))
        assertFalse(demand.wanted)
    }

    @Test
    fun `the sheet closing leaves a probe's scan running`() {
        val demand = HeartRateScanDemand()
        assertTrue(demand.acquire(HeartRateScanDemand.Reason.Probe))
        assertFalse(demand.acquire(HeartRateScanDemand.Reason.Sheet))
        assertFalse(demand.release(HeartRateScanDemand.Reason.Sheet))
        assertTrue(demand.release(HeartRateScanDemand.Reason.Probe))
    }

    @Test
    fun `it says which holders it has`() {
        val demand = HeartRateScanDemand()
        assertFalse(demand.holds(HeartRateScanDemand.Reason.Sheet))
        demand.acquire(HeartRateScanDemand.Reason.Probe)
        assertFalse(demand.holds(HeartRateScanDemand.Reason.Sheet))
        assertTrue(demand.holds(HeartRateScanDemand.Reason.Probe))
    }

    @Test
    fun `releasing what was never held stops nothing`() {
        val demand = HeartRateScanDemand()
        assertFalse(demand.release(HeartRateScanDemand.Reason.Sheet))
        assertTrue(demand.acquire(HeartRateScanDemand.Reason.Sheet))
        assertFalse("asking twice is one hold", demand.acquire(HeartRateScanDemand.Reason.Sheet))
        assertTrue(demand.release(HeartRateScanDemand.Reason.Sheet))
    }

    // Bytes from the strap

    @Test
    fun `garbled bytes and zero are ignored`() {
        assertNull(HeartRateMeasurement.bpm(ByteArray(0)))
        assertNull(HeartRateMeasurement.bpm(byteArrayOf(0x00)))
        assertNull(HeartRateMeasurement.bpm(byteArrayOf(0x01, 0x2C)))
        assertNull(HeartRateMeasurement.bpm(byteArrayOf(0x00, 0x00)))
        assertEquals(72, HeartRateMeasurement.bpm(byteArrayOf(0x00, 72)))
    }
}
