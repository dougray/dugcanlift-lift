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
import com.dugcanlift.macrocalc.watchlink.WatchHeartRateLink
import com.dugcanlift.macrocalc.watchlink.WatchHeartRateRules
import com.dugcanlift.macrocalc.watchlink.WatchLinkTransport
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 * **The watch** (LIFT Link version 5) is a source only when the link is READY and settled at 5 or
 * later at that moment. When it is the pick, the run asks it to start; a request that cannot be sent
 * leaves the run with no live source (Health Connect fills the summary), never another source. While
 * the run lasts it asks again every [WatchHeartRateRules.KEEPALIVE_MS] (the watch ends a run it has
 * not heard about for five minutes) and the link asks again on every reconnect; a link that drops
 * just stops the readings, and the number goes to a dash at ten seconds. The run's end -- Finish,
 * Discard, Cancel, or the next run starting -- tells the watch to stop, once.
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
    private val watch: WatchHeartRateLink,
    private val scope: CoroutineScope,
    private val lookMs: Long = 3_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : LocationTracker.RunListener {

    private var runStartedAt: Long? = null
    private var runSetting: HeartRateSetting = HeartRateSetting.Automatic
    private var session: RunHeartRateSession? = null
    private var picking: Job? = null
    private var keepalive: Job? = null
    // True from a watch start that was sent until the run's end sends the stop.
    private var watchActive = false
    // The watch's own name, read when the run began: it is the run's source name for the run.
    private var runWatchName: String? = null
    // The runs whose Finish is waiting, by start time: the gate belongs to a run, so a Finish still
    // waiting for a discarded run never blocks (or is cleared by) the next run's.
    private val finishing = HashSet<Long>()

    /** Whether any run's Finish is waiting. */
    @get:Synchronized val isFinishing: Boolean get() = finishing.isNotEmpty()

    @Synchronized
    override fun onRunStarted(startedAtEpochMs: Long) {
        endRunLocked()
        val setting = setting()
        val added = added()
        runStartedAt = startedAtEpochMs
        runSetting = setting
        // Read once, now, like the setting: a watch paired mid-run changes nothing. Offered only
        // while its link is READY at version 5 or later (a v4 watch has never heard of any of this).
        val watchName = if (setting != HeartRateSetting.Off) watch.linkedName() else null
        val watchAvailable = watchName != null
        val clock = this.clock
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
            var new = RunHeartRateSession(source)
            // Under the lock, so a Finish or stop cannot slip between the check and the connect.
            synchronized(this@RunHeartRateController) {
                if (runStartedAt != startedAtEpochMs || startedAtEpochMs in finishing) return@launch
                if (source is LiveHeartRateSource.Watch) {
                    // Weak, and the session only, as for a strap: the link holds this callback for
                    // the run and must not hold the run's owner. The watch's own clock is the
                    // reading's time (clamped to now), so the ten second rule is the phone's.
                    val weak = WeakReference(new)
                    val sent = watch.begin { bpm, atEpochSec ->
                        val reading = WatchHeartRateRules.reading(bpm, atEpochSec, clock())
                        if (reading != null) weak.get()?.receive(reading.bpm, reading.atEpochMs)
                    }
                    if (sent) {
                        watchActive = true
                        runWatchName = watchName
                        startKeepaliveLocked(startedAtEpochMs)
                    } else {
                        // Not reachable now: no live source, and Health Connect fills the summary.
                        new = RunHeartRateSession(WatchHeartRateRules.sourceAfterStart(source, requestSent = false))
                    }
                }
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

    /**
     * Asks the watch again every minute for as long as this run lasts. Holds the controller weakly:
     * the loop is the scope's, and a controller that was dropped must not be kept alive by a timer.
     */
    private fun startKeepaliveLocked(startedAtEpochMs: Long) {
        keepalive?.cancel()
        val weak = WeakReference(this)
        keepalive = scope.launch {
            while (true) {
                delay(WatchHeartRateRules.KEEPALIVE_MS)
                val self = weak.get() ?: break
                if (!self.keepaliveTick(startedAtEpochMs)) break
            }
        }
    }

    @Synchronized
    private fun keepaliveTick(startedAtEpochMs: Long): Boolean {
        if (runStartedAt != startedAtEpochMs || !watchActive) return false
        watch.refresh()
        return true
    }

    private fun endRunLocked() {
        picking?.cancel()
        picking = null
        keepalive?.cancel()
        keepalive = null
        if (runStartedAt != null) {
            // Lets the strap go and ends the session, so a late reading is dropped.
            central.disconnect()
            session?.end()
        }
        if (watchActive) watch.end() // one stop; the link ignores a second
        watchActive = false
        runWatchName = null
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
        return LiveHeartRateLine(RunHeartRateWords.live(current.shown(nowEpochMs)), RunHeartRateWords.source(source.storedName(runWatchName)))
    }

    /**
     * Starts Finish for the run named [startedAtEpochMs]: null when *that run's* Finish is already
     * waiting (a double tap), otherwise what Finish decides the run's figures from, taken without ending the
     * session -- if saving fails the run goes on. From here a pick still waiting connects nothing.
     * Pair with [endFinish].
     */
    @Synchronized
    fun beginFinish(startedAtEpochMs: Long): FinishHeartRate? {
        if (!finishing.add(startedAtEpochMs)) return null
        val current = if (runStartedAt == startedAtEpochMs) session else null
        return FinishHeartRate(
            readings = current?.readings ?: emptyList(),
            sourceName = current?.source?.storedName(runWatchName),
            setting = if (runStartedAt == startedAtEpochMs) runSetting else setting(),
        )
    }

    @Synchronized
    fun endFinish(startedAtEpochMs: Long) {
        finishing.remove(startedAtEpochMs)
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
                watch = WatchLinkTransport.get(app).heartRate,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            )
        }
    }
}
