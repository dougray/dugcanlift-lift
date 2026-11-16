package com.dugcanlift.macrocalc

import android.app.Application
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.test.core.app.ApplicationProvider
import com.dugcanlift.macrocalc.data.HealthConnectManager
import com.dugcanlift.macrocalc.data.OutdoorActivity
import com.dugcanlift.macrocalc.data.OutdoorActivityRepository
import com.dugcanlift.macrocalc.data.OutdoorActivityType
import com.dugcanlift.macrocalc.data.RunHeartRateRecord
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

/** What a review's late answer may write, and the two new Health Connect grants. */
@RunWith(RobolectricTestRunner::class)
class RunHeartRateStoreTest {
    private lateinit var repository: OutdoorActivityRepository
    private val record = RunHeartRateRecord(135, 150, "Health Connect")

    private fun run(id: String = "run-1", exportedAs: String? = null, average: Int? = null) = OutdoorActivity(
        id = id, activityType = OutdoorActivityType.RUN, startedAtEpochMs = 1_000L, endedAtEpochMs = 3_601_000L,
        healthConnectRecordId = exportedAs, averageBpm = average, maxBpm = average, heartRateSource = if (average != null) "Polar H10" else null
    )

    @Before
    fun setUp() {
        val field = OutdoorActivityRepository::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
        repository = OutdoorActivityRepository.get(ApplicationProvider.getApplicationContext<Application>())
    }

    @Test
    fun `a late answer fills the stored run`() = runTest {
        repository.save(run())
        val filled = repository.fillHeartRate("run-1", record)
        assertEquals(135, filled?.averageBpm)
        assertEquals("Health Connect", repository.activitiesForBackup().single().heartRateSource)
    }

    @Test
    fun `a run deleted while Health Connect answered is not written back`() = runTest {
        repository.save(run())
        repository.delete("run-1")
        assertNull(repository.fillHeartRate("run-1", record))
        assertEquals(0, repository.activitiesForBackup().size)
    }

    @Test
    fun `a run that gained figures meanwhile keeps them`() = runTest {
        repository.save(run(average = 141))
        assertNull(repository.fillHeartRate("run-1", record))
        assertEquals("Polar H10", repository.activitiesForBackup().single().heartRateSource)
    }

    @Test
    fun `the fill merges into what is stored now and keeps an export made meanwhile`() = runTest {
        repository.save(run())
        // The review exported the run while Health Connect was answering.
        repository.save(run(exportedAs = "hc-record"))
        val filled = repository.fillHeartRate("run-1", record)!!
        assertEquals("hc-record", filled.healthConnectRecordId)
        assertEquals(135, filled.averageBpm)
    }

    @Test
    fun `heart rate and VO2 max are two read grants of their own, kept out of steps and of the steps button`() {
        assertEquals(
            setOf(
                HealthPermission.getReadPermission(HeartRateRecord::class),
                HealthPermission.getReadPermission(Vo2MaxRecord::class)
            ),
            HealthConnectManager.heartRatePermissions
        )
        assertEquals(
            setOf("android.permission.health.READ_HEART_RATE", "android.permission.health.READ_VO2_MAX"),
            HealthConnectManager.heartRatePermissions
        )
        // "Steps connected" and the steps button still mean steps.
        assertTrue(HealthConnectManager.permissions.none { it in HealthConnectManager.heartRatePermissions })
        assertTrue(HealthConnectManager.permissionsToRequest.none { it in HealthConnectManager.heartRatePermissions })
        assertTrue(HealthConnectManager.writePermissions.none { it in HealthConnectManager.heartRatePermissions })
    }

    @Test
    fun `the rationale text names heart rate and VO2 max`() {
        val text = PermissionsRationaleActivity.HEALTH_CONNECT_SUMMARY
        assertTrue(text, text.contains("heart rate"))
        assertTrue(text, text.contains("VO2 max"))
        assertTrue(text, text.contains("steps")) // and still says what it always said
    }

    @Test
    fun `the manifest declares the two read permissions`() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertNotNull(manifest)
        assertTrue(manifest.contains("android.permission.health.READ_HEART_RATE"))
        assertTrue(manifest.contains("android.permission.health.READ_VO2_MAX"))
        assertFalse(manifest.contains("android.permission.health.WRITE_HEART_RATE"))
    }
}
