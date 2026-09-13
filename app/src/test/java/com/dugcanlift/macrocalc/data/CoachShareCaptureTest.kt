package com.dugcanlift.macrocalc.data

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Calendar

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

    /**
     * Second oracle scenario, added when the review of the extraction found
     * that [CoachShareFixture] only ever exercises `itemisedFood = true` and
     * never a set with a non-null `durationSec` — so the non-itemized
     * `foodTotals` ("ft") branch and the duration slot of [setTuple]-style
     * tuples were never checked by any assertion.
     *
     * There is no pre-extraction capture of this exact scenario (it was never
     * captured before the codec moved into the kit), so instead of diffing
     * against a saved fragment this reasons by hand from
     * `git show e4820d0:.../CoachShare.kt`'s `setTuple` and `ft`-branch code
     * to what the OLD encoder would have written, and asserts those literal
     * values against what the NEW code path (this app's [CoachShare] plus the
     * extracted [com.dugcanlift.kit.ShareLinkCodec]) actually produces:
     *
     * - `setTuple` put `set.durationSec` (an `Int`, unchanged by the
     *   extraction) directly into the array; trailing `null`/`0` entries are
     *   trimmed from the end only. A set of (weight=95, reps=10) has
     *   non-null reps as its last non-trimmed slot, so it old-encodes to
     *   `[95,10]`. A set of (durationSec=90) alone trims the trailing
     *   distance-null and the 0 flag but stops at `durationSec`, old-encoding
     *   to `[null,null,null,90]`.
     * - The non-itemized branch put `DayTotals`' `Int` fields straight into
     *   the "ft" array. `DayTotals.calories/proteinG/fatG/carbsG/fiberG` are
     *   sums of `FoodEntry.totalX` (`(x * servings).roundToInt()`), all
     *   `Int`. With two entries at `servings = 1.0` and whole-number macros,
     *   the sums are exact integers: calories 200+300=500, protein
     *   20+10=30, fat 5+10=15, carbs 25+30=55, fiber 3+2=5 — old-encoding to
     *   `[500,30,15,55,5]`.
     *
     * The new path carries these as `Double` (`ShareSet.durationSec` and
     * `ShareDay.foodTotals`), but org.json's `JSONArray.toString()` prints a
     * whole-number `Double` without a trailing `.0` (already relied on by
     * `ShareLinkCodecTest`'s "sets are trimmed" case), so the wire text is
     * expected to be byte-identical to what the `Int`-typed old encoder wrote
     * for these particular, whole-number inputs.
     */
    @Test
    fun `non-itemized foodTotals and a duration-only set match the pre-extraction encoder's numbers`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = CoachStore.get(context)
        store.restoreLifterId("dur-food-fixture")
        store.lifterName = "Duration Fixture"
        store.weeks = 4
        store.itemisedFood = false
        val settings = SettingsStore.get(context)

        val workoutDay = daysAgo(2)
        val foodDay = daysAgo(1)

        val sessions = listOf(
            WorkoutSession(
                id = "fixture-session-duration",
                date = workoutDay,
                name = "Sled Day",
                exercises = listOf(
                    LoggedExercise(
                        id = "fixture-exercise-sled",
                        name = "Sled Push",
                        equipment = "Sled",
                        sets = listOf(
                            // (weight=95, reps=10): old setTuple trims nothing -> [95,10]
                            WorkoutSet(id = "fixture-set-weighted", weightLb = 95.0, reps = 10),
                            // duration only: old setTuple trims distance+flag, stops at duration -> [null,null,null,90]
                            WorkoutSet(id = "fixture-set-duration", durationSec = 90)
                        )
                    )
                )
            )
        )

        val entries = listOf(
            FoodEntry(
                id = "fixture-food-a",
                name = "Oats",
                servings = 1.0,
                calories = 200,
                proteinG = 20,
                fatG = 5,
                carbsG = 25,
                fiberG = 3,
                date = foodDay,
                meal = Meal.BREAKFAST.name
            ),
            FoodEntry(
                id = "fixture-food-b",
                name = "Chicken Breast",
                servings = 1.0,
                calories = 300,
                proteinG = 10,
                fatG = 10,
                carbsG = 30,
                fiberG = 2,
                date = foodDay,
                meal = Meal.LUNCH.name
            )
        )

        val link = CoachShare.buildLink(store, settings, goal = null, sessions = sessions, entries = entries, steps = emptyMap())
        val fragment = link.substringAfter('#')
        val json = JSONObject(CoachShareFixture.canonical(fragment))
        val days = json.getJSONArray("d")

        fun dayWith(key: String): JSONObject =
            (0 until days.length()).map { days.getJSONObject(it) }.first { it.has(key) }

        val wDay = dayWith("w")
        val sets = wDay.getJSONArray("w").getJSONArray(0).getJSONArray(1)
        assertEquals("[95,10]", sets.getJSONArray(0).toString())
        assertEquals("[null,null,null,90]", sets.getJSONArray(1).toString())
        assertFalse("itemized food dict should not appear for a non-itemized payload", wDay.has("f"))

        val fDay = dayWith("ft")
        val ft = fDay.getJSONArray("ft")
        assertEquals("[500,30,15,55,5]", ft.toString())
        assertFalse("non-itemized day should not also carry itemized entries", fDay.has("f"))
        assertTrue("non-itemized payload should not populate the food name dictionary", json.optJSONArray("fd") == null)
    }

    private fun daysAgo(days: Int): String {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -days)
        return dateKey(calendar.timeInMillis)
    }
}
