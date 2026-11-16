package com.dugcanlift.macrocalc.data

import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

// The path from a strap or Health Connect to the numbers a run keeps and shows.
//
// Tracked and shown, never targeted: no zones, colors, thresholds or advice. What a run keeps is
// its average, its max and the source's name; the readings themselves are never stored.
// Plain Kotlin and interfaces only -- no Android framework type -- so every rule is tested on the
// JVM without a radio or a Health Connect store.

/** Heart rates Health Connect holds between two instants, in bpm. [HealthConnectManager] is the real one. */
interface HeartRateHistory {
    suspend fun heartRates(startEpochMs: Long, endEpochMs: Long): List<Int>
}

/** One Health Connect VO2 max estimate. */
data class VO2MaxEstimate(val value: Double, val atEpochMs: Long)

/**
 * Health Connect's VO2 max estimates. Nothing here stores anything: a run shows the estimate and
 * keeps no copy of it.
 */
interface VO2MaxHistory {
    /** The newest estimate at or before [onOrBeforeEpochMs], or null. */
    suspend fun latestVO2Max(onOrBeforeEpochMs: Long): VO2MaxEstimate?
}

/**
 * What the live card, the last-route card and the review say about VO2 max.
 *
 * Asked once per appearance of a screen and held in its state, never from a body that redraws each
 * second. A run that ended is asked "on or before its end", so an old run never shows an estimate
 * made after it; a run in progress asks for the newest ([LATEST]). Anything that goes wrong
 * (permission denied, no estimate, a Health Connect error, a hung query) is no line: nothing is
 * invented and nothing blocks Finish or the review. VO2 max is not gated by the heart rate Off
 * setting. No model is read or written after the wait: the caller hands in a time, not a run.
 */
object VO2MaxLookup {
    const val LATEST = Long.MAX_VALUE

    suspend fun line(
        onOrBeforeEpochMs: Long,
        history: VO2MaxHistory,
        timeoutMs: Long = RunHeartRateFinish.QUERY_TIMEOUT_MS,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault(),
        monthDayPattern: (Locale) -> String = ::readersMonthDayPattern,
    ): String? {
        val latest = try {
            withTimeoutOrNull(timeoutMs) { history.latestVO2Max(onOrBeforeEpochMs) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        return VO2MaxText.line(latest.value, latest.atEpochMs, locale, timeZone, monthDayPattern)
    }
}

/** What a run keeps. */
data class RunHeartRateRecord(val averageBpm: Int, val maxBpm: Int, val source: String)

/** What Finish needs from the live session, taken without ending it. */
class FinishHeartRate(
    val readings: List<Int>,
    val sourceName: String?,
    val setting: HeartRateSetting,
)

object RunHeartRateFinish {
    /** The source stored when Health Connect, not a strap or the watch, supplied the figures. */
    const val HEALTH_SOURCE = "Health Connect"

    /** Stored for a run the watch measured; the watch's own name replaces it in the watch slice. */
    const val WATCH_SOURCE = "Watch"

    /**
     * A summary opened within this long of the run's end asks Health Connect again if it has no
     * heart rate yet: a watch syncs to Health Connect some time after a run. Older than this, the
     * answer is whatever it was.
     */
    const val REQUERY_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    /** A Health Connect that hangs never holds Finish or the review. */
    const val QUERY_TIMEOUT_MS = 10_000L

    /**
     * What a run keeps once it ends. Live readings win, with the source's name, and Health Connect
     * is never asked. With none, and the setting not Off, Health Connect is asked once for the
     * run's own window and the figures are stored as source [HEALTH_SOURCE]. A failure or a
     * timeout is no figures, not a failed run: the review asks again later.
     */
    suspend fun record(
        readings: List<Int>,
        sourceName: String?,
        setting: HeartRateSetting,
        history: HeartRateHistory,
        startEpochMs: Long,
        endEpochMs: Long,
        timeoutMs: Long = QUERY_TIMEOUT_MS,
    ): RunHeartRateRecord? {
        if (setting == HeartRateSetting.Off) return null
        val live = RunHeartRateSummary.of(readings)
        if (live != null && sourceName != null) return RunHeartRateRecord(live.averageBpm, live.maxBpm, sourceName)
        return fromHealthConnect(history, startEpochMs, endEpochMs, timeoutMs)
    }

    suspend fun fromHealthConnect(
        history: HeartRateHistory,
        startEpochMs: Long,
        endEpochMs: Long,
        timeoutMs: Long = QUERY_TIMEOUT_MS,
    ): RunHeartRateRecord? {
        val rates = try {
            withTimeoutOrNull(timeoutMs) { history.heartRates(startEpochMs, endEpochMs) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        val summary = RunHeartRateSummary.of(rates.filter { it > 0 }) ?: return null
        return RunHeartRateRecord(summary.averageBpm, summary.maxBpm, HEALTH_SOURCE)
    }

    /** Whether a saved run should ask again: it ended, has no heart rate, the setting is not Off, and it ended under seven days ago. */
    fun shouldRequery(endedAtEpochMs: Long?, hasFigures: Boolean, setting: HeartRateSetting, nowEpochMs: Long): Boolean {
        if (setting == HeartRateSetting.Off || hasFigures || endedAtEpochMs == null) return false
        return nowEpochMs - endedAtEpochMs < REQUERY_WINDOW_MS
    }

    /** The run with the record's figures, or the run itself when there is no record. */
    fun apply(record: RunHeartRateRecord?, to: OutdoorActivity): OutdoorActivity =
        if (record == null) to
        else to.copy(averageBpm = record.averageBpm, maxBpm = record.maxBpm, heartRateSource = record.source)

    fun hasFigures(activity: OutdoorActivity): Boolean = activity.averageBpm != null || activity.maxBpm != null

    /**
     * What a saved run should keep after Health Connect is asked again, or null when it should
     * not be asked or has nothing. Pure of storage: the caller writes the answer only if the run
     * still exists and still has no figures (see `OutdoorActivityRepository.fillHeartRate`).
     */
    suspend fun requery(
        activity: OutdoorActivity,
        setting: HeartRateSetting,
        history: HeartRateHistory,
        nowEpochMs: Long,
        timeoutMs: Long = QUERY_TIMEOUT_MS,
    ): RunHeartRateRecord? {
        val ended = activity.endedAtEpochMs
        if (!shouldRequery(ended, hasFigures(activity), setting, nowEpochMs) || ended == null) return null
        return fromHealthConnect(history, activity.startedAtEpochMs, ended, timeoutMs)
    }
}

/** What the live card shows: the number (or a dash once stale) and the small line under it. */
data class LiveHeartRateLine(val number: String, val source: String)

/** The words, verbatim: `142 bpm` with `from Polar H10` under it, `Avg 141 · Max 168 bpm` after. */
object RunHeartRateWords {
    fun live(bpm: Int?): String = bpm?.let { "$it bpm" } ?: "—"

    fun source(name: String): String =
        if (name == RunHeartRateFinish.HEALTH_SOURCE) "from Health Connect, after the run" else "from $name"

    /** Null unless both figures exist and are real: blank is never a zero. */
    fun summary(average: Int?, max: Int?): String? {
        if (average == null || max == null || average <= 0 || max <= 0) return null
        return "Avg $average · Max $max bpm"
    }

    data class Line(val summary: String, val source: String?)

    fun line(activity: OutdoorActivity): Line? {
        val summary = summary(activity.averageBpm, activity.maxBpm) ?: return null
        return Line(summary, activity.heartRateSource?.let(::source))
    }
}

/** The name a run stores beside its figures. */
val LiveHeartRateSource.storedName: String
    get() = when (this) {
        is LiveHeartRateSource.Device -> name
        LiveHeartRateSource.Watch -> RunHeartRateFinish.WATCH_SOURCE
    }

/** What the live card's extra lines say, as plain values (the composable only draws them). */
data class LiveRunExtras(val heartRate: LiveHeartRateLine?, val vo2MaxLine: String?) {
    val isEmpty: Boolean get() = heartRate == null && vo2MaxLine == null
}

/**
 * Whether the heart rate card offers the Health Connect grant sheet. People who connected steps
 * before heart rate existed never saw the two newer reads asked, and nothing else asks for a
 * permission added later, so a tap here is how they get it. It is offered while heart rate is not
 * granted; a person who allowed heart rate and declined VO2 max is not nagged for it (the same
 * choice made for step history), and one who declined everything can ask again.
 */
object HeartRateAccessOffer {
    /** [granted] is null until Health Connect has answered once: no button flashes before then. */
    fun shouldOffer(available: Boolean, granted: Set<String>?, heartRatePermission: String): Boolean =
        available && granted != null && heartRatePermission !in granted
}
