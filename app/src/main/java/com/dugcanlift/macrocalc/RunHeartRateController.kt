package com.dugcanlift.macrocalc

import android.content.Context
import com.dugcanlift.macrocalc.data.AddedHeartRateDevice
import com.dugcanlift.macrocalc.data.FinishHeartRate
import com.dugcanlift.macrocalc.data.HeartRateSetting
import com.dugcanlift.macrocalc.data.HeartRateSourcePicker
import com.dugcanlift.macrocalc.data.LiveHeartRateLine
import com.dugcanlift.macrocalc.data.LiveHeartRateSource
import com.dugcanlift.macrocalc.data.RunHeartRateSession
import com.dugcanlift.macrocalc.data.RunHeartRateWords
import com.dugcanlift.macrocalc.data.SettingsStore
import com.dugcanlift.macrocalc.data.storedName
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The radio, as a run uses it. [HeartRateCentral] is the real one; tests use a fake.
 * (An interface rather than the class so the pipeline is tested without Bluetooth.)
 */
interface HeartRateConnecting {
    suspend fun inRange(added: List<AddedHeartRateDevice>, withinMs: Long): List<AddedHeartRateDevice>
    fun connect(address: String, allowed: List<AddedHeartRateDevice>?, onReading: (bpm: Int, atEpochMs: Long) -> Unit)
    fun disconnect()
}

/**
 * One run's heart rate, from Start to the run's end.
 *
 * The source is picked **once**, at start: the setting and the added devices are read now and held
 * for the run, only the person's own added devices are looked for (and only when there is one to
 * look for, so a phone with none never starts a scan), the picker chooses among those in range
 * after a three second look, and **only the picker's answer is connected**. A strap that appears
 * after the look is not used live -- Health Connect fills the summary after the run -- and nothing
 * re-picks when a reading goes quiet: a dropped source is reconnected by the radio, never swapped.
 *
 * A run is named by its start time, the tracker's own identity for it. A pick that completes
 * after the run ended, was replaced, or began finishing connects nothing.
 *
 * Threading: the tracker calls in from the main thread; readings arrive on the main thread (the
 * radio hops them there); every field is guarded by this object's monitor anyway.
 */
class RunHeartRateController(
    private val central: HeartRateConnecting,
    private val setting: () -> HeartRateSetting,
    private val added: () -> List<AddedHeartRateDevice>,
    private val watchAvailable: () -> Boolean,
    private val scope: CoroutineScope,
    private val lookMs: Long = 3_000L,
) : LocationTracker.RunListener {

    private var runStartedAt: Long? = null
    private var runSetting: HeartRateSetting = HeartRateSetting.Automatic
    private var session: RunHeartRateSession? = null
    private var picking: Job? = null
    private var finishingStartedAt: Long? = null

    /** The run being finished, if any; one Finish at a time. */
    @get:Synchronized val isFinishing: Boolean get() = finishingStartedAt != null

    @Synchronized
    override fun onRunStarted(startedAtEpochMs: Long) {
        endRunLocked()
        val setting = setting()
        val added = added()
        runStartedAt = startedAtEpochMs
        runSetting = setting
        // Read once, now, like the setting: a watch paired mid-run changes nothing. The watch is
        // not a source in this build yet (the watch link is its own slice), so it is never offered.
        val watchAvailable = setting != HeartRateSetting.Off && watchAvailable()
        picking = scope.launch {
            var inRange: List<AddedHeartRateDevice> = emptyList()
            when (setting) {
                // A chosen device is looked for too: a random-address strap can fail a direct
                // connect without a recent scan, and a chosen device out of range is no source.
                HeartRateSetting.Automatic, is HeartRateSetting.Device ->
                    if (added.isNotEmpty()) inRange = central.inRange(added, lookMs)
                HeartRateSetting.Watch, HeartRateSetting.Off -> Unit
            }
            val source = HeartRateSourcePicker.pick(setting, inRange, watchAvailable)
            val new = RunHeartRateSession(source)
            // Under the lock, so a Finish or stop cannot slip between the check and the connect.
            synchronized(this@RunHeartRateController) {
                if (runStartedAt != startedAtEpochMs || finishingStartedAt != null) return@launch
                session = new
                if (source is LiveHeartRateSource.Device) {
                    // Weak, and the session only: the radio holds this callback for the run and
                    // must not hold the run's owner, an Activity or a Context.
                    val weak = WeakReference(new)
                    central.connect(source.id, added) { bpm, at -> weak.get()?.receive(bpm, at) }
                }
            }
        }
    }

    @Synchronized
    override fun onRunStopped(startedAtEpochMs: Long) {
        if (runStartedAt == startedAtEpochMs) endRunLocked()
    }

    private fun endRunLocked() {
        picking?.cancel()
        picking = null
        if (runStartedAt != null) {
            // Lets the strap go and ends the session, so a late reading is dropped.
            central.disconnect()
            session?.end()
        }
        session = null
        runStartedAt = null
    }

    /**
     * The live card's heart rate: the number, or a dash once the last reading is more than ten
     * seconds old, with the source under it. Null when this run has no source, which shows
     * nothing. Takes [nowEpochMs], so a view that ticks each second hides the number on time with
     * nothing else changing.
     */
    @Synchronized
    fun liveHeartRate(nowEpochMs: Long): LiveHeartRateLine? {
        val current = session ?: return null
        val source = current.source ?: return null
        return LiveHeartRateLine(RunHeartRateWords.live(current.shown(nowEpochMs)), RunHeartRateWords.source(source.storedName))
    }

    /**
     * Starts Finish for the run named [startedAtEpochMs]: null when another Finish is waiting (a
     * double tap), otherwise what Finish decides the run's figures from, taken without ending the
     * session -- if saving fails the run goes on. From here a pick still waiting connects nothing.
     * Pair with [endFinish].
     */
    @Synchronized
    fun beginFinish(startedAtEpochMs: Long): FinishHeartRate? {
        if (finishingStartedAt != null) return null
        finishingStartedAt = startedAtEpochMs
        val current = if (runStartedAt == startedAtEpochMs) session else null
        return FinishHeartRate(
            readings = current?.readings ?: emptyList(),
            sourceName = current?.source?.storedName,
            setting = if (runStartedAt == startedAtEpochMs) runSetting else setting(),
        )
    }

    @Synchronized
    fun endFinish() {
        finishingStartedAt = null
    }

    companion object {
        /** The app's controller: the one radio, the person's own setting and devices. */
        fun forApp(context: Context): RunHeartRateController {
            val app = context.applicationContext
            val settings = SettingsStore.get(app)
            val central = HeartRateCentral.get(app)
            return RunHeartRateController(
                central = object : HeartRateConnecting {
                    override suspend fun inRange(added: List<AddedHeartRateDevice>, withinMs: Long) =
                        central.inRange(added, withinMs)
                    override fun connect(
                        address: String,
                        allowed: List<AddedHeartRateDevice>?,
                        onReading: (bpm: Int, atEpochMs: Long) -> Unit,
                    ) = central.connect(address, allowed, onReading)
                    override fun disconnect() = central.disconnect()
                },
                setting = { settings.heartRateSetting },
                added = { settings.heartRateDevices },
                watchAvailable = { false },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            )
        }
    }
}
