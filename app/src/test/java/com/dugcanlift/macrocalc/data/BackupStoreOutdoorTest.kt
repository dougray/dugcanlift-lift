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
 * Runs, walks and hikes in the backup file (coach/BACKUP-FORMAT.md, `outdoor[]`).
 *
 * `fixtures/web-backup-outdoor.json` was written by LIFT web's own
 * `LiftOutdoor.toBackup` -- see the note inside it. It is what proves this app
 * reads the file another client writes, rather than agreeing with itself. Do not
 * regenerate it from this code.
 */
@RunWith(RobolectricTestRunner::class)
class BackupStoreOutdoorTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    // Process-wide singletons Robolectric's classloader reuses; see BackupStoreTest
    // for why CoachStore in particular must be reset after as well as before.
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

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    private fun file(outdoor: JSONArray) = JSONObject()
        .put("v", 1).put("app", "lift").put("saved", "2026-09-16")
        .put("data", JSONObject().put("outdoor", outdoor)).toString()

    private val repo get() = OutdoorActivityRepository.get(context)

    private fun stored() = repo.activitiesForBackup().associateBy { it.id }

    // MARK: - Reading LIFT web's file

    @Test
    fun `a web backup restores its finished activities`() {
        val result = BackupStore.restore(context, fixture("web-backup-outdoor.json"))
        assertTrue(result.ok)
        assertEquals(3, result.added)

        val loop = stored().getValue("run-loop")
        assertEquals(OutdoorActivityType.RUN, loop.activityType)
        assertEquals(1789259200000L, loop.startedAtEpochMs)
        assertEquals(1789260920000L, loop.endedAtEpochMs)
        assertEquals(2794.6, loop.distanceMeters, 1e-9)
        assertEquals(36.7, loop.elevationGainMeters, 1e-9)
        assertEquals(400, loop.routePoints.size)
        val second = loop.routePoints[1]
        assertEquals(30.267263, second.latitude, 1e-12)
        assertEquals(-97.743099, second.longitude, 1e-12)
        assertEquals(150.4, second.altitudeMeters, 1e-9)
        assertEquals(1789259204000L, second.recordedAtEpochMs)
        assertEquals(6.0, second.horizontalAccuracyMeters, 1e-9)
        assertNull(loop.healthConnectRecordId)

        assertEquals(OutdoorActivityType.WALK, stored().getValue("walk-short").activityType)
    }

    @Test
    fun `a null altitude from the web restores as this app's no-altitude value`() {
        BackupStore.restore(context, fixture("web-backup-outdoor.json"))
        val long = stored().getValue("run-long")
        assertEquals(60, long.routePoints.size)
        assertTrue(long.routePoints.all { it.altitudeMeters == 0.0 })
    }

    // MARK: - Writing

    @Test
    fun `the saved section matches what LIFT web wrote, field for field`() {
        val web = JSONObject(fixture("web-backup-outdoor.json")).getJSONObject("data").getJSONArray("outdoor")
        BackupStore.restore(context, fixture("web-backup-outdoor.json"))
        val out = JSONObject(BackupStore.build(context)).getJSONObject("data").getJSONArray("outdoor")
        assertEquals(web.length(), out.length())

        val byId = (0 until out.length()).associate { out.getJSONObject(it).let { o -> o.getString("id") to o } }
        for (i in 0 until web.length()) {
            val expected = web.getJSONObject(i)
            val actual = byId.getValue(expected.getString("id"))
            assertEquals(keys(expected), keys(actual))
            for (key in listOf("startedAtEpochMs", "endedAtEpochMs")) {
                assertEquals(expected.getLong(key), actual.getLong(key))
            }
            assertEquals(expected.getString("activityType"), actual.getString("activityType"))
            assertEquals(expected.getDouble("distanceMeters"), actual.getDouble("distanceMeters"), 1e-9)
            assertEquals(expected.getDouble("elevationGainMeters"), actual.getDouble("elevationGainMeters"), 1e-9)

            val ePoints = expected.getJSONArray("routePoints")
            val aPoints = actual.getJSONArray("routePoints")
            assertEquals(ePoints.length(), aPoints.length())
            for (j in 0 until ePoints.length()) {
                val e = ePoints.getJSONObject(j)
                val a = aPoints.getJSONObject(j)
                assertEquals(keys(e), keys(a))
                for (key in listOf("latitude", "longitude", "horizontalAccuracyMeters")) {
                    assertEquals(e.getDouble(key), a.getDouble(key), 1e-12)
                }
                assertEquals(e.getLong("recordedAtEpochMs"), a.getLong("recordedAtEpochMs"))
                val expectedAltitude = if (e.isNull("altitudeMeters")) 0.0 else e.getDouble("altitudeMeters")
                assertEquals(expectedAltitude, a.getDouble("altitudeMeters"), 1e-9)
            }
        }
    }

    @Test
    fun `a round trip through this app's own file loses nothing the format carries`() = runTest {
        BackupStore.restore(context, fixture("web-backup-outdoor.json"))
        val before = repo.activitiesForBackup().sortedBy { it.id }
        val saved = BackupStore.build(context)

        // A new phone: empty store.
        repo.activitiesForBackup().forEach { repo.delete(it.id) }
        assertTrue(repo.activitiesForBackup().isEmpty())

        assertEquals(3, BackupStore.restore(context, saved).added)
        assertEquals(before, repo.activitiesForBackup().sortedBy { it.id })
    }

    @Test
    fun `an unfinished recording on this phone is not written`() = runTest {
        repo.save(OutdoorActivity(id = "live", activityType = OutdoorActivityType.RUN, startedAtEpochMs = 1L))
        repo.save(OutdoorActivity(id = "done", activityType = OutdoorActivityType.HIKE,
            startedAtEpochMs = 1L, endedAtEpochMs = 2L))
        val out = JSONObject(BackupStore.build(context)).getJSONObject("data").getJSONArray("outdoor")
        assertEquals(1, out.length())
        assertEquals("done", out.getJSONObject(0).getString("id"))
    }

    @Test
    fun `the Health Connect record id is never written`() = runTest {
        repo.save(OutdoorActivity(id = "exported", activityType = OutdoorActivityType.WALK,
            startedAtEpochMs = 1L, endedAtEpochMs = 2L, activeCalories = 90.0,
            healthConnectRecordId = "hc-123"))
        val saved = BackupStore.build(context)
        assertFalse(saved.contains("healthConnectRecordId"))
        assertFalse(saved.contains("hc-123"))
    }

    // MARK: - Restore rules

    @Test
    fun `the Health Connect record id is never restored`() {
        val activity = JSONObject(fixture("web-backup-outdoor.json")).getJSONObject("data")
            .getJSONArray("outdoor").getJSONObject(1).put("healthConnectRecordId", "from-another-phone")
        BackupStore.restore(context, file(JSONArray().put(activity)))
        assertNull(stored().getValue("walk-short").healthConnectRecordId)
    }

    @Test
    fun `an id in the other case is the same activity`() {
        BackupStore.restore(context, fixture("web-backup-outdoor.json"))
        val upper = JSONObject(fixture("web-backup-outdoor.json"))
        val outdoor = upper.getJSONObject("data").getJSONArray("outdoor")
        for (i in 0 until outdoor.length()) {
            outdoor.getJSONObject(i).let { it.put("id", it.getString("id").uppercase()) }
        }
        assertEquals(0, BackupStore.restore(context, upper.toString()).added)
        assertEquals(3, repo.activitiesForBackup().size)
    }

    @Test
    fun `a file listing the same activity twice adds it once`() {
        val walk = JSONObject(fixture("web-backup-outdoor.json")).getJSONObject("data")
            .getJSONArray("outdoor").getJSONObject(1)
        val shouted = JSONObject(walk.toString()).put("id", "WALK-SHORT")
        assertEquals(1, BackupStore.restore(context, file(JSONArray().put(walk).put(shouted))).added)
    }

    @Test
    fun `restoring never overwrites an activity already here`() = runTest {
        repo.save(OutdoorActivity(id = "walk-short", activityType = OutdoorActivityType.WALK,
            startedAtEpochMs = 5L, endedAtEpochMs = 6L, healthConnectRecordId = "kept"))
        assertEquals(2, BackupStore.restore(context, fixture("web-backup-outdoor.json")).added)
        val walk = stored().getValue("walk-short")
        assertEquals("kept", walk.healthConnectRecordId)
        assertEquals(5L, walk.startedAtEpochMs)
    }

    @Test
    fun `an unfinished activity, an unknown type and a missing id are skipped`() {
        // LIFT web's share fixture carries a hike with no end, to prove senders skip it.
        val input = JSONObject(fixture("outdoor-share-input.json")).getJSONArray("outdoor")
        val unfinished = (0 until input.length()).map { input.getJSONObject(it) }
            .single { it.getString("id") == "hike-unfinished" }
        val nullEnd = JSONObject(unfinished.toString()).put("id", "null-end").put("endedAtEpochMs", JSONObject.NULL)
        val swim = JSONObject().put("id", "swim").put("activityType", "SWIM")
            .put("startedAtEpochMs", 1L).put("endedAtEpochMs", 2L)
        val lowercase = JSONObject(swim.toString()).put("id", "lower").put("activityType", "run")
        val noId = JSONObject().put("activityType", "RUN").put("startedAtEpochMs", 1L).put("endedAtEpochMs", 2L)
        val blankId = JSONObject(noId.toString()).put("id", "")

        val result = BackupStore.restore(context,
            file(JSONArray().put(unfinished).put(nullEnd).put(swim).put(lowercase).put(noId).put(blankId)))
        assertTrue(result.ok)
        assertEquals(0, result.added)
        assertTrue(repo.activitiesForBackup().isEmpty())
    }

    @Test
    fun `missing distance and elevation are worked out from the route`() {
        val walk = JSONObject(fixture("web-backup-outdoor.json")).getJSONObject("data")
            .getJSONArray("outdoor").getJSONObject(1)
        walk.remove("distanceMeters")
        BackupStore.restore(context, file(JSONArray().put(walk)))
        val restored = stored().getValue("walk-short")
        assertEquals(OutdoorActivityMath.totalDistanceMeters(restored.routePoints), restored.distanceMeters, 1e-9)
        assertTrue(restored.distanceMeters > 0.0)
    }

    // MARK: - A copy preserved before this app stored outdoor

    @Test
    fun `an outdoor section preserved by an older version moves into the store on the next save`() {
        val web = JSONObject(fixture("web-backup-outdoor.json")).getJSONObject("data").getJSONArray("outdoor")
        // What an older version left behind after restoring a web file: the
        // section kept aside as foreign, next to web's steps.
        prefs().edit().putString("foreign_data", JSONObject()
            .put("outdoor", web)
            .put("steps", JSONObject().put("2026-09-16", 8000)).toString()).commit()

        val out = JSONObject(BackupStore.build(context)).getJSONObject("data")
        assertEquals(3, out.getJSONArray("outdoor").length())
        assertEquals(8000, out.getJSONObject("steps").getInt("2026-09-16"))
        assertEquals(3, repo.activitiesForBackup().size)

        val left = JSONObject(prefs().getString("foreign_data", null)!!)
        assertFalse("the preserved copy is not kept twice", left.has("outdoor"))
        assertTrue(left.has("steps"))
    }

    @Test
    fun `a preserved outdoor section alone is cleared once adopted, and a restore adopts it too`() {
        val web = JSONObject(fixture("web-backup-outdoor.json")).getJSONObject("data").getJSONArray("outdoor")
        prefs().edit().putString("foreign_data", JSONObject().put("outdoor", web).toString()).commit()

        val result = BackupStore.restore(context, fixture("web-backup-outdoor.json"))
        assertEquals("already adopted from the preserved copy", 0, result.added)
        assertEquals(3, repo.activitiesForBackup().size)
        assertNull(prefs().getString("foreign_data", null))
    }

    @Test
    fun `outdoor is a stored section now`() {
        assertTrue("outdoor" in BackupStore.STORED)
    }

    private fun prefs() = context.getSharedPreferences("dcl_backup", Context.MODE_PRIVATE)

    private fun keys(o: JSONObject): Set<String> = o.keys().asSequence().toSet()
}
