package com.dugcanlift.macrocalc.data

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Prints (and saves) the link today's [CoachShare] encoder produces for the
 * fixed inputs in [CoachShareFixture]. This is not a correctness check on
 * its own — it is the oracle Task 6 diffs the extracted codec's output
 * against, via [CoachShareFixture.canonical].
 */
@RunWith(RobolectricTestRunner::class)
class CoachShareCaptureTest {

    @Test
    fun `print the link for the fixture inputs`() {
        val link = CoachShareFixture.buildToday(ApplicationProvider.getApplicationContext())
        val fragment = link.substringAfter('#')

        println("SHARE_FIXTURE $fragment")
        File("build/share-link-android.txt").apply {
            parentFile?.mkdirs()
        }.writeText(fragment)
    }

    /**
     * Task 6's extraction oracle: the fragment [CoachShare] built for
     * [CoachShareFixture]'s inputs before the codec moved into the kit,
     * captured 2026-09-12 into `share-link-android.txt`. If this fails, the
     * bug is almost always in the extraction's field mapping, not here —
     * never "fix" a failure by editing [CoachShareFixture.canonical], the
     * kit's day-emission rule, or this assertion.
     *
     * `canonical()` already strips `t` (today) and `z` (capture instant)
     * because both vary run to run. `r` (the lookback window's start date) is
     * exactly as calendar-day-dependent — every `daysAgo(n)` in the fixture is
     * relative to "today", so the window slides a day for every day that
     * passes between a capture and a re-run, and `r` slides with it. It is
     * stripped here, alongside `t`/`z`, rather than in the shared
     * `canonical()`, so as not to touch the pre-extraction oracle fixture.
     */
    @Test
    fun `link is unchanged from the pre-extraction capture, modulo calendar drift`() {
        val beforeFixture = "1zhZFPT4NAEMW_Cpnz1Oxu-VduIknTxGhS46EhHGC7rQ0ElIK1Ur67bzFVb5LAvJ15s_PLMNA7RZJJUzTQgSLKZaH0fOsSU41j0vR7yB6yKiBeraXe0sjUQiqh_JkIZ9JHrftJLGZSIfGJq4NwoQLpCZ_pg6KU4lyXztNbn3eXOG8LU1Uw3uVFZZx1c7pMijKmLczDhDCZnSQ_w7hrkIgfk038vLpPVg9L5E5wpoLTVCmPvYx_o-vxPMODvm-PYikEy4AF-3iFLZUUKXfk4dsj4VHSZw_RtrO6ejzrsTy3WpvjsWnP_zFJMC0wToGl7quKp0944_1hUpbJxyDJriVieZ3njxB7-1u0JQS3Xb4MxdQZIOgCBWXPEHPw0c5ujZatMaWzafZ924ElbptT7awP2thNvxx0aWonbk1-7CgbvwA"

        val afterExtraction = CoachShareFixture.buildToday(ApplicationProvider.getApplicationContext())
            .substringAfter('#')

        fun withoutCalendarDrift(fragment: String): String {
            val json = JSONObject(CoachShareFixture.canonical(fragment))
            json.remove("r")
            return json.toString()
        }

        assertEquals(withoutCalendarDrift(beforeFixture), withoutCalendarDrift(afterExtraction))
    }
}
