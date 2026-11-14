package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Settings rows and words, pinned verbatim: Doug approved this copy. */
class HeartRateSettingsCopyTest {
    private val a = AddedHeartRateDevice("AA:BB:CC:DD:EE:01", "Polar H10")
    private val b = AddedHeartRateDevice("AA:BB:CC:DD:EE:02", "")

    @Test
    fun `the words are the approved ones`() {
        assertEquals("Heart rate", HeartRateSettingsCopy.SECTION_TITLE)
        assertEquals("Add a Bluetooth heart rate device", HeartRateSettingsCopy.ADD_BUTTON)
        assertEquals(
            "Automatic uses a device you've added when it's nearby, then LIFT on your watch. " +
                "Bands that save heart rate to Health Connect fill in the summary after the run.",
            HeartRateSettingsCopy.FOOTER
        )
        assertEquals("Add a device", HeartRateSettingsCopy.SHEET_TITLE)
        assertEquals("Turn on your strap, or heart rate broadcast on your watch.", HeartRateSettingsCopy.SHEET_HINT)
        assertEquals("Looking for heart rate devices…", HeartRateRadioState.LOOKING)
    }

    @Test
    fun `the rows are Automatic, Off, then each added device in the order added`() {
        val rows = HeartRateChoices.rows(listOf(a, b))
        assertEquals(listOf("Automatic", "Off", "Polar H10", "Heart rate device"), rows.map { it.title })
        assertEquals(
            listOf(HeartRateSetting.Automatic, HeartRateSetting.Off, HeartRateSetting.Device(a.id), HeartRateSetting.Device(b.id)),
            rows.map { it.setting }
        )
    }

    @Test
    fun `no watch row without a watch that can send heart rate`() {
        assertEquals(false, HeartRateChoices.rows(listOf(a)).any { it.setting == HeartRateSetting.Watch })
        val withWatch = HeartRateChoices.rows(listOf(a), watchName = "Pixel Watch 2")
        assertEquals("Pixel Watch 2", withWatch.last().title)
        assertEquals(HeartRateSetting.Watch, withWatch.last().setting)
    }
}
