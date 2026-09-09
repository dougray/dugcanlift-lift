package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ServingUnitTest {

    @Test
    fun `ounces round trip through conversion`() {
        val original = 28.3495
        val roundTrip = ServingUnit.OUNCES.toGrams(ServingUnit.OUNCES.fromGrams(original))
        assertEquals(original, roundTrip, 0.0001)
    }

    @Test
    fun `grams is identity conversion`() {
        val value = 100.0
        assertEquals(value, ServingUnit.GRAMS.fromGrams(value), 0.0001)
        assertEquals(value, ServingUnit.GRAMS.toGrams(value), 0.0001)
    }

    @Test
    fun `ounces conversion factor matches expected value`() {
        val grams = 28.3495
        val ounces = ServingUnit.OUNCES.fromGrams(grams)
        assertEquals(1.0, ounces, 0.0001)
    }
}
