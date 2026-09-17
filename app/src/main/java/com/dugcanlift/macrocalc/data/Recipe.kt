package com.dugcanlift.macrocalc.data

import com.dugcanlift.kit.IngredientParser
import com.dugcanlift.kit.RecipeIngredient
import com.dugcanlift.kit.RecipeNutrition
import com.dugcanlift.kit.trimZeros
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

data class Recipe(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val servings: Double = 1.0,
    /** Total finished weight of the whole dish, in grams. Null until
     * filled in via the recipe editor — neither this field nor any
     * reliable per-ingredient gram total existed before this feature
     * (see IngredientParser's own doc comment: only ingredients already
     * written in grams resolve to a gram value). */
    val totalWeightGrams: Double? = null,
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

    /** Total nutrition for the whole finished dish. Null if
     * nutritionPerServing was never entered/estimated. */
    val totalNutrition: RecipeNutrition?
        get() = nutritionPerServing?.scaled(servings)

    /** Nutrition per gram of the finished dish. Null until both
     * totalNutrition and totalWeightGrams exist. */
    val nutritionPerGram: RecipeNutrition?
        get() {
            val total = totalNutrition ?: return null
            val weight = totalWeightGrams ?: return null
            if (weight <= 0) return null
            return total.scaled(1.0 / weight)
        }
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
    /** Grams of the dish this instance represents, when planned/logged
     * via the gram-based flow. Null for legacy servings-based instances. */
    val amountGrams: Double? = null,
    // Snapshot fields, copied at plan time for the same reason FoodEntry
    // snapshots reference data.
    val recipeName: String = "",
    val snapshotNutrition: RecipeNutrition? = null,
    /** recipe.nutritionPerGram captured once at creation time — matches
     * snapshotNutrition's own snapshot-on-write convention, so a later
     * edit to the recipe never changes an already-planned/logged meal. */
    val snapshotNutritionPerGram: RecipeNutrition? = null,
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
     * When [amountGrams]/[snapshotNutritionPerGram] are both set (the
     * gram-based flow), the result is pre-scaled to that amount and
     * `servings` is pinned to 1.0 — see [FoodEntry.amountGrams]. Otherwise
     * [FoodEntry] holds per-serving macros and scales by `servings`, so the
     * snapshot is passed through unscaled and `servings` carries the multiple.
     */
    fun toFoodEntry(): FoodEntry? {
        val grams = amountGrams
        val perGram = snapshotNutritionPerGram
        if (grams != null && perGram != null) {
            val scaled = perGram.scaled(grams)
            val details = NutrientDetailsText.scaled(perGram.details, grams)
            return FoodEntry(
                name = recipeName,
                servings = 1.0,
                amountGrams = grams,
                calories = scaled.calories.roundToIntSafe(),
                proteinG = scaled.proteinG.roundToIntSafe(),
                fatG = scaled.fatG.roundToIntSafe(),
                carbsG = scaled.carbsG.roundToIntSafe(),
                fiberG = scaled.fiberG.roundToIntSafe(),
                date = date,
                meal = meal,
                saturatedFatG = details.saturatedFatG,
                sugarG = details.sugarG,
                sodiumMg = details.sodiumMg
            )
        }
        val perServing = snapshotNutrition ?: return null
        // Per serving, like the macros beside them; `servings` multiplies both.
        val details = NutrientDetailsText.scaled(perServing.details, 1.0)
        return FoodEntry(
            name = recipeName,
            servings = servings,
            calories = perServing.calories.roundToIntSafe(),
            proteinG = perServing.proteinG.roundToIntSafe(),
            fatG = perServing.fatG.roundToIntSafe(),
            carbsG = perServing.carbsG.roundToIntSafe(),
            fiberG = perServing.fiberG.roundToIntSafe(),
            date = date,
            meal = meal,
            saturatedFatG = details.saturatedFatG,
            sugarG = details.sugarG,
            sodiumMg = details.sodiumMg
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

/**
 * Renders an aggregated amount map for display.
 *
 * Counts print bare — "2", not "2 x banana".
 */
fun Map<String, Double>.shoppingAmountLabel(): String =
    entries
        .sortedBy { it.key }
        .joinToString(" + ") { (unit, value) ->
            if (unit == IngredientParser.COUNT_UNIT) value.trimZeros()
            else "${value.trimZeros()} $unit"
        }

/* ---------- editing ---------- */

/**
 * A recipe's per-serving nutrition from the editor's text fields, or null.
 *
 * Null unless a macro was actually typed -- an untouched form must not write
 * zeros, which would later log as a zero-calorie meal. Saturated fat, sugar and
 * sodium ride along when a macro is there, blank staying null; on their own
 * they have nowhere to go, because a [RecipeNutrition] cannot exist without
 * calories and inventing a zero for them is the very thing this refuses to do.
 * The editor says so beside the fields.
 */
fun recipeNutritionFromText(
    calories: String,
    protein: String,
    carbs: String,
    fat: String,
    fiber: String,
    saturatedFat: String,
    sugar: String,
    sodium: String,
    estimated: Boolean
): RecipeNutrition? {
    val typed = listOf(calories, protein, carbs, fat, fiber).map { it.trim().toDoubleOrNull() }
    if (typed.all { it == null }) return null
    return RecipeNutrition(
        calories = typed[0] ?: 0.0,
        proteinG = typed[1] ?: 0.0,
        carbsG = typed[2] ?: 0.0,
        fatG = typed[3] ?: 0.0,
        fiberG = typed[4] ?: 0.0,
        estimated = estimated,
        saturatedFatG = NutrientDetailsText.parse(saturatedFat),
        sugarG = NutrientDetailsText.parse(sugar),
        sodiumMg = NutrientDetailsText.parse(sodium)
    )
}

/* ---------- helpers ---------- */

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
    // Written only when known. Absent is unknown, never zero (BACKUP-FORMAT).
    saturatedFatG?.let { put("saturatedFatG", it) }
    sugarG?.let { put("sugarG", it) }
    sodiumMg?.let { put("sodiumMg", it) }
}

internal fun recipeNutritionFromJson(o: JSONObject?): RecipeNutrition? {
    if (o == null) return null
    return RecipeNutrition(
        calories = o.finiteDouble("calories", 0.0),
        proteinG = o.finiteDouble("proteinG", 0.0),
        carbsG = o.finiteDouble("carbsG", 0.0),
        fatG = o.finiteDouble("fatG", 0.0),
        fiberG = o.finiteDouble("fiberG", 0.0),
        estimated = o.optBoolean("estimated", false),
        saturatedFatG = optNutrient(o, "saturatedFatG"),
        sugarG = optNutrient(o, "sugarG"),
        sodiumMg = optNutrient(o, "sodiumMg")
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
    qty = o.finiteDoubleOrNull("qty"),
    unit = o.optStringOrNull("unit"),
    grams = o.finiteDoubleOrNull("grams"),
    optional = o.optBoolean("optional", false),
    note = o.optStringOrNull("note")
)

internal fun Recipe.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("servings", servings)
    totalWeightGrams?.let { put("totalWeightGrams", it) }
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
    servings = o.finiteDouble("servings", 1.0),
    totalWeightGrams = o.finiteDoubleOrNull("totalWeightGrams"),
    ingredients = o.optJSONArray("ingredients").mapObjects(::recipeIngredientFromJson),
    steps = o.optJSONArray("steps").mapStrings(),
    nutritionPerServing = recipeNutritionFromJson(o.optJSONObject("nutritionPerServing")),
    sourceUrl = o.optStringOrNull("sourceUrl"),
    sourceAuthor = o.optStringOrNull("sourceAuthor"),
    sourceTranscript = o.optStringOrNull("sourceTranscript"),
    prepMinutes = o.finiteIntOrNull("prepMinutes"),
    cookMinutes = o.finiteIntOrNull("cookMinutes"),
    importedAt = o.finiteLong("importedAt", System.currentTimeMillis())
)

internal fun PlannedMeal.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("recipeId", recipeId)
    put("date", date)
    put("meal", meal)
    put("servings", servings)
    amountGrams?.let { put("amountGrams", it) }
    put("recipeName", recipeName)
    snapshotNutrition?.let { put("snapshotNutrition", it.toJson()) }
    snapshotNutritionPerGram?.let { put("snapshotNutritionPerGram", it.toJson()) }
    loggedFoodEntryId?.let { put("loggedFoodEntryId", it) }
}

internal fun plannedMealFromJson(o: JSONObject): PlannedMeal = PlannedMeal(
    id = o.optString("id", UUID.randomUUID().toString()),
    recipeId = o.optString("recipeId", ""),
    date = o.optString("date", todayKey()),
    meal = o.optString("meal", Meal.DINNER.name),
    servings = o.finiteDouble("servings", 1.0),
    amountGrams = o.finiteDoubleOrNull("amountGrams"),
    recipeName = o.optString("recipeName", ""),
    snapshotNutrition = recipeNutritionFromJson(o.optJSONObject("snapshotNutrition")),
    snapshotNutritionPerGram = recipeNutritionFromJson(o.optJSONObject("snapshotNutritionPerGram")),
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
    servings = o.finiteDouble("servings", 1.0),
    ingredients = o.optJSONArray("ingredients").mapObjects(::recipeIngredientFromJson),
    steps = o.optJSONArray("steps").mapStrings(),
    // Absent means unknown. Do not substitute zeros.
    nutritionPerServing = recipeNutritionFromJson(o.optJSONObject("nutritionPerServing")),
    sourceUrl = o.optStringOrNull("sourceURL"),
    sourceAuthor = o.optStringOrNull("sourceAuthor"),
    sourceTranscript = o.optStringOrNull("sourceTranscript"),
    prepMinutes = o.finiteIntOrNull("prepMinutes"),
    cookMinutes = o.finiteIntOrNull("cookMinutes")
)

/* ---------- json helpers ---------- */

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key, "").takeIf { it.isNotEmpty() }

internal fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }.map(transform)
}

internal fun JSONArray?.mapStrings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it, "") }.filter { it.isNotBlank() }
}
