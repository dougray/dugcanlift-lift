package com.dugcanlift.macrocalc

import com.dugcanlift.macrocalc.data.IngredientParser
import com.dugcanlift.macrocalc.data.Meal
import com.dugcanlift.macrocalc.data.PlannedMeal
import com.dugcanlift.macrocalc.data.Recipe
import com.dugcanlift.macrocalc.data.ShoppingList
import com.dugcanlift.macrocalc.data.shoppingAmountLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors `IngredientParserTests` in the iOS build. The two parsers must agree:
 * a recipe written on one phone can reach the other through the shared wire
 * format, and a quantity that parses differently on each side is a bug nobody
 * would think to look for.
 */
class IngredientParserTest {

    @Test
    fun `parses quantity unit and item`() {
        val parsed = IngredientParser.parse("2 tbsp olive oil")
        assertEquals(2.0, parsed.qty!!, 0.0001)
        assertEquals("tbsp", parsed.unit)
        assertEquals("olive oil", parsed.item)
    }

    @Test
    fun `grams are resolved for macro lookup`() {
        assertEquals(400.0, IngredientParser.parse("400 g chicken thigh").grams!!, 0.0001)
        assertNull(
            "only grams resolve to a mass",
            IngredientParser.parse("2 tbsp olive oil").grams
        )
    }

    @Test
    fun `counts get the sentinel not a unit`() {
        val parsed = IngredientParser.parse("2 eggs")
        assertEquals(2.0, parsed.qty!!, 0.0001)
        assertEquals("eggs", parsed.item)
        assertEquals(IngredientParser.COUNT_UNIT, parsed.unit)
    }

    /**
     * The sentinel must be something nobody can type, or a real ingredient
     * measured in it would silently be treated as a count.
     */
    @Test
    fun `sentinel cannot collide with typed text`() {
        assertTrue(IngredientParser.COUNT_UNIT.contains('\u0000'))
    }

    @Test
    fun `counts print bare`() {
        val counts = mapOf(IngredientParser.COUNT_UNIT to 2.0)
        assertEquals("2", counts.shoppingAmountLabel())

        val grams = mapOf("g" to 400.0)
        assertEquals("400 g", grams.shoppingAmountLabel())
    }

    @Test
    fun `fractions`() {
        assertEquals(0.5, IngredientParser.parse("1/2 cup rice").qty!!, 0.0001)
        assertEquals(1.5, IngredientParser.parse("1 1/2 cups rice").qty!!, 0.0001)
        assertEquals(0.5, IngredientParser.parse("0.5 kg beef").qty!!, 0.0001)
    }

    @Test
    fun `unparseable lines keep their raw text and nothing else`() {
        val parsed = IngredientParser.parse("salt to taste")
        assertEquals("salt to taste", parsed.rawText)
        assertNull("a line that did not parse must not invent an item", parsed.item)
        assertNull(parsed.qty)
    }

    @Test
    fun `raw text is kept even when the parse succeeds`() {
        assertEquals("2 tbsp olive oil", IngredientParser.parse("2 tbsp olive oil").rawText)
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
}
