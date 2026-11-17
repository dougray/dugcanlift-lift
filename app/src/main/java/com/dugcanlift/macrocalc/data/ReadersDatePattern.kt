package com.dugcanlift.macrocalc.data

import java.util.Locale

// The one Android framework call behind [VO2MaxText]'s date, kept out of that pure file so it
// compiles and tests on the JVM with a plain pattern handed in.

/** "MMM d" the way the reader's locale orders it ("Nov 8", "8 Nov"). */
internal fun readersMonthDayPattern(locale: Locale): String =
    android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
