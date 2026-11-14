package com.dugcanlift.macrocalc

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.dugcanlift.macrocalc.data.AddedHeartRateDevice
import com.dugcanlift.macrocalc.data.DiscoveredHeartRateDevice
import com.dugcanlift.macrocalc.data.HeartRateDevices
import com.dugcanlift.macrocalc.data.HeartRateMeasurement
import com.dugcanlift.macrocalc.data.HeartRateRadioState
import com.dugcanlift.macrocalc.data.HeartRateRange
import com.dugcanlift.macrocalc.data.HeartRateReconnect
import com.dugcanlift.macrocalc.data.HeartRateScan
import com.dugcanlift.macrocalc.data.HeartRateScanDemand
import com.dugcanlift.macrocalc.data.SettingsStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The radio side of heart rate: a BLE central that finds Bluetooth heart rate devices (service
 * 0x180D), tells which of the ones the person added are nearby, and streams one device's readings
 * during a run. Every rule that can be decided without a radio lives in
 * `data/HeartRateBluetooth.kt` and is tested there; this is the thin shell that feeds it. **It is
 * unproven on hardware**: there was no strap to try it against when it was written.
 *
 * **It never connects to a device nobody added.** A scan only lists. A connection is made by
 * `getRemoteDevice(address)` for an address the caller hands over, which must be in the person's
 * own list ([connect] refuses anything else); no scan result is ever connected to.
 *
 * **One instance for the whole app** ([get]): a run and Settings' add-a-device sheet share it, so
 * neither can end the other's scan or connection. Creating it touches no radio: no scanner, no
 * GATT, no receiver. The radio is first used when the person opens the add sheet, or a run looks
 * for an added device. This class never asks for a permission (only an Activity can): it checks
 * them and says so through [radio].
 *
 * All state lives on the main thread. Bluetooth callbacks arrive on binder threads and hop to it
 * before touching anything. The reading callback is dropped by [disconnect], so a run that forgets
 * to end cannot keep its owner alive; it should still capture no Activity.
 */
class HeartRateCentral internal constructor(context: Context) {
    private val appContext = context.applicationContext
    private val manager: BluetoothManager? = appContext.getSystemService(BluetoothManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val _radio = MutableStateFlow(HeartRateRadioState.Unknown)

    /** What the radio is doing, for the add sheet's words. Updated by [refreshRadio] and by system broadcasts. */
    val radio: StateFlow<HeartRateRadioState> = _radio.asStateFlow()

    private val _found = MutableStateFlow<List<DiscoveredHeartRateDevice>>(emptyList())

    /** Devices advertising nearby while the add sheet is scanning, one row per address. */
    val found: StateFlow<List<DiscoveredHeartRateDevice>> = _found.asStateFlow()

    // Scanning: the sheet's list and a run's range probe share one radio scan.
    private val demand = HeartRateScanDemand()
    private var scanning = false
    private var probing = false
    private val seen = HashSet<String>()

    // A run's connection.
    private val reconnect = HeartRateReconnect()
    private var gatt: BluetoothGatt? = null
    private var onReading: ((bpm: Int, atEpochMs: Long) -> Unit)? = null

    /** The link is carrying readings (services found, notifications on) -- as opposed to merely connected. */
    private var working = false
    private var pendingAutoConnect = false
    private var receiverRegistered = false

    // For tests, and for the sheet's "is anything going on" checks.
    internal val isScanning: Boolean get() = scanning
    internal val hasConnection: Boolean get() = gatt != null
    internal val hasReadingCallback: Boolean get() = onReading != null
    internal val hasStartedRadio: Boolean get() = receiverRegistered || scanning || gatt != null

    // ---- the radio's state ------------------------------------------------------------------

    /** Re-reads permission, Bluetooth and Location (on Android 11 and older) into [radio]. */
    fun refreshRadio() {
        _radio.value = computeRadio()
    }

    private fun computeRadio(): HeartRateRadioState {
        val adapter = manager?.adapter
        return HeartRateRadioState.of(
            supported = adapter != null && appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
            permitted = hasPermission(scanPermission) && hasPermission(connectPermission),
            enabled = adapter?.isEnabled == true,
            locationOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || locationOn(),
        )
    }

    /** Registered on first use, not at creation, so merely opening Settings starts nothing. */
    private fun ensureStarted() {
        if (receiverRegistered) return
        receiverRegistered = true
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(appContext, stateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshRadio()
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshRadio()
            if (_radio.value != HeartRateRadioState.On) {
                // Bluetooth went away: the scan handle is dead with it. Starting again waits for On.
                scanning = false
                return
            }
            if (demand.wanted) startRadioScan()
            if (reconnect.target != null && gatt == null) attemptConnection(autoConnect = true)
        }
    }

    // ---- scanning: the add sheet ------------------------------------------------------------

    /** Starts listing heart rate devices into [found]. Safe to call again (after a permission is granted). */
    fun startScan() {
        ensureStarted()
        refreshRadio()
        demand.acquire(HeartRateScanDemand.Reason.Sheet)
        startRadioScan()
    }

    /** Stops the sheet's list. A run's range probe, if one is going, keeps the scan. */
    fun stopScan() {
        if (demand.release(HeartRateScanDemand.Reason.Sheet)) stopRadioScan()
    }

    // ---- scanning: in range -----------------------------------------------------------------

    /**
     * The added devices that are nearby right now, in the order they were added. Looks for
     * [withinMs] (3 s: a strap advertises about once a second). Empty when Bluetooth is off or not
     * allowed, when nothing was added, and while another probe is running.
     */
    suspend fun inRange(added: List<AddedHeartRateDevice>, withinMs: Long = 3_000L): List<AddedHeartRateDevice> =
        withContext(Dispatchers.Main.immediate) {
            if (added.isEmpty() || probing) return@withContext emptyList()
            ensureStarted()
            refreshRadio()
            if (_radio.value != HeartRateRadioState.On) return@withContext emptyList()
            seen.clear()
            probing = true
            demand.acquire(HeartRateScanDemand.Reason.Probe)
            startRadioScan()
            try {
                delay(withinMs)
            } finally {
                probing = false
                if (demand.release(HeartRateScanDemand.Reason.Probe)) stopRadioScan()
            }
            val found = seen.toSet()
            seen.clear()
            HeartRateRange.inRange(added, found)
        }

    @SuppressLint("MissingPermission")
    private fun startRadioScan() {
        if (scanning || !demand.wanted || _radio.value != HeartRateRadioState.On) return
        val scanner = manager?.adapter?.bluetoothLeScanner ?: return
        // Only heart rate devices (0x180D). Nothing else nearby is listed or recorded.
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }.isSuccess
    }

    @SuppressLint("MissingPermission")
    private fun stopRadioScan() {
        if (!scanning) return
        scanning = false
        if (hasPermission(scanPermission)) runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address ?: return
            val name = result.scanRecord?.deviceName
                ?: (if (hasPermission(connectPermission)) runCatching { result.device.name }.getOrNull() else null)
            val rssi = result.rssi.takeIf { it != RSSI_NOT_AVAILABLE }
            main.post { onAdvertisement(address, name, rssi) }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post { scanning = false }
        }
    }

    private fun onAdvertisement(address: String, name: String?, rssi: Int?) {
        if (probing) seen.add(address)
        if (demand.wanted) _found.value = HeartRateScan.merged(_found.value, address, name, rssi)
    }

    // ---- a run's connection -----------------------------------------------------------------

    /**
     * Reads one device's heart rate until [disconnect]. A drop is answered by connecting the same
     * device again, never another; a second [connect] for a different device while a run holds one
     * is ignored (one source per run). An address that is not in [allowed] -- the person's added
     * list, read from storage when not handed over -- is refused, so nothing upstream can make this
     * connect to a stranger's strap. [onReading] is called on the main thread with a bpm above zero
     * and the epoch milliseconds it arrived.
     */
    fun connect(
        address: String,
        allowed: List<AddedHeartRateDevice>? = null,
        onReading: (bpm: Int, atEpochMs: Long) -> Unit,
    ) {
        val added = allowed ?: SettingsStore.get(appContext).heartRateDevices
        reconnect.begin(address, added)
        if (reconnect.target != address) return
        this.onReading = onReading
        ensureStarted()
        attemptConnection(autoConnect = false)
    }

    /** Ends the run's connection: no reconnect, no more readings, and the callback is let go. */
    fun disconnect() {
        main.removeCallbacks(retry)
        reconnect.end()
        onReading = null
        closeGatt()
    }

    private val retry = Runnable { attemptConnection(pendingAutoConnect) }

    @SuppressLint("MissingPermission")
    private fun attemptConnection(autoConnect: Boolean) {
        val address = reconnect.target ?: return
        if (gatt != null) return   // an attempt is already live
        val adapter = manager?.adapter ?: return
        if (!adapter.isEnabled || !hasPermission(connectPermission) || !HeartRateDevices.isAddress(address)) return
        // By address, from the person's own list: never from a scan result.
        val device = runCatching { adapter.getRemoteDevice(address.uppercase()) }.getOrNull() ?: return
        working = false
        gatt = connectGatt(device, autoConnect)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")   // the overload without a PHY is the only one at minSdk 26
    private fun connectGatt(device: android.bluetooth.BluetoothDevice, autoConnect: Boolean): BluetoothGatt? =
        runCatching { device.connectGatt(appContext, autoConnect, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE) }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null
        working = false
        if (hasPermission(connectPermission)) runCatching { g.disconnect(); g.close() }
    }

    /** A link ended. A working one that dropped is retried at once; one that never worked, after a pause. */
    private fun linkEnded() {
        val address = reconnect.target
        val wasWorking = working
        closeGatt()
        if (address == null) return
        val next = (if (wasWorking) reconnect.onDisconnect(address) else reconnect.onFailure(address)) ?: return
        // A drop waits for the device to come back (autoConnect); a failure to connect tries direct.
        pendingAutoConnect = wasWorking
        main.removeCallbacks(retry)
        if (next.delayMs == 0L) attemptConnection(pendingAutoConnect) else main.postDelayed(retry, next.delayMs)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g !== gatt) return@post
                when {
                    newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS ->
                        if (!runCatching { g.discoverServices() }.getOrDefault(false)) linkEnded()
                    newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS -> linkEnded()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g !== gatt) return@post
                val characteristic = g.getService(HEART_RATE_SERVICE)?.getCharacteristic(HEART_RATE_MEASUREMENT)
                // Not a heart rate device after all: a failure, not a drop, so it backs off.
                if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null || !enableNotifications(g, characteristic)) linkEnded()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (g !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    working = true
                    reconnect.connected()
                } else {
                    linkEnded()
                }
            }
        }

        @Deprecated("Below API 33 this is the only callback; above it the three-argument one is called instead.")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != HEART_RATE_MEASUREMENT) return
            @Suppress("DEPRECATION") val value = characteristic.value ?: return
            onMeasurement(g, value.copyOf(), System.currentTimeMillis())
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid != HEART_RATE_MEASUREMENT) return
            onMeasurement(g, value.copyOf(), System.currentTimeMillis())
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")   // the pre-API-33 descriptor write, still the only one at minSdk 26
    private fun enableNotifications(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        g.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(CCCD) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)
        }
    }

    /** Binder thread in, main thread out. Garbled bytes and a zero are no reading. */
    private fun onMeasurement(g: BluetoothGatt, data: ByteArray, atEpochMs: Long) {
        val bpm = HeartRateMeasurement.bpm(data) ?: return
        main.post { if (g === gatt) deliver(g.device.address, bpm, atEpochMs) }
    }

    /** What reaches the run: only the run's own device, only while the run holds it. */
    internal fun deliver(address: String, bpm: Int, atEpochMs: Long) {
        if (reconnect.target?.equals(address, ignoreCase = true) != true) return
        onReading?.invoke(bpm, atEpochMs)
    }

    /** Test seam for [onMeasurement]'s thread hop: bytes as a strap sends them, from any thread. */
    internal fun receiveForTest(address: String, data: ByteArray, atEpochMs: Long) {
        val bpm = HeartRateMeasurement.bpm(data) ?: return
        main.post { deliver(address, bpm, atEpochMs) }
    }

    // ---- permissions --------------------------------------------------------------------------

    private fun locationOn(): Boolean {
        val lm = appContext.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else @Suppress("DEPRECATION") (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** 0x180D, the Bluetooth SIG's Heart Rate service. */
        val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")

        /** 0x2A37, Heart Rate Measurement. */
        val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** `ScanResult.rssi` for "not available". */
        private const val RSSI_NOT_AVAILABLE = 127

        /** What the add sheet asks for. Location only below Android 12, where BLE scanning needs it. */
        val runtimePermissions: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        /** On API 30 and below the install-time BLUETOOTH permission covers connecting. */
        private val connectPermission: String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH

        private val scanPermission: String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION

        @Volatile private var instance: HeartRateCentral? = null

        /** The app's one heart rate radio. Creating it creates no scan, no connection and no prompt. */
        fun get(context: Context): HeartRateCentral =
            instance ?: synchronized(this) { instance ?: HeartRateCentral(context).also { instance = it } }
    }
}
