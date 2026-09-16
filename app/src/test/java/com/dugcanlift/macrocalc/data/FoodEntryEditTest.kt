package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodEntryEditTest {

    /** 175 g of 125 kcal / 22 P per 100 g, as the add form stores it: totals, servings 1. */
    private val chicken = FoodEntry(
        id = "8E1C4C2A-0000-4000-8000-000000000001",
        name = "Chicken breast",
        servings = 1.0,
        amountGrams = 175.0,
        calories = 219, proteinG = 39, fatG = 7, carbsG = 0, fiberG = 0,
        date = "2026-09-15",
        loggedAt = 1_789_000_000_000L,
        meal = "LUNCH"
    )

    private val legacy = FoodEntry(
        id = "legacy-1",
        name = "Protein bar",
        servings = 2.0,
        calories = 200, proteinG = 20, fatG = 7, carbsG = 22, fiberG = 3,
        date = "2026-09-10",
        loggedAt = 1_788_500_000_000L,
        meal = "SNACK"
    )

    private fun edit(entry: FoodEntry, unit: ServingUnit = ServingUnit.GRAMS) = FoodEntryEdit.from(entry, unit)

    @Test fun `opens a weight entry as its weight and totals`() {
        val form = edit(chicken)
        assertTrue(form.byWeight)
        assertEquals("175", form.amount)
        assertEquals("219", form.macros.calories)
        assertEquals(Meal.LUNCH, form.meal)
    }

    @Test fun `saving untouched gives back the same entry`() {
        assertEquals(chicken, edit(chicken).applyTo(chicken))
        assertEquals(legacy, edit(legacy).applyTo(legacy))
    }

    @Test fun `saving untouched in ounces keeps the exact grams`() {
        val saved = edit(chicken, ServingUnit.OUNCES).applyTo(chicken)!!
        assertEquals(175.0, saved.amountGrams!!, 0.0)
    }

    @Test fun `editing grams rescales the totals`() {
        val saved = edit(chicken).withAmount("350").applyTo(chicken)!!
        assertEquals(350.0, saved.amountGrams!!, 0.0)
        assertEquals(1.0, saved.servings, 0.0)
        assertEquals(438, saved.calories)
        assertEquals(78, saved.proteinG)
        assertEquals(14, saved.fatG)
        assertEquals(438, listOf(saved).totals().calories)
    }

    @Test fun `rescaling always starts from the typed basis, so it never drifts`() {
        // 219 kcal * 95/175 = 118.9; stepping through 1 g and back must not
        // compound the rounding of each step.
        val form = edit(chicken).withAmount("1").withAmount("95").withAmount("175")
        assertEquals("219", form.macros.calories)
        assertEquals("39", form.macros.proteinG)
    }

    @Test fun `editing in ounces converts to grams`() {
        val saved = edit(chicken, ServingUnit.OUNCES).withAmount("10").applyTo(chicken)!!
        assertEquals(283.495, saved.amountGrams!!, 1e-9)
        assertEquals(Math.round(219 * 283.495 / 175).toInt(), saved.calories)
    }

    @Test fun `switching unit keeps the weight and the macros`() {
        val form = edit(chicken).withUnit(ServingUnit.OUNCES)
        assertEquals("6.2", form.amount)
        assertEquals("219", form.macros.calories)
        assertEquals(175.0, form.applyTo(chicken)!!.amountGrams!!, 0.0)
    }

    @Test fun `a macro typed by hand is the total for the weight beside it`() {
        val form = edit(chicken)
            .withMacro(FoodEntryEdit.Field.CALORIES, "250")
            .withAmount("350")
        assertEquals("500", form.macros.calories)
        assertEquals("78", form.macros.proteinG)
    }

    @Test fun `the servings path multiplies per-serving macros and never gains a weight`() {
        val form = edit(legacy)
        assertTrue(!form.byWeight)
        assertEquals("2", form.amount)

        val saved = form.withAmount("1.5").applyTo(legacy)!!
        assertNull(saved.amountGrams)
        assertEquals(1.5, saved.servings, 0.0)
        assertEquals("per serving, unchanged", 200, saved.calories)
        assertEquals(300, saved.totalCalories)
        assertEquals(30, saved.totalProteinG)
    }

    @Test fun `changing the meal moves it`() {
        val saved = edit(chicken).withMeal(Meal.DINNER).applyTo(chicken)!!
        assertEquals("DINNER", saved.meal)
        assertEquals(Meal.DINNER, saved.mealOrDefault)
        val day = listOf(saved)
        assertTrue(day.none { it.mealOrDefault == Meal.LUNCH })
    }

    @Test fun `the id, date and logged time are preserved`() {
        val saved = edit(chicken).withName("  Grilled chicken ").withAmount("200")
            .withMeal(Meal.BREAKFAST).applyTo(chicken)!!
        assertEquals(chicken.id, saved.id)
        assertEquals(chicken.date, saved.date)
        assertEquals(chicken.loggedAt, saved.loggedAt)
        assertEquals("Grilled chicken", saved.name)
    }

    @Test fun `a blank macro stays blank when the weight changes`() {
        val form = edit(chicken).withMacro(FoodEntryEdit.Field.FIBER, "").withAmount("350")
        assertEquals("", form.macros.fiberG)
        assertEquals("438", form.macros.calories)
    }

    @Test fun `blank calories or amount refuses the save rather than writing zero`() {
        assertNull(edit(chicken).withMacro(FoodEntryEdit.Field.CALORIES, "").applyTo(chicken))
        assertNull(edit(chicken).withAmount("").applyTo(chicken))
        assertNull(edit(chicken).withAmount("0").applyTo(chicken))
        assertNull(edit(legacy).withAmount("").applyTo(legacy))
        assertNull(edit(chicken).withName("  ").applyTo(chicken))
    }

    @Test fun `macros typed while the amount was blank belong to the next weight`() {
        val form = edit(chicken)
            .withAmount("")
            .withMacro(FoodEntryEdit.Field.CALORIES, "300")
            .withAmount("200")
        assertEquals("300", form.macros.calories)
        assertEquals(300, form.applyTo(chicken)!!.calories)
    }
}
