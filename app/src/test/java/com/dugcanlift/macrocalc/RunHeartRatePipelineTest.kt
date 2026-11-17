package com.dugcanlift.macrocalc

import com.dugcanlift.macrocalc.data.AddedHeartRateDevice
import com.dugcanlift.macrocalc.data.HeartRateAccessOffer
import com.dugcanlift.macrocalc.data.HeartRateHistory
import com.dugcanlift.macrocalc.data.HeartRateSetting
import com.dugcanlift.macrocalc.data.OutdoorActivity
import com.dugcanlift.macrocalc.data.OutdoorActivityType
import com.dugcanlift.macrocalc.data.RunHeartRateFinish
import com.dugcanlift.macrocalc.data.RunHeartRateRecord
import com.dugcanlift.macrocalc.data.RunHeartRateWords
import com.dugcanlift.macrocalc.data.VO2MaxEstimate
import com.dugcanlift.macrocalc.data.VO2MaxHistory
import com.dugcanlift.macrocalc.data.VO2MaxLookup
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Health Connect that answers from a list, counts how often it was asked, and can hold or hang. */
class FakeHistory(var result: List<Int> = emptyList()) : HeartRateHistory {
    val calls = ArrayList<Pair<Long, Long>>()
    var error: Exception? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun heartRates(startEpochMs: Long, endEpochMs: Long): List<Int> {
        calls.add(startEpochMs to endEpochMs)
        gate?.await()
        error?.let { throw it }
        return result
    }
}

/** VO2 max estimates as a list: answers the newest at or before the time asked, and records every ask. */
class FakeVO2History(var estimates: List<VO2MaxEstimate> = emptyList()) : VO2MaxHistory {
    val asks = ArrayList<Long>()
    var error: Exception? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun latestVO2Max(onOrBeforeEpochMs: Long): VO2MaxEstimate? {
        asks.add(onOrBeforeEpochMs)
        gate?.await()
        error?.let { throw it }
        return estimates.filter { it.atEpochMs <= onOrBeforeEpochMs }.maxByOrNull { it.atEpochMs }
    }
}

/**
 * What Finish stores, what the review re-reads, the words, and the VO2 max line -- all without a
 * radio or a Health Connect store.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunHeartRatePipelineTest {
    private val start = 1_000_000L
    private val end = start + 3_600_000L
    private val day = 24L * 60 * 60 * 1000

    private fun run(endedAt: Long? = end, average: Int? = null, max: Int? = null) = OutdoorActivity(
        activityType = OutdoorActivityType.RUN, startedAtEpochMs = start, endedAtEpochMs = endedAt,
        averageBpm = average, maxBpm = max, heartRateSource = if (average != null) "Polar H10" else null
    )

    private suspend fun record(
        readings: List<Int>, source: String?, setting: HeartRateSetting, history: FakeHistory, timeout: Long = 10_000L
    ) = RunHeartRateFinish.record(readings, source, setting, history, start, end, timeout)

    // ---- what Finish stores -------------------------------------------------------------------

    @Test
    fun `live readings store their summary and the devices name and never query Health Connect`() = runTest {
        val history = FakeHistory(listOf(99))
        val stored = record(listOf(140, 142, 168), "Polar H10", HeartRateSetting.Automatic, history)
        assertEquals(RunHeartRateRecord(150, 168, "Polar H10"), stored)
        assertTrue(history.calls.isEmpty())
    }

    @Test
    fun `no readings queries Health Connect once for the runs own window and stores the source Health Connect`() = runTest {
        val history = FakeHistory(listOf(120, 130, 140))
        val stored = record(emptyList(), null, HeartRateSetting.Automatic, history)
        assertEquals(RunHeartRateRecord(130, 140, "Health Connect"), stored)
        assertEquals(listOf(start to end), history.calls)
    }

    @Test
    fun `a strap with no readings also falls back to Health Connect`() = runTest {
        val history = FakeHistory(listOf(100, 110))
        val stored = record(emptyList(), "Polar H10", HeartRateSetting.Automatic, history)
        assertEquals("Health Connect", stored?.source)
        assertEquals(1, history.calls.size)
    }

    @Test
    fun `Off stores nothing and queries nothing`() = runTest {
        val history = FakeHistory(listOf(100))
        assertNull(record(listOf(150), "Polar H10", HeartRateSetting.Off, history))
        assertNull(record(emptyList(), null, HeartRateSetting.Off, history))
        assertTrue(history.calls.isEmpty())
    }

    @Test
    fun `an empty or zero-only Health Connect answer stores nothing`() = runTest {
        assertNull(record(emptyList(), null, HeartRateSetting.Automatic, FakeHistory(emptyList())))
        assertNull(record(emptyList(), null, HeartRateSetting.Automatic, FakeHistory(listOf(0, 0))))
    }

    @Test
    fun `a Health Connect failure is no figures and does not throw`() = runTest {
        val history = FakeHistory().apply { error = SecurityException("denied") }
        assertNull(record(emptyList(), null, HeartRateSetting.Automatic, history))
    }

    @Test
    fun `a Health Connect that hangs is given up on at the timeout`() = runTest {
        val history = FakeHistory(listOf(120)).apply { gate = CompletableDeferred() }
        assertNull(record(emptyList(), null, HeartRateSetting.Automatic, history, timeout = 10_000L))
        assertEquals(10_000L, currentTime) // virtual time: it waited the timeout and not a moment more
    }

    @Test
    fun `a cancelled query is cancelled and not swallowed as no figures`() = runTest {
        val history = FakeHistory().apply { gate = CompletableDeferred() }
        var finished = false
        val job = launch {
            record(emptyList(), null, HeartRateSetting.Automatic, history)
            finished = true
        }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertFalse(finished)
    }

    // ---- the review re-asks -------------------------------------------------------------------

    @Test
    fun `summaryOpenedLaterFillsFromHealthConnect`() = runTest {
        val history = FakeHistory(listOf(120, 150))
        val now = end + 2 * day
        val filled = RunHeartRateFinish.requery(run(), HeartRateSetting.Automatic, history, now)
        assertEquals(RunHeartRateRecord(135, 150, "Health Connect"), filled)
        assertEquals(listOf(start to end), history.calls)
    }

    @Test
    fun `a run older than seven days is not requeried`() = runTest {
        val history = FakeHistory(listOf(120))
        assertNull(RunHeartRateFinish.requery(run(), HeartRateSetting.Automatic, history, end + 7 * day))
        assertTrue(history.calls.isEmpty())
    }

    @Test
    fun `a run just under seven days is requeried`() = runTest {
        val history = FakeHistory(listOf(120))
        assertEquals(
            120,
            RunHeartRateFinish.requery(run(), HeartRateSetting.Automatic, history, end + 7 * day - 1)?.averageBpm
        )
    }

    @Test
    fun `a run with figures is never requeried`() = runTest {
        val history = FakeHistory(listOf(120))
        assertNull(RunHeartRateFinish.requery(run(average = 141, max = 168), HeartRateSetting.Automatic, history, end + day))
        assertNull(RunHeartRateFinish.requery(run(max = 168), HeartRateSetting.Automatic, history, end + day))
        assertTrue(history.calls.isEmpty())
    }

    @Test
    fun `Off never requeries an old run either`() = runTest {
        val history = FakeHistory(listOf(120))
        assertNull(RunHeartRateFinish.requery(run(), HeartRateSetting.Off, history, end + day))
        assertTrue(history.calls.isEmpty())
    }

    @Test
    fun `a run that has not ended is not requeried`() = runTest {
        val history = FakeHistory(listOf(120))
        assertNull(RunHeartRateFinish.requery(run(endedAt = null), HeartRateSetting.Automatic, history, end))
        assertTrue(history.calls.isEmpty())
    }

    @Test
    fun `a failed requery changes nothing`() = runTest {
        val history = FakeHistory().apply { error = IllegalStateException() }
        assertNull(RunHeartRateFinish.requery(run(), HeartRateSetting.Automatic, history, end + day))
    }

    @Test
    fun `a requery cancelled while Health Connect answers writes nothing`() = runTest {
        val history = FakeHistory(listOf(120)).apply { gate = CompletableDeferred() }
        var answered: RunHeartRateRecord? = null
        var returned = false
        val job = launch {
            answered = RunHeartRateFinish.requery(run(), HeartRateSetting.Automatic, history, end + day)
            returned = true
        }
        runCurrent()
        job.cancel()
        runCurrent()
        assertFalse(returned)
        assertNull(answered)
    }

    // ---- words --------------------------------------------------------------------------------

    @Test
    fun `the words are verbatim`() {
        assertEquals("142 bpm", RunHeartRateWords.live(142))
        assertEquals("—", RunHeartRateWords.live(null))
        assertEquals("from Polar H10", RunHeartRateWords.source("Polar H10"))
        assertEquals("from Health Connect, after the run", RunHeartRateWords.source("Health Connect"))
        assertEquals("Avg 141 · Max 168 bpm", RunHeartRateWords.summary(141, 168))
        assertNull(RunHeartRateWords.summary(null, 168))
        assertNull(RunHeartRateWords.summary(141, null))
        assertNull(RunHeartRateWords.summary(0, 168))
    }

    @Test
    fun `a saved runs line reads summary and source and a run with none reads nothing`() {
        val line = RunHeartRateWords.line(run(average = 141, max = 168))!!
        assertEquals("Avg 141 · Max 168 bpm", line.summary)
        assertEquals("from Polar H10", line.source)
        assertNull(RunHeartRateWords.line(run()))
    }

    // ---- VO2 max ------------------------------------------------------------------------------

    private val zone = TimeZone.getTimeZone("UTC")
    private fun epoch(month: Int, dayOfMonth: Int, hour: Int = 12) =
        LocalDate.of(2026, month, dayOfMonth).atStartOfDay(ZoneId.of("UTC")).plusHours(hour.toLong()).toInstant().toEpochMilli()

    private suspend fun vo2(onOrBefore: Long, history: VO2MaxHistory) =
        VO2MaxLookup.line(onOrBefore, history, 10_000L, Locale.US, zone) { "MMM d" }

    @Test
    fun aSummaryNeverShowsALaterEstimate() = runTest {
        val history = FakeVO2History(listOf(VO2MaxEstimate(44.0, epoch(3, 1)), VO2MaxEstimate(46.2, epoch(11, 8))))
        val marchRunEnd = epoch(3, 15)
        assertEquals("VO2 max 44 · Mar 1", vo2(marchRunEnd, history))
        assertEquals(listOf(marchRunEnd), history.asks)
    }

    @Test
    fun `the live card asks for the newest`() = runTest {
        val history = FakeVO2History(listOf(VO2MaxEstimate(44.0, epoch(3, 1)), VO2MaxEstimate(46.2, epoch(11, 8))))
        assertEquals("VO2 max 46.2 · Nov 8", vo2(VO2MaxLookup.LATEST, history))
        assertEquals(listOf(VO2MaxLookup.LATEST), history.asks)
    }

    @Test
    fun `a run before every estimate shows nothing`() = runTest {
        assertNull(vo2(epoch(1, 1), FakeVO2History(listOf(VO2MaxEstimate(44.0, epoch(3, 1))))))
    }

    @Test
    fun `no estimate shows nothing`() = runTest {
        assertNull(vo2(VO2MaxLookup.LATEST, FakeVO2History()))
    }

    @Test
    fun `a Health Connect failure shows nothing and does not throw`() = runTest {
        assertNull(vo2(VO2MaxLookup.LATEST, FakeVO2History().apply { error = SecurityException() }))
    }

    @Test
    fun `an estimate that is not a real number shows nothing`() = runTest {
        for (bad in listOf(0.0, -3.0, Double.NaN, Double.POSITIVE_INFINITY, 1e300)) {
            assertNull("$bad", vo2(VO2MaxLookup.LATEST, FakeVO2History(listOf(VO2MaxEstimate(bad, epoch(3, 1))))))
        }
    }

    @Test
    fun `a hung VO2 max query gives up at the timeout`() = runTest {
        val history = FakeVO2History(listOf(VO2MaxEstimate(44.0, epoch(3, 1)))).apply { gate = CompletableDeferred() }
        assertNull(vo2(VO2MaxLookup.LATEST, history))
    }

    @Test
    fun `a cancelled lookup returns nothing to write`() = runTest {
        val history = FakeVO2History(listOf(VO2MaxEstimate(44.0, epoch(3, 1)))).apply { gate = CompletableDeferred() }
        var line: String? = "untouched"
        val job = launch { line = vo2(VO2MaxLookup.LATEST, history) }
        runCurrent()
        job.cancel(CancellationException("screen left"))
        runCurrent()
        assertEquals("untouched", line)
    }

    // ---- the grant offer ----------------------------------------------------------------------

    @Test
    fun `a person who connected steps before heart rate existed is offered the grant again`() {
        val steps = setOf("android.permission.health.READ_STEPS")
        val hr = "android.permission.health.READ_HEART_RATE"
        assertTrue(HeartRateAccessOffer.shouldOffer(available = true, granted = steps, heartRatePermission = hr))
        assertTrue(HeartRateAccessOffer.shouldOffer(available = true, granted = emptySet(), heartRatePermission = hr))
        assertFalse(HeartRateAccessOffer.shouldOffer(available = true, granted = steps + hr, heartRatePermission = hr))
        // Declining only VO2 max is not nagged for it.
        assertFalse(HeartRateAccessOffer.shouldOffer(available = true, granted = setOf(hr), heartRatePermission = hr))
        assertFalse(HeartRateAccessOffer.shouldOffer(available = false, granted = steps, heartRatePermission = hr))
        // Nothing flashes before Health Connect has answered once.
        assertFalse(HeartRateAccessOffer.shouldOffer(available = true, granted = null, heartRatePermission = hr))
    }
}
