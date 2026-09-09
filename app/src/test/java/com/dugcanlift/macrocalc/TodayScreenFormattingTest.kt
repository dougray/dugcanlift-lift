package com.dugcanlift.macrocalc

import com.dugcanlift.macrocalc.data.ServingUnit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [formatAmount], the display-formatting helper behind the food-row
 * amount text (e.g. "140 g" / "4.9 oz") — pulled out of the [EntryRow]
 * Composable the same way [formatServings] already is, so the conversion and
 * rounding can be checked without standing up Compose.
 */
class TodayScreenFormattingTest {

    @Test
    fun `whole gram amount formats without decimal`() {
        assertEquals("140 g", formatAmount(140.0, ServingUnit.GRAMS))
    }

    @Test
    fun `grams amount rounds to one decimal place`() {
        assertEquals("140.3 g", formatAmount(140.25, ServingUnit.GRAMS))
    }

    @Test
    fun `ounces conversion rounds to one decimal place`() {
        // 140 g / 28.3495 g-per-oz = 4.9382... oz
        assertEquals("4.9 oz", formatAmount(140.0, ServingUnit.OUNCES))
    }

    @Test
    fun `ounces amount that rounds to a whole number drops the decimal`() {
        // 1 oz exactly.
        assertEquals("1 oz", formatAmount(28.3495, ServingUnit.OUNCES))
    }

    @Test
    fun `zero grams formats as zero`() {
        assertEquals("0 g", formatAmount(0.0, ServingUnit.GRAMS))
    }
}
