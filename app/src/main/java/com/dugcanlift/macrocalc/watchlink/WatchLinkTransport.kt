package com.dugcanlift.macrocalc.watchlink

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
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
import com.dugcanlift.liftkit.link.AckOutcome
import com.dugcanlift.liftkit.link.CodecEvent
import com.dugcanlift.liftkit.link.FinishedSession
import com.dugcanlift.liftkit.link.Hello
import com.dugcanlift.liftkit.link.LinkAck
import com.dugcanlift.liftkit.link.LinkCodec
import com.dugcanlift.liftkit.link.LinkError
import com.dugcanlift.liftkit.link.LinkErrorMessage
import com.dugcanlift.liftkit.link.LinkEvent
import com.dugcanlift.liftkit.link.LinkMessage
import com.dugcanlift.liftkit.link.LinkPayloads
import com.dugcanlift.liftkit.link.LinkProtocol
import com.dugcanlift.liftkit.link.LinkRole
import com.dugcanlift.liftkit.link.LinkSession
import com.dugcanlift.liftkit.link.LinkState
import com.dugcanlift.liftkit.link.LoggedSetReport
import com.dugcanlift.liftkit.link.MessageType
import com.dugcanlift.liftkit.link.Plan
import com.dugcanlift.liftkit.link.PlanSource
import com.dugcanlift.liftkit.link.Reaction
import com.dugcanlift.macrocalc.data.RoutineRepository
import com.dugcanlift.macrocalc.data.ScheduledSessionRepository
import com.dugcanlift.macrocalc.data.WorkoutRepository
import com.dugcanlift.macrocalc.data.onDate
import com.dugcanlift.macrocalc.data.todayKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.UUID

/**
 * The phone's end of LIFT Link: a GATT **central** that finds the watch, bonds with it, and moves
 * frames for [LinkSession]. The protocol's rules live in the shared `:link` module; this only moves
 * bytes and keeps them in order. `docs/LINK-PROTOCOL.md` in the watch repo is the specification.
 *
 * **The phone scans once, to pair, and never again.** Afterwards it connects to the remembered
 * address with `autoConnect = true`, a pending connection the Bluetooth stack completes whenever
 * the watch next advertises — which it does only while LIFT is open on the wrist.
 *
 * **Bonding is required and enforced by the watch.** Its characteristics are encrypted, so the
 * first protected write fails with an authentication error and pairing starts; Android usually
 * does not retry that write for us, so [onBonded] does.
 *
 * Every GATT callback is marshalled onto the main thread. They arrive on binder threads, and the
 * write queue, the codec and the session are not built for concurrent access — one thread is the
 * simplest thing that is correct.
 *
 * Like `LocationTracker`, this checks its own permissions and returns rather than throwing;
 * *asking* for them is the screen's job.
 */
class WatchLinkTransport private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val store = WatchLinkStore.get(appContext)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    sealed interface Status {
        data object Idle : Status
        data object Scanning : Status
        data class Connecting(val name: String) : Status

        /** The system pairing dialog is up. */
        data class Bonding(val name: String) : Status

        /** Show [code]. [confirmedHere] once this phone's user has said yes. */
        data class Confirming(val code: String, val name: String, val confirmedHere: Boolean) : Status

        /** A remembered watch, not in range or not advertising: the pending connection is armed. */
        data class Waiting(val name: String) : Status
        data class Linked(val name: String) : Status
        data class Unavailable(val reason: String) : Status
        data class Failed(val reason: String) : Status
    }

    data class FoundWatch(val address: String, val name: String, val rssi: Int)

    private val _status = MutableStateFlow<Status>(if (store.isPaired) Status.Waiting(store.pairedName ?: "watch") else Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _found = MutableStateFlow<List<FoundWatch>>(emptyList())
    val found: StateFlow<List<FoundWatch>> = _found.asStateFlow()

    /** One line about the last thing that happened on the link, for the card. Not a log. */
    private val _lastEvent = MutableStateFlow<String?>(null)
    val lastEvent: StateFlow<String?> = _lastEvent.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var session: LinkSession? = null
    private var codec = LinkCodec()
    private var pairingName: String = "watch"
    private var bondReceiver: BroadcastReceiver? = null
    private var notificationsPending = false

    /** One write in flight at a time; ATT allows exactly one, and its response is our flow control. */
    private val outbound = ArrayDeque<ByteArray>()
    private var writing = false

    // ---- scanning, for pairing only -------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun startScan() {
        val reason = unavailableReason(forScan = true)
        if (reason != null) { _status.value = Status.Unavailable(reason); return }
        val scanner = manager?.adapter?.bluetoothLeScanner
            ?: run { _status.value = Status.Unavailable("Bluetooth is not ready."); return }
        _found.value = emptyList()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid(LinkProtocol.SERVICE_UUID))).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
            .onFailure { _status.value = Status.Unavailable("Could not start looking for your watch."); return }
        _status.value = Status.Scanning
        // A scan left running is a battery drain and, on Android 7+, a scan started five times in
        // thirty seconds is silently refused. Thirty seconds is enough to find a watch at arm's
        // length; "Look again" starts another.
        main.removeCallbacks(scanTimeout)
        main.postDelayed(scanTimeout, SCAN_MILLIS)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        main.removeCallbacks(scanTimeout)
        if (hasPermission(scanPermission)) runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (_status.value == Status.Scanning) _status.value = Status.Idle
    }

    private val scanTimeout = Runnable { stopScan() }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName
                ?: (if (hasPermission(connectPermission)) runCatching { result.device.name }.getOrNull() else null)
                ?: "Wear OS watch"
            val watch = FoundWatch(result.device.address, name, result.rssi)
            main.post {
                _found.value = (_found.value.filterNot { it.address == watch.address } + watch).sortedByDescending { it.rssi }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post { _status.value = Status.Unavailable("Could not look for your watch (error $errorCode).") }
        }
    }

    // ---- connecting --------------------------------------------------------------------------

    /** Pair with a watch the scan found. One connection at a time; any other is closed first. */
    @SuppressLint("MissingPermission")
    fun pair(watch: FoundWatch) {
        stopScan()
        val reason = unavailableReason(forScan = false)
        if (reason != null) { _status.value = Status.Unavailable(reason); return }
        val device = manager?.adapter?.getRemoteDevice(watch.address) ?: return
        pairingName = watch.name
        connect(device, autoConnect = false)
        _status.value = Status.Connecting(watch.name)
    }

    /**
     * Arm the pending connection to the remembered watch. Cheap and idempotent: call it whenever the
     * app comes to the foreground. The stack completes it when the watch next advertises.
     */
    @SuppressLint("MissingPermission")
    fun reconnectIfPaired() {
        val address = store.pairedAddress ?: return
        if (gatt != null) return
        if (unavailableReason(forScan = false) != null) return
        val device = manager?.adapter?.getRemoteDevice(address) ?: return
        pairingName = store.pairedName ?: "watch"
        connect(device, autoConnect = true)
        _status.value = Status.Waiting(pairingName)
    }

    /** This phone's user answered the confirmation prompt. */
    fun confirm(accepted: Boolean) {
        val current = session ?: return
        if (current.state != LinkState.CONFIRMING) return
        apply(current.confirm(accepted))
        val status = _status.value
        if (accepted && status is Status.Confirming) _status.value = status.copy(confirmedHere = true)
    }

    fun disconnect() {
        close()
        _status.value = if (store.isPaired) Status.Waiting(store.pairedName ?: "watch") else Status.Idle
    }

    /** Forget the watch. The system Bluetooth bond is the user's to remove in Settings — this stops
     *  LIFT talking to it, which is what the button promises. */
    fun forget() {
        store.forget()
        close()
        _status.value = Status.Idle
    }

    /**
     * Push [plan]. Returns false, and sends nothing, unless the link is paired and up — the session
     * enforces that too, but a button that does nothing should say so rather than throw.
     */
    fun pushPlan(plan: Plan): Boolean {
        val current = session ?: return false
        if (current.state != LinkState.READY) return false
        queue(current.pushPlan(plan))
        _lastEvent.value = "Sent ${plan.name} · ${plan.exercises.size} exercise${if (plan.exercises.size == 1) "" else "s"}"
        return true
    }

    /**
     * Today's plan, built from the routine scheduled for today and pushed. False when nothing is
     * scheduled or the link is not up; [pushRoutine] sends a routine that is not on the calendar.
     */
    fun pushToday(onResult: (Boolean) -> Unit = {}) {
        scope.launch {
            val routines = RoutineRepository.get(appContext).also { it.load() }.routines.value
            val scheduled = ScheduledSessionRepository.get(appContext).also { it.load() }.sessions.value
            val today = todayKey()
            val routine = scheduled.onDate(today).firstNotNullOfOrNull { s -> routines.firstOrNull { it.id == s.routineId } }
            onResult(routine != null && pushRoutine(routine.id, today, PlanSource.COACH_PLAN))
        }
    }

    /** Push a saved routine, for [day] if it is scheduled or for no particular day if not. */
    suspend fun pushRoutine(routineId: String, day: String?, source: PlanSource = PlanSource.ROUTINE): Boolean {
        val routine = RoutineRepository.get(appContext).also { it.load() }.routines.value.firstOrNull { it.id == routineId }
            ?: return false
        val history = WorkoutRepository.get(appContext).also { it.load() }.sessions.value
        val draft = WatchPlanMapper.plan(routine, day, source, history, revision = 1)
        val hash = WatchPlanMapper.fingerprint(draft)
        val revision = WatchPlanMapper.nextPlanRevision(store.pushedPlan(draft.planId), hash)
        val plan = draft.copy(revision = revision)
        if (!pushPlan(plan)) return false
        store.recordPlan(plan.planId, AppliedRevision(revision, hash))
        return true
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")   // deprecated in SDK 37; its replacement does not exist at minSdk 26
    private fun connect(device: BluetoothDevice, autoConnect: Boolean) {
        close()
        codec = LinkCodec()
        session = null
        notificationsPending = false
        gatt = device.connectGatt(appContext, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun close() {
        unregisterBondReceiver()
        if (hasPermission(connectPermission)) runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
        rx = null
        tx = null
        session = null
        outbound.clear()
        writing = false
    }

    // ---- GATT ---------------------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
            if (g != gatt) return@post
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _status.value = Status.Connecting(pairingName)
                    // Ask for a large MTU first; everything after waits for the answer, because
                    // the frame budget has to be right before the first frame is cut.
                    if (!g.requestMtu(LinkProtocol.REQUESTED_MTU)) g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasPaired = store.isPaired
                    close()
                    if (wasPaired) reconnectIfPaired() else if (_status.value !is Status.Failed) _status.value = Status.Idle
                }
            }
        }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
            if (g != gatt) return@post
            if (status == BluetoothGatt.GATT_SUCCESS) codec.frameBudget = LinkProtocol.frameBudget(mtu)
            g.discoverServices()
        }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
            if (g != gatt) return@post
            val service = g.getService(uuid(LinkProtocol.SERVICE_UUID))
            rx = service?.getCharacteristic(uuid(LinkProtocol.RX_CHARACTERISTIC_UUID))
            tx = service?.getCharacteristic(uuid(LinkProtocol.TX_CHARACTERISTIC_UUID))
            if (rx == null || tx == null) {
                fail("That device is not running LIFT.")
                return@post
            }
            enableNotifications(g)
        }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
            if (g != gatt) return@post
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> startSession(g)
                // The CCCD is encrypted, so an unbonded phone lands here. Bond, then try again.
                GATT_INSUFFICIENT_AUTHENTICATION, GATT_INSUFFICIENT_ENCRYPTION -> awaitBond(g)
                else -> fail("The watch would not open the link (status $status).")
            }
        }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            main.post {
            if (g != gatt) return@post
            writing = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // A failed write loses a frame, and a message with a frame missing is refused by
                // the watch's reassembler. Drop the rest of it rather than send a stump; the
                // sender can push again.
                outbound.clear()
                if (status == GATT_INSUFFICIENT_AUTHENTICATION || status == GATT_INSUFFICIENT_ENCRYPTION) awaitBond(g)
                return@post
            }
            pump()
        }
        }

        @Deprecated("Below API 33 this is the only callback; above it the three-argument one is called instead.")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val value = characteristic.value ?: return
            val copy = value.copyOf()
            main.post { if (g == gatt) receive(copy) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            main.post { if (g == gatt) receive(copy) }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")   // the pre-API-33 descriptor write, still the only one at minSdk 26
    private fun enableNotifications(g: BluetoothGatt) {
        val characteristic = tx ?: return
        g.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(uuid(LinkProtocol.CCCD_UUID)) ?: return fail("The watch's link is incomplete.")
        notificationsPending = true
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)
        }
        if (!started) fail("Could not open the link to the watch.")
    }

    /** The link is encrypted and notifications are on: say hello. */
    private fun startSession(g: BluetoothGatt) {
        if (!notificationsPending) return
        notificationsPending = false
        unregisterBondReceiver()
        val device = g.device
        val next = LinkSession(
            role = LinkRole.CENTRAL,
            deviceName = phoneName(),
            nonce = ByteArray(Hello.NONCE_BYTES).also(random::nextBytes),
            alreadyPaired = store.isPaired(device.address),
        )
        session = next
        apply(next.start())
    }

    /**
     * Wait for the system to finish bonding, then retry the write that needed it. Android shows its
     * own pairing dialog here; this app never sees a passkey and never asks for one.
     */
    @SuppressLint("MissingPermission")
    private fun awaitBond(g: BluetoothGatt) {
        val device = g.device
        _status.value = Status.Bonding(pairingName)
        if (bondReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                    @Suppress("DEPRECATION")
                    val changed: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (changed?.address != device.address) return
                    when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                        BluetoothDevice.BOND_BONDED -> main.post { onBonded(g) }
                        BluetoothDevice.BOND_NONE -> main.post { fail("Bluetooth pairing did not complete.") }
                    }
                }
            }
            ContextCompat.registerReceiver(
                appContext, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            bondReceiver = receiver
        }
        if (hasPermission(connectPermission) && device.bondState == BluetoothDevice.BOND_NONE) device.createBond()
        if (device.bondState == BluetoothDevice.BOND_BONDED) onBonded(g)
    }

    private fun onBonded(g: BluetoothGatt) {
        if (g != gatt) return
        unregisterBondReceiver()
        _status.value = Status.Connecting(pairingName)
        if (session == null) enableNotifications(g) else pump()
    }

    private fun unregisterBondReceiver() {
        bondReceiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        bondReceiver = null
    }

    // ---- frames -----------------------------------------------------------------------------

    private fun receive(bytes: ByteArray) {
        val current = session ?: return
        when (val event = codec.accept(bytes)) {
            is CodecEvent.Incomplete -> Unit
            is CodecEvent.Complete -> apply(current.receive(event.message))
            is CodecEvent.Refused -> queue(
                LinkMessage(MessageType.ERROR, LinkPayloads.encodeError(LinkErrorMessage(event.error, event.detail)))
            )
        }
    }

    private fun apply(reaction: Reaction) {
        // Frames first, then the version. A HELLO is framed before this raises the codec, so the
        // handshake itself always goes out at a version every build that has ever existed can read --
        // which is the whole point of the version being byte 0, and what lets a version-2 phone talk
        // to a version-1 watch at all rather than be refused before it can say hello.
        reaction.send.forEach(::queue)
        session?.negotiatedVersion?.let { codec.version = it }
        reaction.events.forEach { event ->
            when (event) {
                is LinkEvent.ConfirmationCode -> _status.value = Status.Confirming(event.code, event.peerName, confirmedHere = false)
                is LinkEvent.Paired -> {
                    val address = gatt?.device?.address
                    val name = event.peerName.ifBlank { pairingName }
                    if (address != null) store.remember(address, name)
                    pairingName = name
                    _status.value = Status.Linked(name)
                }
                LinkEvent.PairingRejected -> {
                    fail("Pairing was refused.")
                }
                LinkEvent.PlanRequested -> pushToday()
                is LinkEvent.SetLogged -> onSetLogged(event.report)
                is LinkEvent.SessionFinished -> onSessionFinished(event.session)
                is LinkEvent.Acknowledged -> if (event.ack.ackType == MessageType.PLAN_PUSHED) {
                    _lastEvent.value = "Watch has the plan (revision ${event.ack.revision})"
                }
                is LinkEvent.Refused -> _lastEvent.value = "Watch refused a message: ${event.error.error.name}"
                is LinkEvent.Failed -> fail(
                    if (event.error.error == LinkError.UNSUPPORTED_VERSION)
                        "The watch app and this app speak different versions. Update both."
                    else "The link failed (${event.error.error.name})."
                )
                is LinkEvent.PlanReceived -> Unit   // only a watch receives plans; the session refuses it here
            }
        }
    }

    /**
     * Streamed sets are shown and not stored. [FinishedSession] is the source of truth — the schema
     * says so, and storing both would mean reconciling a half-session against a whole one.
     */
    private fun onSetLogged(report: LoggedSetReport) {
        val weight = report.set.weightKg?.let { "${WatchPlanMapper.kgToLb(it).let { lb -> if (lb % 1.0 == 0.0) lb.toLong().toString() else lb.toString() }} lb" }
        val reps = report.set.reps?.let { "× $it" }
        _lastEvent.value = listOfNotNull(report.exerciseName, weight, reps).joinToString(" ")
    }

    private fun onSessionFinished(finished: FinishedSession) {
        scope.launch {
            val repository = WorkoutRepository.get(appContext).also { it.load() }
            val applied = store.appliedSession(finished.sessionId)
            val onPhone = repository.sessions.value.firstOrNull { it.id == finished.sessionId }
            val outcome = WatchPlanMapper.reconcile(applied, finished.revision, onPhone)
            if (outcome == AckOutcome.INSERTED || outcome == AckOutcome.ACCEPTED) {
                val mapped = WatchPlanMapper.session(finished)
                repository.save(mapped)
                store.recordSession(finished.sessionId, AppliedRevision(finished.revision, WatchPlanMapper.fingerprint(mapped)))
            }
            // Only a stored outcome is acknowledged as stored; IGNORED is still answered, so the
            // watch can stop resending something this phone has decided to keep its own edit of.
            val current = session
            if (current != null && current.state == LinkState.READY) {
                queue(current.acknowledge(LinkAck(MessageType.SESSION_FINISHED, finished.sessionId, finished.revision, outcome)))
            }
            _lastEvent.value = when (outcome) {
                AckOutcome.INSERTED -> "Saved ${finished.name.ifBlank { "a session" }} from the watch"
                AckOutcome.ACCEPTED -> "Updated ${finished.name.ifBlank { "a session" }} from the watch"
                AckOutcome.IDEMPOTENT -> "Already had that session"
                AckOutcome.IGNORED -> "Kept your edits to ${finished.name.ifBlank { "that session" }}"
            }
        }
    }

    private fun queue(message: LinkMessage) {
        codec.frames(message).forEach(outbound::addLast)
        pump()
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")   // the pre-API-33 characteristic write, still the only one at minSdk 26
    private fun pump() {
        if (writing) return
        val g = gatt ?: return
        val characteristic = rx ?: return
        if (!hasPermission(connectPermission)) return
        val next = outbound.removeFirstOrNull() ?: return
        writing = true
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(characteristic, next, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = next
                g.writeCharacteristic(characteristic)
            }
        }.getOrDefault(false)
        if (!started) {
            writing = false
            outbound.clear()
        }
    }

    private fun fail(reason: String) {
        close()
        _status.value = Status.Failed(reason)
    }

    // ---- permissions and odds and ends --------------------------------------------------------

    /** Why the link cannot run right now, in words for the card, or null when it can. */
    fun unavailableReason(forScan: Boolean): String? {
        val adapter = manager?.adapter ?: return "This phone has no Bluetooth."
        if (!adapter.isEnabled) return "Turn Bluetooth on."
        if (!hasPermission(connectPermission)) return "LIFT needs permission to connect to your watch."
        if (forScan) {
            if (!hasPermission(scanPermission)) return "LIFT needs permission to find your watch."
            // Android 11 and older tie BLE scanning to location services being on, even with the
            // permission granted. Android 12+ does not, because the scan permission says
            // neverForLocation.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !locationOn()) {
                return "Android 11 and older need Location turned on to find a watch."
            }
        }
        return null
    }

    private fun locationOn(): Boolean {
        val lm = appContext.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else @Suppress("DEPRECATION") (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun phoneName(): String = listOfNotNull(
        Build.MANUFACTURER?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() },
        Build.MODEL?.takeIf { it.isNotBlank() },
    ).joinToString(" ").ifBlank { "Android phone" }

    private fun uuid(text: String): UUID = UUID.fromString(text)

    companion object {
        private const val SCAN_MILLIS = 30_000L

        /** Not named in `BluetoothGatt` below API 33's docs, but stable ATT error codes. */
        private const val GATT_INSUFFICIENT_AUTHENTICATION = 0x05
        private const val GATT_INSUFFICIENT_ENCRYPTION = 0x0F

        /** What the Watch card asks for. Location only below Android 12, where BLE scanning needs it. */
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

        private val random = SecureRandom()

        @Volatile private var instance: WatchLinkTransport? = null

        fun get(context: Context): WatchLinkTransport =
            instance ?: synchronized(this) { instance ?: WatchLinkTransport(context).also { instance = it } }
    }
}
