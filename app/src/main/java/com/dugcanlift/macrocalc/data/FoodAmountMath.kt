package com.dugcanlift.macrocalc.data

import com.dugcanlift.kit.NutrientDetails
import kotlin.math.roundToInt

/**
 * Totals for an amount of a food whose macros are given per 100 g.
 *
 * The one arithmetic every version of LIFT has to agree on, because it decides
 * what a logged meal actually counted as. Mirrors `scaleFrom100g` in the
 * browser build's `food-amount.js` and `FoodAmountMath` in LIFT iOS, and the
 * tests here use the same worked examples all three do.
 *
 * Rounded once, at the end, the way a label reads. Rounding each field's
 * per-gram value first and multiplying after drifts by a few grams an entry,
 * which is a few hundred calories across a week.
 */
data class Per100g(
    val calories: Int,
    val proteinG: Int,
    val fatG: Int,
    val carbsG: Int,
    val fiberG: Int,
    /** Saturated fat, sugar and sodium per 100 g; each null when not known. */
    val details: NutrientDetails = NutrientDetails(),
)

data class ScaledMacros(
    val calories: Int,
    val proteinG: Int,
    val fatG: Int,
    val carbsG: Int,
    val fiberG: Int,
    /** Scaled like the macros, rounded once: grams to one decimal, sodium whole. Unknown stays null. */
    val details: NutrientDetails = NutrientDetails(),
)

/**
 * Null for an amount that is not a positive, finite weight — the caller
 * refuses to save rather than writing zeroes. A zero nobody entered is worse
 * than no entry at all.
 */
fun scaleFrom100g(per100: Per100g, grams: Double): ScaledMacros? {
    if (!grams.isFinite() || grams <= 0.0) return null
    val factor = grams / 100.0
    return ScaledMacros(
        calories = (per100.calories * factor).roundToInt(),
        proteinG = (per100.proteinG * factor).roundToInt(),
        fatG = (per100.fatG * factor).roundToInt(),
        carbsG = (per100.carbsG * factor).roundToInt(),
        fiberG = (per100.fiberG * factor).roundToInt(),
        details = NutrientDetailsText.scaled(per100.details, factor),
    )
}
