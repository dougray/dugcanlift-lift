package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DecimalInputTest {
    @Test fun commaLocaleReadsCommaAsDecimalPoint() {
        assertEquals("82.5", normalizeDecimalInput("82,5", localeSeparator = ','))
        assertEquals(82.5, normalizeDecimalInput("82,5", localeSeparator = ',').toDouble(), 0.0)
    }

    @Test fun pointIsAlwaysAccepted() {
        assertEquals("82.5", normalizeDecimalInput("82.5", localeSeparator = ','))
        assertEquals("82.5", normalizeDecimalInput("82.5", localeSeparator = '.'))
    }

    @Test fun commaIsDroppedWhereTheLocaleDoesNotUseIt() {
        assertEquals("1000", normalizeDecimalInput("1,000", localeSeparator = '.'))
    }

    @Test fun onlyTheFirstPointIsKept() {
        assertEquals("1.23", normalizeDecimalInput("1.2.3", localeSeparator = '.'))
        assertEquals("1.23", normalizeDecimalInput("1,2.3", localeSeparator = ','))
    }

    @Test fun everythingElseIsDropped() {
        assertEquals("125", normalizeDecimalInput(" 12a5 g", localeSeparator = '.'))
        assertEquals("", normalizeDecimalInput("", localeSeparator = ','))
    }
}
