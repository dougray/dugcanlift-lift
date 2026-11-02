package com.dugcanlift.macrocalc.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * One rest between sets: when it started and when it is over. A port of LIFT iPhone's
 * `RestCountdown`. A rest is an end time, not a number of seconds left, so it stays right while
 * LIFT is in the background and the notification's countdown runs with no help from the app.
 */
data class RestCountdown(
    val startedAtMs: Long,
    val endsAtMs: Long,
    /** The set whose tick started it, so unticking that set takes it back. */
    val setId: String?,
    /** What the rest is before, for the notification and the banner. */
    val exerciseName: String?
) {
    fun remaining(nowMs: Long): Double = max(0.0, (endsAtMs - nowMs) / 1000.0)

    fun isOver(nowMs: Long): Boolean = nowMs >= endsAtMs

    /**
     * Fifteen seconds more or less. Never below nothing left and never past [LONGEST_SECONDS] from
     * now; added to a rest already over, it counts from now.
     */
    fun adjusted(bySeconds: Double, nowMs: Long): RestCountdown {
        val left = remaining(nowMs) + bySeconds
        return copy(endsAtMs = nowMs + (min(max(0.0, left), LONGEST_SECONDS) * 1000).toLong())
    }

    internal fun toJson(): String = JSONObject().apply {
        put("startedAt", startedAtMs)
        put("endsAt", endsAtMs)
        setId?.let { put("setId", it) }
        exerciseName?.let { put("exerciseName", it) }
    }.toString()

    companion object {
        /** Fifteen minutes, as long as anyone rests between sets on purpose. */
        const val LONGEST_SECONDS = 15.0 * 60

        fun start(seconds: Double, setId: String?, exerciseName: String?, nowMs: Long): RestCountdown =
            RestCountdown(
                startedAtMs = nowMs,
                endsAtMs = nowMs + (min(max(0.0, seconds), LONGEST_SECONDS) * 1000).toLong(),
                setId = setId,
                exerciseName = exerciseName
            )

        /** "1:35". Rounded up, so it never reads 0:00 with time still left. */
        fun clock(seconds: Double): String {
            val whole = ceil(seconds - 1e-9).toInt().coerceAtLeast(0)
            return "%d:%02d".format(whole / 60, whole % 60)
        }

        /** Unreadable is no rest at all, never a crash on the way back into the app. */
        internal fun fromJson(raw: String?): RestCountdown? = runCatching {
            val o = JSONObject(raw ?: return null)
            RestCountdown(
                startedAtMs = o.getLong("startedAt"),
                endsAtMs = o.getLong("endsAt"),
                setId = o.optString("setId").takeIf { o.has("setId") },
                exerciseName = o.optString("exerciseName").takeIf { o.has("exerciseName") }
            )
        }.getOrNull()
    }
}

/**
 * "Rest between sets", the one number a rest starts from. LIFT iPhone keeps it as
 * `WatchPlanSettings`, the setting its Apple Watch rests for too; this build has one place for it,
 * Home → Training settings. A routine and PLAN-FORMAT's set tuple carry no rest time, so there is no
 * prescribed rest to prefer over it.
 */
object RestBetweenSets {
    const val KEY = "rest_between_sets_seconds"
    const val DEFAULT_SECONDS = 90
    val RANGE = 30..300
    const val STEP = 15

    /**
     * The rest to start, or null for none. Never set -- the normal case, since nothing is written
     * until someone moves the stepper -- is [DEFAULT_SECONDS]: reading it as zero would mean a rest
     * never started for anyone who had not been into Settings. Only a stored non-positive value is
     * "nothing". A port of LIFT iPhone's `WatchPlanSettings.resolvedRestSeconds`.
     */
    fun resolved(stored: Int?): Int? {
        if (stored == null) return DEFAULT_SECONDS
        return stored.takeIf { it > 0 }
    }
}

/**
 * Where a rest shows up outside the screen it was started on: the ongoing countdown notification
 * and the "Rest over" alert. An interface so [RestTimer]'s rules can be tested without either.
 */
interface RestOutlets {
    fun restStarted(rest: RestCountdown)
    fun restChanged(rest: RestCountdown)
    fun restCleared()
    /** The session is over: anything still in the notification shade goes. */
    fun sessionEnded()
}

/** Where the running rest is kept, so Android ending LIFT mid-rest loses nothing. */
interface RestStorage {
    fun read(): String?
    fun write(value: String?)
}

/**
 * The rest between sets, during a live session. A port of LIFT iPhone's `RestTimer`, and
 * [RestTimerTest] is a port of its `RestTimerTests`, case for case.
 *
 * Ticking a set done starts a rest of "Rest between sets". Ticking the next set starts a fresh one;
 * unticking the set that started a rest takes it back; ending the session ends it. The rest is kept
 * in [RestStorage], so Android ending LIFT mid-rest does not lose it, and one long over is dropped
 * on the way back in rather than greeting someone the next morning.
 */
class RestTimer(private val outlets: RestOutlets, private val storage: RestStorage) {

    /**
     * What the banner says when a tick starts no rest: "Next: Seated Cable Rows" inside a superset
     * round, "Drop set next" before a drop set ([SessionRest]). Never at the same time as a rest.
     * Not kept across a relaunch: it has no end time, and the next tick says it again.
     */
    data class Cue(val setId: String, val text: String)

    private val _current = MutableStateFlow(RestCountdown.fromJson(storage.read()))
    val current: StateFlow<RestCountdown?> = _current.asStateFlow()

    private val _cue = MutableStateFlow<Cue?>(null)
    val cue: StateFlow<Cue?> = _cue.asStateFlow()

    /** [restSeconds] null is "no rest": nothing starts. */
    fun setDone(setId: String, exerciseName: String?, restSeconds: Int?, nowMs: Long = System.currentTimeMillis()) {
        _cue.value = null
        if (restSeconds == null || restSeconds <= 0) return
        val rest = RestCountdown.start(restSeconds.toDouble(), setId, exerciseName, nowMs)
        store(rest)
        outlets.restStarted(rest)
    }

    /** A tick that starts no rest. A rest still running is over -- the next set has begun -- and the banner says what comes next instead. */
    fun setDoneNoRest(setId: String, text: String) {
        if (_current.value != null) clear()
        _cue.value = Cue(setId, text)
    }

    /** What [SessionRest] decided, carried out. */
    fun setDone(setId: String, after: SessionRest.After, exerciseName: String?, nowMs: Long = System.currentTimeMillis()) {
        when (after) {
            is SessionRest.After.Rest -> setDone(setId, after.before ?: exerciseName, after.seconds, nowMs)
            is SessionRest.After.NoRest -> setDoneNoRest(setId, after.cue)
            SessionRest.After.Nothing -> setDone(setId, exerciseName, null, nowMs)
        }
    }

    fun setUndone(setId: String) {
        if (_cue.value?.setId == setId) _cue.value = null
        if (_current.value?.setId != setId) return
        clear()
    }

    fun adjust(bySeconds: Double, nowMs: Long = System.currentTimeMillis()) {
        val rest = _current.value?.adjusted(bySeconds, nowMs) ?: return
        store(rest)
        outlets.restChanged(rest)
    }

    fun skip() {
        _cue.value = null
        if (_current.value == null) return
        clear()
    }

    fun sessionEnded() {
        skip()
        outlets.sessionEnded()
    }

    /** On the way back into the app: a rest over for a while is done with. */
    fun restore(nowMs: Long = System.currentTimeMillis()) {
        val rest = _current.value ?: return
        if (nowMs - rest.endsAtMs <= KEEPS_OVER_FOR_MS) return
        clear()
    }

    private fun store(rest: RestCountdown) {
        _current.value = rest
        storage.write(rest.toJson())
    }

    private fun clear() {
        _current.value = null
        storage.write(null)
        outlets.restCleared()
    }

    companion object {
        /** How long "Rest over" stays on screen once it is. */
        const val KEEPS_OVER_FOR_MS = 120_000L
        const val STEP_SECONDS = 15.0
    }
}
