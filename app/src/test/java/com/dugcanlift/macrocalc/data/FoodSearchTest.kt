package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodSearchTest {

    private val chickenBreast = FoodSearchResult(
        code = "0000000000001",
        name = "Chicken breast",
        brand = "",
        servingSize = "1 breast (172g)",
        // Real-ish per-100g figures for skinless, boneless chicken breast.
        per100g = Nutriments(calories = 165, proteinG = 31, fatG = 4, carbsG = 0, fiberG = 0),
        perServing = Nutriments(calories = 284, proteinG = 53, fatG = 6, carbsG = 0, fiberG = 0)
    )

    @Test
    fun `nutrition scales from per100g at 100 grams unchanged`() {
        val result = chickenBreast.nutrition(100.0)
        assertEquals(165, result.calories)
        assertEquals(31, result.proteinG)
        assertEquals(4, result.fatG)
        assertEquals(0, result.carbsG)
        assertEquals(0, result.fiberG)
    }

    @Test
    fun `nutrition scales from per100g for an arbitrary gram amount`() {
        // 250g of chicken breast: factor 2.5 on every per-100g figure.
        val result = chickenBreast.nutrition(250.0)
        assertEquals(413, result.calories) // 165 * 2.5 = 412.5 -> rounds to 413
        assertEquals(78, result.proteinG) // 31 * 2.5 = 77.5 -> rounds to 78 (half-up)
        assertEquals(10, result.fatG) // 4 * 2.5 = 10
        assertEquals(0, result.carbsG)
    }

    @Test
    fun `nutrition never uses perServing even when present`() {
        // perServing here (284 kcal) would NOT equal what 100g should produce
        // (165 kcal). nutrition(100.0) must ignore perServing entirely.
        val result = chickenBreast.nutrition(100.0)
        assertTrue(result.calories != chickenBreast.perServing!!.calories)
        assertEquals(chickenBreast.per100g.calories, result.calories)
    }

    @Test
    fun `nutrition at zero grams is all zero`() {
        val result = chickenBreast.nutrition(0.0)
        assertEquals(0, result.calories)
        assertEquals(0, result.proteinG)
        assertEquals(0, result.fatG)
        assertEquals(0, result.carbsG)
        assertEquals(0, result.fiberG)
    }

    @Test
    fun `toFoodEntry pins servings to 1 and stores amountGrams and the given nutrition totals`() {
        val grams = 250.0
        val nutrition = chickenBreast.nutrition(grams)
        val entry = chickenBreast.toFoodEntry("2026-09-09", grams, nutrition)

        assertEquals(1.0, entry.servings, 0.0001)
        assertEquals(grams, entry.amountGrams!!, 0.0001)
        assertEquals(nutrition.calories, entry.calories)
        assertEquals(nutrition.proteinG, entry.proteinG)
        assertEquals(nutrition.fatG, entry.fatG)
        assertEquals(nutrition.carbsG, entry.carbsG)
        assertEquals(nutrition.fiberG, entry.fiberG)
        assertEquals("2026-09-09", entry.date)
        // The already-scaled totals must survive the servings=1.0 multiplier
        // unchanged, matching the gram-based FoodEntry design decision.
        assertEquals(nutrition.calories, entry.totalCalories)
    }

    @Test
    fun `toFoodEntry name includes the gram amount so history reads unambiguously`() {
        val entry = chickenBreast.toFoodEntry("2026-09-09", 250.0, chickenBreast.nutrition(250.0))
        assertTrue(entry.name.contains("250"))
        assertTrue(entry.name.contains("g"))
        assertTrue(entry.name.contains("Chicken breast"))
    }

    @Test
    fun `toFoodEntry name rounds an imprecise ounce-to-gram conversion to a clean label`() {
        // Regression guard: 8.8185 oz * 28.3495 g/oz is 250.00006575g, not an
        // exact 250.0, due to floating point — the label must still read
        // "250 g" rather than leaking that imprecision to the user.
        val grams = ServingUnit.OUNCES.toGrams(8.8185)
        val entry = chickenBreast.toFoodEntry("2026-09-09", grams, chickenBreast.nutrition(grams))
        assertEquals("Chicken breast, 250 g", entry.name)
        // amountGrams itself keeps full precision, unrounded.
        assertEquals(grams, entry.amountGrams!!, 0.0)
    }

    @Test
    fun `ounce entry converts to grams before nutrition is computed`() {
        // 8.8185 oz is exactly 250g at the app's conversion factor.
        val ounces = 8.8185
        val grams = ServingUnit.OUNCES.toGrams(ounces)
        val fromOunces = chickenBreast.nutrition(grams)
        val fromGrams = chickenBreast.nutrition(250.0)

        assertEquals(fromGrams.calories, fromOunces.calories)
        assertEquals(fromGrams.proteinG, fromOunces.proteinG)
    }

    @Test
    fun `displayName includes brand only when present`() {
        val branded = chickenBreast.copy(brand = "Acme")
        assertEquals("Chicken breast (Acme)", branded.displayName)
        assertEquals("Chicken breast", chickenBreast.displayName)
    }

    @Test
    fun `nutrition ignores servingSize entirely`() {
        // Regression guard for the bug this task fixes: servingSize is a
        // free-text OFF field ("1 breast (172g)") that must never be parsed
        // or relied on for scaling — only per100g is a reliable weight basis.
        val noServingSize = chickenBreast.copy(servingSize = "")
        assertEquals(chickenBreast.nutrition(250.0), noServingSize.nutrition(250.0))
    }

    @Test
    fun `nutrition works when perServing is entirely absent`() {
        val searchOnly = chickenBreast.copy(perServing = null)
        val result = searchOnly.nutrition(250.0)
        assertEquals(413, result.calories)
        assertNull(searchOnly.perServing)
    }
}
