package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeTest {

    private val perServing = RecipeNutrition(
        calories = 300.0,
        proteinG = 20.0,
        carbsG = 30.0,
        fatG = 10.0,
        fiberG = 5.0
    )

    @Test
    fun `totalNutrition scales nutritionPerServing by servings`() {
        val recipe = Recipe(name = "Chili", servings = 4.0, nutritionPerServing = perServing)
        val total = recipe.totalNutrition!!
        assertEquals(1200.0, total.calories, 0.0001)
        assertEquals(80.0, total.proteinG, 0.0001)
        assertEquals(120.0, total.carbsG, 0.0001)
        assertEquals(40.0, total.fatG, 0.0001)
        assertEquals(20.0, total.fiberG, 0.0001)
    }

    @Test
    fun `nutritionPerGram divides totalNutrition by totalWeightGrams`() {
        val recipe = Recipe(
            name = "Chili",
            servings = 4.0,
            totalWeightGrams = 1200.0,
            nutritionPerServing = perServing
        )
        val perGram = recipe.nutritionPerGram!!
        assertEquals(1.0, perGram.calories, 0.0001)
        assertEquals(80.0 / 1200.0, perGram.proteinG, 0.0001)
        assertEquals(120.0 / 1200.0, perGram.carbsG, 0.0001)
        assertEquals(40.0 / 1200.0, perGram.fatG, 0.0001)
        assertEquals(20.0 / 1200.0, perGram.fiberG, 0.0001)
    }

    @Test
    fun `nutritionPerGram is null without totalWeightGrams`() {
        val recipe = Recipe(name = "Chili", servings = 4.0, nutritionPerServing = perServing)
        assertNull(recipe.nutritionPerGram)
    }

    @Test
    fun `nutritionPerGram is null without nutritionPerServing`() {
        val recipe = Recipe(name = "Chili", servings = 4.0, totalWeightGrams = 1200.0)
        assertNull(recipe.nutritionPerGram)
    }

    @Test
    fun `totalWeightGrams round trips through JSON when present`() {
        val recipe = Recipe(name = "Chili", totalWeightGrams = 950.5)
        val restored = recipeFromJson(recipe.toJson())
        assertEquals(950.5, restored.totalWeightGrams!!, 0.0001)
    }

    @Test
    fun `totalWeightGrams is null after round trip when never set`() {
        val recipe = Recipe(name = "Chili")
        val restored = recipeFromJson(recipe.toJson())
        assertNull(restored.totalWeightGrams)
    }

    @Test
    fun `toFoodEntry uses the gram branch and pins servings to 1 when amountGrams and snapshotNutritionPerGram are set`() {
        val perGram = RecipeNutrition(calories = 2.0, proteinG = 0.2, carbsG = 0.3, fatG = 0.1, fiberG = 0.02)
        val plannedMeal = PlannedMeal(
            recipeId = "r1",
            servings = 3.0, // must be ignored in favor of the gram branch
            amountGrams = 100.0,
            recipeName = "Chili",
            snapshotNutritionPerGram = perGram
        )
        val entry = plannedMeal.toFoodEntry()!!
        assertEquals(1.0, entry.servings, 0.0001)
        assertEquals(100.0, entry.amountGrams!!, 0.0001)
        assertEquals(200, entry.calories)
        assertEquals(20, entry.proteinG)
        assertEquals(30, entry.carbsG)
        assertEquals(10, entry.fatG)
        assertEquals(2, entry.fiberG)
    }

    @Test
    fun `toFoodEntry falls back to the per-serving branch when there is no gram snapshot`() {
        val plannedMeal = PlannedMeal(
            recipeId = "r1",
            servings = 2.0,
            recipeName = "Chili",
            snapshotNutrition = perServing
        )
        val entry = plannedMeal.toFoodEntry()!!
        assertEquals(2.0, entry.servings, 0.0001)
        assertNull(entry.amountGrams)
        assertEquals(300, entry.calories)
        assertEquals(600, entry.totalCalories)
    }

    @Test
    fun `PlannedMeal amountGrams and snapshotNutritionPerGram round trip through JSON`() {
        val perGram = RecipeNutrition(calories = 2.0, proteinG = 0.2, carbsG = 0.3, fatG = 0.1, fiberG = 0.02)
        val plannedMeal = PlannedMeal(
            recipeId = "r1",
            amountGrams = 175.0,
            recipeName = "Chili",
            snapshotNutritionPerGram = perGram
        )
        val restored = plannedMealFromJson(plannedMeal.toJson())
        assertEquals(175.0, restored.amountGrams!!, 0.0001)
        assertEquals(2.0, restored.snapshotNutritionPerGram!!.calories, 0.0001)
    }

    @Test
    fun `PlannedMeal amountGrams and snapshotNutritionPerGram are null after round trip when never set`() {
        val plannedMeal = PlannedMeal(recipeId = "r1", recipeName = "Chili", snapshotNutrition = perServing)
        val restored = plannedMealFromJson(plannedMeal.toJson())
        assertNull(restored.amountGrams)
        assertNull(restored.snapshotNutritionPerGram)
    }
}
