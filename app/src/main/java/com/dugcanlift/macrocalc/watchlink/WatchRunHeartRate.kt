package com.dugcanlift.macrocalc.watchlink

import com.dugcanlift.liftkit.link.LinkMessage
import com.dugcanlift.liftkit.link.LinkProtocol
import com.dugcanlift.liftkit.link.LinkRole
import com.dugcanlift.liftkit.link.LinkSession
import com.dugcanlift.liftkit.link.LinkState
import com.dugcanlift.liftkit.link.RunHeartRate
import com.dugcanlift.macrocalc.data.LiveHeartRateSource

// The Wear watch as a source of a run's heart rate (LIFT Link version 5), phone side.
//
// Tracked and shown, never targeted. Plain Kotlin and the shared :link module only -- no Android
// framework type -- so every rule here is tested on the JVM, with real LinkSessions and no radio.

/**
 * What a run needs from the watch link. [WatchHeartRateRelay] is the real one (the transport owns it);
 * [None] is a phone with no watch; tests use a fake.
 */
interface WatchHeartRateLink {
    /**
     * The watch's name when a Wear watch is paired **and linked right now at version 5 or later**,
     * else null. The negotiated version, not what the watch said it could speak: a link that settled
     * at 4 has a watch that has never heard of these messages.
     */
    fun linkedName(): String?

    /**
     * Asks the watch to start measuring for a run, and hands readings to [onReading] (bpm, and the
     * sample's own time in whole epoch seconds) until [end]. True only when the request was handed to a
     * link that carries it; false changes nothing and holds nothing.
     */
    fun begin(onReading: (bpm: Int, atEpochSec: Long) -> Unit): Boolean

    /** Asks again (the watch ends a run it has not heard about for five minutes). Never throws. */
    fun refresh(): Boolean

    /** Tells the watch to stop, once, and lets go of the reading callback. Idempotent. */
    fun end()

    object None : WatchHeartRateLink {
        override fun linkedName(): String? = null
        override fun begin(onReading: (bpm: Int, atEpochSec: Long) -> Unit): Boolean = false
        override fun refresh(): Boolean = false
        override fun end() = Unit
    }
}

/** The rules around the watch as a source, as values. */
object WatchHeartRateRules {
    /**
     * How often a run asks the watch again. The watch ends a run after five minutes of silence; the
     * requirement is at least every two, and a minute leaves room for a request lost on the way.
     */
    const val KEEPALIVE_MS = 60_000L

    /** What a watch with no name of its own is called on screen. */
    const val FALLBACK_NAME = "Wear watch"

    /**
     * A sensor's own sanity limit, not advice: a `u16` on the wire can say 65,535, which is not a
     * pulse and would become the run's max.
     */
    const val MAX_PLAUSIBLE_BPM = 300

    /**
     * The settings row: the watch's name, only for a watch that is paired and whose link last settled
     * at version 5 or later. A watch last seen at 4 is not a source, so it has no row.
     */
    fun settingsName(paired: Boolean, name: String?, lastLinkVersion: Int?): String? =
        if (paired && lastLinkVersion != null && lastLinkVersion >= LinkProtocol.VERSION_WITH_RUN_HEART_RATE) {
            displayName(name)
        } else null

    fun displayName(name: String?): String = name?.trim()?.takeIf { it.isNotEmpty() } ?: FALLBACK_NAME

    /**
     * The decision when the watch is asked to start: [picked] stands only if the request was sent.
     * A watch that cannot be reached at that moment leaves the run with **no live source** -- it is
     * not stranded, Finish is not blocked, and Health Connect fills the summary after the run -- and
     * it is never swapped for a strap, because one run is one source.
     */
    fun sourceAfterStart(picked: LiveHeartRateSource?, requestSent: Boolean): LiveHeartRateSource? =
        if (picked is LiveHeartRateSource.Watch && !requestSent) null else picked

    /** One reading as the run keeps it: a pulse, and a time in epoch milliseconds that is never in the future. */
    data class Reading(val bpm: Int, val atEpochMs: Long)

    /**
     * A reading from the wire, or null when it is not a pulse. The sample's own time is kept (it can
     * be old, and the stale rule hides it then), but **never later than [nowEpochMs]**: a watch clock
     * ahead of the phone's must not keep a dead reading looking fresh for as long as the difference.
     */
    fun reading(bpm: Int, atEpochSec: Long, nowEpochMs: Long): Reading? {
        if (bpm < 1 || bpm > MAX_PLAUSIBLE_BPM) return null
        val atMs = if (atEpochSec > Long.MAX_VALUE / 1000) Long.MAX_VALUE else atEpochSec * 1000
        return Reading(bpm, minOf(atMs, nowEpochMs))
    }
}

/**
 * The phone's side of version 5, over whatever link is up: [session] is the transport's current
 * session (null when the link is down) and [send] queues a message for the watch.
 *
 * A run **wants** heart rate from [begin] to [end]. While it does, [onLinkReady] asks again whenever
 * the link comes back (the watch ends a run on any disconnect, so a reconnect without a new request
 * would be a watch that never measures), and [refresh] is the periodic ask. Nothing is ever sent to a
 * link that settled below 5: the watch would answer `UNKNOWN_TYPE`, and the watch is not listed as a
 * source in the first place.
 *
 * Main-thread only, like the transport that owns it; synchronized anyway, because it is cheap.
 */
class WatchHeartRateRelay(
    private val session: () -> LinkSession?,
    private val send: (LinkMessage) -> Unit,
    private val pairedName: () -> String?,
) : WatchHeartRateLink {

    private var wanted = false
    private var sink: ((Int, Long) -> Unit)? = null

    /** The session, only when it can carry run heart rate right now. */
    private fun usable(): LinkSession? = session()?.takeIf {
        it.role == LinkRole.CENTRAL && it.state == LinkState.READY && it.supportsRunHeartRate
    }

    @Synchronized
    override fun linkedName(): String? =
        if (usable() != null) WatchHeartRateRules.displayName(pairedName()) else null

    @Synchronized
    override fun begin(onReading: (bpm: Int, atEpochSec: Long) -> Unit): Boolean {
        val message = usable()?.startRunHeartRate() ?: return false
        sink = onReading
        wanted = true
        send(message)
        return true
    }

    @Synchronized
    override fun refresh(): Boolean {
        if (!wanted) return false
        val message = usable()?.startRunHeartRate() ?: return false
        send(message)
        return true
    }

    @Synchronized
    override fun end() {
        if (!wanted) return
        wanted = false
        sink = null
        // Down, or settled below 5 on a reconnect: nothing to say. The watch ends a run on a disconnect.
        usable()?.stopRunHeartRate()?.let(send)
    }

    /** The link is READY again, after any pairing or reconnect. */
    @Synchronized
    fun onLinkReady() {
        if (wanted) refresh()
    }

    /** A reading from the watch. Dropped unless a run wants it. */
    fun onReading(reading: RunHeartRate) {
        val deliver = synchronized(this) { if (wanted) sink else null }
        deliver?.invoke(reading.bpm, reading.atEpochSec)
    }
}
