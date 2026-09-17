package com.dugcanlift.macrocalc

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Route recording keeps going with the phone locked without
 * `ACCESS_BACKGROUND_LOCATION`: the Start tap starts [LocationRecordingService],
 * a `location` foreground service, while the app is visible. Declaring background
 * location would bring back Play's declaration form and video, so this pins the
 * manifest's location permissions and the service type that makes it unnecessary.
 *
 * Reads the source manifest rather than asking Robolectric's package manager:
 * this module's unit tests run without `includeAndroidResources`, so Robolectric
 * sees an empty default manifest and a "not declared" check would pass for
 * nothing.
 */
class LocationPermissionsManifestTest {

    private val android = "http://schemas.android.com/apk/res/android"

    private val manifest: Element by lazy {
        // Gradle runs unit tests from the module directory.
        val file = File("src/main/AndroidManifest.xml")
        assertTrue("manifest not found at ${file.absolutePath}", file.isFile)
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
            .documentElement
    }

    private fun elements(tag: String): List<Element> {
        val nodes = manifest.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun requested(): List<String> =
        elements("uses-permission").map { it.getAttributeNS(android, "name") }

    @Test
    fun `background location is not declared`() {
        val permissions = requested()
        assertTrue("no permissions read", permissions.isNotEmpty())
        assertFalse("android.permission.ACCESS_BACKGROUND_LOCATION" in permissions)
    }

    @Test
    fun `foreground location, notifications and the location foreground service are declared`() {
        val permissions = requested()
        listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_LOCATION",
            "android.permission.POST_NOTIFICATIONS"
        ).forEach { assertTrue("$it missing", it in permissions) }
    }

    @Test
    fun `recording service is a location foreground service`() {
        val service = elements("service")
            .single { it.getAttributeNS(android, "name") == ".LocationRecordingService" }
        assertEquals("location", service.getAttributeNS(android, "foregroundServiceType"))
    }
}
