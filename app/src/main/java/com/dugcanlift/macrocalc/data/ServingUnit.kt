package com.dugcanlift.macrocalc.data

/** Mirrors LIFT iOS's ServingUnit. Amounts are stored canonically in
 * grams; this handles display/entry conversion only. */
enum class ServingUnit {
    GRAMS, OUNCES;

    companion object {
        private const val GRAMS_PER_OUNCE = 28.3495
    }

    val abbreviation: String get() = if (this == GRAMS) "g" else "oz"

    /** What the chip says. The abbreviation is for amounts, not for choosing. */
    val label: String get() = if (this == GRAMS) "Grams" else "Ounces"

    fun fromGrams(grams: Double): Double =
        if (this == GRAMS) grams else grams / GRAMS_PER_OUNCE

    fun toGrams(value: Double): Double =
        if (this == GRAMS) value else value * GRAMS_PER_OUNCE
}
