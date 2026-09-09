package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodEntryTest {

    @Test
    fun `amountGrams round trips through JSON when present`() {
        val entry = FoodEntry(
            name = "Chicken breast",
            servings = 1.0,
            amountGrams = 150.0,
            calories = 248,
            proteinG = 46,
            fatG = 5,
            carbsG = 0
        )
        val restored = foodEntryFromJson(entry.toJson())
        assertEquals(150.0, restored.amountGrams!!, 0.0001)
    }

    @Test
    fun `amountGrams is null after round trip when never set`() {
        val entry = FoodEntry(
            name = "Legacy entry",
            servings = 2.0,
            calories = 100,
            proteinG = 10,
            fatG = 2,
            carbsG = 5
        )
        assertTrue(!entry.toJson().has("amountGrams"))
        val restored = foodEntryFromJson(entry.toJson())
        assertNull(restored.amountGrams)
    }

    @Test
    fun `fromJson gives null, not zero, when amountGrams key is absent from an older file`() {
        val json = FoodEntry(name = "Old row", calories = 50, proteinG = 1, fatG = 1, carbsG = 1).toJson()
        json.remove("amountGrams")
        val restored = foodEntryFromJson(json)
        assertNull(restored.amountGrams)
    }

    @Test
    fun `gram-based entry with servings pinned to 1 reports totals unchanged`() {
        // Design decision: a gram-based FoodEntry stores calories/etc as the
        // already-scaled totals for the entered gram amount, with servings
        // pinned to 1.0 — so the existing totalXxx computed properties need
        // no changes at all to do the right thing here.
        val entry = FoodEntry(
            name = "Rice, 200g",
            servings = 1.0,
            amountGrams = 200.0,
            calories = 260,
            proteinG = 5,
            fatG = 1,
            carbsG = 56,
            fiberG = 2
        )
        assertEquals(260, entry.totalCalories)
        assertEquals(5, entry.totalProteinG)
        assertEquals(1, entry.totalFatG)
        assertEquals(56, entry.totalCarbsG)
        assertEquals(2, entry.totalFiberG)
    }
}
