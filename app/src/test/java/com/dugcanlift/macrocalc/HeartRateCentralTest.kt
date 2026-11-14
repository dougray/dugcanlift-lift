package com.dugcanlift.macrocalc

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.dugcanlift.macrocalc.data.AddedHeartRateDevice
import com.dugcanlift.macrocalc.data.HeartRateRadioState
import com.dugcanlift.macrocalc.data.SettingsStore
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.junit.Assert.assertNotNull
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The radio shell, as far as a JVM can see it: what creating it does (nothing), which devices it
 * will connect (only added ones), and how a reading reaches the run. The Bluetooth stack itself is
 * not exercised: there was no strap to try it against.
 */
@RunWith(RobolectricTestRunner::class)
class HeartRateCentralTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val strap = "AA:BB:CC:DD:EE:01"
    private val stranger = "AA:BB:CC:DD:EE:09"
    private val added = listOf(AddedHeartRateDevice(strap, "Polar H10"))

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun grantBluetooth() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true)
        shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH, Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    // Robolectric gives each test a fresh application; the production singleton would hold the last
    // test's context, so each test makes its own through the internal constructor.
    private fun central() = HeartRateCentral(app)

    @Test
    fun `creating the central starts no radio and asks for nothing`() {
        val central = central()
        assertFalse(central.isScanning)
        assertFalse(central.hasConnection)
        assertFalse(central.hasStartedRadio)
        assertEquals(HeartRateRadioState.Unknown, central.radio.value)
        assertEquals(emptyList<Any>(), central.found.value)
    }

    @Test
    fun `an address nobody added is never connected`() {
        grantBluetooth()
        val central = central()
        val got = ArrayList<Int>()
        central.connect(stranger, allowed = added) { bpm, _ -> got.add(bpm) }
        assertFalse(central.hasConnection)
        assertFalse(central.hasReadingCallback)
        central.deliver(stranger, 150, 1L)
        assertTrue(got.isEmpty())
    }

    @Test
    fun `an empty added list connects nothing`() {
        grantBluetooth()
        val central = central()
        central.connect(strap, allowed = emptyList()) { _, _ -> }
        assertFalse(central.hasConnection)
    }

    /** A central with Bluetooth on and allowed, counting scan starts and stops, and a run holding [strap]. */
    private class FakeScan : HeartRateCentral.ScanControl {
        var starts = 0
        var stops = 0
        var result = true
        var throws = false
        override fun start(callback: android.bluetooth.le.ScanCallback): Boolean {
            starts++
            if (throws) throw SecurityException("no scan")
            return result
        }
        override fun stop(callback: android.bluetooth.le.ScanCallback) { stops++ }
    }

    private fun bluetoothOn() {
        grantBluetooth()
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(true)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** A central holding a run on [strap], with its GATT handle. */
    private fun running(got: MutableList<Int>): HeartRateCentral {
        bluetoothOn()
        val central = central()
        central.connect(strap, allowed = added) { bpm, _ -> got.add(bpm) }
        assertNotNull("Robolectric made a GATT handle", central.currentGatt)
        return central
    }

    // The path from the radio to the run: the real one, hop and gate included.

    @Test
    fun `a reading from the strap reaches the run on the main thread`() {
        val got = ArrayList<Pair<Int, Long>>()
        val threads = ArrayList<Thread>()
        bluetoothOn()
        val central = central()
        central.connect(strap, allowed = added) { bpm, at -> got.add(bpm to at); threads.add(Thread.currentThread()) }
        val gatt = central.currentGatt!!
        // A reading arrives on a binder thread: nothing is delivered until the main looper runs it.
        Thread { central.onMeasurement(gatt, bytes(0x00, 142), 1_000L) }.also { it.start(); it.join() }
        assertTrue("delivered off the main thread", got.isEmpty())
        idle()
        assertEquals(listOf(142 to 1_000L), got)
        assertEquals(listOf(Looper.getMainLooper().thread), threads)
        assertEquals(0, central.offMainMutations)
    }

    @Test
    fun `a reading from a link that is no longer the run's is not heard`() {
        val got = ArrayList<Int>()
        val central = running(got)
        val old = central.currentGatt!!
        central.disconnect()
        central.connect(strap, allowed = added) { bpm, _ -> got.add(bpm) }
        assertTrue("a new handle", central.currentGatt !== old)
        central.onMeasurement(old, bytes(0x00, 111), 1L)
        idle()
        assertTrue("the old link's reading was heard", got.isEmpty())
        central.onMeasurement(central.currentGatt!!, bytes(0x00, 112), 2L)
        idle()
        assertEquals(listOf(112), got)
    }

    @Test
    fun `garbled bytes and a zero reach nobody`() {
        val got = ArrayList<Int>()
        val central = running(got)
        for (data in listOf(ByteArray(0), bytes(0x00), bytes(0x01, 0x2C), bytes(0x00, 0x00))) {
            central.onMeasurement(central.currentGatt!!, data, 1L)
        }
        idle()
        assertTrue(got.isEmpty())
    }

    @Test
    fun `another device's reading never reaches the run`() {
        val got = ArrayList<Int>()
        bluetoothOn()
        val central = central()
        central.connect(strap, allowed = added + AddedHeartRateDevice(stranger, "Other")) { bpm, _ -> got.add(bpm) }
        central.deliver(stranger, 99, 1L)
        assertTrue("one source per run", got.isEmpty())
        central.deliver(strap, 98, 1L)
        assertEquals(listOf(98), got)
    }

    @Test
    fun `a second device mid run is ignored and does not replace the callback`() {
        val first = ArrayList<Int>()
        val second = ArrayList<Int>()
        bluetoothOn()
        val central = central()
        val both = added + AddedHeartRateDevice(stranger, "Other")
        central.connect(strap, allowed = both) { bpm, _ -> first.add(bpm) }
        central.connect(stranger, allowed = both) { bpm, _ -> second.add(bpm) }
        central.deliver(strap, 120, 1L)
        central.deliver(stranger, 121, 1L)
        assertEquals(listOf(120), first)
        assertTrue(second.isEmpty())
    }

    @Test
    fun `ending the run lets the callback go and stops readings`() {
        val got = ArrayList<Int>()
        val central = running(got)
        val gatt = central.currentGatt!!
        central.disconnect()
        assertFalse(central.hasReadingCallback)
        assertFalse(central.hasConnection)
        central.onMeasurement(gatt, bytes(0x00, 130), 1L)
        idle()
        assertTrue(got.isEmpty())
        // The run's one source is free again.
        central.connect(strap, allowed = added) { bpm, _ -> got.add(bpm) }
        central.onMeasurement(central.currentGatt!!, bytes(0x00, 131), 1L)
        idle()
        assertEquals(listOf(131), got)
    }

    // Every public mutator is main-confined.

    @Test
    fun `every mutator called from another thread runs on the main thread`() {
        bluetoothOn()
        val got = ArrayList<Int>()
        // The stored list is read on the hop, not on the caller's thread.
        SettingsStore.get(app).heartRateDevices = added
        val fake = FakeScan()
        val central = HeartRateCentral(app, fake)
        fun background(block: () -> Unit) = Thread(block).also { it.start(); it.join() }

        background { central.connect(strap, allowed = null) { bpm, _ -> got.add(bpm) } }
        assertFalse("connect touched state off the main thread", central.hasReadingCallback)
        assertFalse(central.hasConnection)
        idle()
        assertTrue(central.hasReadingCallback)
        assertTrue(central.hasConnection)

        background { central.startScan() }
        assertEquals("startScan ran off the main thread", 0, fake.starts)
        idle()
        assertEquals(1, fake.starts)

        background { central.refreshRadio() }
        background { central.stopScan() }
        idle()
        background { central.disconnect() }
        assertTrue("disconnect touched state off the main thread", central.hasReadingCallback)
        idle()
        assertFalse(central.hasReadingCallback)
        assertFalse(central.hasConnection)
        idleFor(HeartRateCentral.LINGER_MS + 100)
        assertEquals(1, fake.stops)
        assertEquals(0, central.offMainMutations)
    }

    @Test
    fun `calls from the main thread run at once`() {
        bluetoothOn()
        val central = central()
        central.connect(strap, allowed = added) { _, _ -> }
        assertTrue(central.hasReadingCallback)
        central.disconnect()
        assertFalse(central.hasReadingCallback)
    }

    // One radio scan, shared, and its failures said in words.

    private fun scanning(): Pair<HeartRateCentral, FakeScan> {
        bluetoothOn()
        val fake = FakeScan()
        return HeartRateCentral(app, fake) to fake
    }

    @Test
    fun `asking for the scan twice starts it once`() {
        val (central, fake) = scanning()
        central.startScan()
        central.startScan()
        assertEquals(1, fake.starts)
        assertEquals(HeartRateRadioState.On, central.radio.value)
    }

    @Test
    fun `a close then an open inside the window keeps the one scan`() {
        val (central, fake) = scanning()
        central.startScan()
        central.stopScan()      // a rotation: dispose...
        idleFor(500)
        central.startScan()     // ...and recreate
        idleFor(HeartRateCentral.LINGER_MS + 100)
        assertEquals(1, fake.starts)
        assertEquals("never stopped while the sheet is open", 0, fake.stops)
        central.stopScan()
        idleFor(HeartRateCentral.LINGER_MS + 100)
        assertEquals(1, fake.stops)
        assertFalse(central.isScanning)
    }

    @Test
    fun `a scan the system refuses is said in words`() {
        val (central, fake) = scanning()
        fake.result = false
        central.startScan()
        assertEquals(HeartRateRadioState.ScanFailed, central.radio.value)
        assertFalse(central.radio.value.isLooking)
        // Opening the sheet again tries again, and a start that works clears the message.
        fake.result = true
        central.startScan()
        assertEquals(HeartRateRadioState.On, central.radio.value)
        assertEquals(2, fake.starts)
    }

    @Test
    fun `a scan that throws is said in words`() {
        val (central, fake) = scanning()
        fake.throws = true
        central.startScan()
        assertEquals(HeartRateRadioState.ScanFailed, central.radio.value)
        assertFalse(central.isScanning)
    }

    @Test
    fun `a scan the system fails later is said in words`() {
        val (central, _) = scanning()
        central.startScan()
        central.onScanFailure()
        assertEquals(HeartRateRadioState.ScanFailed, central.radio.value)
        assertFalse(central.isScanning)
    }

    @Test
    fun `what a closed sheet found is not shown to the next one`() {
        val (central, _) = scanning()
        central.startScan()
        central.onAdvertisement(strap, "Polar H10", -60)
        assertEquals(listOf(strap), central.found.value.map { it.address })
        central.stopScan()
        idleFor(HeartRateCentral.LINGER_MS + 100)
        central.startScan()
        assertEquals(emptyList<Any>(), central.found.value)
    }

    @Test
    fun `a rotation keeps the list the sheet had`() {
        val (central, _) = scanning()
        central.startScan()
        central.onAdvertisement(strap, "Polar H10", -60)
        central.stopScan()
        central.startScan()
        assertEquals(listOf(strap), central.found.value.map { it.address })
    }

    @Test
    fun `a run's probe never writes the sheet's list`() = runBlocking {
        val (central, _) = scanning()
        val probe = CoroutineScope(Dispatchers.Main.immediate).async { central.inRange(added, withinMs = 3_000) }
        central.onAdvertisement(strap, "Polar H10", -60)
        assertEquals("the sheet is not open", emptyList<Any>(), central.found.value)
        idleFor(3_100)
        assertEquals(listOf(strap), probe.getCompleted().map { it.id })
    }

    @Test
    fun `a probe ending leaves the sheet's scan and a probe uses the sheet's scan`() = runBlocking {
        val (central, fake) = scanning()
        central.startScan()
        val probe = CoroutineScope(Dispatchers.Main.immediate).async { central.inRange(added, withinMs = 1_000) }
        idleFor(1_100)
        probe.getCompleted()
        idleFor(HeartRateCentral.LINGER_MS + 100)
        assertEquals("one scan serves both", 1, fake.starts)
        assertEquals("the sheet still wants it", 0, fake.stops)
        assertTrue(central.isScanning)
    }

    // Bluetooth going away and coming back.

    @Test
    fun `Bluetooth going off closes the link so it can be made again when it returns`() {
        val got = ArrayList<Int>()
        val central = running(got)
        val adapter = shadowOf(BluetoothAdapter.getDefaultAdapter())
        adapter.setEnabled(false)
        central.onRadioBroadcast()
        assertFalse("a stale handle would block the reconnect", central.hasConnection)
        assertEquals("the run still wants that strap", true, central.hasReadingCallback)
        adapter.setEnabled(true)
        central.onRadioBroadcast()
        assertTrue(central.hasConnection)
    }

    @Test
    fun `without the permission the sheet says so instead of looking for ever`() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true)
        val central = central()
        central.startScan()
        assertEquals(HeartRateRadioState.Denied, central.radio.value)
        assertFalse(central.isScanning)
        central.stopScan()
    }

    @Test
    fun `an in range look with nothing added or no permission finds nothing and scans nothing`() = runBlocking {
        val central = central()
        assertEquals(emptyList<AddedHeartRateDevice>(), central.inRange(emptyList(), withinMs = 10))
        assertEquals(emptyList<AddedHeartRateDevice>(), central.inRange(added, withinMs = 10))
        assertFalse(central.isScanning)
    }

    // The source rules: what no unit test of behaviour could notice being broken.

    private val main = File("src/main/java/com/dugcanlift/macrocalc")
    private val central = File(main, "HeartRateCentral.kt")

    private fun kotlinSources() = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `nothing but the central itself constructs one`() {
        assertTrue(central.isFile)
        val constructors = Regex("""(?<![A-Za-z0-9_])HeartRateCentral\(""")
        val offenders = kotlinSources().filter { it != central && constructors.containsMatchIn(it.readText()) }
        assertEquals(emptyList<File>(), offenders)
        assertEquals("the file builds its one instance in get()", 1, constructors.findAll(central.readText()).count())
    }

    @Test
    fun `only the central and the watch link open a GATT connection or scan`() {
        val offenders = kotlinSources()
            .filter { "connectGatt(" in it.readText() || "bluetoothLeScanner" in it.readText() }
            .map { it.name }.sorted()
        assertEquals(listOf("HeartRateCentral.kt", "WatchLinkTransport.kt"), offenders)
    }

    @Test
    fun `a connection is made by address from the added list and never from a scan result`() {
        val text = central.readText()
        assertTrue("getRemoteDevice(" in text)
        assertEquals("one place connects", 1, Regex("""\.connectGatt\(""").findAll(text).count())
        assertFalse("result.device.connectGatt" in text)
        // The scan is filtered to the Heart Rate service.
        assertTrue("setServiceUuid(ParcelUuid(HEART_RATE_SERVICE))" in text)
        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", HeartRateCentral.HEART_RATE_SERVICE.toString())
    }

    @Test
    fun `the central holds no Activity or screen`() {
        val text = central.readText()
        for (needle in listOf("import android.app.Activity", "LocalContext", "androidx.compose", "androidx.activity")) {
            assertFalse("$needle in HeartRateCentral.kt", needle in text)
        }
    }

    @Test
    fun `the central asks for no permission itself`() {
        assertFalse("requestPermissions" in central.readText())
    }
}
