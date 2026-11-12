package com.dugcanlift.macrocalc

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Street maps send nothing while they are looked at. The only request the maps make is the
 * download the person starts, and that lives inside `:liftmap`; nothing in this module may reach a
 * tile server, load a style from a URL or fill MapLibre's own offline cache.
 *
 * Source scans, like `RoadFoodTest`'s: they read `app/src/main` as text, so a call added in a
 * file nobody was looking at still fails here.
 */
class MapNoNetworkTest {

    private val main = File("src/main")

    private fun sources(): List<File> {
        assertTrue("source tree not found at ${main.absolutePath}", main.isDirectory)
        return main.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "xml", "kts") }
            .toList()
    }

    private fun offenders(needle: String) =
        sources().filter { needle in it.readText() }.map { it.relativeTo(main).path }

    @Test
    fun `no tile server is named in this app`() {
        assertEquals(emptyList<String>(), offenders("openfreemap"))
    }

    @Test
    fun `no map style is loaded from a URI`() {
        assertEquals(emptyList<String>(), offenders("fromUri("))
    }

    @Test
    fun `MapLibre's own offline cache is not used`() {
        assertEquals(emptyList<String>(), offenders("org.maplibre.android.offline"))
    }

    @Test
    fun `food search is still the only HttpURLConnection in the app`() {
        assertEquals(listOf("java/com/dugcanlift/macrocalc/data/FoodSearch.kt"), offenders("HttpURLConnection"))
    }

    @Test
    fun `the three route canvases draw through RouteMap`() {
        for (name in listOf("OutdoorRecordingScreen.kt", "OutdoorReviewScreen.kt", "WorkoutScreen.kt")) {
            val text = File(main, "java/com/dugcanlift/macrocalc/$name").readText()
            assertTrue("$name should call RouteMap", "RouteMap(" in text)
        }
    }

    @Test
    fun `the plain line is only ever the fallback`() {
        // RoutePolylineCanvas may be defined once and called inside a RouteMap fallback; a call
        // outside one is a screen that never shows streets.
        for (f in sources().filter { it.extension == "kt" }) {
            val lines = f.readLines()
            lines.forEachIndexed { i, line ->
                if ("RoutePolylineCanvas(" in line && "fun RoutePolylineCanvas" !in line && !line.trimStart().startsWith("*")) {
                    val context = lines.subList(maxOf(0, i - 4), i + 1).joinToString("\n")
                    assertTrue("${f.name}:${i + 1} draws the plain line outside a RouteMap fallback", "fallback" in context)
                }
            }
        }
    }

    @Test
    fun `the manifest removes the two permissions MapLibre merges in`() {
        val text = File("src/main/AndroidManifest.xml").readText()
        for (p in listOf("ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE")) {
            val line = text.lines().firstOrNull { "android.permission.$p" in it || it.contains(p) }
            assertTrue("$p is not removed", line != null && text.substringAfter(p).substringBefore("/>").contains("tools:node=\"remove\""))
        }
    }

    @Test
    fun `the downloaded streets stay out of every backup`() {
        for (f in listOf("data_extraction_rules.xml", "backup_rules.xml")) {
            val text = File("src/main/res/xml/$f").readText()
            assertTrue("$f does not exclude liftmap/", "liftmap" in text)
        }
    }

    @Test
    fun `the application installs the network guard at launch`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("manifest names no Application class", "android:name=\".LiftApplication\"" in manifest)
        val app = File(main, "java/com/dugcanlift/macrocalc/LiftApplication.kt").readText()
        assertTrue("MapNetworkGuard.install(this)" in app)
    }
}
