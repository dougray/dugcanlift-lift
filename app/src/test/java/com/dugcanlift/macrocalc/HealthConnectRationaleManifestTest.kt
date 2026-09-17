package com.dugcanlift.macrocalc

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Health Connect lists an app's privacy policy from its own settings through two
 * entry points: `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` (Android 13 and
 * earlier) and `VIEW_PERMISSION_USAGE` in the `HEALTH_PERMISSIONS` category on an
 * alias guarded by `START_VIEW_PERMISSION_USAGE` (Android 14+). Without them Health
 * Connect refuses to show the app's permissions, and Play review flags it. Both
 * must reach [PermissionsRationaleActivity], which opens the policy the Play
 * listing names.
 *
 * Reads the source manifest, as [LocationPermissionsManifestTest] explains.
 */
class HealthConnectRationaleManifestTest {

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

    private fun elements(parent: Element, tag: String): List<Element> {
        val nodes = parent.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.attr(name: String) = getAttributeNS(android, name)

    private fun Element.actions() = elements(this, "action").map { it.attr("name") }

    private fun Element.categories() = elements(this, "category").map { it.attr("name") }

    @Test
    fun `the rationale action opens the privacy policy activity`() {
        val activity = elements(manifest, "activity").single { it.attr("name") == ".PermissionsRationaleActivity" }
        assertEquals("true", activity.attr("exported"))
        assertTrue(activity.actions().contains("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"))
    }

    @Test
    fun `the Android 14 permission usage alias targets the same activity`() {
        val alias = elements(manifest, "activity-alias").single {
            it.actions().contains("android.intent.action.VIEW_PERMISSION_USAGE")
        }
        assertEquals(".PermissionsRationaleActivity", alias.attr("targetActivity"))
        assertEquals("android.permission.START_VIEW_PERMISSION_USAGE", alias.attr("permission"))
        assertEquals("true", alias.attr("exported"))
        assertTrue(alias.categories().contains("android.intent.category.HEALTH_PERMISSIONS"))
    }

    @Test
    fun `the policy is the one the Play listing names`() {
        assertEquals("https://www.dugcanlift.com/app/privacy/", PermissionsRationaleActivity.PRIVACY_POLICY_URL)
    }
}
