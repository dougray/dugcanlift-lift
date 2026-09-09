package com.dugcanlift.macrocalc.data

import com.dugcanlift.macrocalc.formatAmount
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
        val entry = chickenBreast.toFoodEntry("2026-09-09", grams, nutrition, "250 g")

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
    fun `toFoodEntry name includes the given amount label so history reads unambiguously`() {
        val entry = chickenBreast.toFoodEntry(
            "2026-09-09", 250.0, chickenBreast.nutrition(250.0), "250 g"
        )
        assertEquals("Chicken breast, 250 g", entry.name)
    }

    @Test
    fun `toFoodEntry name suffix is unit-aware, not hardcoded to grams`() {
        // Regression guard for the bug this task fixes: previously the name
        // suffix was always "<grams> g" regardless of the person's
        // ServingUnit preference, so an ounces user saw grams on the name
        // line but ounces on the amount/macro line below it. The caller now
        // formats the suffix with formatAmount against the actual unit, so
        // an ounces preference must show ounces in the name too.
        val grams = 250.0
        val label = formatAmount(grams, ServingUnit.OUNCES)
        val entry = chickenBreast.toFoodEntry(
            "2026-09-09", grams, chickenBreast.nutrition(grams), label
        )
        assertEquals("Chicken breast, 8.8 oz", entry.name)
    }

    @Test
    fun `toFoodEntry name suffix matches formatAmount's rounding exactly, in grams or ounces`() {
        // Regression guard: 8.8185 oz * 28.3495 g/oz is 250.00006575g, not an
        // exact 250.0, due to floating point — the label must still read
        // "250 g" rather than leaking that imprecision to the user, and must
        // read identically to what formatAmount would print on the amount
        // line for the same grams + unit.
        val grams = ServingUnit.OUNCES.toGrams(8.8185)

        val gramsLabel = formatAmount(grams, ServingUnit.GRAMS)
        val gramsEntry = chickenBreast.toFoodEntry(
            "2026-09-09", grams, chickenBreast.nutrition(grams), gramsLabel
        )
        assertEquals("Chicken breast, 250 g", gramsEntry.name)
        assertEquals(formatAmount(grams, ServingUnit.GRAMS), gramsLabel)
        // amountGrams itself keeps full precision, unrounded.
        assertEquals(grams, gramsEntry.amountGrams!!, 0.0)

        val ouncesLabel = formatAmount(grams, ServingUnit.OUNCES)
        val ouncesEntry = chickenBreast.toFoodEntry(
            "2026-09-09", grams, chickenBreast.nutrition(grams), ouncesLabel
        )
        assertEquals("Chicken breast, 8.8 oz", ouncesEntry.name)
        assertEquals(formatAmount(grams, ServingUnit.OUNCES), ouncesLabel)
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
