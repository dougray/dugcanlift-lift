package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * These run against the **shipped asset**, not a fixture. The library's whole
 * value is that all three versions read one file, so a test that parses a
 * hand-written sample would pass on a file the app doesn't have.
 */
class ExerciseLibraryTest {

    private val library: List<LibraryExercise> by lazy {
        parseExerciseLibrary(File("src/main/assets/$EXERCISE_LIBRARY_ASSET").readText())
    }

    // MARK: - The bundled file

    @Test fun `the asset is present and holds all 873 exercises`() {
        assertEquals(873, library.size)
    }

    @Test fun `every row resolves its indices to a real muscle`() {
        // An index the arrays don't cover comes back empty. If a row loses its
        // muscle, its name is still searchable but the label reads "Squat - ".
        val nameless = library.filter { it.muscle.isBlank() }
        assertTrue("rows with no muscle: ${nameless.take(3).map { it.name }}", nameless.isEmpty())
    }

    @Test fun `the first rows match the file byte for byte`() {
        val first = library.first()
        assertEquals("3/4 Sit-Up", first.name)
        assertEquals("abdominals", first.muscle)
        assertEquals("body only", first.equipment)
        assertEquals("strength", first.category)
        assertEquals("beginner", first.level)
    }

    @Test fun `every equipment filter chip matches something`() {
        // A chip that returns nothing is a chip that looks broken.
        EQUIPMENT_FILTERS.forEach { filter ->
            val hits = searchExerciseLibrary(library, query = "", equipmentFilter = filter)
            assertFalse("no exercise uses equipment '$filter'", hits.isEmpty())
        }
    }

    @Test fun `a malformed row costs that row, not the library`() {
        val json = """
            {"muscles":["chest"],"equipment":["barbell"],"categories":["strength"],
             "levels":["beginner"],
             "exercises":[["Bench Press",0,0,0,0],["Nameless",9,9,9,9],["",0,0,0,0]]}
        """.trimIndent()
        val parsed = parseExerciseLibrary(json)

        assertEquals("the blank-named row is dropped, the other two are kept", 2, parsed.size)
        assertEquals("Bench Press", parsed[0].name)
        assertEquals("Nameless", parsed[1].name)
        assertEquals("an out-of-range index is empty, not a crash", "", parsed[1].muscle)
    }

    // MARK: - Equipment is stored the way the browser stores it

    @Test fun `stored equipment is title cased like the browser`() {
        assertEquals("Body Only", LibraryExercise("X", "", "body only", "", "").storedEquipment)
        assertEquals("Barbell", LibraryExercise("X", "", "barbell", "", "").storedEquipment)
        assertEquals("Kettlebells", LibraryExercise("X", "", "kettlebells", "", "").storedEquipment)
    }

    @Test fun `a hyphen starts a word, as it does in the browser's regex`() {
        assertEquals("E-Z Curl Bar", titleCaseAscii("e-z curl bar"))
    }

    @Test fun `an exercise with no equipment stores no equipment`() {
        assertEquals("", LibraryExercise("X", "", "", "", "").storedEquipment)
    }

    @Test fun `every equipment value in the file title cases cleanly`() {
        library.forEach {
            assertFalse(
                "'${it.equipment}' still starts lowercase after title casing",
                it.storedEquipment.firstOrNull()?.isLowerCase() == true
            )
        }
    }

    @Test fun `the match key is the one logged exercises use`() {
        val squat = library.first { it.name == "Barbell Squat" }
        val logged = LoggedExercise(name = squat.name, equipment = squat.storedEquipment)

        assertEquals(
            "picking this from the library and typing it by hand are the same lift",
            logged.matchKey, squat.matchKey
        )
    }

    // MARK: - Row text

    @Test fun `a name containing a dash keeps all of itself out of the subtitle`() {
        // Caught on the device: the row built its subtitle by slicing the
        // joined label at the first " - ", and this name has one, so the row
        // read "Medium Grip - chest, barbell" under the name.
        val hit = library.first { it.name == "Barbell Bench Press - Medium Grip" }

        assertEquals("chest, barbell", hit.detailLabel)
        assertEquals("Barbell Bench Press - Medium Grip - chest, barbell", hit.resultLabel)
    }

    @Test fun `an exercise needing no equipment says only the muscle`() {
        val hit = library.first { it.equipment.isBlank() }
        assertEquals(hit.muscle, hit.detailLabel)
    }

    @Test fun `plenty of these names carry their own dash`() {
        // If this ever hits zero the test above is guarding nothing.
        assertTrue(library.count { " - " in it.name } > 10)
    }

    // MARK: - Search

    @Test fun `search matches a name`() {
        val hits = searchExerciseLibrary(library, "bench press")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.name.lowercase().contains("bench press") })
    }

    @Test fun `search matches a muscle too`() {
        // Typing a body part is how someone looks for what to do, not what to find.
        val hits = searchExerciseLibrary(library, "hamstrings")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.muscle == "hamstrings" || it.name.lowercase().contains("hamstrings") })
    }

    @Test fun `search ignores case and surrounding space`() {
        assertEquals(
            searchExerciseLibrary(library, "squat").map { it.name },
            searchExerciseLibrary(library, "  SQUAT  ").map { it.name }
        )
    }

    @Test fun `an empty query returns the top of the library, capped`() {
        assertEquals(EXERCISE_SEARCH_LIMIT, searchExerciseLibrary(library, "").size)
    }

    @Test fun `a query nothing matches returns nothing`() {
        // The picker turns this into "Add it as typed" — the library is a
        // convenience, not a gate on what you are allowed to have done.
        assertTrue(searchExerciseLibrary(library, "zzzznotalift").isEmpty())
    }

    @Test fun `the equipment filter and the query both apply`() {
        val hits = searchExerciseLibrary(library, "press", equipmentFilter = "dumbbell")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.equipment == "dumbbell" && it.name.lowercase().contains("press") })
    }

    // MARK: - History ranking

    @Test fun `a lift you have logged comes back first`() {
        val plain = searchExerciseLibrary(library, "squat")
        val last = plain.last()
        assertNotNull(last)

        val ranked = searchExerciseLibrary(library, "squat", recentKeys = listOf(last.matchKey))
        assertEquals("the one they actually do should not be at the bottom", last.name, ranked.first().name)
    }

    @Test fun `ranking happens before the cap, not after`() {
        // The browser slices to 40 and never reorders. Doing that here would
        // hide exactly the row the ranking exists to surface.
        val all = searchExerciseLibrary(library, "", limit = library.size)
        val buried = all[500]

        val ranked = searchExerciseLibrary(library, "", recentKeys = listOf(buried.matchKey))
        assertEquals(buried.name, ranked.first().name)
        assertEquals(EXERCISE_SEARCH_LIMIT, ranked.size)
    }

    @Test fun `several logged lifts keep their recency order`() {
        val all = searchExerciseLibrary(library, "", limit = library.size)
        val newest = all[300]
        val older = all[100]

        val ranked = searchExerciseLibrary(
            library, "", recentKeys = listOf(newest.matchKey, older.matchKey)
        )
        assertEquals(listOf(newest.name, older.name), ranked.take(2).map { it.name })
    }

    @Test fun `everything you have not logged keeps the file's order`() {
        val plain = searchExerciseLibrary(library, "squat", limit = library.size)
        val ranked = searchExerciseLibrary(
            library, "squat", recentKeys = listOf(plain.last().matchKey), limit = library.size
        )
        assertEquals(plain.dropLast(1).map { it.name }, ranked.drop(1).map { it.name })
    }

    @Test fun `a history of things not in the library changes nothing`() {
        val plain = searchExerciseLibrary(library, "squat")
        val ranked = searchExerciseLibrary(library, "squat", recentKeys = listOf("sandbag carry|sandbag"))
        assertEquals(plain.map { it.name }, ranked.map { it.name })
    }

    @Test fun `no history changes nothing`() {
        assertEquals(
            searchExerciseLibrary(library, "row").map { it.name },
            searchExerciseLibrary(library, "row", recentKeys = emptyList()).map { it.name }
        )
    }
}
