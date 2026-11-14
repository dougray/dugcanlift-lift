package com.dugcanlift.macrocalc.data

// Heart rate on a run: the rules, as values.
//
// Tracked and shown, never targeted: no zones, colors, thresholds or advice.
// Plain Kotlin only -- no Android framework type, so every rule here is tested on the JVM.
// Times are epoch milliseconds, as the rest of this app's outdoor code keeps them.

/**
 * The Bluetooth Heart Rate Measurement characteristic (0x2A37).
 *
 * Byte 0 is flags; bit 0 says whether the value is a UInt8 (clear) or a **little-endian** UInt16
 * (set). Standard Bluetooth is little-endian; LIFT Link's own payloads are big-endian -- do not
 * borrow that reader. The other flag bits (contact, energy, RR intervals) come after the value and
 * are not read.
 */
object HeartRateMeasurement {
    /**
     * Beats per minute, or null for data too short to hold one and for 0 -- a strap with no skin
     * contact reports 0, which is no reading, not a pulse. Bytes are unsigned (`and 0xFF`).
     */
    fun bpm(data: ByteArray): Int? {
        if (data.isEmpty()) return null
        val flags = data[0].toInt() and 0xFF
        val value = if (flags and 0x01 == 0) {
            if (data.size < 2) return null
            data[1].toInt() and 0xFF
        } else {
            if (data.size < 3) return null
            (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        }
        return value.takeIf { it > 0 }
    }
}

/**
 * What the heart rate setting says. Kept in [SettingsStore] as one short string
 * ([toStored] / [fromStored]); a device is named by its Bluetooth address.
 */
sealed class HeartRateSetting {
    object Automatic : HeartRateSetting()
    object Off : HeartRateSetting()
    object Watch : HeartRateSetting()
    data class Device(val id: String) : HeartRateSetting()

    fun toStored(): String = when (this) {
        Automatic -> "automatic"
        Off -> "off"
        Watch -> "watch"
        is Device -> "$DEVICE_PREFIX$id"
    }

    companion object {
        private const val DEVICE_PREFIX = "device:"

        /** Anything unset or unreadable is automatic, which never picks a device nobody added. */
        fun fromStored(stored: String?): HeartRateSetting = when {
            stored == "off" -> Off
            stored == "watch" -> Watch
            stored != null && stored.startsWith(DEVICE_PREFIX) && stored.length > DEVICE_PREFIX.length ->
                Device(stored.substring(DEVICE_PREFIX.length))
            else -> Automatic
        }
    }
}

/** A heart rate device the person added themselves, kept in the order added. */
data class AddedHeartRateDevice(val id: String, val name: String)

/** Where a run's heart rate comes from. */
sealed class LiveHeartRateSource {
    data class Device(val id: String, val name: String) : LiveHeartRateSource()
    object Watch : LiveHeartRateSource()
}

object HeartRateSourcePicker {
    /**
     * Automatic takes only what was added: the first added device in range, in the order added,
     * else the watch, else nothing. It never connects to a device nobody added, whatever is
     * advertising nearby. A chosen device that is out of range is null, not the watch: the person
     * picked that one.
     */
    fun pick(
        setting: HeartRateSetting,
        addedInRange: List<AddedHeartRateDevice>,
        watchAvailable: Boolean
    ): LiveHeartRateSource? = when (setting) {
        HeartRateSetting.Automatic -> addedInRange.firstOrNull()?.let { LiveHeartRateSource.Device(it.id, it.name) }
            ?: if (watchAvailable) LiveHeartRateSource.Watch else null
        is HeartRateSetting.Device -> addedInRange.firstOrNull { it.id == setting.id }
            ?.let { LiveHeartRateSource.Device(it.id, it.name) }
        HeartRateSetting.Watch -> if (watchAvailable) LiveHeartRateSource.Watch else null
        HeartRateSetting.Off -> null
    }
}

object LiveHeartRate {
    /**
     * No reading for this long hides the number: a stale pulse on a screen is worse than a dash.
     * A reading exactly this old still shows (9.9 s shows, 10.1 s hides).
     */
    const val STALE_AFTER_MS = 10_000L

    data class Reading(val bpm: Int, val atEpochMs: Long)

    fun shown(last: Reading?, nowEpochMs: Long): Int? {
        if (last == null || nowEpochMs - last.atEpochMs > STALE_AFTER_MS) return null
        return last.bpm
    }
}

/** All a run keeps of its heart rate: average and max (the source's name is kept beside them). */
data class RunHeartRateSummary(val averageBpm: Int, val maxBpm: Int) {
    companion object {
        /** Mean rounded half up; null when there is nothing to average. */
        fun of(readings: List<Int>): RunHeartRateSummary? {
            val max = readings.maxOrNull() ?: return null
            val sum = readings.fold(0L) { total, bpm -> total + bpm }
            val count = readings.size.toLong()
            // Integer half-up: (2 * sum + count) / (2 * count).
            val average = (2 * sum + count) / (2 * count)
            return RunHeartRateSummary(averageBpm = average.toInt(), maxBpm = max)
        }
    }
}

/**
 * One run's heart rate. The source is fixed when the session begins: a source that drops is
 * reconnected by whoever feeds the session, never swapped for another mid-run, so one run is never
 * a mix of a strap and a wrist. Readings arrive on Bluetooth and watch threads, so every access
 * is synchronized.
 */
class RunHeartRateSession(begin: LiveHeartRateSource?) {
    val source: LiveHeartRateSource? = begin
    private val kept = ArrayList<Int>()
    private var latest: LiveHeartRate.Reading? = null
    private var ended = false

    val readings: List<Int> @Synchronized get() = kept.toList()
    val last: LiveHeartRate.Reading? @Synchronized get() = latest

    /** Records a reading. Ignored with no source, after [end], and for a value that is not a pulse. */
    @Synchronized
    fun receive(bpm: Int, atEpochMs: Long) {
        if (source == null || ended || bpm <= 0) return
        kept.add(bpm)
        latest = LiveHeartRate.Reading(bpm, atEpochMs)
    }

    /** The number to show now, null once the last reading is stale. */
    @Synchronized
    fun shown(nowEpochMs: Long): Int? = LiveHeartRate.shown(latest, nowEpochMs)

    @Synchronized
    fun end(): RunHeartRateSummary? {
        ended = true
        return RunHeartRateSummary.of(kept)
    }
}

/**
 * A heart rate as a backup file or the stored file holds it: a whole bpm above zero that is
 * actually a number, or nothing. Stricter than `JSONObject.optDouble`, which coerces the string
 * "168" and would restore it: a string, a bool, 141.5 and 1e300 are not measurements.
 */
internal fun wholeBpmOrNull(value: Any?): Int? {
    val number = (value as? Number)?.toDouble() ?: return null
    return if (number.isFinite() && number >= 1.0 && number % 1.0 == 0.0 && number <= Int.MAX_VALUE) number.toInt() else null
}

/** A heart rate source's name as a file holds it: a non-empty string, or nothing. */
internal fun sourceNameOrNull(value: Any?): String? = (value as? String)?.takeIf { it.isNotEmpty() }
