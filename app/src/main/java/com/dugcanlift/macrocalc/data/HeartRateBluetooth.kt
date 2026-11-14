package com.dugcanlift.macrocalc.data

// The Bluetooth heart rate path, as values: no Android framework type in this file, so every rule
// here is tested on the JVM without a radio. `HeartRateCentral` is the thin shell that feeds these.
// A device is named by its Bluetooth address, as `AddedHeartRateDevice.id` already is.

/** A device seen while scanning, as the add sheet lists it. */
data class DiscoveredHeartRateDevice(val address: String, val name: String, val rssi: Int?)

object HeartRateScan {
    const val UNNAMED = "Heart rate device"

    /** A device that advertises no name still gets a row. */
    fun displayName(raw: String?): String = raw?.trim().orEmpty().ifEmpty { UNNAMED }

    /**
     * Folds one advertisement into the list: one row per address, in the order first seen (so rows
     * do not jump about as signals change), the newest signal, and a real name replacing the
     * fallback but never the other way round.
     */
    fun merged(list: List<DiscoveredHeartRateDevice>, address: String, name: String?, rssi: Int?): List<DiscoveredHeartRateDevice> {
        val shown = displayName(name)
        val at = list.indexOfFirst { it.address == address }
        if (at < 0) return list + DiscoveredHeartRateDevice(address, shown, rssi)
        val old = list[at]
        return list.toMutableList().also {
            it[at] = old.copy(name = if (shown != UNNAMED) shown else old.name, rssi = rssi)
        }
    }
}

object HeartRateRange {
    /**
     * The added devices that were seen, in the order they were added -- not the order they were
     * discovered, which is whichever advertised first. Automatic takes the first of this list, so
     * the person's own order is their preference. A device nobody added is never in it.
     */
    fun inRange(added: List<AddedHeartRateDevice>, seen: Set<String>): List<AddedHeartRateDevice> {
        val kept = HashSet<String>()
        return added.filter { it.id in seen && kept.add(it.id) }
    }
}

object HeartRateDevices {
    private val ADDRESS = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

    /** `BluetoothAdapter.getRemoteDevice` throws on anything else, so nothing else reaches it. */
    fun isAddress(text: String): Boolean = ADDRESS.matches(text)

    /** Adding a device already in the list keeps its place and refreshes its name. */
    fun adding(device: AddedHeartRateDevice, list: List<AddedHeartRateDevice>): List<AddedHeartRateDevice> {
        val at = list.indexOfFirst { it.id == device.id }
        return if (at < 0) list + device else list.toMutableList().also { it[at] = device }
    }

    /**
     * A removed device cannot stay chosen: the person's choice falls back to Automatic rather than
     * to a source they never picked.
     */
    fun settingAfterRemoving(setting: HeartRateSetting, id: String): HeartRateSetting =
        if (setting is HeartRateSetting.Device && setting.id == id) HeartRateSetting.Automatic else setting
}

/**
 * What the radio is doing, as far as the add sheet cares. [of] maps what the phone says onto this,
 * so the words the sheet shows can be tested without a radio and the sheet never says "Looking" for
 * ever when Bluetooth is denied or off.
 */
enum class HeartRateRadioState {
    /** Not asked yet. */
    Unknown,
    On,
    Off,

    /** The Bluetooth permission is not granted (declined, or not asked yet). */
    Denied,
    Unsupported,

    /** Android 11 and older tie a BLE scan to Location being switched on. */
    LocationOff;

    /**
     * What the add sheet says while it has nothing to list. Only what the person can fix gets its
     * own words; an unsettled radio or a working one is still looking.
     */
    val emptyListMessage: String
        get() = when (this) {
            Denied -> "Bluetooth is off for LIFT. Turn it on in the Settings app."
            Off -> "Bluetooth is turned off. Turn it on to look for heart rate devices."
            Unsupported -> "This device can't look for Bluetooth heart rate devices."
            LocationOff -> "Android 11 and older need Location turned on to look for heart rate devices."
            Unknown, On -> LOOKING
        }

    /** Whether the sheet is actually searching (and so shows its spinner). */
    val isLooking: Boolean
        get() = when (this) {
            Unknown, On -> true
            Off, Denied, Unsupported, LocationOff -> false
        }

    companion object {
        const val LOOKING = "Looking for heart rate devices…"

        /** Unsupported, then not allowed, then switched off, then Location: the first that fails. */
        fun of(supported: Boolean, permitted: Boolean, enabled: Boolean, locationOk: Boolean): HeartRateRadioState = when {
            !supported -> Unsupported
            !permitted -> Denied
            !enabled -> Off
            !locationOk -> LocationOff
            else -> On
        }
    }
}

/**
 * Who needs the radio scanning right now. The add sheet's list and the run's range probe share one
 * scan: one starting must not restart the other's, and one ending must not stop the other's.
 * [acquire] says whether the radio scan has to be started, [release] whether it can now be stopped.
 */
class HeartRateScanDemand {
    enum class Reason { Sheet, Probe }

    private val holders = HashSet<Reason>()

    val wanted: Boolean get() = holders.isNotEmpty()

    /** True when nothing was scanning before: start the radio. Asking twice is one hold. */
    fun acquire(reason: Reason): Boolean {
        val wasIdle = holders.isEmpty()
        holders.add(reason)
        return wasIdle
    }

    /** True when this was the last hold: stop the radio. Releasing what was not held changes nothing. */
    fun release(reason: Reason): Boolean = holders.remove(reason) && holders.isEmpty()
}

/**
 * Reconnecting while a run records: the same device, and only that one. A run has one source, so a
 * drop is never answered by trying another device (or the watch); the number simply hides while
 * nothing arrives ([LiveHeartRate.STALE_AFTER_MS]) and returns with the next reading.
 */
class HeartRateReconnect {
    /** Connect [id] again after waiting [delayMs]. */
    data class Attempt(val id: String, val delayMs: Long)

    /** The device this run is reading, null when no run is recording. */
    var target: String? = null
        private set
    private var failures = 0

    /**
     * Starts a run's connection. Ignored while one is already held (the source is fixed at the
     * start) and for a device nobody added: only the person's own list can be connected to,
     * whatever a caller upstream passes.
     */
    fun begin(id: String, among: List<AddedHeartRateDevice>) {
        if (target != null || among.none { it.id == id }) return
        target = id
        failures = 0
    }

    fun end() {
        target = null
        failures = 0
    }

    /** The attempt to make after [id] dropped (at once), or null to leave it. */
    fun onDisconnect(id: String): Attempt? = target?.takeIf { it == id }?.let { Attempt(it, 0) }

    /**
     * The same device, and how long to wait, after an attempt to connect *failed*: two seconds,
     * then five for every try after, so a device that keeps refusing is not hammered.
     */
    fun onFailure(id: String): Attempt? {
        val current = target?.takeIf { it == id } ?: return null
        val delay = RETRY_DELAYS_MS[minOf(failures, RETRY_DELAYS_MS.size - 1)]
        failures += 1
        return Attempt(current, delay)
    }

    /** A connection was made: the next failure starts the delays again. */
    fun connected() {
        failures = 0
    }

    companion object {
        val RETRY_DELAYS_MS = listOf(2_000L, 5_000L)
    }
}
