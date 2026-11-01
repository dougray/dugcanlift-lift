package com.dugcanlift.macrocalc.data

import com.dugcanlift.kit.RecipeNutrition
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * A recipe's fiber is unknown when nobody stated it, never 0 g -- LIFT iPhone's
 * `NutritionFacts.fiberG` rule, which this build could not hold before kit-android 1.10.0 made
 * [RecipeNutrition.fiberG] nullable. Stated zeros, including every zero an earlier build stored,
 * stay zeros.
 */
class RecipeFiberTest {

    private fun recipe(nutrition: String) =
        recipeFromJson(JSONObject("""{"id":"r1","name":"Oats","servings":2,"nutritionPerServing":$nutrition}"""))

    @Test fun `no fiber key reads as unknown and writes no key back`() {
        // The shape LIFT iPhone writes for a nil fiberG, and the shape BACKUP-FORMAT allows.
        val r = recipe("""{"calories":320,"proteinG":12,"carbsG":52,"fatG":8}""")
        assertNull(r.nutritionPerServing!!.fiberG)
        assertFalse(r.toJson().getJSONObject("nutritionPerServing").has("fiberG"))
        assertNull(recipeFromJson(r.toJson()).nutritionPerServing!!.fiberG)
    }

    @Test fun `null and junk are unknown`() {
        assertNull(recipe("""{"calories":320,"fiberG":null}""").nutritionPerServing!!.fiberG)
        assertNull(recipe("""{"calories":320,"fiberG":"some"}""").nutritionPerServing!!.fiberG)
    }

    @Test fun `a stored zero stays zero`() {
        // Every recipe an earlier build saved wrote "fiberG": 0.0 for a blank field. Nobody can
        // tell now which of those were unknown, so they keep meaning what they said.
        val r = recipe("""{"calories":320,"proteinG":12,"carbsG":52,"fatG":8,"fiberG":0.0}""")
        assertEquals(0.0, r.nutritionPerServing!!.fiberG!!, 0.0)
        assertEquals(0.0, r.toJson().getJSONObject("nutritionPerServing").getDouble("fiberG"), 0.0)
    }

    @Test fun `a planned meal's snapshots keep unknown fiber unknown`() {
        val meal = PlannedMeal(recipeId = "r1", snapshotNutrition = RecipeNutrition(calories = 320.0),
            snapshotNutritionPerGram = RecipeNutrition(calories = 0.5))
        val back = plannedMealFromJson(meal.toJson())
        assertNull(back.snapshotNutrition!!.fiberG)
        assertNull(back.snapshotNutritionPerGram!!.fiberG)
    }

    @Test fun `the editor saves blank fiber as unknown and a typed zero as zero`() {
        val blank = recipeNutritionFromText("450", "30", "40", "12", "", "", "", "", estimated = false)!!
        assertNull(blank.fiberG)
        val zero = recipeNutritionFromText("450", "30", "40", "12", "0", "", "", "", estimated = false)!!
        assertEquals(0.0, zero.fiberG!!, 0.0)
        // Fiber alone is still something typed.
        assertEquals(7.0, recipeNutritionFromText("", "", "", "", "7", "", "", "", estimated = false)!!.fiberG!!, 0.0)
    }

    @Test fun `a catalogue copy states no fiber, so it has none`() {
        val catalogue = parseRecipeCatalog(File("src/main/assets/${RecipeCatalogStore.ASSET}").readText())
        val withMacros = catalogue.first { it.nutrition(4.0) != null }
        assertNull(withMacros.makeRecipe(4.0).nutritionPerServing!!.fiberG)
    }

    @Test fun `a page that states no fiber imports with none`() {
        val r = com.dugcanlift.kit.RecipeJsonLd.recipeFromJson(
            """{"@type":"Recipe","name":"X","nutrition":{"calories":"350","proteinContent":"12 g"}}"""
        )!!
        assertNull(r.nutritionPerServing!!.fiberG)
    }

    @Test fun `logging a recipe with unknown fiber writes 0 to the food log, which holds whole numbers`() {
        // FoodEntry.fiberG is an Int across the whole food log. LIFT iPhone's day total reads an
        // unknown as 0 too (`totals.fiberG ?? 0`), so the day reads the same on both phones.
        val unknown = PlannedMeal(recipeId = "r1", servings = 2.0, snapshotNutrition = RecipeNutrition(calories = 320.0))
        assertEquals(0, unknown.toFoodEntry()!!.fiberG)
        val known = PlannedMeal(recipeId = "r1", servings = 2.0, snapshotNutrition = RecipeNutrition(calories = 320.0, fiberG = 6.6))
        assertEquals(7, known.toFoodEntry()!!.fiberG)
        val byWeight = PlannedMeal(recipeId = "r1", amountGrams = 200.0,
            snapshotNutritionPerGram = RecipeNutrition(calories = 1.5))
        assertEquals(0, byWeight.toFoodEntry()!!.fiberG)
    }
}
