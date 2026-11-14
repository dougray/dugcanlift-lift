package com.dugcanlift.macrocalc.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a run keeps of its heart rate -- `averageBpm`, `maxBpm`, `heartRateSource` -- in the stored
 * file and in the backup. Twins of lift-ios's BackupOutdoorTests heart rate cases. Omitted when
 * unknown, and junk restores as unknown, never as a throw.
 */
@RunWith(RobolectricTestRunner::class)
class OutdoorBackupHeartRateTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun resetSingletons() {
        listOf(FoodRepository::class.java, WorkoutRepository::class.java,
               RoutineRepository::class.java, RecipeRepository::class.java,
               OutdoorActivityRepository::class.java,
               CoachStore::class.java, SettingsStore::class.java, GoalStore::class.java).forEach {
            val field = it.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        }
    }

    private val repo get() = OutdoorActivityRepository.get(context)

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    private fun file(vararg records: JSONObject) = JSONObject()
        .put("v", 1).put("app", "lift").put("saved", "2026-11-10")
        .put("data", JSONObject().put("outdoor", JSONArray().apply { records.forEach { put(it) } })).toString()

    private fun finishedRun(averageBpm: Int? = null, maxBpm: Int? = null, source: String? = null) = OutdoorActivity(
        id = "run-1", activityType = OutdoorActivityType.RUN,
        startedAtEpochMs = 1_789_600_000_000L, endedAtEpochMs = 1_789_601_800_000L,
        distanceMeters = 5000.0, averageBpm = averageBpm, maxBpm = maxBpm, heartRateSource = source
    )

    private fun bare(id: String = "run-junk") = JSONObject()
        .put("id", id).put("activityType", "RUN")
        .put("startedAtEpochMs", 1_789_600_000_000L).put("endedAtEpochMs", 1_789_601_800_000L)
        .put("distanceMeters", 5000).put("elevationGainMeters", 0).put("routePoints", JSONArray())

    // Writing and reading the backup

    @Test
    fun `a runs heart rate round trips through the backup file`() = runTest {
        repo.save(finishedRun(141, 168, "Polar H10"))
        val record = JSONObject(BackupStore.build(context)).getJSONObject("data")
            .getJSONArray("outdoor").getJSONObject(0)
        assertEquals(141, record.getInt("averageBpm"))
        assertEquals(168, record.getInt("maxBpm"))
        assertEquals("Polar H10", record.getString("heartRateSource"))

        val saved = BackupStore.build(context)
        repo.delete("run-1")
        assertEquals(1, BackupStore.restore(context, saved).added)
        val restored = repo.activitiesForBackup().single()
        assertEquals(141, restored.averageBpm)
        assertEquals(168, restored.maxBpm)
        assertEquals("Polar H10", restored.heartRateSource)
    }

    @Test
    fun `a run without heart rate writes none of the three keys`() = runTest {
        repo.save(finishedRun())
        val record = JSONObject(BackupStore.build(context)).getJSONObject("data")
            .getJSONArray("outdoor").getJSONObject(0)
        assertFalse(record.has("averageBpm"))
        assertFalse(record.has("maxBpm"))
        assertFalse(record.has("heartRateSource"))
    }

    @Test
    fun `a backup without the keys restores null`() {
        assertEquals(3, BackupStore.restore(context, fixture("web-backup-outdoor.json")).added)
        for (run in repo.activitiesForBackup()) {
            assertNull(run.averageBpm)
            assertNull(run.maxBpm)
            assertNull(run.heartRateSource)
        }
    }

    @Test
    fun `a non integer heart rate restores null`() {
        val record = bare().put("averageBpm", 141.5).put("maxBpm", "168").put("heartRateSource", 7)
        assertEquals(1, BackupStore.restore(context, file(record)).added)
        val run = repo.activitiesForBackup().single()
        assertNull("141.5 is not a whole bpm", run.averageBpm)
        assertNull("a string is not a figure", run.maxBpm)
        assertNull("a number is not a source name", run.heartRateSource)
    }

    @Test
    fun `junk heart rates restore null and never throw`() {
        for (junk in listOf<Any>(true, false, 1e300, 0, -5, JSONObject.NULL, "NaN", JSONArray())) {
            val record = bare("run-${junk.hashCode()}").put("averageBpm", junk).put("maxBpm", junk)
                .put("heartRateSource", junk)
            val run = BackupStore.outdoorForRestore(record)!!
            assertNull("average from $junk", run.averageBpm)
            assertNull("max from $junk", run.maxBpm)
            // A string is a name, however odd; everything else is not.
            if (junk !is String) assertNull("source from $junk", run.heartRateSource)
        }
    }

    @Test
    fun `an empty source name restores null`() {
        val run = BackupStore.outdoorForRestore(bare().put("heartRateSource", ""))!!
        assertNull(run.heartRateSource)
    }

    @Test
    fun `a whole number as a double restores`() {
        val run = BackupStore.outdoorForRestore(bare().put("averageBpm", 141.0).put("maxBpm", 168L))!!
        assertEquals(141, run.averageBpm)
        assertEquals(168, run.maxBpm)
    }

    // The stored file

    @Test
    fun `the stored file keeps the three fields`() {
        val json = finishedRun(141, 168, "Pixel Watch 2").toJson()
        val back = outdoorActivityFromJson(json)
        assertEquals(141, back.averageBpm)
        assertEquals(168, back.maxBpm)
        assertEquals("Pixel Watch 2", back.heartRateSource)
    }

    @Test
    fun `the stored file omits what is unknown`() {
        val json = finishedRun().toJson()
        assertFalse(json.has("averageBpm"))
        assertFalse(json.has("maxBpm"))
        assertFalse(json.has("heartRateSource"))
    }

    @Test
    fun `a stored file written before heart rate reads as nothing measured`() {
        // Exactly what main wrote: no heart rate keys. Additive and optional, so no migration.
        val old = JSONObject()
            .put("id", "old").put("activityType", "WALK").put("startedAtEpochMs", 1L)
            .put("endedAtEpochMs", 2L).put("distanceMeters", 10.0).put("elevationGainMeters", 0.0)
            .put("routePoints", JSONArray())
        val run = outdoorActivityFromJson(old)
        assertNull(run.averageBpm)
        assertNull(run.maxBpm)
        assertNull(run.heartRateSource)
        assertTrue(run.routePoints.isEmpty())
    }

    @Test
    fun `junk in the stored file reads as nothing measured`() {
        val stored = finishedRun().toJson().put("averageBpm", "168").put("maxBpm", 141.5).put("heartRateSource", 7)
        val run = outdoorActivityFromJson(stored)
        assertNull(run.averageBpm)
        assertNull(run.maxBpm)
        assertNull(run.heartRateSource)
    }

    @Test
    fun `the repository keeps the fields across a reload`() = runTest {
        repo.save(finishedRun(150, 171, "Wahoo TICKR"))
        val read = repo.activitiesForBackup().single()
        assertEquals(150, read.averageBpm)
        assertEquals(171, read.maxBpm)
        assertEquals("Wahoo TICKR", read.heartRateSource)
    }
}
