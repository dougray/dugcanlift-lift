package com.dugcanlift.macrocalc.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dugcanlift.kit.ShareLinkCodec
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SHARE-FORMAT "Saturated fat, sugar and sodium": `fx` on a day, `fe` beside an
 * itemised `f`, and neither when no food recorded any of the three.
 */
@RunWith(RobolectricTestRunner::class)
class CoachShareNutrientTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // Before and after: a random lifter id left behind changes the link
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

    private val nowMs = 1_789_345_600_000L
    private val today = dateKey(nowMs)

    private fun store(itemised: Boolean) = CoachStore.get(context).apply {
        restoreLifterId("nutrients1")
        lifterName = "Nutrient Fixture"
        weeks = 4
        itemisedFood = itemised
    }

    private fun food(name: String, servings: Double = 1.0, satFat: Double? = null, sugar: Double? = null, sodium: Double? = null) =
        FoodEntry(name = name, servings = servings, calories = 100, proteinG = 5, fatG = 3, carbsG = 12,
            date = today, loggedAt = nowMs, meal = "LUNCH", saturatedFatG = satFat, sugarG = sugar, sodiumMg = sodium)

    private fun lastDay(itemised: Boolean, entries: List<FoodEntry>): JSONObject {
        val json = ShareLinkCodec.buildJson(CoachShare.buildSharePayload(
            store(itemised), SettingsStore.get(context), null, emptyList(), entries, emptyMap(), emptyList(), nowMs))
        val days = json.getJSONArray("d")
        return days.getJSONObject(days.length() - 1)
    }

    private val day = listOf(
        food("Soup", servings = 2.0, satFat = 1.25, sodium = 700.0),
        food("Bread", sugar = 3.0, sodium = 440.4),
        food("Apple"),
    )

    @Test
    fun `an itemised day sends fe per food in order and fx over the recorded ones`() {
        val d = lastDay(itemised = true, entries = day)
        assertEquals(3, d.getJSONArray("f").length())

        val fe = d.getJSONArray("fe")
        assertEquals(3, fe.length())
        assertEquals("[1.3,null,700]", fe.getJSONArray(0).toString().replace(".0", ""))
        assertEquals("[null,3,440]", fe.getJSONArray(1).toString().replace(".0", ""))
        assertTrue("a food with none is null", fe.isNull(2))

        // Soup's 1.25 g x 2 servings, summed unrounded then rounded once.
        assertEquals("[2.5,3,1840,3,1,1,2]", d.getJSONArray("fx").toString().replace(".0", ""))
    }

    @Test
    fun `a totals-only day still sends fx`() {
        val d = lastDay(itemised = false, entries = day)
        assertFalse(d.has("f"))
        assertFalse(d.has("fe"))
        assertEquals("[2.5,3,1840,3,1,1,2]", d.getJSONArray("fx").toString().replace(".0", ""))
    }

    @Test
    fun `a day where no food recorded any sends neither`() {
        val entries = listOf(food("Apple"), food("Rice"))
        listOf(true, false).forEach { itemised ->
            val d = lastDay(itemised, entries)
            assertFalse(d.has("fx"))
            assertFalse(d.has("fe"))
        }
    }
}
