package com.dugcanlift.macrocalc.data

import com.dugcanlift.kit.NutrientDetails
import com.dugcanlift.kit.ShareNutrients
import java.util.Locale

/**
 * Saturated fat, sugar and sodium as text: the compact line under a logged
 * food, the rows under a day's totals, and reading them back out of a form.
 *
 * No bars and no goals, anywhere. These are tracked, not targeted, so there is
 * nothing to measure them against -- only what was eaten, and how much of the
 * day that number actually covers.
 *
 * Rounding is the kit's ([ShareNutrients.roundGrams], [ShareNutrients.roundMilligrams]):
 * grams to one decimal, sodium to whole milligrams, the same figures a coach's
 * link carries.
 */
object NutrientDetailsText {

    data class Row(val label: String, val value: String)

    /** Grams to one decimal, without a trailing ".0". */
    fun grams(value: Double): String {
        val rounded = ShareNutrients.roundGrams(value)
        return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
    }

    /** Whole milligrams, grouped as the phone's locale groups them: "1,840". */
    fun milligrams(value: Double, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%,d", ShareNutrients.roundMilligrams(value).toLong())

    /**
     * "Sat fat 3.1 g - Sugar 12 g - Sodium 540 mg", naming only what is known,
     * multiplied by [factor] (an entry's servings). Null when none is known, so
     * a food typed without them gets no line at all rather than a row of dashes.
     */
    fun line(details: NutrientDetails?, factor: Double = 1.0, locale: Locale = Locale.getDefault()): String? {
        if (details == null || details.isEmpty) return null
        val parts = listOfNotNull(
            details.saturatedFatG?.let { "Sat fat ${grams(it * factor)} g" },
            details.sugarG?.let { "Sugar ${grams(it * factor)} g" },
            details.sodiumMg?.let { "Sodium ${milligrams(it * factor, locale)} mg" },
        )
        return parts.joinToString(" - ")
    }

    /** What this entry counted as, on the same basis as its macro line. */
    fun entryLine(entry: FoodEntry, locale: Locale = Locale.getDefault()): String? =
        line(entry.details, entry.servings, locale)

    /**
     * A day's totals, one row per nutrient any food recorded. When not every
     * food recorded it, the row says so -- "1,840 mg · from 3 of 5 foods" --
     * because a total over some of the day is a floor, not the day. Empty when
     * no food recorded any of the three.
     */
    fun dayRows(entries: List<FoodEntry>, locale: Locale = Locale.getDefault()): List<Row> {
        val totals = entries.nutrientTotals() ?: return emptyList()
        fun coverage(with: Int) =
            if (with < totals.foods) " · from $with of ${totals.foods} foods" else ""
        return listOfNotNull(
            totals.saturatedFatG?.let { Row("Saturated fat", "${grams(it)} g" + coverage(totals.withSaturatedFat)) },
            totals.sugarG?.let { Row("Sugar", "${grams(it)} g" + coverage(totals.withSugar)) },
            totals.sodiumMg?.let { Row("Sodium", "${milligrams(it, locale)} mg" + coverage(totals.withSodium)) },
        )
    }

    /**
     * A form field read back. Blank stays blank: blank, unreadable, negative or
     * non-finite is null -- not recorded -- and never zero.
     */
    fun parse(text: String): Double? =
        text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

    /** A stored value put back in a form field; blank when not recorded. */
    fun field(value: Double?): String = when {
        value == null -> ""
        value == Math.floor(value) && !value.isInfinite() -> value.toLong().toString()
        else -> value.toString()
    }

    /** [details] multiplied by [factor] and rounded once: grams to one decimal, sodium whole. */
    fun scaled(details: NutrientDetails, factor: Double): NutrientDetails = NutrientDetails(
        details.saturatedFatG?.let { ShareNutrients.roundGrams(it * factor) },
        details.sugarG?.let { ShareNutrients.roundGrams(it * factor) },
        details.sodiumMg?.let { ShareNutrients.roundMilligrams(it * factor) },
    )
}
