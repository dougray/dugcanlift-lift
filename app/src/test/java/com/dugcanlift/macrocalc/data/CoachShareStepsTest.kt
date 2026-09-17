package com.dugcanlift.macrocalc.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dugcanlift.kit.ShareDecodeResult
import com.dugcanlift.kit.ShareLinkCodec
import com.dugcanlift.macrocalc.MacroResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Steps come from Health Connect, and Health Connect data may reach a third
 * party only with explicit consent. So Send to Coach carries them only when
 * [CoachStore.sendSteps] is on -- off by default -- and says so before the
 * person taps Send ([CoachShare.includedSummary]).
 */
@RunWith(RobolectricTestRunner::class)
class CoachShareStepsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // Before and after: a stores' cached opt-in left behind would change the
    // link CoachShareCaptureTest checks when it runs next in the same JVM.
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
    private val steps = mapOf(today to 9_321L)

    private fun store(sendSteps: Boolean? = null) = CoachStore.get(context).apply {
        restoreLifterId("steps001")
        lifterName = "Steps Fixture"
        weeks = 4
        if (sendSteps != null) this.sendSteps = sendSteps
    }

    private fun sentSteps(store: CoachStore): List<Long> =
        CoachShare.buildSharePayload(
            store, SettingsStore.get(context), null, emptyList(), emptyList(), steps, emptyList(), nowMs
        ).days.mapNotNull { it.steps }

    @Test
    fun `steps are off until the person turns them on`() {
        val fresh = store()
        assertFalse(fresh.sendSteps)
        assertEquals(emptyList<Long>(), sentSteps(fresh))
    }

    @Test
    fun `steps passed in are dropped from the link while sending steps is off`() {
        assertEquals(emptyList<Long>(), sentSteps(store(sendSteps = false)))
    }

    @Test
    fun `steps go in the link once sending steps is on`() {
        assertEquals(listOf(9_321L), sentSteps(store(sendSteps = true)))
    }

    @Test
    fun `the whole link, decoded, carries steps only with the opt-in`() {
        fun decodedSteps(store: CoachStore): List<Long> {
            val link = CoachShare.buildLink(store, SettingsStore.get(context), null, emptyList(), emptyList(),
                mapOf(todayKey() to 4_000L))
            val result = ShareLinkCodec.decode(link.substringAfter('#'))
            assertTrue(result is ShareDecodeResult.Success)
            return (result as ShareDecodeResult.Success).payload.days.mapNotNull { it.steps }
        }
        assertEquals(emptyList<Long>(), decodedSteps(store(sendSteps = false)))
        assertEquals(listOf(4_000L), decodedSteps(store(sendSteps = true)))
    }

    @Test
    fun `the email's readable summary leaves steps out while sending steps is off`() {
        val todaySteps = mapOf(todayKey() to 8_000L)
        assertFalse(CoachShare.weekSummary(null, emptyList(), emptyList(), store(sendSteps = false), todaySteps)
            .contains("Steps"))
        assertTrue(CoachShare.weekSummary(null, emptyList(), emptyList(), store(sendSteps = true), todaySteps)
            .contains("Steps"))
    }

    /* ---------- what the card says before Send ---------- */

    @Test
    fun `summary for a new user names only what always goes`() {
        val s = CoachStore.get(context)
        assertEquals(
            "The email includes every workout set; daily food totals; and your runs, walks and hikes.",
            CoachShare.includedSummary(s, goal = null, stepsAvailable = true)
        )
    }

    @Test
    fun `summary names name, profile, bodyweight, goal, itemised food, route and steps when each goes`() {
        val s = store(sendSteps = true).apply {
            itemisedFood = true
            sendLastRoute = true
            profile = LifterProfile(sex = "male", age = 41, heightIn = 70.0)
            recordBodyweight(190.0, today)
        }
        val goal = MacroResult(calories = 2400, proteinG = 180, fatG = 70, carbsG = 220, fiberG = 35)
        assertEquals(
            "The email includes your name; sex, age and height; bodyweight; your calorie and macro goal; " +
                "every workout set; every food you logged; your runs, walks and hikes; " +
                "the trimmed map of your last route; and daily steps from Health Connect.",
            CoachShare.includedSummary(s, goal, stepsAvailable = true)
        )
    }

    @Test
    fun `summary names only the profile fields that are filled in`() {
        val s = store().apply { profile = LifterProfile(sex = "", age = 30, heightIn = 0.0) }
        val text = CoachShare.includedSummary(s, goal = null, stepsAvailable = false)
        assertTrue(text, text.contains("; age; "))
        assertFalse(text, text.contains("sex"))
        assertFalse(text, text.contains("height"))
    }

    @Test
    fun `summary does not promise steps that are off or cannot be read`() {
        assertFalse(CoachShare.includedSummary(store(sendSteps = false), null, stepsAvailable = true).contains("steps"))
        assertFalse(CoachShare.includedSummary(store(sendSteps = true), null, stepsAvailable = false).contains("steps"))
        assertTrue(CoachShare.includedSummary(store(sendSteps = true), null, stepsAvailable = true)
            .contains("daily steps from Health Connect"))
    }
}
