package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone

/**
 * The one place the reader's own month-and-day pattern (`DateFormat.getBestDateTimePattern`) is
 * used for real, which needs Robolectric. Everything else about the line is in [VO2MaxTextTest].
 */
@RunWith(RobolectricTestRunner::class)
class VO2MaxTextLocaleTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val dec25 = ZonedDateTime.of(2026, 12, 25, 12, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()

    @Test
    fun `the readers own pattern gives month then day in the US`() {
        assertEquals("VO2 max 46.2 · Dec 25", VO2MaxText.line(46.2, dec25, Locale.US, utc))
    }

    @Test
    fun `the day and month order follows the locale`() {
        // Not asserting a foreign string, which can vary across OS versions: only that it is not
        // the US spelling, and that the day (25) and the value are both still there.
        val us = VO2MaxText.line(46.2, dec25, Locale.US, utc)
        val uk = VO2MaxText.line(46.2, dec25, Locale.UK, utc)
        assertNotNull(uk)
        assertNotEquals(us, uk)
        assertTrue(uk!!.contains("25"))
        val prefix = "VO2 max 46.2 · "
        assertTrue(uk.startsWith(prefix))
        assertTrue(us!!.removePrefix(prefix).startsWith("Dec"))
        assertTrue(uk.removePrefix(prefix).startsWith("25"))
    }
}
