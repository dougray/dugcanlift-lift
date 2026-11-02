package com.dugcanlift.macrocalc.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dugcanlift.kit.ShareLinkCodec
import com.dugcanlift.liftkit.link.FinishedExercise
import com.dugcanlift.liftkit.link.FinishedSession
import com.dugcanlift.liftkit.link.LinkPayloads
import com.dugcanlift.liftkit.link.LogSide
import com.dugcanlift.liftkit.link.LoggedSet
import com.dugcanlift.macrocalc.watchlink.AppliedRevision
import com.dugcanlift.macrocalc.watchlink.WatchPlanMapper
import com.dugcanlift.liftkit.link.AckOutcome
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
import java.util.Locale

/**
 * Heart rate per set, as this phone receives it: the highest bpm the Wear OS watch measured around
 * each set and the session's average and maximum, over LIFT Link version 4 (the LIFT superproject's
 * build plan, item 16). This phone reads no heart rate of its own; it stores what arrives, shows it
 * in muted text, and sends it nowhere -- no coach, no LIFT web, no CSV.
 *
 * `fixtures/backup-main-no-sides.json` was written by `BackupStore.build` on main before any of this
 * existed, and is what "an older backup restores unchanged" is checked against.
 */
@RunWith(RobolectricTestRunner::class)
class HeartRatePerSetTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun resetSingletons() {
        listOf(FoodRepository::class.java, WorkoutRepository::class.java,
               RoutineRepository::class.java, RecipeRepository::class.java,
               CoachStore::class.java, SettingsStore::class.java, GoalStore::class.java).forEach {
            val field = it.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        }
    }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    private val fromWatch = FinishedSession(
        sessionId = "1f4c8a26-70d5-4b93-8e11-6a0b3d9c2457",
        revision = 2,
        day = "2026-11-02",
        name = "Upper A",
        startedAtEpochSeconds = 1_762_092_000L,
        exercises = listOf(
            FinishedExercise("Bench Press", "Barbell", sets = listOf(
                LoggedSet(weightKg = WatchPlanMapper.lbToKg(185.0), reps = 5, maxBpm = 142),
                LoggedSet(weightKg = WatchPlanMapper.lbToKg(185.0), reps = 5),
            )),
            FinishedExercise("Split Squat", sets = listOf(
                LoggedSet(weightKg = WatchPlanMapper.lbToKg(40.0), reps = 8, side = LogSide.LEFT, maxBpm = 131),
            )),
        ),
        averageBpm = 118,
        maxBpm = 156,
    )

    /** Through the real codec, as the phone receives it. */
    private fun received(session: FinishedSession): WorkoutSession =
        WatchPlanMapper.session(LinkPayloads.decodeSession(LinkPayloads.encodeSession(session)))

    // ---- storing what arrives ----------------------------------------------------------------------

    @Test
    fun `a watch session's heart rates land on its sets and its card, and absent stays absent`() {
        val stored = received(fromWatch)
        assertEquals(listOf(142, null), stored.exercises[0].sets.map { it.maxBpm })
        assertEquals(131, stored.exercises[1].sets.single().maxBpm)
        assertEquals(SetSide.LEFT, stored.exercises[1].sets.single().side)
        assertEquals(118, stored.averageBpm)
        assertEquals(156, stored.maxBpm)
        assertEquals(185.0, stored.exercises[0].sets[0].weightLb!!, 0.0)
    }

    @Test
    fun `a session from a watch that measured nothing has no heart rate anywhere`() {
        val stored = received(fromWatch.withoutHeartRate())
        assertTrue(stored.exercises.flatMap { it.sets }.all { it.maxBpm == null })
        assertNull(stored.averageBpm)
        assertNull(stored.maxBpm)
        assertNull(HeartRateLines.session(stored))
    }

    @Test
    fun `the stored file carries them under the backup's names, and only when there is one`() {
        val stored = received(fromWatch)
        val json = stored.toJson()
        assertEquals(118, json.getInt("averageBpm"))
        assertEquals(156, json.getInt("maxBpm"))
        val bench = json.getJSONArray("exercises").getJSONObject(0).getJSONArray("sets")
        assertEquals(142, bench.getJSONObject(0).getInt("maxBpm"))
        assertFalse("a set with no figure writes no key", bench.getJSONObject(1).has("maxBpm"))

        val back = workoutSessionFromJson(JSONObject(json.toString()))
        assertEquals(stored, back)

        val unmeasured = received(fromWatch.withoutHeartRate()).toJson()
        assertFalse(unmeasured.has("averageBpm"))
        assertFalse(unmeasured.has("maxBpm"))
        assertFalse(unmeasured.toString().contains("Bpm"))
    }

    @Test
    fun `a heart rate in a file is read leniently, and junk is not measured`() {
        listOf("0", "-3", "141.5", "\"fast\"", "null", "true").forEach { junk ->
            val set = workoutSetFromJson(JSONObject("""{"id":"s","reps":5,"maxBpm":$junk}"""))
            assertNull(junk, set.maxBpm)
            assertEquals(5, set.reps)
        }
        assertEquals(142, workoutSetFromJson(JSONObject("""{"id":"s","maxBpm":142}""")).maxBpm)
    }

    @Test
    fun `a backup written before heart rate restores unchanged, with none`() {
        val result = BackupStore.restore(context, fixture("backup-main-no-sides.json"))
        assertTrue(result.problem ?: "restore failed", result.ok)
        val session = WorkoutRepository.get(context).sessions.value.single()
        assertNull(session.averageBpm)
        assertNull(session.maxBpm)
        assertTrue(session.exercises.flatMap { it.sets }.all { it.maxBpm == null })
        assertFalse(session.toJson().toString().contains("Bpm"))
    }

    @Test
    fun `an edit to a set's numbers keeps its figure, and a deleted set takes it`() {
        val bench = received(fromWatch).exercises[0]
        val edited = bench.sets[0].copy(weightLb = 190.0, reps = 4)
        assertEquals(142, edited.maxBpm)
        val deleted = bench.copy(sets = bench.sets.drop(1))
        assertTrue(deleted.sets.none { it.maxBpm != null })
    }

    // ---- the watch's next revision is not refused for it -----------------------------------------------

    @Test
    fun `a session stored before heart rate hashes as it did, so the watch's next revision is still accepted`() {
        val stored = received(fromWatch.withoutHeartRate())
        val hash = WatchPlanMapper.fingerprint(stored)
        assertEquals(hash, WatchPlanMapper.fingerprint(stored.copy(averageBpm = null, maxBpm = null)))
        assertEquals(
            AckOutcome.ACCEPTED,
            WatchPlanMapper.reconcile(AppliedRevision(1, hash), incomingRevision = 2, onPhone = stored),
        )
        // And with heart rate, the stored object's own hash still says "unchanged since we stored it".
        val measured = received(fromWatch)
        assertEquals(
            AckOutcome.ACCEPTED,
            WatchPlanMapper.reconcile(AppliedRevision(1, WatchPlanMapper.fingerprint(measured)), 3, measured),
        )
    }

    // ---- what never leaves the phone ----------------------------------------------------------------

    @Test
    fun `a heart rate reaches no coach and no spreadsheet`() {
        val measured = received(fromWatch)
        val unmeasured = received(fromWatch.withoutHeartRate())
        assertEquals(WorkoutCsv.export(WorkoutCsv.exportRows(listOf(unmeasured))),
            WorkoutCsv.export(WorkoutCsv.exportRows(listOf(measured))))

        val store = CoachStore.get(context).apply { restoreLifterId("heart001"); lifterName = "Doug"; weeks = 4 }
        val nowMs = 1_762_100_000_000L
        fun share(session: WorkoutSession) = ShareLinkCodec.buildJson(CoachShare.buildSharePayload(
            store = store, settings = SettingsStore.get(context), goal = null,
            sessions = listOf(session.copy(date = dateKey(nowMs))), entries = emptyList(), steps = emptyMap(),
            nowMs = nowMs,
        )).toString()
        assertEquals(share(unmeasured), share(measured))
    }

    // ---- the words ---------------------------------------------------------------------------------

    @Test
    fun `the words are every build's words`() {
        assertEquals("max 142 bpm", HeartRateLines.set(142))
        assertNull(HeartRateLines.set(null))
        assertNull(HeartRateLines.set(0))
        assertEquals("Heart rate: average 118 · max 156 bpm", HeartRateLines.session(118, 156))
        assertEquals("Heart rate: average 118 · max 156 bpm", HeartRateLines.session(received(fromWatch)))
        assertEquals("Heart rate: max 156 bpm", HeartRateLines.session(null, 156))
        assertEquals("Heart rate: average 118 bpm", HeartRateLines.session(118, null))
        assertNull(HeartRateLines.session(null, null))
    }

    @Test
    fun `nothing in these lines tells a lifter what to do or grades a number`() {
        val lines = listOfNotNull(
            HeartRateLines.set(142), HeartRateLines.set(61), HeartRateLines.set(199),
            HeartRateLines.session(118, 156), HeartRateLines.session(null, 156), HeartRateLines.session(118, null),
        ).joinToString(" · ").lowercase(Locale.US)
        listOf(
            "zone", "recover", "ready", "rest", "%", "percent", "of max", "target", "goal", "should",
            "too ", "high", "low", "warning", "good", "great", "poor", "easy", "hard", "effort",
            "intensity", "fat burn", "cardio", "peak", "resting", "calorie", "trend", "!",
        ).forEach { word -> assertFalse("\"$word\" reached a screen: $lines", lines.contains(word)) }
    }
}
