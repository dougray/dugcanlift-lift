package com.dugcanlift.macrocalc.data

import com.dugcanlift.kit.ShareNutrients
import kotlin.math.roundToInt

/**
 * What the edit form holds while a logged entry is being changed, and the
 * arithmetic that turns it back into a [FoodEntry]. A value type with no view in
 * it, so the rules that decide what a day's total becomes can be tested.
 *
 * An entry is edited in the shape it was logged in, because the two shapes mean
 * different things by the same numbers (see [FoodEntry.amountGrams]):
 *
 * - **By weight** (`amountGrams` set): servings is 1 and the macros are already
 *   the totals for that weight. The form shows those totals. Changing the weight
 *   rescales them in proportion, from the weight and macros they were last
 *   *typed* for, and rounds once -- so nudging the weight back and forth never
 *   drifts a number, and a macro typed by hand is taken as the total for the
 *   weight shown beside it.
 * - **By servings** (legacy, `amountGrams` null): the macros are per serving and
 *   the servings count multiplies them, as [FoodEntry.totalCalories] does.
 *   Changing servings leaves the per-serving numbers alone.
 *
 * There is no switching between the two. A serving has no weight, and inventing
 * one to convert would put a number in someone's log that nobody measured.
 *
 * Blank stays blank: a blank macro field is never rescaled into a number, and a
 * blank or unreadable amount or calories refuses the save rather than writing a
 * zero nobody entered. FoodEntry's protein/fat/carbs/fibre are not nullable, so
 * a blank one saves as 0, the same as the add form does. Saturated fat, sugar
 * and sodium are nullable, and blank saves as null -- not recorded -- because
 * a zero there would count as a food with no sodium in a day's total. They
 * rescale with the weight like the macros, rounded to one decimal of a gram
 * and to a whole milligram.
 */
data class FoodEntryEdit(
    val name: String,
    val meal: Meal,
    val byWeight: Boolean,
    /** Weight in [unit] when [byWeight], otherwise a servings count. */
    val amount: String,
    val unit: ServingUnit,
    val macros: MacroText,
    /** The grams [basisMacros] were last typed for. Null when unknown. */
    val basisGrams: Double?,
    val basisMacros: MacroText,
    /**
     * The exact weight, while the amount field only shows it rounded -- on
     * opening, and after a unit switch. Without it, opening a 150 g entry in
     * ounces and saving untouched would store 5.3 oz, which is 150.25 g.
     * Cleared the moment the amount is typed.
     */
    val exactGrams: Double? = null,
) {
    data class MacroText(
        val calories: String,
        val proteinG: String,
        val fatG: String,
        val carbsG: String,
        val fiberG: String,
        val saturatedFatG: String = "",
        val sugarG: String = "",
        val sodiumMg: String = "",
    ) {
        /** Every field scaled from one weight to another. Blank stays blank. */
        fun rescaled(fromGrams: Double, toGrams: Double) = MacroText(
            rescale(calories, fromGrams, toGrams), rescale(proteinG, fromGrams, toGrams),
            rescale(fatG, fromGrams, toGrams), rescale(carbsG, fromGrams, toGrams),
            rescale(fiberG, fromGrams, toGrams),
            rescaleDetail(saturatedFatG, fromGrams, toGrams, milligrams = false),
            rescaleDetail(sugarG, fromGrams, toGrams, milligrams = false),
            rescaleDetail(sodiumMg, fromGrams, toGrams, milligrams = true),
        )
    }

    enum class Field { CALORIES, PROTEIN, FAT, CARBS, FIBER, SATURATED_FAT, SUGAR, SODIUM }

    /** The weight the amount field currently reads as, in grams. */
    val grams: Double?
        get() = if (!byWeight) null
            else exactGrams ?: amount.toDoubleOrNull()?.let(unit::toGrams)?.takeIf(::positive)

    val servings: Double?
        get() = if (byWeight) null else amount.toDoubleOrNull()?.takeIf(::positive)

    fun withName(value: String) = copy(name = value)

    fun withMeal(value: Meal) = copy(meal = value)

    /** A new amount. By weight, the macros follow it from their basis. */
    fun withAmount(text: String): FoodEntryEdit {
        val next = copy(amount = text, exactGrams = null)
        if (!byWeight) return next
        val newGrams = next.grams ?: return next
        val base = basisGrams
            // Macros typed while the amount was blank belong to this weight.
            ?: return next.copy(basisGrams = newGrams, basisMacros = macros)
        return next.copy(macros = basisMacros.rescaled(base, newGrams))
    }

    /** Switches the weight unit. The same weight, so nothing rescales. */
    fun withUnit(newUnit: ServingUnit): FoodEntryEdit {
        if (!byWeight || newUnit == unit) return copy(unit = newUnit)
        val g = grams
        return if (g == null) copy(unit = newUnit)
            else copy(unit = newUnit, amount = trimOneDecimal(newUnit.fromGrams(g)), exactGrams = g)
    }

    /** A macro typed by hand. It becomes the basis for any later weight change. */
    fun withMacro(field: Field, text: String): FoodEntryEdit {
        val next = when (field) {
            Field.CALORIES -> macros.copy(calories = text)
            Field.PROTEIN -> macros.copy(proteinG = text)
            Field.FAT -> macros.copy(fatG = text)
            Field.CARBS -> macros.copy(carbsG = text)
            Field.FIBER -> macros.copy(fiberG = text)
            Field.SATURATED_FAT -> macros.copy(saturatedFatG = text)
            Field.SUGAR -> macros.copy(sugarG = text)
            Field.SODIUM -> macros.copy(sodiumMg = text)
        }
        return copy(macros = next, basisMacros = next, basisGrams = grams)
    }

    /**
     * The edited entry, keeping the original's id, date and logged time so it
     * stays the same record in the same place. Null when it cannot be saved.
     */
    fun applyTo(original: FoodEntry): FoodEntry? {
        if (name.isBlank()) return null
        val calories = whole(macros.calories) ?: return null
        val common = original.copy(
            name = name.trim(),
            meal = meal.name,
            calories = calories,
            proteinG = whole(macros.proteinG) ?: 0,
            fatG = whole(macros.fatG) ?: 0,
            carbsG = whole(macros.carbsG) ?: 0,
            fiberG = whole(macros.fiberG) ?: 0,
            saturatedFatG = NutrientDetailsText.parse(macros.saturatedFatG),
            sugarG = NutrientDetailsText.parse(macros.sugarG),
            sodiumMg = NutrientDetailsText.parse(macros.sodiumMg),
        )
        return if (byWeight) {
            common.copy(servings = 1.0, amountGrams = grams ?: return null)
        } else {
            common.copy(servings = servings ?: return null, amountGrams = null)
        }
    }

    companion object {
        fun from(entry: FoodEntry, unit: ServingUnit): FoodEntryEdit {
            val macros = MacroText(
                entry.calories.toString(), entry.proteinG.toString(), entry.fatG.toString(),
                entry.carbsG.toString(), entry.fiberG.toString(),
                NutrientDetailsText.field(entry.saturatedFatG), NutrientDetailsText.field(entry.sugarG),
                NutrientDetailsText.field(entry.sodiumMg),
            )
            val grams = entry.amountGrams
            return FoodEntryEdit(
                name = entry.name,
                meal = entry.mealOrDefault,
                byWeight = grams != null,
                amount = if (grams != null) trimOneDecimal(unit.fromGrams(grams))
                    else trimOneDecimal(entry.servings, places = 2),
                unit = unit,
                macros = macros,
                basisGrams = grams,
                basisMacros = macros,
                exactGrams = grams,
            )
        }

        /** Scales a macro from one weight to another, rounding once. Blank stays blank. */
        internal fun rescale(text: String, fromGrams: Double, toGrams: Double): String {
            if (text.isBlank()) return text
            val value = text.toDoubleOrNull() ?: return text
            if (!positive(fromGrams) || !positive(toGrams)) return text
            return (value * toGrams / fromGrams).roundToInt().toString()
        }

        /**
         * Saturated fat, sugar or sodium scaled from one weight to another:
         * grams to one decimal, milligrams whole, rounding once from the basis.
         * Blank stays blank.
         */
        internal fun rescaleDetail(text: String, fromGrams: Double, toGrams: Double, milligrams: Boolean): String {
            if (text.isBlank()) return text
            val value = text.toDoubleOrNull() ?: return text
            if (!positive(fromGrams) || !positive(toGrams)) return text
            val scaled = value * toGrams / fromGrams
            return NutrientDetailsText.field(
                if (milligrams) ShareNutrients.roundMilligrams(scaled) else ShareNutrients.roundGrams(scaled)
            )
        }

        private fun whole(text: String): Int? =
            text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }?.roundToInt()

        private fun positive(value: Double) = value.isFinite() && value > 0.0

        private fun trimOneDecimal(value: Double, places: Int = 1): String {
            val factor = if (places == 2) 100.0 else 10.0
            val rounded = (value * factor).roundToInt() / factor
            return if (rounded == rounded.roundToInt().toDouble()) rounded.roundToInt().toString()
            else rounded.toString()
        }
    }
}
