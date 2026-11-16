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
import android.location.Location
import java.io.File
import kotlinx.coroutines.Job
import org.junit.Assert.assertSame
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
        tracker.installHeartRate(RunHeartRateController(central, { setting }, { listOf(polar) }, com.dugcanlift.macrocalc.watchlink.WatchHeartRateLink.None, scope))
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
        fix(40.0, -105.0, altitude = 1600.0)
        fix(40.0001, -105.0, altitude = 1601.0)
        val route = tracker.routePoints.value
        assertEquals(2, route.size)
        val startedAt = tracker.startedAtEpochMs.value
        // Make the store unwritable: its temp file's name is taken by a directory.
        val blocker = File(context.filesDir, "outdoor_activities.json.tmp").also { it.mkdirs() }

        var threw = false
        try { RecordingFinisher.finish(context, tracker, repository, history) } catch (e: Exception) { threw = true }

        assertTrue(threw)
        assertTrue(tracker.isRecording.value) // nothing lost: the run is back
        assertEquals(startedAt, tracker.startedAtEpochMs.value) // with its own start time
        assertEquals(route, tracker.routePoints.value) // and its route points
        assertFalse(tracker.heartRate.isFinishing)
        assertEquals(0, repository.activitiesForBackup().size)

        blocker.delete()
        assertNotNull(RecordingFinisher.finish(context, tracker, repository, history))
        assertEquals(1, repository.activitiesForBackup().size)
        assertNull(RecordingFinisher.finish(context, tracker, repository, history))
        assertEquals(1, repository.activitiesForBackup().size)
    }

    // ---- the run-ending path: put back, leaving the screen, gates, cancellation -----------------

    private var fixes = 0

    /** A GPS fix the tracker's own listener receives; each is 10 s and 11 m on from the last, past its filters. */
    private fun fix(lat: Double, lon: Double, altitude: Double? = null) {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        fixes++
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat; longitude = lon; accuracy = 5f; time = System.currentTimeMillis()
            elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos() + fixes * 10_000_000_000L
            if (altitude != null) this.altitude = altitude
        }
        shadowOf(manager).simulateLocation(location)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    /** Finish with a save that fails after [beforeFailing] has changed the world. */
    private suspend fun finishWithFailingSave(beforeFailing: () -> Unit): RunNotSavedException {
        try {
            RecordingFinisher.finish(context, tracker, repository, history, saveRun = {
                beforeFailing()
                throw java.io.IOException("disk full")
            })
        } catch (e: RunNotSavedException) {
            return e
        }
        throw AssertionError("finish did not fail")
    }

    @Test
    fun `a run that cannot be put back says so, with GPS off`() = runTest {
        installRadio(backgroundScope).inRangeAnswer = emptyList()
        assertTrue(tracker.start(OutdoorActivityType.RUN))
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val failure = finishWithFailingSave { shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false) }
        assertFalse(failure.putBack)
        assertFalse(tracker.isRecording.value)
        assertEquals(RunFinishCopy.RUN_LOST, RunFinishCopy.messageFor(failure))
    }

    @Test
    fun `a run that cannot be put back says so, with location permission revoked`() = runTest {
        installRadio(backgroundScope).inRangeAnswer = emptyList()
        assertTrue(tracker.start(OutdoorActivityType.RUN))
        val app = context as Application
        val failure = finishWithFailingSave { shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION) }
        assertFalse(failure.putBack)
        assertEquals(RunFinishCopy.RUN_LOST, RunFinishCopy.messageFor(failure))
    }

    @Test
    fun `a run that cannot be put back says so, when another run began during the save`() = runTest {
        installRadio(backgroundScope).inRangeAnswer = emptyList()
        assertTrue(tracker.start(OutdoorActivityType.RUN))
        val failure = finishWithFailingSave { assertTrue(tracker.start(OutdoorActivityType.WALK)) }
        assertFalse(failure.putBack)
        assertEquals(OutdoorActivityType.WALK, tracker.activityType.value) // the new run is not disturbed
        assertEquals(RunFinishCopy.RUN_LOST, RunFinishCopy.messageFor(failure))
    }

    @Test
    fun `a run that is put back says it is still recording`() = runTest {
        installRadio(backgroundScope).inRangeAnswer = emptyList()
        assertTrue(tracker.start(OutdoorActivityType.RUN))
        val failure = finishWithFailingSave { }
        assertTrue(failure.putBack)
        assertTrue(tracker.isRecording.value)
        assertEquals(RunFinishCopy.SAVE_FAILED, RunFinishCopy.messageFor(failure))
    }

    @Test
    fun `the words follow what is true and a cancellation says nothing`() {
        assertNull(RunFinishCopy.messageFor(kotlinx.coroutines.CancellationException("screen left")))
        assertEquals(RunFinishCopy.SAVE_FAILED, RunFinishCopy.messageFor(RunNotSavedException(true, RuntimeException())))
        assertEquals(RunFinishCopy.RUN_LOST, RunFinishCopy.messageFor(RunNotSavedException(false, RuntimeException())))
        assertEquals(RunFinishCopy.SAVE_FAILED, RunFinishCopy.messageFor(IllegalStateException()))
        assertFalse(RunFinishCopy.SAVE_FAILED == RunFinishCopy.RUN_LOST)
    }

    @Test
    fun `a Finish started from a screen still saves exactly once after that screen's scope is cancelled`() = runTest {
        installRadio(backgroundScope).inRangeAnswer = emptyList()
        assertTrue(tracker.start(OutdoorActivityType.RUN))
        runCurrent()
        history.gate = CompletableDeferred()
        history.result = listOf(110, 130)
        // A click handler on a screen: it only starts the Finish, which runs in the tracker's scope.
        val screen = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        var saved: com.dugcanlift.macrocalc.data.OutdoorActivity? = null
        val failures = ArrayList<String>()
        lateinit var finish: Job
        screen.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            finish = RecordingFinisher.launchFinish(
                context, tracker, repository, onSaved = { saved = it }, onFailed = { failures.add(it) },
                scope = backgroundScope, history = history
            )
            kotlinx.coroutines.awaitCancellation()
        }
        runCurrent()
        screen.coroutineContext[Job]!!.cancel() // a tab switch or Back during the Health Connect wait
        history.gate!!.complete(Unit)
        finish.join()

        assertNotNull(saved)
        assertEquals(1, repository.activitiesForBackup().size)
        assertEquals(saved!!.id, repository.activitiesForBackup().single().id)
        assertTrue(failures.isEmpty())
        assertEquals(1, history.calls.size)
    }

    @Test
    fun `a cancelled Finish shows no failure and saves nothing`() = runTest {
        installRadio(backgroundScope).inRangeAnswer = emptyList()
        assertTrue(tracker.start(OutdoorActivityType.RUN))
        runCurrent()
        history.gate = CompletableDeferred()
        val failures = ArrayList<String>()
        var savedCount = 0
        val job = RecordingFinisher.launchFinish(
            context, tracker, repository, onSaved = { savedCount++ }, onFailed = { failures.add(it) },
            scope = backgroundScope, history = history
        )
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(failures.toString(), failures.isEmpty())
        assertEquals(0, savedCount)
        assertEquals(0, repository.activitiesForBackup().size)
        assertTrue(tracker.isRecording.value) // the run goes on, and the next Finish saves it
    }

    @Test
    fun `a failed Finish through the launcher says the words that are true`() = runTest {
        installRadio(backgroundScope).inRangeAnswer = emptyList()
        assertTrue(tracker.start(OutdoorActivityType.RUN))
        runCurrent()
        val blocker = File(context.filesDir, "outdoor_activities.json.tmp").also { it.mkdirs() }
        val failures = ArrayList<String>()
        RecordingFinisher.launchFinish(
            context, tracker, repository, onSaved = {}, onFailed = { failures.add(it) },
            scope = backgroundScope, history = history
        ).join()
        blocker.delete()
        assertEquals(listOf(RunFinishCopy.SAVE_FAILED), failures)
    }

    @Test
    fun `cancelled during the save, a saved run is never put back and never saved twice`() = runTest {
        installRadio(backgroundScope).inRangeAnswer = emptyList()
        assertTrue(tracker.start(OutdoorActivityType.RUN))
        runCurrent()
        lateinit var job: Job
        var saves = 0
        job = launch {
            // Whether the cancellation surfaces on return from the save or at the next suspension,
            // the run is on disk once and is not put back to record again.
            try {
                RecordingFinisher.finish(context, tracker, repository, history, saveRun = {
                    job.cancel() // the screen's scope dies while the save runs
                    saves++
                    repository.save(it)
                })
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            }
        }
        job.join()
        assertEquals(1, saves)
        assertEquals(1, repository.activitiesForBackup().size) // saved
        assertFalse(tracker.isRecording.value) // and not put back
        assertNull(RecordingFinisher.finish(context, tracker, repository, history)) // a retry has nothing to save
        assertEquals(1, repository.activitiesForBackup().size)
    }

    @Test
    fun `a Finish still waiting for a discarded run does not cost the next run its strap or its own Finish`() = runTest {
        val central = installRadio(backgroundScope)
        assertTrue(tracker.start(OutdoorActivityType.RUN))
        runCurrent()
        // Run A: no readings, so its Finish asks a slow Health Connect.
        history.gate = CompletableDeferred()
        history.result = listOf(100)
        var aResult: Any? = "unset"
        val a = launch { aResult = RecordingFinisher.finish(context, tracker, repository, history) }
        runCurrent()
        tracker.stop() // Discard
        Thread.sleep(5)
        central.probe = CompletableDeferred() // B's three second look is still going when...
        assertTrue(tracker.start(OutdoorActivityType.WALK))
        runCurrent()
        central.probe!!.complete(Unit) // ...it finishes, with A's wait still open
        runCurrent()
        central.deliver(150, System.currentTimeMillis())
        central.deliver(170, System.currentTimeMillis())

        val b = RecordingFinisher.finish(context, tracker, repository, history)!! // B's own Finish works
        assertEquals("Polar H10", b.heartRateSource)
        assertEquals(160, b.averageBpm)
        assertEquals(OutdoorActivityType.WALK, b.activityType)

        history.gate!!.complete(Unit)
        a.join()
        assertNull(aResult)
        assertEquals(1, repository.activitiesForBackup().size) // only B
    }

    @Test
    fun `a put-back run carries on from its last altitude, not sea level`() = runTest {
        installRadio(backgroundScope).inRangeAnswer = emptyList()
        assertTrue(tracker.start(OutdoorActivityType.RUN))
        fix(40.0, -105.0, altitude = 1600.0)
        val failure = finishWithFailingSave { }
        assertTrue(failure.putBack)
        fix(40.0002, -105.0) // a fix with no altitude of its own
        val points = tracker.routePoints.value
        assertEquals(2, points.size)
        assertEquals(1600.0, points.last().altitudeMeters, 0.0)
    }
}
