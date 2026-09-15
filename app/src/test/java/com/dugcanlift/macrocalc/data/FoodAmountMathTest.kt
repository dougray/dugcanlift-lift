package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The same worked examples the browser build's `food-amount.test.mjs` uses.
 * If these two ever disagree, the same food logged on a phone and in a browser
 * counts as different meals.
 */
class FoodAmountMathTest {

    private val chicken = Per100g(calories = 125, proteinG = 22, fatG = 4, carbsG = 0, fiberG = 0)

    @Test fun `a chicken breast logged by weight`() {
        // Doug's report: Tyson breast, 125 kcal / 22 P per 100 g, 175 g eaten.
        assertEquals(ScaledMacros(219, 39, 7, 0, 0), scaleFrom100g(chicken, 175.0))
    }

    @Test fun `100 g is the macros unchanged`() {
        assertEquals(ScaledMacros(125, 22, 4, 0, 0), scaleFrom100g(chicken, 100.0))
    }

    @Test fun `rounding happens once, at the end`() {
        // Rounding per-gram first and multiplying after gives 21 here.
        assertEquals(21, scaleFrom100g(chicken, 95.0)!!.proteinG)
        assertEquals(16, scaleFrom100g(Per100g(0, 1, 0, 0, 0), 1550.0)!!.proteinG)
    }

    @Test fun `an amount that is not a positive weight is refused, not zeroed`() {
        listOf(0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertNull("grams=$it", scaleFrom100g(chicken, it))
        }
    }

    @Test fun `six ounces of a hundred-calorie food`() {
        // The browser's own worked example, through this app's converter.
        val grams = ServingUnit.OUNCES.toGrams(6.0)
        assertEquals(170.097, grams, 1e-3)
        assertEquals(ScaledMacros(170, 17, 0, 0, 0),
                     scaleFrom100g(Per100g(100, 10, 0, 0, 0), grams))
    }

    @Test fun `ounces convert on the factor every version uses`() {
        assertEquals(28.3495, ServingUnit.OUNCES.toGrams(1.0), 1e-9)
        assertEquals(1.0, ServingUnit.OUNCES.fromGrams(28.3495), 1e-9)
    }
}
