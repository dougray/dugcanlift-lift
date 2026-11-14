package com.dugcanlift.macrocalc

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Bluetooth heart rate devices add no permission of their own: the scan and connect permissions are
 * LIFT Link's (see [WatchLinkManifestTest]), and `PermissionsPinTest` still pins the exact set.
 * What this pins is what was decided *not* to add, until Doug decides otherwise.
 */
class HeartRateManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private val manifest: Element by lazy {
        val file = File("src/main/AndroidManifest.xml")
        assertTrue("manifest not found at ${file.absolutePath}", file.isFile)
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file).documentElement
    }

    private fun names(tag: String): List<String> {
        val nodes = manifest.getElementsByTagName(tag)
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(android, "name") }
    }

    @Test
    fun `no connectedDevice foreground service permission is declared`() {
        // The recording's location foreground service keeps the process alive while a run is
        // recording, so a strap's connection lives with it. Whether Android 14 also wants the
        // connectedDevice type for that is Doug's call, not a silent addition.
        assertFalse("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" in names("uses-permission"))
    }

    @Test
    fun `the recording service stays a location service and nothing else`() {
        val services = manifest.getElementsByTagName("service")
        val types = (0 until services.length).map { services.item(it) as Element }
            .filter { it.getAttributeNS(android, "name").endsWith("LocationRecordingService") }
            .map { it.getAttributeNS(android, "foregroundServiceType") }
        assertEquals(listOf("location"), types)
    }

    @Test
    fun `scanning for a heart rate strap still says it never derives a location`() {
        val nodes = manifest.getElementsByTagName("uses-permission")
        val scan = (0 until nodes.length).map { nodes.item(it) as Element }
            .first { it.getAttributeNS(android, "name") == "android.permission.BLUETOOTH_SCAN" }
        assertEquals("neverForLocation", scan.getAttributeNS(android, "usesPermissionFlags"))
    }
}
