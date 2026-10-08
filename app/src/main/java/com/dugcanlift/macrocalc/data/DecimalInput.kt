package com.dugcanlift.macrocalc.data

import java.text.DecimalFormatSymbols

/**
 * What a number field keeps of what was typed, normalised so every caller's
 * `toDoubleOrNull()` reads it.
 *
 * Many locales put a comma on the number pad's decimal key, and the field used
 * to drop it, so "82,5" became 825. The locale's own separator is now read as
 * the decimal point and stored as '.'; '.' is always accepted too. A comma in a
 * locale that does not use one is still dropped, as before ("1,000" is 1000).
 * Only the first decimal point is kept: a second one would make the number
 * unreadable rather than different.
 */
fun normalizeDecimalInput(
    input: String,
    localeSeparator: Char = DecimalFormatSymbols.getInstance().decimalSeparator
): String {
    val out = StringBuilder(input.length)
    var seenPoint = false
    for (c in input) {
        when {
            c.isDigit() -> out.append(c)
            c == '.' || c == localeSeparator -> if (!seenPoint) {
                out.append('.')
                seenPoint = true
            }
        }
    }
    return out.toString()
}
