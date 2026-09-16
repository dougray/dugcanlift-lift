package com.dugcanlift.macrocalc.data

import com.dugcanlift.kit.NutrientDetails
import com.dugcanlift.kit.RecipeNutrition
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Saturated fat, sugar and sodium: stored, read off Open Food Facts, scaled,
 * shown and carried by recipes. The rule under every test here is that
 * unknown is null and never zero.
 */
class NutrientDetailsTest {

    // MARK: - Food entry JSON

    @Test fun `a food entry's three round trip through JSON`() {
        val entry = FoodEntry(name = "Crisps", calories = 160, proteinG = 2, fatG = 10, carbsG = 15,
            saturatedFatG = 0.9, sugarG = 0.4, sodiumMg = 170.0)
        val restored = foodEntryFromJson(entry.toJson())
        assertEquals(entry, restored)
    }

    @Test fun `an entry written before them reads as not recorded`() {
        val old = JSONObject().put("id", "a").put("name", "Oats").put("servings", 1.0)
            .put("calories", 300).put("proteinG", 10).put("fatG", 5).put("carbsG", 50).put("fiberG", 8)
            .put("date", "2026-09-01").put("loggedAt", 1L).put("meal", "BREAKFAST")
        val entry = foodEntryFromJson(old)
        assertNull(entry.saturatedFatG)
        assertNull(entry.sugarG)
        assertNull(entry.sodiumMg)
        assertTrue(entry.details.isEmpty)
    }

    @Test fun `unrecorded ones are not written, so they cannot come back as zero`() {
        val json = FoodEntry(name = "Apple", calories = 80, proteinG = 0, fatG = 0, carbsG = 21, sugarG = 16.0).toJson()
        assertFalse(json.has("saturatedFatG"))
        assertFalse(json.has("sodiumMg"))
        assertEquals(16.0, json.getDouble("sugarG"), 0.0)
    }

    @Test fun `null, negative or non-numeric values read as not recorded`() {
        val o = JSONObject().put("name", "x").put("saturatedFatG", JSONObject.NULL)
            .put("sugarG", -1).put("sodiumMg", "lots")
        val entry = foodEntryFromJson(o)
        assertTrue(entry.details.isEmpty)
    }

    // MARK: - Per 100 g arithmetic

    @Test fun `scaling from 100 g rounds grams to a decimal and sodium to a milligram`() {
        val per100 = Per100g(calories = 500, proteinG = 6, fatG = 25, carbsG = 60, fiberG = 3,
            details = NutrientDetails(saturatedFatG = 12.34, sugarG = null, sodiumMg = 456.7))
        val scaled = scaleFrom100g(per100, 30.0)!!
        assertEquals(3.7, scaled.details.saturatedFatG!!, 0.0)   // 3.702
        assertNull("unknown stays unknown", scaled.details.sugarG)
        assertEquals(137.0, scaled.details.sodiumMg!!, 0.0)      // 137.01
    }

    // MARK: - Open Food Facts

    private fun product(nutriments: JSONObject) = JSONObject()
        .put("code", "123").put("product_name", "Tortilla chips").put("nutriments", nutriments)

    @Test fun `reads saturated fat, sugars and sodium, converting sodium from grams`() {
        val result = FoodSearch.parseProduct(product(JSONObject()
            .put("energy-kcal_100g", 490).put("fat_100g", 23)
            .put("saturated-fat_100g", 2.1).put("sugars_100g", "1.5").put("sodium_100g", 0.48)
            .put("energy-kcal_serving", 137).put("saturated-fat_serving", 0.59).put("sodium_serving", 0.134)))!!
        assertEquals(2.1, result.per100g.details.saturatedFatG!!, 1e-9)
        assertEquals(1.5, result.per100g.details.sugarG!!, 1e-9)
        assertEquals("Open Food Facts stores sodium in grams", 480.0, result.per100g.details.sodiumMg!!, 1e-9)
        assertEquals(134.0, result.perServing!!.details.sodiumMg!!, 1e-9)
        assertNull(result.perServing!!.details.sugarG)
    }

    @Test fun `only salt listed gives sodium at salt divided by 2_5`() {
        val result = FoodSearch.parseProduct(product(JSONObject()
            .put("energy-kcal_100g", 250).put("salt_100g", 1.25)))!!
        assertEquals(500.0, result.per100g.details.sodiumMg!!, 1e-9)
    }

    @Test fun `sodium wins over salt when both are listed`() {
        val result = FoodSearch.parseProduct(product(JSONObject()
            .put("energy-kcal_100g", 250).put("salt_100g", 1.0).put("sodium_100g", 0.3)))!!
        assertEquals(300.0, result.per100g.details.sodiumMg!!, 1e-9)
    }

    @Test fun `a product listing none of them has none, not zeros`() {
        val result = FoodSearch.parseProduct(product(JSONObject().put("energy-kcal_100g", 52)))!!
        assertTrue(result.per100g.details.isEmpty)
        val entry = result.toFoodEntry("2026-09-16", 150.0, result.nutrition(150.0), "150 g")
        assertTrue(entry.details.isEmpty)
    }

    @Test fun `a logged amount scales them and the entry keeps them`() {
        val result = FoodSearch.parseProduct(product(JSONObject()
            .put("energy-kcal_100g", 490).put("saturated-fat_100g", 2.1)
            .put("sugars_100g", 1.5).put("sodium_100g", 0.48)))!!
        val nutrition = result.nutrition(45.0)
        val entry = result.toFoodEntry("2026-09-16", 45.0, nutrition, "45 g")
        assertEquals(0.9, entry.saturatedFatG!!, 0.0)   // 0.945
        assertEquals(0.7, entry.sugarG!!, 0.0)          // 0.675
        assertEquals(216.0, entry.sodiumMg!!, 0.0)
    }

    // MARK: - Text

    private fun food(servings: Double = 1.0, satFat: Double? = null, sugar: Double? = null, sodium: Double? = null) =
        FoodEntry(name = "f", servings = servings, calories = 100, proteinG = 1, fatG = 1, carbsG = 1,
            saturatedFatG = satFat, sugarG = sugar, sodiumMg = sodium)

    @Test fun `an entry's line names only what is known, times servings`() {
        assertEquals("Sat fat 3.1 g - Sodium 1,080 mg",
            NutrientDetailsText.entryLine(food(servings = 2.0, satFat = 1.55, sodium = 540.0), Locale.US))
        assertEquals("Sugar 12 g", NutrientDetailsText.entryLine(food(sugar = 12.0), Locale.US))
        assertNull(NutrientDetailsText.entryLine(food()))
    }

    @Test fun `a day's rows say how many foods each total covers`() {
        val day = listOf(
            food(sodium = 1000.0, sugar = 5.0),
            food(sodium = 600.0, sugar = 2.5),
            food(sodium = 240.0, sugar = 1.0, satFat = 4.0),
            food(sugar = 3.0),
            food(sugar = 0.0),
        )
        val rows = NutrientDetailsText.dayRows(day, Locale.US)
        assertEquals(listOf(
            NutrientDetailsText.Row("Saturated fat", "4 g · from 1 of 5 foods"),
            NutrientDetailsText.Row("Sugar", "11.5 g"),
            NutrientDetailsText.Row("Sodium", "1,840 mg · from 3 of 5 foods"),
        ), rows)
    }

    @Test fun `a day with none recorded has no rows`() {
        assertTrue(NutrientDetailsText.dayRows(listOf(food(), food())).isEmpty())
        assertTrue(NutrientDetailsText.dayRows(emptyList()).isEmpty())
    }

    @Test fun `a recorded zero is a real zero and counts`() {
        val rows = NutrientDetailsText.dayRows(listOf(food(sodium = 0.0), food()), Locale.US)
        assertEquals(listOf(NutrientDetailsText.Row("Sodium", "0 mg · from 1 of 2 foods")), rows)
    }

    @Test fun `a form field reads blank as not recorded`() {
        assertNull(NutrientDetailsText.parse(""))
        assertNull(NutrientDetailsText.parse("  "))
        assertNull(NutrientDetailsText.parse("."))
        assertEquals(0.0, NutrientDetailsText.parse("0")!!, 0.0)
        assertEquals(2.5, NutrientDetailsText.parse(" 2.5 ")!!, 0.0)
        assertEquals("", NutrientDetailsText.field(null))
        assertEquals("540", NutrientDetailsText.field(540.0))
        assertEquals("3.1", NutrientDetailsText.field(3.1))
    }

    // MARK: - Recipes

    @Test fun `recipe nutrition carries them through JSON, and omits unknown ones`() {
        val nutrition = RecipeNutrition(calories = 546.0, proteinG = 40.0, carbsG = 42.0, fatG = 23.0, fiberG = 6.0,
            saturatedFatG = 4.5, sodiumMg = 410.0)
        val json = nutrition.toJson()
        assertFalse(json.has("sugarG"))
        assertEquals(nutrition, recipeNutritionFromJson(json))
        val recipe = Recipe(name = "Salmon", servings = 2.0, nutritionPerServing = nutrition)
        assertEquals(nutrition, recipeFromJson(recipe.toJson()).nutritionPerServing)
    }

    @Test fun `a planned meal logged by servings carries them per serving`() {
        val meal = PlannedMeal(recipeId = "r", servings = 2.0, recipeName = "Chilli",
            snapshotNutrition = RecipeNutrition(calories = 500.0, sugarG = 8.26, sodiumMg = 700.4))
        val entry = meal.toFoodEntry()!!
        assertEquals(8.3, entry.sugarG!!, 0.0)
        assertEquals(700.0, entry.sodiumMg!!, 0.0)
        assertNull(entry.saturatedFatG)
        assertEquals("servings multiplies them, as it does the macros", 1400.0,
            listOf(entry).nutrientTotals()!!.sodiumMg!!, 0.0)
    }

    @Test fun `a planned meal logged by weight scales them to the amount`() {
        val recipe = Recipe(name = "Chilli", servings = 4.0, totalWeightGrams = 1200.0,
            nutritionPerServing = RecipeNutrition(calories = 450.0, saturatedFatG = 6.0, sodiumMg = 800.0))
        val meal = PlannedMeal(recipeId = recipe.id, amountGrams = 450.0, recipeName = recipe.name,
            snapshotNutritionPerGram = recipe.nutritionPerGram)
        val entry = meal.toFoodEntry()!!
        // 4 servings x 6 g = 24 g over 1200 g; 450 g of it is 9 g. Sodium 3200 mg -> 1200 mg.
        assertEquals(9.0, entry.saturatedFatG!!, 0.0)
        assertEquals(1200.0, entry.sodiumMg!!, 0.0)
        assertNull(entry.sugarG)
    }

    @Test fun `the recipe editor keeps them with the macros and blank stays blank`() {
        val n = recipeNutritionFromText("450", "30", "", "", "", "6.5", "", "800", estimated = false)!!
        assertEquals(6.5, n.saturatedFatG!!, 0.0)
        assertNull(n.sugarG)
        assertEquals(800.0, n.sodiumMg!!, 0.0)
    }

    @Test fun `without a macro there is no nutrition to hold them, and no zeros invented`() {
        assertNull(recipeNutritionFromText("", "", "", "", "", "6.5", "12", "800", estimated = false))
    }

    @Test fun `a wire recipe keeps them`() {
        val o = JSONObject().put("name", "Imported").put("servings", 2)
            .put("nutritionPerServing", JSONObject().put("calories", 300).put("sugarG", 9.5))
        assertEquals(9.5, recipeFromWireJson(o).nutritionPerServing!!.sugarG!!, 0.0)
    }
}
