package com.dugcanlift.macrocalc

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.dugcanlift.macrocalc.data.AddedHeartRateDevice
import com.dugcanlift.macrocalc.data.HeartRateRadioState
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

    @Test
    fun `a reading from the strap reaches the run on the main thread`() {
        val central = central()
        val got = ArrayList<Pair<Int, Long>>()
        val threads = ArrayList<Thread>()
        central.connect(strap, allowed = added) { bpm, at -> got.add(bpm to at); threads.add(Thread.currentThread()) }
        assertTrue(central.hasReadingCallback)
        // A reading arrives on a binder thread: nothing is delivered until the main looper runs it.
        Thread { central.receiveForTest(strap, bytes(0x00, 142), 1_000L) }.also { it.start(); it.join() }
        assertTrue("delivered off the main thread", got.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(142 to 1_000L), got)
        assertEquals(listOf(Looper.getMainLooper().thread), threads)
    }

    @Test
    fun `garbled bytes and a zero reach nobody`() {
        val central = central()
        val got = ArrayList<Int>()
        central.connect(strap, allowed = added) { bpm, _ -> got.add(bpm) }
        for (bytes in listOf(ByteArray(0), byteArrayOf(0x00), byteArrayOf(0x01, 0x2C), byteArrayOf(0x00, 0x00))) {
            central.receiveForTest(strap, bytes, 1L)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(got.isEmpty())
    }

    @Test
    fun `another device's reading never reaches the run`() {
        val central = central()
        val got = ArrayList<Int>()
        central.connect(strap, allowed = added + AddedHeartRateDevice(stranger, "Other")) { bpm, _ -> got.add(bpm) }
        central.receiveForTest(stranger, bytes(0x00, 99), 1L)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("one source per run", got.isEmpty())
    }

    @Test
    fun `a second device mid run is ignored and does not replace the callback`() {
        val central = central()
        val first = ArrayList<Int>()
        val second = ArrayList<Int>()
        val both = added + AddedHeartRateDevice(stranger, "Other")
        central.connect(strap, allowed = both) { bpm, _ -> first.add(bpm) }
        central.connect(stranger, allowed = both) { bpm, _ -> second.add(bpm) }
        central.receiveForTest(strap, bytes(0x00, 120), 1L)
        central.receiveForTest(stranger, bytes(0x00, 121), 1L)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(120), first)
        assertTrue(second.isEmpty())
    }

    @Test
    fun `ending the run lets the callback go and stops readings`() {
        val central = central()
        val got = ArrayList<Int>()
        central.connect(strap, allowed = added) { bpm, _ -> got.add(bpm) }
        central.disconnect()
        assertFalse(central.hasReadingCallback)
        assertFalse(central.hasConnection)
        central.receiveForTest(strap, bytes(0x00, 130), 1L)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(got.isEmpty())
        // The run's one source is free again.
        central.connect(strap, allowed = added) { bpm, _ -> got.add(bpm) }
        central.receiveForTest(strap, bytes(0x00, 131), 1L)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(131), got)
    }

    @Test
    fun `a phone without Bluetooth LE says so`() {
        val central = central()
        central.startScan()
        assertEquals(HeartRateRadioState.Unsupported, central.radio.value)
        assertFalse(central.isScanning)
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
