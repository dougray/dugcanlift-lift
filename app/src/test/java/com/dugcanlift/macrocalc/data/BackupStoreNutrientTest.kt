package com.dugcanlift.macrocalc.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dugcanlift.kit.RecipeNutrition
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `saturatedFatG`, `sugarG` and `sodiumMg` in the backup file (BACKUP-FORMAT
 * `food[]` and a recipe's `nutritionPerServing`), and the older iPhone files
 * that carried sugar and sodium only under `ext.ios`.
 */
@RunWith(RobolectricTestRunner::class)
class BackupStoreNutrientTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    // Before and after, for the reasons BackupStoreTest gives.
    @Before
    @After
    fun resetSingletons() {
        listOf(FoodRepository::class.java, WorkoutRepository::class.java,
               RoutineRepository::class.java, RecipeRepository::class.java,
               CoachStore::class.java, SettingsStore::class.java, GoalStore::class.java).forEach {
            val field = it.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        }
    }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    private fun file(data: JSONObject, ext: JSONObject? = null) = JSONObject()
        .put("v", 1).put("app", "lift").put("saved", "2026-09-16").put("data", data)
        .apply { ext?.let { put("ext", it) } }
        .toString()

    private fun food(id: String) = JSONObject().put("id", id).put("name", "Soup")
        .put("date", "2026-09-16").put("servings", 1).put("calories", 200)

    @Test
    fun `a food entry's three are written under their common names and restored`() = runTest {
        FoodRepository.get(context).add(FoodEntry(id = "f1", name = "Soup", calories = 200, proteinG = 8,
            fatG = 6, carbsG = 25, saturatedFatG = 2.4, sugarG = 5.0, sodiumMg = 890.0))
        val written = BackupStore.build(context)
        val record = JSONObject(written).getJSONObject("data").getJSONArray("food").getJSONObject(0)
        assertEquals(2.4, record.getDouble("saturatedFatG"), 0.0)
        assertEquals(5.0, record.getDouble("sugarG"), 0.0)
        assertEquals(890.0, record.getDouble("sodiumMg"), 0.0)

        resetSingletons()
        BackupStore.restore(context, written.replace("\"f1\"", "\"f2\""))
        val restored = FoodRepository.get(context).entries.value.first { it.id == "f2" }
        assertEquals(890.0, restored.sodiumMg!!, 0.0)
        assertEquals(2.4, restored.saturatedFatG!!, 0.0)
    }

    @Test
    fun `a food entry that recorded none writes none`() = runTest {
        FoodRepository.get(context).add(FoodEntry(id = "f1", name = "Oats", calories = 300, proteinG = 10, fatG = 5, carbsG = 50))
        val record = JSONObject(BackupStore.build(context)).getJSONObject("data").getJSONArray("food").getJSONObject(0)
        assertFalse(record.has("saturatedFatG"))
        assertFalse(record.has("sugarG"))
        assertFalse(record.has("sodiumMg"))
    }

    @Test
    fun `a recipe's nutrition carries them in the file`() = runTest {
        RecipeRepository.get(context).addRecipe(Recipe(id = "r1", name = "Chilli", servings = 4.0,
            nutritionPerServing = RecipeNutrition(calories = 450.0, saturatedFatG = 6.0, sodiumMg = 800.0)))
        val nutrition = JSONObject(BackupStore.build(context)).getJSONObject("data").getJSONArray("recipes")
            .getJSONObject(0).getJSONObject("nutritionPerServing")
        assertEquals(6.0, nutrition.getDouble("saturatedFatG"), 0.0)
        assertEquals(800.0, nutrition.getDouble("sodiumMg"), 0.0)
        assertFalse("unknown is left out, never written as zero", nutrition.has("sugarG"))
    }

    /** Written by LIFT iOS before the common names existed: sugar and sodium only in `ext.ios.recipes`. */
    @Test
    fun `an iOS recipe's sugar and sodium come from ext when the common fields are missing`() {
        BackupStore.restore(context, fixture("ios-backup-recipes.json"))
        val nutrition = RecipeRepository.get(context).recipesForBackup().single().nutritionPerServing!!
        assertEquals(410.0, nutrition.sodiumMg!!, 0.0)
        assertEquals(9.0, nutrition.sugarG!!, 0.0)
        assertNull("iOS never recorded saturated fat", nutrition.saturatedFatG)
    }

    @Test
    fun `an iOS food entry's sugar and sodium come from ext, matched in any case`() {
        val ext = JSONObject().put("ios", JSONObject().put("food", JSONObject()
            .put("8E1C4C2A-0000-4000-8000-000000000009", JSONObject().put("sugarG", 4.5).put("sodiumMg", 620))))
        BackupStore.restore(context, file(
            JSONObject().put("food", JSONArray().put(food("8e1c4c2a-0000-4000-8000-000000000009"))), ext))
        val entry = FoodRepository.get(context).entries.value.single()
        assertEquals(4.5, entry.sugarG!!, 0.0)
        assertEquals(620.0, entry.sodiumMg!!, 0.0)
    }

    @Test
    fun `the common field wins over ext when both are there`() {
        val ext = JSONObject().put("ios", JSONObject().put("food", JSONObject()
            .put("f9", JSONObject().put("sugarG", 99).put("sodiumMg", 999))))
        BackupStore.restore(context, file(
            JSONObject().put("food", JSONArray().put(food("f9").put("sodiumMg", 120))), ext))
        val entry = FoodRepository.get(context).entries.value.single()
        assertEquals(120.0, entry.sodiumMg!!, 0.0)
        assertEquals("the missing one still falls back", 99.0, entry.sugarG!!, 0.0)
    }

    @Test
    fun `a file with neither restores them as not recorded`() {
        BackupStore.restore(context, file(JSONObject().put("food", JSONArray().put(food("f3")))))
        val entry = FoodRepository.get(context).entries.value.single()
        assertNull(entry.sugarG)
        assertNull(entry.sodiumMg)
        assertNull(entry.saturatedFatG)
    }
}
