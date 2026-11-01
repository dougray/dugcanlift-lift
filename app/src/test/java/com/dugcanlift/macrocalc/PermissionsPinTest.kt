package com.dugcanlift.macrocalc

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Every permission LIFT Android ships with, pinned to an explicit list that the public privacy
 * policy has to match.
 *
 * The page is `www.dugcanlift.com/app/privacy/` (`dugcanlift-site/app-privacy.md`). Each entry
 * below names the section of it that tells people what the permission is for. **A new permission
 * fails this test until someone has updated that page.** The page is public copy and Doug reviews
 * it, so change `app-privacy.md` first and this list second, never the list alone.
 *
 * This reads the **merged** manifest of the release variant, not `src/main/AndroidManifest.xml`:
 * the merged one is the app's own manifest plus every library's, which is what an installed APK
 * actually declares. A dependency that brings a permission in behind the app's back (WorkManager's
 * `WAKE_LOCK`, a network library's `ACCESS_NETWORK_STATE`) shows up here and nowhere in `src/`.
 * `app/build.gradle.kts` hands the file's path across as `releaseMergedManifest` and declares it an
 * input of every unit test task, so `testDebugUnitTest` runs `processReleaseManifest` first and a
 * dependency bump that changes the merged manifest reruns this.
 */
class PermissionsPinTest {

    private val expected: Set<String> = setOf(
        // "What leaves your device" > "Food search (Android and web)": Open Food Facts, directly.
        "android.permission.INTERNET",
        // "Camera" > "Android": the barcode scanner only.
        "android.permission.CAMERA",
        // "Location: recording runs, walks and hikes" > "Android": precise location while in use,
        // never background; a foreground service keeps a recording going, and its ongoing
        // "Recording your route" notification "is what the notifications permission is for".
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_LOCATION",
        "android.permission.POST_NOTIFICATIONS",
        // "Health Connect and Apple Health" > "Android — Health Connect". Reads: steps only, plus
        // history older than 30 days. Writes: a finished run, walk or hike as an exercise session
        // with its route, distance and elevation gain.
        "android.permission.health.READ_STEPS",
        "android.permission.health.READ_HEALTH_DATA_HISTORY",
        "android.permission.health.WRITE_EXERCISE",
        "android.permission.health.WRITE_EXERCISE_ROUTE",
        "android.permission.health.WRITE_DISTANCE",
        "android.permission.health.WRITE_ELEVATION_GAINED",
        // LIFT Link, the Bluetooth LE channel to the LIFT Wear OS watch (watchlink/). The page does
        // not describe it yet: it arrived after v1.16, and the release that ships it needs the
        // page's wording first.
        "android.permission.BLUETOOTH_SCAN usesPermissionFlags=neverForLocation",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH maxSdkVersion=30",
        "android.permission.BLUETOOTH_ADMIN maxSdkVersion=30",
        // Not a request to the user or to the system: androidx.core declares this signature-level
        // permission and holds it itself, so a receiver registered NOT_EXPORTED on Android 12 and
        // older is protected from other apps. Only an app signed with LIFT's key can hold it.
        "com.dugcanlift.macrocalc.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    )

    private val page = "dugcanlift-site/app-privacy.md"

    private val android = "http://schemas.android.com/apk/res/android"

    private val manifest: Element by lazy {
        val path = System.getProperty("releaseMergedManifest")
        requireNotNull(path) { "releaseMergedManifest is not set: see app/build.gradle.kts" }
        val file = File(path)
        assertTrue("merged manifest not found at ${file.absolutePath}", file.isFile)
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

    /** A permission and the qualifiers that change what it grants, written the way [expected] is. */
    private fun describe(element: Element): String {
        val qualifiers = listOf("maxSdkVersion", "minSdkVersion", "usesPermissionFlags")
            .mapNotNull { name -> element.getAttributeNS(android, name).takeIf { it.isNotEmpty() }?.let { "$name=$it" } }
        return (listOf(element.getAttributeNS(android, "name")) + qualifiers).joinToString(" ")
    }

    @Test
    fun `the release APK asks for exactly what the privacy page describes`() {
        // uses-permission-sdk-23 is the same request, limited to API 23 and up; it counts.
        val declared = (elements("uses-permission") + elements("uses-permission-sdk-23")).map(::describe)
        assertEquals("a permission is declared twice: $declared", declared.size, declared.toSet().size)
        assertEquals(
            "The release APK's permissions changed. Update $page first, then this list.",
            expected.sorted().joinToString("\n"),
            declared.sorted().joinToString("\n"),
        )
    }

    @Test
    fun `the only permission LIFT defines is androidx's signature-level receiver guard`() {
        val defined = elements("permission").associate {
            it.getAttributeNS(android, "name") to it.getAttributeNS(android, "protectionLevel")
        }
        assertEquals(
            mapOf("com.dugcanlift.macrocalc.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" to "signature"),
            defined,
        )
    }

    @Test
    fun `this is the release manifest, merged`() {
        // The release application id, not the debug build's ".debug" one: what ships is pinned.
        assertEquals("com.dugcanlift.macrocalc", manifest.getAttribute("package"))
        // Merging writes the libraries' components into the file (androidx.startup's provider,
        // profileinstaller's receiver); src/main names none of them.
        assertTrue(
            "this does not look like a merged manifest",
            listOf("activity", "provider", "receiver", "service").flatMap(::elements)
                .any { it.getAttributeNS(android, "name").startsWith("androidx.") },
        )
    }
}
