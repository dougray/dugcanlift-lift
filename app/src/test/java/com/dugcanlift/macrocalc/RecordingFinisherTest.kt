package com.dugcanlift.macrocalc

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.dugcanlift.macrocalc.data.OutdoorActivityRepository
import com.dugcanlift.macrocalc.data.OutdoorActivityType
import com.dugcanlift.macrocalc.data.AddedHeartRateDevice
import com.dugcanlift.macrocalc.data.HeartRateSetting
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RecordingFinisherTest {

    // Held for the whole test class so nothing here goes out of scope mid-test.
    private lateinit var context: Context
    private lateinit var tracker: LocationTracker
    private lateinit var repository: OutdoorActivityRepository
    private val history = FakeHistory()

    @Before
    fun setUp() {
        listOf(LocationTracker::class.java, OutdoorActivityRepository::class.java).forEach { cls ->
            val field = cls.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        context = app
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        tracker = LocationTracker.get(app)
        repository = OutdoorActivityRepository.get(app)
    }

    @Test
    fun finishFromTheCardSavesOnce() = runTest {
        assertTrue(tracker.start(OutdoorActivityType.WALK))
        assertTrue(tracker.isRecording.value)

        val first = RecordingFinisher.finish(context, tracker, repository, history)
        val second = RecordingFinisher.finish(context, tracker, repository, history)

        assertNotNull(first)
        assertNull(second)
        assertEquals(OutdoorActivityType.WALK, first!!.activityType)
        assertNotNull(first.endedAtEpochMs)
        assertFalse(tracker.isRecording.value)
        assertEquals(1, repository.activitiesForBackup().size)
        assertEquals(first.id, repository.activitiesForBackup().single().id)
    }

    @Test
    fun finishWithNothingRecordingSavesNothing() = runTest {
        assertNull(RecordingFinisher.finish(context, tracker, repository, history))
        assertEquals(0, repository.activitiesForBackup().size)
    }

    @Test
    fun finishWorksWithNoScreenAtAll() = runTest {
        // The stranded case: the tracker runs, no recording screen was ever composed.
        assertTrue(tracker.start(OutdoorActivityType.HIKE))
        val saved = RecordingFinisher.finish(context, tracker, repository, history)
        assertNotNull(saved)
        assertEquals(OutdoorActivityType.HIKE, saved!!.activityType)
        assertEquals(1, repository.activitiesForBackup().size)
    }

    @Test
    fun cancelStopsAndSavesNothing() = runTest {
        assertTrue(tracker.start(OutdoorActivityType.RUN))
        tracker.stop()
        assertFalse(tracker.isRecording.value)
        assertNull(RecordingFinisher.finish(context, tracker, repository, history))
        assertEquals(0, repository.activitiesForBackup().size)
    }

    // ---- heart rate at Finish ----------------------------------------------------------------

    private val polar = AddedHeartRateDevice("AA:BB:CC:DD:EE:01", "Polar H10")

    private fun installRadio(scope: kotlinx.coroutines.CoroutineScope, setting: HeartRateSetting = HeartRateSetting.Automatic): FakeCentral {
        val central = FakeCentral(inRangeAnswer = listOf(polar))
        tracker.installHeartRate(RunHeartRateController(central, { setting }, { listOf(polar) }, { false }, scope))
        return central
    }

    @Test
    fun `live readings are saved with the straps name and Health Connect is never asked`() = runTest {
        val central = installRadio(backgroundScope)
        assertTrue(tracker.start(OutdoorActivityType.RUN))
        runCurrent()
        central.deliver(140, System.currentTimeMillis())
        central.deliver(160, System.currentTimeMillis())
        history.result = listOf(90)

        val saved = RecordingFinisher.finish(context, tracker, repository, history)!!

        assertEquals(150, saved.averageBpm)
        assertEquals(160, saved.maxBpm)
        assertEquals("Polar H10", saved.heartRateSource)
        assertTrue(history.calls.isEmpty())
        assertEquals(saved, repository.activitiesForBackup().single())
        assertEquals(1, central.disconnects) // the strap is let go
    }

    @Test
    fun `no strap asks Health Connect once and saves its figures as Health Connect`() = runTest {
        installRadio(backgroundScope, HeartRateSetting.Automatic).inRangeAnswer = emptyList()
        assertTrue(tracker.start(OutdoorActivityType.RUN))
        runCurrent()
        history.result = listOf(120, 130, 140)

        val saved = RecordingFinisher.finish(context, tracker, repository, history)!!

        assertEquals(130, saved.averageBpm)
        assertEquals(140, saved.maxBpm)
        assertEquals("Health Connect", saved.heartRateSource)
        assertEquals(1, history.calls.size)
        assertEquals(saved.startedAtEpochMs to saved.endedAtEpochMs!!, history.calls.single())
    }

    @Test
    fun `Off saves no figures and asks nothing`() = runTest {
        installRadio(backgroundScope, HeartRateSetting.Off)
        assertTrue(tracker.start(OutdoorActivityType.RUN))
        runCurrent()
        history.result = listOf(120)
        val saved = RecordingFinisher.finish(context, tracker, repository, history)!!
        assertNull(saved.averageBpm)
        assertNull(saved.heartRateSource)
        assertTrue(history.calls.isEmpty())
    }

    @Test
    fun `a hung Health Connect never holds Finish and the run saves with no figures`() = runTest {
        installRadio(backgroundScope).inRangeAnswer = emptyList()
        assertTrue(tracker.start(OutdoorActivityType.RUN))
        runCurrent()
        history.gate = CompletableDeferred()
        val saved = RecordingFinisher.finish(context, tracker, repository, history, queryTimeoutMs = 10_000L)!!
        assertNull(saved.averageBpm)
        assertEquals(1, repository.activitiesForBackup().size)
        assertFalse(tracker.isRecording.value)
    }

    @Test
    fun `a concurrent second Finish saves nothing more and never asks Health Connect again`() = runTest {
        installRadio(backgroundScope).inRangeAnswer = emptyList()
        assertTrue(tracker.start(OutdoorActivityType.RUN))
        runCurrent()
        history.gate = CompletableDeferred()
        history.result = listOf(100, 120)

        var first: com.dugcanlift.macrocalc.data.OutdoorActivity? = null
        val firstJob = launch { first = RecordingFinisher.finish(context, tracker, repository, history) }
        runCurrent()
        val second = RecordingFinisher.finish(context, tracker, repository, history)
        assertNull(second)
        assertTrue(tracker.isRecording.value) // still recording while Health Connect answers
        history.gate!!.complete(Unit)
        firstJob.join()

        assertNotNull(first)
        assertEquals(1, history.calls.size)
        assertEquals(1, repository.activitiesForBackup().size)
    }

    @Test
    fun `Cancel while Finish waits on Health Connect discards the run with no half state`() = runTest {
        installRadio(backgroundScope).inRangeAnswer = emptyList()
        assertTrue(tracker.start(OutdoorActivityType.RUN))
        runCurrent()
        history.gate = CompletableDeferred()
        var result: Any? = "unset"
        val job = launch { result = RecordingFinisher.finish(context, tracker, repository, history) }
        runCurrent()

        tracker.stop() // the Discard button
        history.gate!!.complete(Unit)
        job.join()

        assertNull(result)
        assertEquals(0, repository.activitiesForBackup().size)
        assertFalse(tracker.isRecording.value)
        assertFalse(tracker.heartRate.isFinishing) // the gate is released
    }

    @Test
    fun `a Finish from a cancelled run does not end the next run`() = runTest {
        installRadio(backgroundScope).inRangeAnswer = emptyList()
        assertTrue(tracker.start(OutdoorActivityType.RUN))
        runCurrent()
        history.gate = CompletableDeferred()
        var result: Any? = "unset"
        val job = launch { result = RecordingFinisher.finish(context, tracker, repository, history) }
        runCurrent()

        tracker.stop()
        Thread.sleep(5) // a different start time
        assertTrue(tracker.start(OutdoorActivityType.WALK))
        history.gate!!.complete(Unit)
        job.join()

        assertNull(result)
        assertTrue(tracker.isRecording.value) // the new run is untouched
        assertEquals(OutdoorActivityType.WALK, tracker.activityType.value)
        assertEquals(0, repository.activitiesForBackup().size)
    }

    @Test
    fun `a screen leaving while Health Connect answers leaves the run recording`() = runTest {
        installRadio(backgroundScope).inRangeAnswer = emptyList()
        assertTrue(tracker.start(OutdoorActivityType.RUN))
        runCurrent()
        history.gate = CompletableDeferred()
        val job = launch { RecordingFinisher.finish(context, tracker, repository, history) }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(tracker.isRecording.value)
        assertFalse(tracker.heartRate.isFinishing)
        history.gate = null
        assertNotNull(RecordingFinisher.finish(context, tracker, repository, history)) // a retry saves once
        assertEquals(1, repository.activitiesForBackup().size)
    }

    @Test
    fun `a failed save keeps the run recording and a retry saves it once`() = runTest {
        installRadio(backgroundScope).inRangeAnswer = emptyList()
        assertTrue(tracker.start(OutdoorActivityType.RUN))
        runCurrent()
        val startedAt = tracker.startedAtEpochMs.value
        // Make the store unwritable: its temp file's name is taken by a directory.
        val blocker = File(context.filesDir, "outdoor_activities.json.tmp").also { it.mkdirs() }

        var threw = false
        try { RecordingFinisher.finish(context, tracker, repository, history) } catch (e: Exception) { threw = true }

        assertTrue(threw)
        assertTrue(tracker.isRecording.value) // nothing lost: the run is back
        assertEquals(startedAt, tracker.startedAtEpochMs.value) // with its own start time
        assertFalse(tracker.heartRate.isFinishing)
        assertEquals(0, repository.activitiesForBackup().size)

        blocker.delete()
        assertNotNull(RecordingFinisher.finish(context, tracker, repository, history))
        assertEquals(1, repository.activitiesForBackup().size)
        assertNull(RecordingFinisher.finish(context, tracker, repository, history))
        assertEquals(1, repository.activitiesForBackup().size)
    }
}
