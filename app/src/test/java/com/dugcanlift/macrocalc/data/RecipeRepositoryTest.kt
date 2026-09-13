package com.dugcanlift.macrocalc.data

import com.dugcanlift.kit.RecipeNutrition
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecipeRepositoryTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    // See PlanImporterTest for why this reset is necessary: RecipeRepository
    // is a process-wide singleton that Robolectric's classloader reuses
    // across test methods.
    @Before
    fun resetSingleton() {
        val field = RecipeRepository::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    @Test
    fun `plan with amountGrams snapshots the recipe's nutritionPerGram and pins servings on toFoodEntry`() = runTest {
        val repo = RecipeRepository.get(context)
        val recipe = Recipe(
            name = "Chili",
            servings = 4.0,
            totalWeightGrams = 1200.0,
            nutritionPerServing = RecipeNutrition(
                calories = 300.0,
                proteinG = 20.0,
                carbsG = 30.0,
                fatG = 10.0,
                fiberG = 5.0
            )
        )
        repo.addRecipe(recipe)

        repo.plan(recipe, date = "2026-09-09", meal = Meal.DINNER, amountGrams = 300.0)

        val planned = repo.plan.value.single()
        assertEquals(300.0, planned.amountGrams!!, 0.0001)
        assertEquals(1.0, planned.snapshotNutritionPerGram!!.calories, 0.0001)

        val entry = planned.toFoodEntry()!!
        assertEquals(1.0, entry.servings, 0.0001)
        assertEquals(300.0, entry.amountGrams!!, 0.0001)
        assertEquals(300, entry.calories) // 1 kcal/g * 300g
    }

    @Test
    fun `plan without amountGrams leaves the gram snapshot null, matching legacy servings-based behavior`() = runTest {
        val repo = RecipeRepository.get(context)
        val recipe = Recipe(
            name = "Chili",
            servings = 4.0,
            nutritionPerServing = RecipeNutrition(calories = 300.0, proteinG = 20.0, carbsG = 30.0, fatG = 10.0, fiberG = 5.0)
        )
        repo.addRecipe(recipe)

        repo.plan(recipe, date = "2026-09-09", meal = Meal.DINNER, servings = 2.0)

        val planned = repo.plan.value.single()
        assertNull(planned.amountGrams)
        assertNull(planned.snapshotNutritionPerGram)
        assertTrue(planned.snapshotNutrition != null)
    }
}
