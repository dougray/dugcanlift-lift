package com.dugcanlift.macrocalc.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dugcanlift.kit.ShareLinkCodec
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
import java.util.Calendar

/**
 * Runs, walks and hikes in a coach link (SHARE-FORMAT "Outdoor").
 *
 * The expected `o`, `ob` and `lr` come from LIFT web's own encoder, copied from
 * `lift/fixtures/` in the site repo. Never regenerate them from this code: the
 * point is that Android produces what web produced, down to the polyline.
 */
@RunWith(RobolectricTestRunner::class)
class CoachShareOutdoorTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // Before and after: the stores cache the first context's prefs, and a
    // route opt-in or random lifter id left behind would change the link
    // CoachShareCaptureTest checks when it runs next in the same JVM.
    @Before
    @After
    fun resetSingletons() {
        listOf(CoachStore::class.java, SettingsStore::class.java).forEach {
            val field = it.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        }
    }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    private fun fixtureActivities(): List<OutdoorActivity> {
        val array = JSONObject(fixture("outdoor-share-input.json")).getJSONArray("outdoor")
        return (0 until array.length()).map { outdoorActivityFromJson(array.getJSONObject(it)) }
    }

    private val expected get() = JSONObject(fixture("outdoor-share-expected.json"))

    /** A day after the fixture's last recording, so every activity sits in a four-week window. */
    private val fixtureNowMs = 1_789_345_600_000L + 86_400_000L

    private fun store(sendRoute: Boolean): CoachStore = CoachStore.get(context).apply {
        restoreLifterId("outdoor1")
        lifterName = "Outdoor Fixture"
        weeks = 4
        itemisedFood = true
        sendLastRoute = sendRoute
    }

    private fun json(
        store: CoachStore,
        outdoor: List<OutdoorActivity>,
        sessions: List<WorkoutSession> = emptyList(),
        entries: List<FoodEntry> = emptyList(),
        nowMs: Long = fixtureNowMs
    ): JSONObject = ShareLinkCodec.buildJson(
        CoachShare.buildSharePayload(store, SettingsStore.get(context), null, sessions, entries, emptyMap(), outdoor, nowMs)
    )

    private fun days(json: JSONObject): List<JSONObject> =
        json.getJSONArray("d").let { d -> (0 until d.length()).map { d.getJSONObject(it) } }

    private fun allOutdoor(json: JSONObject): JSONArray = JSONArray().also { out ->
        days(json).forEach { day -> day.optJSONArray("o")?.let { o -> (0 until o.length()).forEach { out.put(o.get(it)) } } }
    }

    @Test
    fun `fixture activities produce web's o, ob and lr exactly`() {
        val activities = fixtureActivities()
        assertTrue("the fixture's unfinished hike must reach the builder", activities.any { it.endedAtEpochMs == null })

        val json = json(store(sendRoute = true), activities)

        assertEquals(expected.getJSONArray("o").toString(), allOutdoor(json).toString())
        assertEquals(expected.getJSONArray("ob").toString(), json.getJSONArray("ob").toString())
        assertEquals(expected.getJSONArray("lr").toString(), json.getJSONArray("lr").toString())
        assertEquals(expected.getJSONArray("lr").getString(5), json.getJSONArray("lr").getString(5))
    }

    @Test
    fun `a day holding only outdoor activity is still sent, one day per activity start`() {
        val json = json(store(sendRoute = false), fixtureActivities())
        val outdoorDays = days(json)

        // Three finished activities on three different days; the unfinished hike's day has nothing to send.
        assertEquals(3, outdoorDays.size)
        outdoorDays.forEach { day ->
            assertEquals(setOf("k", "o"), day.keys().asSequence().toSet())
            assertEquals(1, day.getJSONArray("o").length())
        }
    }

    @Test
    fun `route off still sends o and ob, but no lr`() {
        val json = json(store(sendRoute = false), fixtureActivities())

        assertEquals(expected.getJSONArray("o").toString(), allOutdoor(json).toString())
        assertEquals(expected.getJSONArray("ob").toString(), json.getJSONArray("ob").toString())
        assertFalse(json.has("lr"))
    }

    @Test
    fun `route off is the default`() {
        val fresh = CoachStore.get(context)
        assertFalse(fresh.sendLastRoute)
        assertFalse(json(fresh, fixtureActivities()).has("lr"))
    }

    @Test
    fun `bests are all-time even when every activity is outside the window`() {
        val monthsLater = fixtureNowMs + 200L * 86_400_000L
        val json = json(store(sendRoute = true), fixtureActivities(), nowMs = monthsLater)

        assertEquals(0, allOutdoor(json).length())
        assertEquals(expected.getJSONArray("ob").toString(), json.getJSONArray("ob").toString())
        assertEquals(expected.getJSONArray("lr").toString(), json.getJSONArray("lr").toString())
    }

    @Test
    fun `no outdoor data sends no outdoor keys, even with the route turned on`() {
        val store = store(sendRoute = true)
        val now = System.currentTimeMillis()
        val json = json(store, emptyList(), CoachShareFixture.sessions, CoachShareFixture.entries, now)

        assertFalse(json.has("ob"))
        assertFalse(json.has("lr"))
        days(json).forEach { assertFalse(it.has("o")) }
        // And the builder's default leaves the payload exactly as it was before outdoor existed.
        val withoutArgument = ShareLinkCodec.buildJson(
            CoachShare.buildSharePayload(store, SettingsStore.get(context), null, CoachShareFixture.sessions, CoachShareFixture.entries, emptyMap(), nowMs = now)
        )
        assertEquals(withoutArgument.toString(), json.toString())
    }

    @Test
    fun `outdoor activity adds to a log without changing anything else in it`() {
        val store = store(sendRoute = true)
        val now = System.currentTimeMillis()
        val base = fixtureActivities().filter { it.endedAtEpochMs != null }
        // The fixture's activities moved to recent days: one alongside a logged
        // session and food, one on a day with nothing else logged.
        val onLoggedDay = base[0].shiftedToDaysAgo(1)
        val onEmptyDay = base[1].shiftedToDaysAgo(5)

        val without = json(store, emptyList(), CoachShareFixture.sessions, CoachShareFixture.entries, now)
        val with = json(store, listOf(onLoggedDay, onEmptyDay), CoachShareFixture.sessions, CoachShareFixture.entries, now)

        assertTrue(with.has("ob"))
        assertTrue(with.has("lr"))
        assertEquals(without.getJSONArray("d").length() + 1, with.getJSONArray("d").length())

        val stripped = JSONObject(with.toString()).apply {
            remove("ob"); remove("lr")
            val kept = JSONArray()
            days(this).forEach { day ->
                day.remove("o")
                if (day.keys().asSequence().toSet() != setOf("k")) kept.put(day)
            }
            put("d", kept)
        }
        assertEquals(without.toString(), stripped.toString())
    }

    private fun OutdoorActivity.shiftedToDaysAgo(days: Int): OutdoorActivity {
        val target = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -days)
            set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val shift = target - startedAtEpochMs
        return copy(startedAtEpochMs = target, endedAtEpochMs = endedAtEpochMs?.plus(shift))
    }

    @Test
    fun `the unfinished hike alone sends nothing`() {
        val hike = fixtureActivities().single { it.endedAtEpochMs == null }
        val json = json(store(sendRoute = true), listOf(hike))

        assertEquals(0, days(json).size)
        assertFalse(json.has("ob"))
        assertNull(json.optJSONArray("lr"))
    }
}
