package com.dugcanlift.macrocalc.data

import android.content.Context
import com.dugcanlift.kit.RecipeNutrition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.Normalizer

/**
 * One recipe from the bundled starter catalogue (`assets/recipes.json`).
 *
 * A port of LiftReference's `CatalogRecipe` and `CatalogEntry`, the type the
 * iPhone catalogue reads out of `recipes.db`. This build cannot open that
 * database, so it reads the kit's JSON export of it, every column under the
 * database's own name (`dugcanlift-kit/Tools/export_recipes_json.py`).
 *
 * Read-only reference data, like a Road Food item. It is not a [Recipe]:
 * nothing here is in the library until the person taps Add, and [makeRecipe]
 * is what performs that copy.
 *
 * **Nothing is re-costed.** The catalogue's build already priced what converts
 * to a weight and wrote down what it could not ([nutritionNote]); running the
 * costing again could only reach the same answer or a worse one.
 */
data class CatalogRecipe(
    val id: String,
    val name: String,
    val summary: String? = null,
    /** ISO country code, for the world recipes. Null for the heritage books. */
    val country: String? = null,
    val category: String? = null,
    /**
     * How many the source says it feeds. **Null when the source never said**,
     * which is every public-domain book: every macro is divided by this, so it
     * is left empty rather than guessed at.
     */
    val servings: Double? = null,
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val caloriesPerServing: Double? = null,
    val proteinPerServing: Double? = null,
    val carbsPerServing: Double? = null,
    val fatPerServing: Double? = null,
    /** The whole finished dish, populated only when [servings] is null. */
    val caloriesTotal: Double? = null,
    val proteinTotal: Double? = null,
    val carbsTotal: Double? = null,
    val fatTotal: Double? = null,
    val nutritionIsEstimated: Boolean = false,
    /** Plain-English coverage, or why there are no macros. Always shown. */
    val nutritionNote: String? = null,
    val steps: List<String> = emptyList(),
    val source: String = "",
    /** Shown wherever the recipe is shown: CC BY-SA asks for it. */
    val license: String = "",
    val attribution: String = "",
    val sourceUrl: String? = null,
    /** Raw lines, in order, parsed only when the recipe is added. */
    val ingredients: List<String> = emptyList()
) {
    /**
     * True when the macros describe the whole dish because the source stated no
     * yield. The person supplies a serving count on add.
     */
    val needsServings: Boolean get() = servings == null && caloriesTotal != null

    /**
     * Per serving at [count].
     *
     * A published per-serving figure ignores the argument: the publisher measured
     * it, and rescaling it would be inventing a second opinion. A whole-dish
     * recipe divides. Null when the catalogue has no macros, never zeros.
     */
    fun nutrition(count: Double): RecipeNutrition? {
        caloriesPerServing?.let { calories ->
            return RecipeNutrition(
                calories = calories,
                proteinG = proteinPerServing ?: 0.0,
                carbsG = carbsPerServing ?: 0.0,
                fatG = fatPerServing ?: 0.0,
                estimated = nutritionIsEstimated
            )
        }
        val total = caloriesTotal ?: return null
        val divisor = if (count.isFinite() && count > 0) count else 1.0
        return RecipeNutrition(
            calories = total / divisor,
            proteinG = (proteinTotal ?: 0.0) / divisor,
            carbsG = (carbsTotal ?: 0.0) / divisor,
            fatG = (fatTotal ?: 0.0) / divisor,
            estimated = nutritionIsEstimated
        )
    }

    /**
     * Copies this recipe into the person's own library as an ordinary [Recipe].
     *
     * Ingredients go in as their raw lines through [ingredientsFromLines], the
     * same path a typed or pasted recipe takes on save. Attribution, licence and
     * the costing caveat travel in [Recipe.sourceTranscript], as they do on the
     * iPhone: CC BY-SA requires the credit, and the caveat is what stops a short
     * macro figure being read as a whole one.
     */
    fun makeRecipe(servings: Double): Recipe = Recipe(
        name = name,
        servings = servings,
        ingredients = ingredientsFromLines(ingredients),
        steps = steps,
        nutritionPerServing = nutrition(servings),
        sourceUrl = sourceUrl,
        sourceAuthor = attribution.ifBlank { null },
        sourceTranscript = listOf(attribution, license, nutritionNote.orEmpty())
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .ifBlank { null },
        prepMinutes = prepMinutes,
        cookMinutes = cookMinutes
    )
}

/**
 * Search, browse and credits over the catalogue: LiftReference's
 * `searchRecipes`, `browseRecipes` and `recipeAttributions`, without SQLite.
 */
object RecipeCatalog {

    /** The iPhone's floor: one character is a keystroke, not a query. */
    const val MIN_QUERY = 2
    const val SEARCH_LIMIT = 40
    /** What the stepper starts on for a recipe whose source stated no yield. */
    const val DEFAULT_SERVINGS = 4.0
    const val MIN_SERVINGS = 1.0
    const val MAX_SERVINGS = 48.0

    /**
     * Every recipe, ordered by name, so the two collections interleave rather
     * than running 501 world recipes before the first heritage one. Binary
     * order, as SQLite's `ORDER BY name` is.
     */
    fun browse(recipes: List<CatalogRecipe>): List<CatalogRecipe> = recipes.sortedBy { it.name }

    /**
     * Every query word must begin a word of the name, the summary or the country
     * code -- the columns the iPhone's `recipes_fts` indexes, matched as prefixes
     * the way `FTS5Pattern(matchingAllPrefixesIn:)` builds its pattern. Case and
     * accents are ignored, as `unicode61` ignores them.
     *
     * Ranked name hits first, then the shorter name, then by name: a stand-in
     * for `bm25(...), length(name)`, which has no JVM equivalent here.
     */
    fun search(recipes: List<CatalogRecipe>, query: String, limit: Int = SEARCH_LIMIT): List<CatalogRecipe> {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY) return emptyList()
        val terms = words(trimmed)
        if (terms.isEmpty()) return emptyList()

        return recipes.asSequence()
            .mapNotNull { recipe ->
                val nameWords = words(recipe.name)
                val otherWords = words(recipe.summary.orEmpty()) + words(recipe.country.orEmpty())
                val all = nameWords + otherWords
                if (!terms.all { term -> matches(term, all) }) return@mapNotNull null
                val inName = terms.count { term -> matches(term, nameWords) }
                Triple(recipe, inName, recipe.name.length)
            }
            .sortedWith(
                compareByDescending<Triple<CatalogRecipe, Int, Int>> { it.second }
                    .thenBy { it.third }
                    .thenBy { it.first.name }
            )
            .take(limit)
            .map { it.first }
            .toList()
    }

    /**
     * Every distinct "attribution — licence", sorted, for the credits on the
     * catalogue's own screen. CC BY-SA 4.0 requires the credit be given, so this
     * is the obligation, not decoration.
     */
    fun attributions(recipes: List<CatalogRecipe>): List<String> =
        recipes.map { "${it.attribution} — ${it.license}" }.distinct().sorted()

    /**
     * A query word matches a text word it begins. `porter` stems both sides on
     * the iPhone, so "potatoes" finds "potato"; this does not stem, and instead
     * lets a plural query word fall back to its singular.
     */
    private fun matches(term: String, words: List<String>): Boolean {
        if (words.any { it.startsWith(term) }) return true
        val singular = when {
            term.length > 4 && term.endsWith("es") -> listOf(term.dropLast(2), term.dropLast(1))
            term.length > 3 && term.endsWith("s") -> listOf(term.dropLast(1))
            else -> emptyList()
        }
        return singular.any { s -> words.any { it.startsWith(s) } }
    }

    private val SEPARATOR = Regex("[^\\p{L}\\p{N}]+")
    private val MARKS = Regex("\\p{Mn}+")

    internal fun words(text: String): List<String> =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(MARKS, "")
            .split(SEPARATOR)
            .filter { it.isNotEmpty() }
}

/** Parses `recipes.json` as the kit's exporter writes it. */
fun parseRecipeCatalog(json: String): List<CatalogRecipe> {
    val root = JSONObject(json)
    val array = root.optJSONArray("recipes") ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map { o ->
        CatalogRecipe(
            id = o.optString("id", ""),
            name = o.optString("name", ""),
            summary = o.textOrNull("summary"),
            country = o.textOrNull("country"),
            category = o.textOrNull("category"),
            servings = o.finiteDoubleOrNull("servings")?.takeIf { it > 0 },
            prepMinutes = o.finiteIntOrNull("prepMinutes"),
            cookMinutes = o.finiteIntOrNull("cookMinutes"),
            caloriesPerServing = o.finiteDoubleOrNull("caloriesPerServing"),
            proteinPerServing = o.finiteDoubleOrNull("proteinPerServing"),
            carbsPerServing = o.finiteDoubleOrNull("carbsPerServing"),
            fatPerServing = o.finiteDoubleOrNull("fatPerServing"),
            caloriesTotal = o.finiteDoubleOrNull("caloriesTotal"),
            proteinTotal = o.finiteDoubleOrNull("proteinTotal"),
            carbsTotal = o.finiteDoubleOrNull("carbsTotal"),
            fatTotal = o.finiteDoubleOrNull("fatTotal"),
            nutritionIsEstimated = o.optBoolean("nutritionIsEstimated", false),
            nutritionNote = o.textOrNull("nutritionNote"),
            steps = o.optJSONArray("steps").mapStrings(),
            source = o.optString("source", ""),
            license = o.optString("license", ""),
            attribution = o.optString("attribution", ""),
            sourceUrl = o.textOrNull("sourceURL"),
            ingredients = o.optJSONArray("ingredients").mapStrings()
        )
    }
}

private fun JSONObject.textOrNull(key: String): String? =
    if (isNull(key)) null else optString(key, "").takeIf { it.isNotBlank() }

/**
 * Loads the bundled catalogue once, off the main thread. An asset, never a
 * request: the in-house rule, and the reason the catalogue works with no signal.
 */
object RecipeCatalogStore {
    const val ASSET = "recipes.json"

    @Volatile private var cached: List<CatalogRecipe>? = null
    @Volatile private var failure: String? = null

    /** The parsed file, or null with [lastError] set when it could not load. */
    suspend fun load(context: Context): List<CatalogRecipe>? = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }
        failure?.let { return@withContext null }
        try {
            val json = context.applicationContext.assets.open(ASSET).bufferedReader().use { it.readText() }
            parseRecipeCatalog(json).also { cached = it }
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
            null
        }
    }

    val lastError: String? get() = failure
}
