package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Heart rate on a run: the rules, as values. Twins of lift-ios's RunHeartRateTests, case for case.
 * Plain JVM: nothing here may need an Android framework class.
 */
class RunHeartRateTest {
    private val start = 1_800_000_000_000L
    private val strap = AddedHeartRateDevice(id = "AA:BB:CC:DD:EE:01", name = "Polar H10")
    private val band = AddedHeartRateDevice(id = "AA:BB:CC:DD:EE:02", name = "Wahoo TICKR")

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    // The Bluetooth measurement

    @Test
    fun `an eight bit reading`() {
        assertEquals(72, HeartRateMeasurement.bpm(bytes(0x00, 72)))
    }

    @Test
    fun `a sixteen bit reading is little endian`() {
        assertEquals(300, HeartRateMeasurement.bpm(bytes(0x01, 0x2C, 0x01)))
    }

    @Test
    fun `a byte above 127 is not read as negative`() {
        assertEquals(200, HeartRateMeasurement.bpm(bytes(0x00, 200)))
        // 0xFF, 0x00 little endian is 255; a signed read would be -1.
        assertEquals(255, HeartRateMeasurement.bpm(bytes(0x01, 0xFF, 0x00)))
        assertEquals(0x80FF, HeartRateMeasurement.bpm(bytes(0x01, 0xFF, 0x80)))
    }

    @Test
    fun `a sixteen bit flag with one byte is no reading`() {
        assertNull(HeartRateMeasurement.bpm(bytes(0x01, 0x2C)))
    }

    @Test
    fun `zero is no reading`() {
        assertNull(HeartRateMeasurement.bpm(bytes(0x00, 0)))
        assertNull(HeartRateMeasurement.bpm(bytes(0x01, 0, 0)))
    }

    @Test
    fun `empty and short data are no reading`() {
        assertNull(HeartRateMeasurement.bpm(ByteArray(0)))
        assertNull(HeartRateMeasurement.bpm(bytes(0x00)))
    }

    @Test
    fun `bit zero with other flag bits set is still sixteen bit`() {
        // 0x11: UInt16 plus bit 4. 0x10: bit 4 alone is still UInt8.
        assertEquals(300, HeartRateMeasurement.bpm(bytes(0x11, 0x2C, 0x01, 0xFF)))
        assertEquals(65, HeartRateMeasurement.bpm(bytes(0x10, 65, 0xFF)))
        assertNull(HeartRateMeasurement.bpm(bytes(0x11, 0x2C)))
    }

    @Test
    fun `other flag bits do not change the width`() {
        // Bit 3 (energy expended) set, bit 0 clear: still one byte of bpm.
        assertEquals(65, HeartRateMeasurement.bpm(bytes(0x08, 65, 0xFF, 0xFF)))
    }

    // Choosing a source

    @Test
    fun `automatic never picks an unadded device`() {
        assertNull(HeartRateSourcePicker.pick(HeartRateSetting.Automatic, emptyList(), watchAvailable = false))
    }

    @Test
    fun `automatic prefers an added device over the watch`() {
        val source = HeartRateSourcePicker.pick(HeartRateSetting.Automatic, listOf(strap), watchAvailable = true)
        assertEquals(LiveHeartRateSource.Device(strap.id, "Polar H10"), source)
    }

    @Test
    fun `automatic takes the first added in the order added`() {
        val source = HeartRateSourcePicker.pick(HeartRateSetting.Automatic, listOf(band, strap), watchAvailable = false)
        assertEquals(LiveHeartRateSource.Device(band.id, "Wahoo TICKR"), source)
    }

    @Test
    fun `automatic falls back to the watch`() {
        assertEquals(LiveHeartRateSource.Watch,
            HeartRateSourcePicker.pick(HeartRateSetting.Automatic, emptyList(), watchAvailable = true))
    }

    @Test
    fun `a chosen device out of range is null not the watch`() {
        val source = HeartRateSourcePicker.pick(HeartRateSetting.Device(strap.id), listOf(band), watchAvailable = true)
        assertNull(source)
    }

    @Test
    fun `a chosen device in range`() {
        val source = HeartRateSourcePicker.pick(HeartRateSetting.Device(band.id), listOf(strap, band), watchAvailable = true)
        assertEquals(LiveHeartRateSource.Device(band.id, "Wahoo TICKR"), source)
    }

    @Test
    fun `watch only when available`() {
        assertEquals(LiveHeartRateSource.Watch,
            HeartRateSourcePicker.pick(HeartRateSetting.Watch, listOf(strap), watchAvailable = true))
        assertNull(HeartRateSourcePicker.pick(HeartRateSetting.Watch, listOf(strap), watchAvailable = false))
    }

    @Test
    fun `off is null`() {
        assertNull(HeartRateSourcePicker.pick(HeartRateSetting.Off, listOf(strap), watchAvailable = true))
    }

    @Test
    fun `the setting round trips through its stored text`() {
        for (setting in listOf(HeartRateSetting.Automatic, HeartRateSetting.Off, HeartRateSetting.Watch,
            HeartRateSetting.Device(strap.id))) {
            assertEquals(setting, HeartRateSetting.fromStored(setting.toStored()))
        }
    }

    @Test
    fun `an unreadable stored setting is automatic`() {
        assertEquals(HeartRateSetting.Automatic, HeartRateSetting.fromStored(null))
        assertEquals(HeartRateSetting.Automatic, HeartRateSetting.fromStored(""))
        assertEquals(HeartRateSetting.Automatic, HeartRateSetting.fromStored("zones"))
        assertEquals(HeartRateSetting.Automatic, HeartRateSetting.fromStored("device:"))
    }

    // Stale readings

    @Test
    fun `a reading is shown under ten seconds`() {
        assertEquals(150, LiveHeartRate.shown(LiveHeartRate.Reading(150, start), start + 9_900))
    }

    @Test
    fun `a reading over ten seconds old is hidden`() {
        assertNull(LiveHeartRate.shown(LiveHeartRate.Reading(150, start), start + 10_100))
    }

    @Test
    fun `a reading exactly ten seconds old still shows because the rule is at most ten`() {
        // lift-ios: `now - at <= staleAfter` shows. Same here, so both platforms agree to the ms.
        assertEquals(150, LiveHeartRate.shown(LiveHeartRate.Reading(150, start), start + 10_000))
        assertNull(LiveHeartRate.shown(LiveHeartRate.Reading(150, start), start + 10_001))
    }

    @Test
    fun `no reading yet shows nothing`() {
        assertNull(LiveHeartRate.shown(null, start))
    }

    @Test
    fun `the stale limit is ten seconds`() {
        assertEquals(10_000L, LiveHeartRate.STALE_AFTER_MS)
    }

    // The run's summary

    @Test
    fun `summary is mean and max`() {
        assertEquals(RunHeartRateSummary(averageBpm = 141, maxBpm = 142), RunHeartRateSummary.of(listOf(140, 141, 142)))
    }

    @Test
    fun `summary mean rounds half up`() {
        assertEquals(142, RunHeartRateSummary.of(listOf(141, 142))?.averageBpm)
        assertEquals(141, RunHeartRateSummary.of(listOf(140, 141))?.averageBpm)
    }

    @Test
    fun `no readings is no summary`() {
        assertNull(RunHeartRateSummary.of(emptyList()))
    }

    // The session

    @Test
    fun `a dropped source is reconnected not swapped`() {
        val session = RunHeartRateSession(begin = LiveHeartRateSource.Device(strap.id, "Polar H10"))
        session.receive(150, start)
        // The strap goes quiet for a minute, then comes back.
        session.receive(152, start + 60_000)
        assertEquals(LiveHeartRateSource.Device(strap.id, "Polar H10"), session.source)
        assertEquals(listOf(150, 152), session.readings)
    }

    @Test
    fun `ending gives the summary and a stopped session ignores readings`() {
        val session = RunHeartRateSession(begin = LiveHeartRateSource.Watch)
        session.receive(140, start)
        session.receive(142, start + 1_000)
        assertEquals(RunHeartRateSummary(averageBpm = 141, maxBpm = 142), session.end())
        session.receive(200, start + 2_000)
        assertEquals(listOf(140, 142), session.readings)
    }

    @Test
    fun `a session with no source keeps nothing`() {
        val session = RunHeartRateSession(begin = null)
        session.receive(150, start)
        assertTrue(session.readings.isEmpty())
        assertNull(session.end())
    }

    @Test
    fun `the latest reading is recorded for the stale rule`() {
        val session = RunHeartRateSession(begin = LiveHeartRateSource.Watch)
        assertNull(session.shown(start))
        session.receive(150, start)
        assertEquals(150, session.shown(start + 5_000))
        assertNull(session.shown(start + 11_000))
    }

    @Test
    fun `a zero or negative reading is ignored`() {
        val session = RunHeartRateSession(begin = LiveHeartRateSource.Watch)
        session.receive(0, start)
        session.receive(-3, start)
        assertTrue(session.readings.isEmpty())
    }
}
