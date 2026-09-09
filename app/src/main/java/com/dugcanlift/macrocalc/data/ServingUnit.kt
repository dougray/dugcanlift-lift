package com.dugcanlift.macrocalc.data

/** Mirrors LIFT iOS's ServingUnit. Amounts are stored canonically in
 * grams; this handles display/entry conversion only. */
enum class ServingUnit {
    GRAMS, OUNCES;

    companion object {
        private const val GRAMS_PER_OUNCE = 28.3495
    }

    val abbreviation: String get() = if (this == GRAMS) "g" else "oz"

    fun fromGrams(grams: Double): Double =
        if (this == GRAMS) grams else grams / GRAMS_PER_OUNCE

    fun toGrams(value: Double): Double =
        if (this == GRAMS) value else value * GRAMS_PER_OUNCE
}
