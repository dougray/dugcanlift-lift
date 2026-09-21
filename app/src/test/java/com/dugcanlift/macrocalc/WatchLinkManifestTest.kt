package com.dugcanlift.macrocalc

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The Bluetooth permissions LIFT Link needs, and the ones it must not.
 *
 * Read from the source manifest, for the reason [LocationPermissionsManifestTest] gives: this
 * module's Robolectric sees an empty default manifest, so a "not declared" check through it would
 * pass for nothing.
 */
class WatchLinkManifestTest {

    private val android = "http://schemas.android.com/apk/res/android"

    private val manifest: Element by lazy {
        val file = File("src/main/AndroidManifest.xml")
        assertTrue("manifest not found at ${file.absolutePath}", file.isFile)
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
            .documentElement
    }

    private fun permissions(): Map<String, Element> {
        val nodes = manifest.getElementsByTagName("uses-permission")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associateBy { it.getAttributeNS(android, "name") }
    }

    @Test
    fun `the phone may scan and connect`() {
        val declared = permissions()
        assertTrue("android.permission.BLUETOOTH_SCAN" in declared)
        assertTrue("android.permission.BLUETOOTH_CONNECT" in declared)
    }

    @Test
    fun `the scan says it is never used for location`() {
        // Without this flag, Android 12+ treats a BLE scan as a location request and the app has
        // to explain why a workout tracker wants to know where you are. It does not: it looks for
        // one service UUID, its own.
        val scan = permissions().getValue("android.permission.BLUETOOTH_SCAN")
        assertEquals("neverForLocation", scan.getAttributeNS(android, "usesPermissionFlags"))
    }

    @Test
    fun `the phone never advertises, so it does not ask to`() {
        assertFalse("android.permission.BLUETOOTH_ADVERTISE" in permissions())
    }

    @Test
    fun `the legacy pair stops at the last API level that needs them`() {
        val declared = permissions()
        listOf("android.permission.BLUETOOTH", "android.permission.BLUETOOTH_ADMIN").forEach { name ->
            val element = declared[name]
            assertNotNull("$name missing", element)
            assertEquals(name, "30", element!!.getAttributeNS(android, "maxSdkVersion"))
        }
    }

    @Test
    fun `Bluetooth LE is optional, because the rest of the app does not need it`() {
        val nodes = manifest.getElementsByTagName("uses-feature")
        val feature = (0 until nodes.length).map { nodes.item(it) as Element }
            .firstOrNull { it.getAttributeNS(android, "name") == "android.hardware.bluetooth_le" }
        assertNotNull(feature)
        assertEquals("false", feature!!.getAttributeNS(android, "required"))
    }

    @Test
    fun `the watch link brings no background location with it`() {
        // Old Android needs FINE location to scan, which route recording already declares. What
        // must never appear is the background variant: that is a Play declaration and a video,
        // and nothing about pairing a watch needs it.
        assertFalse("android.permission.ACCESS_BACKGROUND_LOCATION" in permissions())
    }
}
