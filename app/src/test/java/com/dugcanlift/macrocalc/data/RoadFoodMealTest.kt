package com.dugcanlift.macrocalc.data

import com.dugcanlift.macrocalc.MacroResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A port of LIFT iPhone's `RoadFoodMealTests`, case for case, plus the two
 * things only Android has: the total read against [RoadFood.entryFor]'s own
 * entries, and the saved form `rememberSaveable` keeps.
 */
class RoadFoodMealTest {
    private val sandwich = RoadFoodItem(id = "x-sandwich", name = "Sandwich", kcal = 420.4, proteinG = 31.6, fatG = 12.0, carbsG = 40.0)
    private val milk = RoadFoodItem(id = "x-milk", name = "1% Milk", kcal = 100.0, proteinG = 8.0, fatG = 2.5, carbsG = 12.0)
    // What cannot be logged differs by platform: the iPhone needs all four
    // macros, LIFT Android (and web) only a calorie figure (RoadFood.canLog).
    // So the unloggable item here is one with no calories.
    private val unlisted = RoadFoodItem(id = "x-secret", name = "Secret menu", kcal = null, proteinG = 10.0, fatG = 10.0, carbsG = 30.0)

    private val menu get() = listOf(sandwich, milk, unlisted)

    private fun left(kcal: Int) = Remaining(calories = kcal, proteinG = 60, eaten = DayTotals())

    @Test fun `items add up as they would be logged`() {
        val meal = RoadFoodMeal().add(sandwich).add(milk).add(milk)
        assertEquals(3, meal.itemCount)
        assertEquals(2, meal.count(milk.id))
        // Rounded per item, as each entry is logged: 420 + 100 + 100.
        assertEquals(RoadFoodMeal.Total(kcal = 620, proteinG = 48), meal.total(menu))
        assertEquals(listOf(sandwich.id, milk.id, milk.id), meal.items(menu).map { it.id })
    }

    /**
     * An item that can't be logged can't be in a total either: a sum missing one
     * item would be a smaller number passed off as the whole.
     */
    @Test fun `an item that cannot be logged cannot be added`() {
        val meal = RoadFoodMeal().add(unlisted)
        assertTrue(meal.isEmpty)
    }

    @Test fun `removing takes one and then the line`() {
        var meal = RoadFoodMeal().add(milk).add(milk)
        meal = meal.remove(milk)
        assertEquals(1, meal.count(milk.id))
        meal = meal.remove(milk)
        assertTrue(meal.isEmpty)
        meal = meal.remove(milk)
        assertTrue(meal.isEmpty)
    }

    @Test fun `the line says what is left`() {
        val meal = RoadFoodMeal().add(sandwich).add(milk)
        // The iPhone passes 900.7 and truncates it to 900; Remaining is whole
        // calories here already.
        assertEquals("2 items · 520 kcal · 40 g protein · fits, 380 kcal to spare", meal.line(menu, left(900)))
        assertEquals("2 items · 520 kcal · 40 g protein · 120 kcal over what is left", meal.line(menu, left(400)))
        assertEquals("2 items · 520 kcal · 40 g protein · uses exactly what is left", meal.line(menu, left(520)))
        assertEquals("2 items · 520 kcal · 40 g protein", meal.line(menu, null))
    }

    /** A menu that has lost an item since it was added logs nothing for it. */
    @Test fun `an item no longer on the menu is dropped`() {
        val meal = RoadFoodMeal().add(sandwich).add(milk)
        assertEquals(listOf(milk.id), meal.items(listOf(milk)).map { it.id })
    }

    // What only Android adds.

    @Test fun `the total is what the logged entries add up to, against the screen's own remaining`() {
        val meal = RoadFoodMeal().add(sandwich).add(milk).add(milk)
        val entries = meal.items(menu).map { RoadFood.entryFor(it, "Place", Meal.LUNCH, date = "2026-10-28") }
        val totals = entries.totals()
        assertEquals(meal.total(menu).kcal, totals.calories)
        assertEquals(meal.total(menu).proteinG, totals.proteinG)

        val goal = MacroResult(calories = 2200, proteinG = 180, fatG = 70, carbsG = 220, fiberG = 30)
        val eaten = listOf(FoodEntry(name = "Lunch", calories = 1560, proteinG = 125, fatG = 50, carbsG = 150))
        assertEquals(
            "3 items · 620 kcal · 48 g protein · fits, 20 kcal to spare",
            meal.line(menu, remainingFor(goal, eaten))
        )
    }

    @Test fun `one item is one item`() {
        assertEquals("1 item · 100 kcal · 8 g protein", RoadFoodMeal().add(milk).line(menu, null))
    }

    @Test fun `the saved form restores the same meal, an id holding a colon included`() {
        val odd = RoadFoodItem(id = "chain:odd", name = "Odd", kcal = 10.0)
        val meal = RoadFoodMeal().add(milk).add(odd).add(milk)
        assertEquals(meal, RoadFoodMeal.fromSaved(meal.toSaved()))
        assertEquals(RoadFoodMeal(), RoadFoodMeal.fromSaved(listOf("junk", "0:x", ":y")))
    }
}
