package com.dugcanlift.macrocalc.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dugcanlift.kit.CompactEncoding
import com.dugcanlift.kit.ShareDecodeResult
import com.dugcanlift.kit.ShareLinkCodec
import com.dugcanlift.kit.ShareSide
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
 * SHARE-FORMAT: the side rides in the set tuple's `flags`, bits 1-2, beside bit
 * 0's warmup flag. No new positions, so a coach app that knows nothing about
 * sides still reads the weight, the reps and the volume — the correct way for
 * this to degrade.
 */
@RunWith(RobolectricTestRunner::class)
class CoachShareSideTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun resetSingletons() {
        listOf(CoachStore::class.java, SettingsStore::class.java).forEach {
            val field = it.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        }
    }

    private val nowMs = 1_789_345_600_000L
    private val today = dateKey(nowMs)

    private fun store() = CoachStore.get(context).apply {
        restoreLifterId("sides001")
        lifterName = "Doug"
        weeks = 4
    }

    private fun session(vararg sets: WorkoutSet) = WorkoutSession(
        date = today, name = "Legs", startedAt = nowMs,
        exercises = listOf(LoggedExercise(
            name = "Bulgarian Split Squat", equipment = "Dumbbell", sets = sets.toList()))
    )

    private fun payloadFor(session: WorkoutSession) = CoachShare.buildSharePayload(
        store = store(), settings = SettingsStore.get(context), goal = null,
        sessions = listOf(session), entries = emptyList(), steps = emptyMap(), nowMs = nowMs
    )

    private fun setTuples(session: WorkoutSession) = ShareLinkCodec.buildJson(payloadFor(session))
        .getJSONArray("d").let { days ->
            (0 until days.length()).map { days.getJSONObject(it) }
                .first { it.has("w") }
        }
        .getJSONArray("w").getJSONArray(0).getJSONArray(1)

    @Test
    fun `both left and right survive the link`() {
        val session = session(
            WorkoutSet(weightLb = 60.0, reps = 8, side = SetSide.LEFT),
            WorkoutSet(weightLb = 55.0, reps = 8, side = SetSide.RIGHT),
            WorkoutSet(weightLb = 95.0, reps = 10)
        )
        val fragment = ShareLinkCodec.encodeFragment(payloadFor(session))
        val decoded = (ShareLinkCodec.decode(fragment) as ShareDecodeResult.Success).payload
        val sets = decoded.days.first { it.exercises.isNotEmpty() }.exercises[0].sets
        assertEquals(ShareSide.LEFT, sets[0].side)
        assertEquals(ShareSide.RIGHT, sets[1].side)
        assertNull("both is the absence of a side, all the way to the wire", sets[2].side)
    }

    @Test
    fun `the side is flags bits 1 and 2, not a seventh field`() {
        val tuples = setTuples(session(
            WorkoutSet(weightLb = 60.0, reps = 8, side = SetSide.LEFT),
            WorkoutSet(weightLb = 55.0, reps = 8, side = SetSide.RIGHT)
        ))
        assertEquals("[60,8,null,null,null,2]", tuples.getJSONArray(0).toString())
        assertEquals("[55,8,null,null,null,4]", tuples.getJSONArray(1).toString())
        assertEquals(6, tuples.getJSONArray(0).length())
    }

    @Test
    fun `a log with no per-side sets writes the link it always did`() {
        val tuples = setTuples(session(
            WorkoutSet(weightLb = 95.0, reps = 10),
            WorkoutSet(weightLb = 105.0, reps = 8, rpe = 8.5)
        ))
        // 0 flags is trimmed away entirely, exactly as before sides existed.
        assertEquals("[95,10]", tuples.getJSONArray(0).toString())
        assertEquals("[105,8,8.5]", tuples.getJSONArray(1).toString())
    }

    @Test
    fun `flags do not corrupt the numbers a reader that ignores them sees`() {
        val tuples = setTuples(session(
            WorkoutSet(weightLb = 60.0, reps = 8, side = SetSide.LEFT),
            WorkoutSet(weightLb = 55.0, reps = 6, side = SetSide.RIGHT),
            WorkoutSet(weightLb = 95.0, reps = 10)
        ))
        // The five positions such a reader already knew, and the volume it adds up.
        val volume = (0 until tuples.length()).sumOf {
            tuples.getJSONArray(it).getDouble(0) * tuples.getJSONArray(it).getInt(1)
        }
        assertEquals(60.0 * 8 + 55.0 * 6 + 95.0 * 10, volume, 1e-9)
        // And the one bit it does read: none of these is a warmup.
        assertEquals(0, tuples.getJSONArray(0).getInt(5) and 1)
        assertEquals(0, tuples.getJSONArray(1).getInt(5) and 1)
    }

    @Test
    fun `a link written before sides existed decodes as both`() {
        val json = JSONObject(
            """{"v":1,"c":{"i":"old","n":"Older Build"},"r":"2026-09-01","t":"2026-09-01","z":1,""" +
                """"x":["Bulgarian Split Squat|Dumbbell"],"d":[{"k":0,"w":[[0,[[60,8],[55,8,null,null,null,1]]]]}]}"""
        )
        val fragment = "1u" + CompactEncoding.base64Url(json.toString().toByteArray())
        val decoded = (ShareLinkCodec.decode(fragment) as ShareDecodeResult.Success).payload
        val sets = decoded.days[0].exercises[0].sets
        assertNull(sets[0].side)
        assertTrue("bit 0 still means warmup and nothing else", sets[1].isWarmup)
        assertNull(sets[1].side)
        assertFalse(sets[0].isWarmup)
    }
}
