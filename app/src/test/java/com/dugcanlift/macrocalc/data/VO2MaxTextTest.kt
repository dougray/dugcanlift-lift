package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone

/**
 * The one line a run shows for VO2 max: `VO2 max 46.2 · Nov 8`. Twins of lift-ios's VO2MaxTextTests.
 * Tracked and shown, never targeted -- these are about spelling and rounding, and that a missing
 * estimate is no line at all. Robolectric only so the reader's own month-and-day pattern
 * (`DateFormat.getBestDateTimePattern`) is the real one.
 */
@RunWith(RobolectricTestRunner::class)
class VO2MaxTextTest {

    private val enUS = Locale.US
    private val utc = TimeZone.getTimeZone("UTC")

    private fun day(month: Int, day: Int, year: Int = 2026): Long =
        ZonedDateTime.of(year, month, day, 12, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()

    private fun line(value: Double, on: Long = day(11, 8), locale: Locale = enUS): String? =
        VO2MaxText.line(value, on, locale, utc)

    // The words

    @Test
    fun `a whole number drops its trailing zero`() {
        assertEquals("VO2 max 46 · Nov 8", line(46.0))
    }

    @Test
    fun `one decimal is kept`() {
        assertEquals("VO2 max 46.2 · Nov 8", line(46.24))
        assertEquals("VO2 max 46.2 · Nov 8", line(46.2))
    }

    @Test
    fun `the separator is a middle dot`() {
        assertTrue(line(46.2)!!.contains("·"))
    }

    // Rounding: half up on the value's shortest decimal string

    @Test
    fun `a half rounds up on the written value`() {
        assertEquals("VO2 max 46.3 · Nov 8", line(46.25))
        assertEquals("VO2 max 46.4 · Nov 8", line(46.35))
        assertEquals("VO2 max 46.5 · Nov 8", line(46.45))
        assertEquals("VO2 max 46.2 · Nov 8", line(46.24))
    }

    @Test
    fun `rounding can carry into the whole number`() {
        assertEquals("VO2 max 47 · Nov 8", line(46.96))
        assertEquals("VO2 max 47 · Nov 8", line(46.95))
        assertEquals("VO2 max 46 · Nov 8", line(46.04))
        assertEquals("VO2 max 46 · Nov 8", line(45.95))
    }

    // Nothing

    @Test
    fun `an unusable value is no line not a zero or a dash`() {
        assertNull(line(0.0))
        assertNull(line(-3.0))
        assertNull(line(Double.NaN))
        assertNull(line(Double.POSITIVE_INFINITY))
        assertNull(line(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `a value that rounds to zero is no line`() {
        // 0.04 is written "0"; "VO2 max 0" would be a zero, not an estimate.
        assertNull(line(0.04))
        assertEquals("VO2 max 0.1 · Nov 8", line(0.05))
    }

    // The date follows the reader's locale

    @Test
    fun `the date is abbreviated month then day in the US`() {
        assertEquals("VO2 max 46 · Mar 1", line(46.0, day(3, 1)))
        assertEquals("VO2 max 46 · Dec 25", line(46.0, day(12, 25)))
    }

    @Test
    fun `the day and month order follows the locale`() {
        // Not asserting a foreign string, which can vary across OS versions: only that it is not
        // the US spelling, and that the day (25) and the value are both still there.
        val us = line(46.2, day(12, 25), enUS)
        val uk = line(46.2, day(12, 25), Locale.UK)
        assertNotNull(uk)
        assertNotEquals(us, uk)
        assertTrue(uk!!.contains("25"))
        assertTrue(uk.startsWith("VO2 max 46.2 · "))
        val prefix = "VO2 max 46.2 · "
        assertTrue(us!!.removePrefix(prefix).startsWith("Dec"))
        assertTrue(uk.removePrefix(prefix).startsWith("25"))
    }

    @Test
    fun `the number always uses a dot`() {
        // German writes 46,2; the line's number is not localized, only its date.
        val de = line(46.2, day(12, 25), Locale.GERMANY)!!
        assertTrue(de, de.startsWith("VO2 max 46.2 · "))
    }

    @Test
    fun `the day is the time zones day not UTCs`() {
        // 20:00 UTC on Nov 8 is already Nov 9 in Tokyo.
        val instant = ZonedDateTime.of(2026, 11, 8, 20, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("VO2 max 46 · Nov 9", VO2MaxText.line(46.0, instant, enUS, TimeZone.getTimeZone("Asia/Tokyo")))
        assertEquals("VO2 max 46 · Nov 8", VO2MaxText.line(46.0, instant, enUS, utc))
    }
}
