package com.dugcanlift.macrocalc.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * COOK — recipes, meal planning and the shopping list derived from them.
 *
 * COOK is to eating what COACH is to training: a named surface spanning an
 * in-app section, a page on the site, and one written contract every client
 * shares. See `CoachShare.kt` for the pattern.
 *
 * The wire format these are built from is defined once and shared with the iOS
 * app and the public library, the way `coach/SHARE-FORMAT.md` already is. It
 * currently lives at `homelab/cook-ingest/schema/recipe.schema.json` and
 * belongs in `dugcanlift-site/cook/` next to COACH's.
 *
 * Nutrition on the wire is always PER SERVING, which is also how
 * [FoodEntry] stores it here — scaling happens in `totalCalories` and friends.
 *
 * Same storage approach as the rest of `data/`: plain data classes and
 * org.json, no Room, no annotation processing. And the same forward-compatible
 * reader rule — every field falls back to a default, so a file written by an
 * older version still loads.
 */

/**
 * Macros for one serving. Deliberately nullable at the [Recipe] level: an
 * estimate of the macros of a hand-written recipe is a guess, and `null` says
 * so where zeros would quietly enter someone's daily total as fact.
 *
 * Doubles here, not Int as in [FoodEntry]. The wire format is unrounded, and
 * rounding once at log time beats rounding at every scale.
 */
data class RecipeNutrition(
    val calories: Double = 0.0,
    val proteinG: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
    val fiberG: Double = 0.0,
    /** True when derived by an LLM rather than a food database. Show it. */
    val estimated: Boolean = false
) {
    fun scaled(factor: Double) = copy(
        calories = calories * factor,
        proteinG = proteinG * factor,
        carbsG = carbsG * factor,
        fatG = fatG * factor,
        fiberG = fiberG * factor
    )
}

/**
 * One line of a recipe's ingredients.
 *
 * [rawText] is always populated, even when the parse succeeded. It is what the
 * person checks the parse against, and what the shopping list falls back to
 * when [qty] and [unit] could not be resolved.
 */
data class RecipeIngredient(
    val rawText: String,
    val item: String? = null,
    val qty: Double? = null,
    val unit: String? = null,
    /** Resolved mass, when a conversion was possible. Drives macro lookup. */
    val grams: Double? = null,
    val optional: Boolean = false,
    val note: String? = null
) {
    /** The parse when it worked, the raw text when it didn't. */
    val displayText: String
        get() {
            val name = item?.takeIf { it.isNotBlank() } ?: return rawText
            val amount = listOfNotNull(qty?.trimZeros(), unit).joinToString(" ")
            return if (amount.isBlank()) name else "$amount $name"
        }
}

data class Recipe(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val servings: Double = 1.0,
    val ingredients: List<RecipeIngredient> = emptyList(),
    val steps: List<String> = emptyList(),
    /** Per serving. Null means unknown — not zero. */
    val nutritionPerServing: RecipeNutrition? = null,
    /** The post this came from. Null for a hand-entered recipe. */
    val sourceUrl: String? = null,
    val sourceAuthor: String? = null,
    /**
     * Caption, transcript and OCR text merged, kept verbatim.
     *
     * Not a nicety. An import is shown beside its source text so a misheard
     * quantity is visible before it is saved. Never auto-log an import, and
     * never drop this to save space.
     */
    val sourceTranscript: String? = null,
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val importedAt: Long = System.currentTimeMillis()
) {
    val wasImported: Boolean get() = sourceUrl != null

    /** Servings can never be zero; a shopping list divides by it. */
    val safeServings: Double get() = if (servings > 0) servings else 1.0
}

/**
 * A recipe placed on a future day. Not a log entry — nothing is eaten yet.
 *
 * Logging one writes a separate [FoodEntry] carrying its own copy of the
 * numbers. Editing a recipe in March must not rewrite what January's log says
 * was eaten.
 */
data class PlannedMeal(
    val id: String = UUID.randomUUID().toString(),
    val recipeId: String,
    /** "yyyy-MM-dd", same key format the food log uses. */
    val date: String = todayKey(),
    val meal: String = Meal.DINNER.name,
    val servings: Double = 1.0,
    // Snapshot fields, copied at plan time for the same reason FoodEntry
    // snapshots reference data.
    val recipeName: String = "",
    val snapshotNutrition: RecipeNutrition? = null,
    /** Set once turned into a real log entry, so logging twice is visible. */
    val loggedFoodEntryId: String? = null
) {
    val isLogged: Boolean get() = loggedFoodEntryId != null

    val mealOrDefault: Meal
        get() = Meal.entries.firstOrNull { it.name == meal } ?: Meal.DINNER

    /**
     * Builds the log entry for this planned meal, or null when the recipe never
     * had macros. Better no entry than a zero-calorie dinner in the day total.
     *
     * [FoodEntry] holds per-serving macros and scales by `servings`, so the
     * snapshot is passed through unscaled and `servings` carries the multiple.
     */
    fun toFoodEntry(): FoodEntry? {
        val perServing = snapshotNutrition ?: return null
        return FoodEntry(
            name = recipeName,
            servings = servings,
            calories = perServing.calories.roundToIntSafe(),
            proteinG = perServing.proteinG.roundToIntSafe(),
            fatG = perServing.fatG.roundToIntSafe(),
            carbsG = perServing.carbsG.roundToIntSafe(),
            fiberG = perServing.fiberG.roundToIntSafe(),
            date = date,
            meal = meal
        )
    }
}

/* ---------- shopping list ---------- */

/**
 * One aggregated line. Derived from the plan, never stored — only the tick-off
 * state is persisted, so re-deriving the list doesn't empty the basket.
 *
 * [amounts] is a map because units that cannot be combined must stay apart:
 * "2 cloves" and "30 g" of garlic are not addable.
 */
data class ShoppingListLine(
    val key: String,
    val displayName: String,
    val amounts: Map<String, Double> = emptyMap(),
    /** Ingredients that never parsed, shown as-is so nothing is dropped. */
    val unparsed: List<String> = emptyList()
)

object ShoppingList {

    /**
     * Aggregates ingredients across planned meals.
     *
     * Amounts scale by each meal's servings against the recipe's own serving
     * count, so planning two servings of a four-serving recipe buys half.
     */
    fun build(meals: List<PlannedMeal>, recipes: Map<String, Recipe>): List<ShoppingListLine> {
        val amounts = mutableMapOf<String, MutableMap<String, Double>>()
        val names = mutableMapOf<String, String>()
        val unparsed = mutableMapOf<String, MutableList<String>>()

        for (meal in meals) {
            val recipe = recipes[meal.recipeId] ?: continue
            val factor = meal.servings / recipe.safeServings

            for (ingredient in recipe.ingredients) {
                if (ingredient.optional) continue
                val name = ingredient.item?.takeIf { it.isNotBlank() } ?: ingredient.rawText
                val key = name.trim().lowercase()
                if (key.isEmpty()) continue
                names.putIfAbsent(key, name)

                val qty = ingredient.qty
                val unit = ingredient.unit
                if (qty != null && unit != null) {
                    val byUnit = amounts.getOrPut(key) { mutableMapOf() }
                    byUnit[unit] = (byUnit[unit] ?: 0.0) + qty * factor
                } else {
                    unparsed.getOrPut(key) { mutableListOf() }.add(ingredient.rawText)
                }
            }
        }

        return names.keys.sorted().map { key ->
            ShoppingListLine(
                key = key,
                displayName = names[key] ?: key,
                amounts = amounts[key]?.toMap() ?: emptyMap(),
                unparsed = unparsed[key]?.toList() ?: emptyList()
            )
        }
    }
}

/* ---------- helpers ---------- */

internal fun Double.trimZeros(): String =
    if (this == Math.floor(this) && !isInfinite()) toInt().toString() else toString()

internal fun Double.roundToIntSafe(): Int =
    if (isNaN() || isInfinite()) 0 else Math.round(this).toInt()

/* ---------- JSON ---------- */

internal fun RecipeNutrition.toJson(): JSONObject = JSONObject().apply {
    put("calories", calories)
    put("proteinG", proteinG)
    put("carbsG", carbsG)
    put("fatG", fatG)
    put("fiberG", fiberG)
    put("estimated", estimated)
}

internal fun recipeNutritionFromJson(o: JSONObject?): RecipeNutrition? {
    if (o == null) return null
    return RecipeNutrition(
        calories = o.optDouble("calories", 0.0),
        proteinG = o.optDouble("proteinG", 0.0),
        carbsG = o.optDouble("carbsG", 0.0),
        fatG = o.optDouble("fatG", 0.0),
        fiberG = o.optDouble("fiberG", 0.0),
        estimated = o.optBoolean("estimated", false)
    )
}

internal fun RecipeIngredient.toJson(): JSONObject = JSONObject().apply {
    put("rawText", rawText)
    item?.let { put("item", it) }
    qty?.let { put("qty", it) }
    unit?.let { put("unit", it) }
    grams?.let { put("grams", it) }
    put("optional", optional)
    note?.let { put("note", it) }
}

internal fun recipeIngredientFromJson(o: JSONObject): RecipeIngredient = RecipeIngredient(
    rawText = o.optString("rawText", ""),
    item = o.optStringOrNull("item"),
    qty = o.optDoubleOrNull("qty"),
    unit = o.optStringOrNull("unit"),
    grams = o.optDoubleOrNull("grams"),
    optional = o.optBoolean("optional", false),
    note = o.optStringOrNull("note")
)

internal fun Recipe.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("servings", servings)
    put("ingredients", JSONArray().also { a -> ingredients.forEach { a.put(it.toJson()) } })
    put("steps", JSONArray().also { a -> steps.forEach { a.put(it) } })
    nutritionPerServing?.let { put("nutritionPerServing", it.toJson()) }
    sourceUrl?.let { put("sourceUrl", it) }
    sourceAuthor?.let { put("sourceAuthor", it) }
    sourceTranscript?.let { put("sourceTranscript", it) }
    prepMinutes?.let { put("prepMinutes", it) }
    cookMinutes?.let { put("cookMinutes", it) }
    put("importedAt", importedAt)
}

internal fun recipeFromJson(o: JSONObject): Recipe = Recipe(
    id = o.optString("id", UUID.randomUUID().toString()),
    name = o.optString("name", ""),
    servings = o.optDouble("servings", 1.0),
    ingredients = o.optJSONArray("ingredients").mapObjects(::recipeIngredientFromJson),
    steps = o.optJSONArray("steps").mapStrings(),
    nutritionPerServing = recipeNutritionFromJson(o.optJSONObject("nutritionPerServing")),
    sourceUrl = o.optStringOrNull("sourceUrl"),
    sourceAuthor = o.optStringOrNull("sourceAuthor"),
    sourceTranscript = o.optStringOrNull("sourceTranscript"),
    prepMinutes = o.optIntOrNull("prepMinutes"),
    cookMinutes = o.optIntOrNull("cookMinutes"),
    importedAt = o.optLong("importedAt", System.currentTimeMillis())
)

internal fun PlannedMeal.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("recipeId", recipeId)
    put("date", date)
    put("meal", meal)
    put("servings", servings)
    put("recipeName", recipeName)
    snapshotNutrition?.let { put("snapshotNutrition", it.toJson()) }
    loggedFoodEntryId?.let { put("loggedFoodEntryId", it) }
}

internal fun plannedMealFromJson(o: JSONObject): PlannedMeal = PlannedMeal(
    id = o.optString("id", UUID.randomUUID().toString()),
    recipeId = o.optString("recipeId", ""),
    date = o.optString("date", todayKey()),
    meal = o.optString("meal", Meal.DINNER.name),
    servings = o.optDouble("servings", 1.0),
    recipeName = o.optString("recipeName", ""),
    snapshotNutrition = recipeNutritionFromJson(o.optJSONObject("snapshotNutrition")),
    loggedFoodEntryId = o.optStringOrNull("loggedFoodEntryId")
)

/**
 * Parses the shared wire format from `recipe.schema.json`.
 *
 * Kept separate from [recipeFromJson], which reads this app's own stored form.
 * The two look similar today and must be free to drift: one is a contract with
 * the ingest service, the other is a private file format.
 */
fun recipeFromWireJson(o: JSONObject): Recipe = Recipe(
    name = o.optString("name", "").ifBlank { "Untitled recipe" },
    servings = o.optDouble("servings", 1.0),
    ingredients = o.optJSONArray("ingredients").mapObjects(::recipeIngredientFromJson),
    steps = o.optJSONArray("steps").mapStrings(),
    // Absent means unknown. Do not substitute zeros.
    nutritionPerServing = recipeNutritionFromJson(o.optJSONObject("nutritionPerServing")),
    sourceUrl = o.optStringOrNull("sourceURL"),
    sourceAuthor = o.optStringOrNull("sourceAuthor"),
    sourceTranscript = o.optStringOrNull("sourceTranscript"),
    prepMinutes = o.optIntOrNull("prepMinutes"),
    cookMinutes = o.optIntOrNull("cookMinutes")
)

/* ---------- json helpers ---------- */

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key, "").takeIf { it.isNotEmpty() }

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key)) null else if (has(key)) optInt(key) else null

private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }.map(transform)
}

private fun JSONArray?.mapStrings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it, "") }.filter { it.isNotBlank() }
}
