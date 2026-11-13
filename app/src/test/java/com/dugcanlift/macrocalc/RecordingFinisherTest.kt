package com.dugcanlift.macrocalc

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.dugcanlift.macrocalc.data.OutdoorActivityRepository
import com.dugcanlift.macrocalc.data.OutdoorActivityType
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

        val first = RecordingFinisher.finish(tracker, repository)
        val second = RecordingFinisher.finish(tracker, repository)

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
        assertNull(RecordingFinisher.finish(tracker, repository))
        assertEquals(0, repository.activitiesForBackup().size)
    }

    @Test
    fun finishWorksWithNoScreenAtAll() = runTest {
        // The stranded case: the tracker runs, no recording screen was ever composed.
        assertTrue(tracker.start(OutdoorActivityType.HIKE))
        val saved = RecordingFinisher.finish(tracker, repository)
        assertNotNull(saved)
        assertEquals(OutdoorActivityType.HIKE, saved!!.activityType)
        assertEquals(1, repository.activitiesForBackup().size)
    }

    @Test
    fun cancelStopsAndSavesNothing() = runTest {
        assertTrue(tracker.start(OutdoorActivityType.RUN))
        tracker.stop()
        assertFalse(tracker.isRecording.value)
        assertNull(RecordingFinisher.finish(tracker, repository))
        assertEquals(0, repository.activitiesForBackup().size)
    }
}
