package com.dugcanlift.macrocalc.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** A port of LIFT iPhone's `MyEquipmentTests`, case for case, plus the search it narrows. */
@RunWith(RobolectricTestRunner::class)
class MyEquipmentTest {

    private val library: List<LibraryExercise> by lazy {
        parseExerciseLibrary(File("src/main/assets/$EXERCISE_LIBRARY_ASSET").readText())
    }

    @Test fun `owning everything hides nothing`() {
        assertTrue(MyEquipment.allows("barbell", emptySet()))
        val records = listOf("barbell", "cable", null)
        val result = MyEquipment.filter(records, emptySet()) { it }
        assertEquals(records, result.shown)
        assertEquals(0, result.leftOut)
    }

    @Test fun `what you do not have is left out and counted`() {
        val records = listOf("barbell", "cable", "dumbbell", "Cable ")
        val result = MyEquipment.filter(records, setOf("cable")) { it }
        assertEquals(listOf("barbell", "dumbbell"), result.shown)
        assertEquals(2, result.leftOut)
    }

    /** Unknown is not "needs something you lack". */
    @Test fun `an exercise with no equipment listed always shows`() {
        val everything = MyEquipment.kinds.map { it.id }.toSet()
        assertTrue(MyEquipment.allows(null, everything))
        assertTrue(MyEquipment.allows("  ", everything))
    }

    @Test fun `owning everything stores no key`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("my-equipment-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        assertEquals(emptySet<String>(), MyEquipment.load(prefs))
        MyEquipment.save(setOf("cable", "machine"), prefs)
        assertEquals(setOf("cable", "machine"), MyEquipment.load(prefs))
        MyEquipment.save(emptySet(), prefs)
        assertFalse(prefs.contains(MyEquipment.STORAGE_KEY))
    }

    /**
     * A value in the data with no switch could never be turned off, and that exercise would show for
     * someone who has none of it.
     */
    @Test fun `every equipment value in the data has a switch`() {
        assertTrue(library.size > 800)
        val known = MyEquipment.kinds.map { it.id }.toSet()
        val values = library.map { MyEquipment.normalised(it.equipment) }.toSet() - ""
        assertEquals(emptySet<String>(), values - known)
    }

    // MARK: - Search, this build's own

    /**
     * The equipment is filtered over every match before the 40-row cap, so the count is of everything
     * left out and the rows shown are the ones the lifter can do -- the iPhone's "searches deeper".
     */
    @Test fun `search leaves out what you do not have, counts it, and fills the cap from the rest`() {
        val all = searchExerciseLibraryOwned(library, query = "", limit = 5000)
        val cableCount = all.shown.count { it.equipment == "cable" }
        assertTrue(cableCount > 40)

        val narrowed = searchExerciseLibraryOwned(library, query = "", notOwned = setOf("cable"))
        assertEquals(cableCount, narrowed.leftOut)
        assertEquals(EXERCISE_SEARCH_LIMIT, narrowed.shown.size)
        assertTrue(narrowed.shown.none { it.equipment == "cable" })

        // Exercises with no equipment listed are still there with everything switched off.
        val nothing = searchExerciseLibraryOwned(
            library, query = "", notOwned = MyEquipment.kinds.map { it.id }.toSet(), limit = 5000
        )
        assertTrue(nothing.shown.isNotEmpty())
        assertTrue(nothing.shown.all { it.equipment.isBlank() })
    }

    @Test fun `with everything owned the search is exactly what it was`() {
        assertEquals(
            searchExerciseLibrary(library, "row"),
            searchExerciseLibraryOwned(library, "row").shown
        )
        assertEquals(0, searchExerciseLibraryOwned(library, "row").leftOut)
    }

    @Test fun `search says how many it left out`() {
        assertEquals("Your equipment · 3 left out", MyEquipment.statusText(filtering = true, leftOut = 3))
        assertEquals("Your equipment", MyEquipment.statusText(filtering = true, leftOut = 0))
        assertEquals("All equipment", MyEquipment.statusText(filtering = false, leftOut = 3))
        assertEquals("1 exercise needs equipment you don't have.", MyEquipment.leftOutText(1))
        assertEquals("12 exercises need equipment you don't have.", MyEquipment.leftOutText(12))
    }
}
