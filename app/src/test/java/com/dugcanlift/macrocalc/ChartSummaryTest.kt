package com.dugcanlift.macrocalc

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ChartSummaryTest {
    private val days = listOf("Mon", "Tue", "Wed")

    @Test fun rangeAndLatestPerSeries() {
        val text = chartSummary(
            listOf(
                ChartSeries("Calories", Color.Red, listOf(1850f, null, 2400f)),
                ChartSeries("Protein", Color.Blue, listOf(150f, 180f, 160f))
            ),
            days
        )
        assertEquals(
            "Calories, Mon to Wed: 1,850 to 2,400, latest 2,400. " +
                "Protein, Mon to Wed: 150 to 180, latest 160.",
            text
        )
    }

    @Test fun oneValueAndNoValues() {
        assertEquals(
            "Fiber, Mon to Wed: 30, latest 30. Fat, Mon to Wed: nothing logged.",
            chartSummary(
                listOf(
                    ChartSeries("Fiber", Color.Red, listOf(null, 30f, null)),
                    ChartSeries("Fat", Color.Red, listOf(null, null, null))
                ),
                days
            )
        )
    }

    @Test fun smallFractionsKeepOneDecimal() {
        assertEquals(
            "Distance (km), Mon to Wed: 2.5 to 5.2, latest 5.2.",
            chartSummary(listOf(ChartSeries("Distance (km)", Color.Red, listOf(2.5f, 5.2f))), days)
        )
    }
}
