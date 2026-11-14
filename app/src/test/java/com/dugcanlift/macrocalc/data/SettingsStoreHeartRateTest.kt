package com.dugcanlift.macrocalc.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The heart rate setting and the added devices, through the real SharedPreferences. */
@RunWith(RobolectricTestRunner::class)
class SettingsStoreHeartRateTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("dcl_settings", Context.MODE_PRIVATE)

    @Before
    @After
    fun reset() {
        SettingsStore::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        prefs.edit().clear().commit()
    }

    private val strap = AddedHeartRateDevice("AA:BB:CC:DD:EE:01", "Polar H10")
    private val band = AddedHeartRateDevice("AA:BB:CC:DD:EE:02", "Wahoo TICKR")

    @Test
    fun `devices round trip in the order added`() {
        val store = SettingsStore.get(context)
        store.heartRateDevices = listOf(band, strap)
        assertEquals(listOf(band, strap), store.heartRateDevices)
        // And from a fresh store over the same prefs.
        SettingsStore::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        assertEquals(listOf(band, strap), SettingsStore.get(context).heartRateDevices)
    }

    @Test
    fun `a colon id keeps its colons`() {
        val store = SettingsStore.get(context)
        store.heartRateDevices = listOf(strap)
        assertEquals("AA:BB:CC:DD:EE:01", store.heartRateDevices.single().id)
    }

    @Test
    fun `unreadable stored devices are an empty list`() {
        val store = SettingsStore.get(context)
        for (junk in listOf("not json", "{\"id\":\"x\"}", "[1, 2, \"x\"]", "")) {
            prefs.edit().putString("heart_rate_devices", junk).commit()
            assertEquals(junk, emptyList<AddedHeartRateDevice>(), store.heartRateDevices)
        }
    }

    @Test
    fun `entries with a missing or empty id are dropped`() {
        prefs.edit().putString("heart_rate_devices",
            """[{"name":"No id"},{"id":"","name":"Empty"},{"id":7,"name":"Number"},{"id":"AA:BB","name":"Kept"},{"id":"CC:DD"}]""").commit()
        assertEquals(listOf(AddedHeartRateDevice("AA:BB", "Kept"), AddedHeartRateDevice("CC:DD", "")),
            SettingsStore.get(context).heartRateDevices)
    }

    @Test
    fun `an empty list removes the key`() {
        val store = SettingsStore.get(context)
        store.heartRateDevices = listOf(strap)
        assertTrue(prefs.contains("heart_rate_devices"))
        store.heartRateDevices = emptyList()
        assertFalse(prefs.contains("heart_rate_devices"))
        assertEquals(emptyList<AddedHeartRateDevice>(), store.heartRateDevices)
    }

    @Test
    fun `a repeated id is kept once in the first place`() {
        val store = SettingsStore.get(context)
        store.heartRateDevices = listOf(band, strap, AddedHeartRateDevice(band.id, "Renamed"), strap)
        assertEquals(listOf(band, strap), store.heartRateDevices)
    }

    @Test
    fun `an unreadable stored setting is automatic`() {
        val store = SettingsStore.get(context)
        assertEquals(HeartRateSetting.Automatic, store.heartRateSetting)
        for (junk in listOf("zones", "", "device:", "DEVICE:x")) {
            prefs.edit().putString("heart_rate_setting", junk).commit()
            assertEquals(junk, HeartRateSetting.Automatic, store.heartRateSetting)
        }
        prefs.edit().putInt("heart_rate_setting", 3).commit()
        assertEquals(HeartRateSetting.Automatic, store.heartRateSetting)
    }

    @Test
    fun `every setting round trips through the real prefs including a MAC`() {
        val store = SettingsStore.get(context)
        for (setting in listOf(HeartRateSetting.Automatic, HeartRateSetting.Off, HeartRateSetting.Watch,
            HeartRateSetting.Device("AA:BB:CC:DD:EE:01"))) {
            store.heartRateSetting = setting
            assertEquals(setting, store.heartRateSetting)
        }
        assertEquals("device:AA:BB:CC:DD:EE:01", prefs.getString("heart_rate_setting", null))
    }
}
