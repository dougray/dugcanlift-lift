package com.dugcanlift.macrocalc.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Realistic COOK data for screenshots and manual testing.
 *
 * Debug only — callers must gate on `BuildConfig.DEBUG`. This is not COACH's
 * "Load a sample client", which is a real shipped feature because a trainer
 * needs to see a roster working before asking a client to send anything. Nobody
 * needs fake recipes in a shipping build; they have their own.
 *
 * The catalogue and the schedule match `CookSampleData` in the iOS build, so a
 * screenshot taken on either platform shows the same week.
 */
object CookSampleData {

    suspend fun load(repo: RecipeRepository) {
        // Wiped and rebuilt on each call so repeated runs produce identical
        // screenshots.
        for (recipe in repo.recipes.value) repo.deleteRecipe(recipe.id)
        for (planned in repo.plan.value) repo.unplan(planned.id)
        repo.clearChecked()

        val built = catalogue.map { spec ->
            val recipe = Recipe(
                name = spec.name,
                servings = spec.servings,
                ingredients = spec.ingredients.map { IngredientParser.parse(it) },
                steps = spec.steps,
                nutritionPerServing = spec.nutrition
            )
            repo.addRecipe(recipe)
            recipe
        }

        val days = (0 until 7).map { offset ->
            val day = Calendar.getInstance()
            day.add(Calendar.DAY_OF_YEAR, offset)
            DAY_KEY.format(day.time)
        }

        // Not every slot filled: a real plan never is, and a screenshot of a
        // perfectly full week looks like a mock-up.
        val schedule = listOf(
            Triple(0, Meal.BREAKFAST, 0) to 1.0,
            Triple(0, Meal.DINNER, 2) to 2.0,
            Triple(1, Meal.BREAKFAST, 0) to 1.0,
            Triple(1, Meal.LUNCH, 1) to 1.0,
            Triple(1, Meal.DINNER, 3) to 2.0,
            Triple(2, Meal.LUNCH, 1) to 1.0,
            Triple(2, Meal.DINNER, 2) to 2.0,
            Triple(3, Meal.BREAKFAST, 4) to 1.0,
            Triple(3, Meal.DINNER, 3) to 2.0
        )

        for ((slot, servings) in schedule) {
            val (dayOffset, meal, recipeIndex) = slot
            if (recipeIndex >= built.size || dayOffset >= days.size) continue
            repo.plan(built[recipeIndex], days[dayOffset], meal, servings)
        }
    }

    private data class Spec(
        val name: String,
        val servings: Double,
        val nutrition: RecipeNutrition,
        val ingredients: List<String>,
        val steps: List<String>
    )

    private val catalogue = listOf(
        Spec(
            name = "Peanut Butter Banana Creami",
            servings = 1.0,
            nutrition = RecipeNutrition(342.0, 31.0, 38.0, 8.0, 4.0),
            ingredients = listOf(
                "1 banana",
                "30 g whey protein",
                "1 tbsp peanut butter powder",
                "240 ml skim milk",
                "1 pinch salt"
            ),
            steps = listOf(
                "Blend everything until smooth.",
                "Freeze in the pint 24 hours.",
                "Spin on Lite Ice Cream, then respin with a splash of milk."
            )
        ),
        Spec(
            name = "Chicken Rice Bowl",
            servings = 2.0,
            nutrition = RecipeNutrition(512.0, 44.0, 58.0, 11.0, 5.0),
            ingredients = listOf(
                "400 g chicken thigh",
                "300 g jasmine rice",
                "2 tbsp soy sauce",
                "1 tbsp sesame oil",
                "2 cloves garlic",
                "200 g tenderstem broccoli"
            ),
            steps = listOf(
                "Rice on first.",
                "Sear the thighs hard, then garlic and soy off the heat.",
                "Steam the broccoli over the rice for the last four minutes."
            )
        ),
        Spec(
            name = "Beef Chilli",
            servings = 4.0,
            nutrition = RecipeNutrition(438.0, 36.0, 31.0, 19.0, 9.0),
            ingredients = listOf(
                "500 g lean beef mince",
                "1 can kidney beans",
                "1 can chopped tomatoes",
                "1 onion",
                "2 cloves garlic",
                "1 tbsp smoked paprika",
                "2 tsp cumin"
            ),
            steps = listOf(
                "Brown the mince properly — do it in two batches.",
                "Onion, garlic, spices, then the tins.",
                "Forty minutes with the lid off."
            )
        ),
        Spec(
            name = "Overnight Oats",
            servings = 1.0,
            nutrition = RecipeNutrition(388.0, 28.0, 47.0, 10.0, 7.0),
            ingredients = listOf(
                "80 g rolled oats",
                "25 g whey protein",
                "200 ml milk",
                "1 tbsp chia seeds",
                "100 g blueberries"
            ),
            steps = listOf("Stir, jar, fridge overnight.")
        ),
        Spec(
            name = "Salmon and Sweet Potato",
            servings = 2.0,
            nutrition = RecipeNutrition(546.0, 40.0, 42.0, 23.0, 6.0),
            ingredients = listOf(
                "2 salmon fillets",
                "500 g sweet potato",
                "1 tbsp olive oil",
                "1 lemon",
                "200 g green beans"
            ),
            steps = listOf(
                "Sweet potato in at 200C for 35 minutes.",
                "Salmon joins for the last 12.",
                "Beans in the last four."
            )
        )
    )

    private val DAY_KEY: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
}
