package com.dugcanlift.macrocalc.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/**
 * The one line a run shows for VO2 max: `VO2 max 46.2 · Nov 8`.
 *
 * Tracked and shown, never targeted: no zone, color, threshold or advice, and no placeholder when
 * Health Connect has no estimate (the caller shows nothing). No Health Connect type in here, so
 * it is tested with plain numbers.
 */
object VO2MaxText {

    /**
     * `VO2 max 46.2 · Nov 8`, or null for a value that is not a real estimate (zero, negative, not
     * finite): blank is never a zero.
     *
     * One decimal, a trailing `.0` dropped (`46`, `46.2`), rounded half up on the digits the
     * value is written with -- 46.25 is 46.3 -- not on its binary expansion. The date is the
     * reader's own short month and day, in the locale and time zone handed in. The number always
     * uses ".", whatever the locale.
     */
    fun line(
        value: Double,
        dateEpochMs: Long,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault(),
        monthDayPattern: (Locale) -> String = ::readersMonthDayPattern
    ): String? {
        val number = number(value) ?: return null
        val date = DateTimeFormatter.ofPattern(monthDayPattern(locale), locale)
            .format(Instant.ofEpochMilli(dateEpochMs).atZone(timeZone.toZoneId()))
        return "VO2 max $number · $date"
    }

    /**
     * "46", "46.2", or null when the value is no estimate. `Double.toString` is the shortest string
     * that reads back as the same value, which is what a person would have written; Kotlin's
     * `String.format` and `Math.round` round the binary value instead and disagree on ties.
     * The rounded figure must be positive too: 0.04 is written "0", and a line reading "VO2 max 0"
     * is a zero, not an estimate.
     */
    internal fun number(value: Double): String? {
        if (!value.isFinite() || value <= 0.0) return null
        val rounded = BigDecimal(value.toString()).setScale(1, RoundingMode.HALF_UP)
        if (rounded.signum() <= 0) return null
        return rounded.stripTrailingZeros().toPlainString()
    }
}

/** "MMM d" the way the reader's locale orders it ("Nov 8", "8 Nov"). */
internal fun readersMonthDayPattern(locale: Locale): String =
    android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
