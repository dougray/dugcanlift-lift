package com.dugcanlift.macrocalc

import com.dugcanlift.kit.IngredientParser
import com.dugcanlift.macrocalc.data.Meal
import com.dugcanlift.macrocalc.data.PlannedMeal
import com.dugcanlift.macrocalc.data.Recipe
import com.dugcanlift.macrocalc.data.ShoppingList
import com.dugcanlift.macrocalc.data.cookDisplay
import com.dugcanlift.macrocalc.data.shoppingAmountLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The parser itself is tested in the kit (`dugcanlift-kit-android`). These
 * three tests are LIFT-coupled — they exercise `shoppingAmountLabel` and
 * `ShoppingList`, which stay here — so they didn't move with it.
 */
class IngredientParserTest {

    @Test
    fun `counts print bare`() {
        val counts = mapOf(IngredientParser.COUNT_UNIT to 2.0)
        assertEquals("2", counts.shoppingAmountLabel())

        val grams = mapOf("g" to 400.0)
        assertEquals("400 g", grams.shoppingAmountLabel())
    }

    /** Two cloves of garlic must never be added to two cups of anything. */
    @Test
    fun `counts and units stay in separate buckets`() {
        val recipe = Recipe(
            name = "Test",
            servings = 1.0,
            ingredients = listOf(
                IngredientParser.parse("2 cloves garlic"),
                IngredientParser.parse("30 g garlic")
            )
        )
        val meal = PlannedMeal(
            recipeId = recipe.id,
            recipeName = recipe.name,
            meal = Meal.DINNER.name
        )

        val lines = ShoppingList.build(listOf(meal), mapOf(recipe.id to recipe))

        assertEquals("same item name, one line", 1, lines.size)
        assertEquals("but two units, kept apart", 2, lines[0].amounts.size)
        assertEquals(2.0, lines[0].amounts["cloves"]!!, 0.0001)
        assertEquals(30.0, lines[0].amounts["g"]!!, 0.0001)
    }

    /**
     * Planning half a four-serving recipe must buy half the ingredients.
     */
    @Test
    fun `amounts scale by servings against the recipe's own count`() {
        val recipe = Recipe(
            name = "Chilli",
            servings = 4.0,
            ingredients = listOf(IngredientParser.parse("500 g beef mince"))
        )
        val meal = PlannedMeal(
            recipeId = recipe.id,
            recipeName = recipe.name,
            servings = 2.0,
            meal = Meal.DINNER.name
        )

        val lines = ShoppingList.build(listOf(meal), mapOf(recipe.id to recipe))
        assertEquals(250.0, lines[0].amounts["g"]!!, 0.0001)
    }

    /**
     * One serving planned from a three-serving recipe. The amounts are exact
     * thirds and stay that way; only the label rounds, as LIFT web's `trimNum`
     * does. It printed "1.6666666666666665" and "416.66666666666663 g".
     */
    @Test
    fun `an uneven split prints two decimals and keeps the exact amount`() {
        val recipe = Recipe(
            name = "Frittata",
            servings = 3.0,
            ingredients = listOf(
                IngredientParser.parse("5 eggs"),
                IngredientParser.parse("1250 g potatoes"),
                IngredientParser.parse("300 g spinach")
            )
        )
        val meal = PlannedMeal(recipeId = recipe.id, recipeName = recipe.name, servings = 1.0)

        val lines = ShoppingList.build(listOf(meal), mapOf(recipe.id to recipe)).associateBy { it.key }

        assertEquals(5.0 * (1.0 / 3.0), lines.getValue("eggs").amounts[IngredientParser.COUNT_UNIT]!!, 0.0)
        assertEquals(1250.0 * (1.0 / 3.0), lines.getValue("potatoes").amounts["g"]!!, 0.0)
        assertEquals("1.67", lines.getValue("eggs").amounts.shoppingAmountLabel())
        assertEquals("416.67 g", lines.getValue("potatoes").amounts.shoppingAmountLabel())
        assertEquals("100 g", lines.getValue("spinach").amounts.shoppingAmountLabel())
    }

    /** LIFT web's `trimNum`: integers bare, otherwise at most two decimals. */
    @Test
    fun `cook amounts format like LIFT web`() {
        assertEquals("2", 2.0.cookDisplay())
        assertEquals("0", 0.0.cookDisplay())
        assertEquals("0", 0.001.cookDisplay())
        assertEquals("0.5", 0.5.cookDisplay())
        assertEquals("0.33", (1.0 / 3.0).cookDisplay())
        assertEquals("3", 2.999.cookDisplay())
        assertEquals("1234567.5", 1234567.5.cookDisplay())
        assertEquals("437.5", (175.0 * 2.5).cookDisplay())
        assertEquals("2 cloves + 30.5 g",
            mapOf("g" to 30.5, "cloves" to 2.0).shoppingAmountLabel())
    }
}
