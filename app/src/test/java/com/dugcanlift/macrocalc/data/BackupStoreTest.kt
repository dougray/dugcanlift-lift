package com.dugcanlift.macrocalc.data

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The backup file is the only thing that survives a new phone, so these pin the
 * rules in coach/BACKUP-FORMAT.md rather than this app's agreement with itself.
 * The recipe tests decode `fixtures/web-backup-recipes.json`, which the web
 * build's own `saveBackup` wrote -- do not regenerate it from this code.
 */
@RunWith(RobolectricTestRunner::class)
class BackupStoreTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    // The repositories are process-wide singletons that Robolectric's
    // classloader reuses across test methods; see RecipeRepositoryTest.
    // Reset after as well as before. `build` makes CoachStore generate and cache
    // a random lifter id, and leaving that behind changed the link
    // CoachShareCaptureTest captured when it ran next in the same JVM.
    @Before
    @After
    fun resetSingletons() {
        // CoachStore matters most: `build` reads `lifterId`, which generates and
        // caches a random id, and CoachShareCaptureTest asserts a fixed one.
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

    private fun file(data: JSONObject) = JSONObject()
        .put("v", 1).put("app", "lift").put("saved", "2026-09-16").put("data", data).toString()

    // MARK: - Interop with the web build

    @Test
    fun `a web backup restores its recipe and planned meal`() {
        val result = BackupStore.restore(context, fixture("web-backup-recipes.json"))
        assertTrue(result.ok)
        assertEquals("one recipe and one planned meal", 2, result.added)

        val repo = RecipeRepository.get(context)
        val recipe = repo.recipesForBackup().single()
        assertEquals("Beef Chilli", recipe.name)
        assertEquals(4.0, recipe.servings, 1e-9)
        assertEquals(listOf("Brown the beef", "Simmer for an hour"), recipe.steps)
        assertEquals(9.0, recipe.nutritionPerServing!!.fiberG, 1e-9)

        val meal = repo.planForBackup().single()
        assertEquals(recipe.id, meal.recipeId)
        assertEquals("2026-09-17", meal.date)
        assertEquals("DINNER", meal.meal)
    }

    /** The web writes the count sentinel as an ingredient's unit, and a line
     *  that never parsed as bare rawText. Both must come through the reparse. */
    @Test
    fun `the web file's ingredients are reparsed from rawText`() {
        BackupStore.restore(context, fixture("web-backup-recipes.json"))
        val ingredients = RecipeRepository.get(context).recipesForBackup().single().ingredients
        assertEquals(4, ingredients.size)
        assertEquals(500.0, ingredients[0].grams!!, 1e-9)
        assertEquals("salt to taste", ingredients[3].rawText)
        assertNull("a line that does not parse carries no quantity", ingredients[3].qty)
    }

    /** A cached quantity in the file must not outlive the line's own text. */
    @Test
    fun `a stale cached quantity loses to the reparse`() {
        val recipe = JSONObject().put("id", "r1").put("name", "Salted").put("servings", 1)
            .put("ingredients", JSONArray().put(JSONObject()
                .put("rawText", "salt to taste").put("item", "STALE").put("qty", 7)
                .put("note", "flaky")))
        BackupStore.restore(context, file(JSONObject().put("recipes", JSONArray().put(recipe))))
        val ingredient = RecipeRepository.get(context).recipesForBackup().single().ingredients.single()
        assertNull(ingredient.qty)
        assertEquals("flaky", ingredient.note)
    }

    // MARK: - Interop with the iOS build

    /** Written by LIFT iOS's own BackupStore.build, so every id is upper case. */
    @Test
    fun `an iOS backup restores its recipe and keeps the meal linked`() {
        val result = BackupStore.restore(context, fixture("ios-backup-recipes.json"))
        assertEquals("one recipe and one planned meal", 2, result.added)
        val repo = RecipeRepository.get(context)
        val recipe = repo.recipesForBackup().single()
        assertEquals("Salmon and Sweet Potato", recipe.name)
        assertEquals(900.0, recipe.totalWeightGrams!!, 1e-9)
        assertEquals(recipe.id, repo.planForBackup().single().recipeId)
    }

    /** iOS's `ext` block -- sugar, sodium, ingredient foodRefIDs -- has no home
     *  here and must go back out untouched on the next save. */
    @Test
    fun `an iOS backup's ext survives a restore and save`() {
        BackupStore.restore(context, fixture("ios-backup-recipes.json"))
        val ext = JSONObject(BackupStore.build(context)).getJSONObject("ext")
        val recipes = ext.getJSONObject("ios").getJSONObject("recipes")
        assertEquals(410, recipes.getJSONObject(recipes.keys().next()).getInt("sodiumMg"))
    }

    @Test
    fun `the same recipe spelled in both cases restores once`() {
        val upper = JSONObject(fixture("ios-backup-recipes.json"))
        val lower = JSONObject(upper.toString())
        val recipe = lower.getJSONObject("data").getJSONArray("recipes").getJSONObject(0)
        recipe.put("id", recipe.getString("id").lowercase())
        BackupStore.restore(context, lower.toString())
        BackupStore.restore(context, upper.toString())
        assertEquals(1, RecipeRepository.get(context).recipesForBackup().size)
    }

    // MARK: - Restoring

    @Test
    fun `an id in the other case is the same record`() {
        val lower = JSONObject().put("id", "8e1c4c2a-0000-4000-8000-000000000001")
            .put("name", "Oats").put("date", "2026-09-16").put("servings", 1).put("calories", 300)
        BackupStore.restore(context, file(JSONObject().put("food", JSONArray().put(lower))))
        val upper = JSONObject(lower.toString()).put("id", "8E1C4C2A-0000-4000-8000-000000000001")
        val second = BackupStore.restore(context, file(JSONObject().put("food", JSONArray().put(upper))))

        assertEquals("an iPhone round trip must not duplicate the entry", 0, second.added)
        assertEquals(1, FoodRepository.get(context).entries.value.size)
    }

    @Test
    fun `a planned meal whose recipe exists nowhere is skipped`() {
        val meal = JSONObject().put("id", "m1").put("recipeId", "gone").put("date", "2026-09-17")
            .put("meal", "LUNCH").put("servings", 1)
        val result = BackupStore.restore(context, file(JSONObject().put("plan", JSONArray().put(meal))))
        assertEquals(0, result.added)
        assertTrue(RecipeRepository.get(context).planForBackup().isEmpty())
    }

    // MARK: - Saving

    @Test
    fun `a backup carries recipes and the plan`() {
        BackupStore.restore(context, fixture("web-backup-recipes.json"))
        val data = JSONObject(BackupStore.build(context)).getJSONObject("data")
        assertEquals("Beef Chilli", data.getJSONArray("recipes").getJSONObject(0).getString("name"))
        assertEquals(1, data.getJSONArray("plan").length())
    }

    @Test
    fun `a section this app does not store survives restore and save`() {
        val data = JSONObject()
            .put("steps", JSONObject().put("2026-09-16", 8000))       // web writes it; Android does not store it
            .put("futureSection", JSONArray().put("keep me"))        // one a newer client might add
        BackupStore.restore(context, file(data))
        val out = JSONObject(BackupStore.build(context)).getJSONObject("data")
        assertEquals(8000, out.getJSONObject("steps").getInt("2026-09-16"))
        assertEquals("keep me", out.getJSONArray("futureSection").getString(0))
    }

    @Test
    fun `shopping ticks are neither restored nor written`() {
        BackupStore.restore(context, file(JSONObject().put("shopping", JSONArray().put("beef"))))
        assertFalse(JSONObject(BackupStore.build(context)).getJSONObject("data").has("shopping"))
    }
}
