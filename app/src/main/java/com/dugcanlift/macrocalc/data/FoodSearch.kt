package com.dugcanlift.macrocalc.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * Looks up food in Open Food Facts — a free, open-data, community-maintained
 * database. No API key, no account, no tracking on our side.
 *
 * This is the ONLY part of the app that touches the network. Nothing the person
 * logs is ever uploaded; only the search term or barcode goes out.
 *
 * Deliberately uses HttpURLConnection and org.json, both already in Android,
 * so this adds no dependencies.
 */
object FoodSearch {

    // Open Food Facts asks clients to identify themselves.
    private const val USER_AGENT = "DugCanLift-MacroCalc/1.0 (https://www.dugcanlift.com)"
    private const val FIELDS = "code,product_name,brands,serving_size,nutriments"
    private const val TIMEOUT_MS = 12_000

    sealed class Outcome {
        data class Success(val results: List<FoodSearchResult>) : Outcome()
        data class Failure(val message: String) : Outcome()
    }

    /**
     * Full-text search via Search-a-licious.
     *
     * Open Food Facts' old /cgi/search.pl endpoint returns HTTP 503 and is
     * deprecated; full-text search is not in their v2 API at all. This is the
     * supported replacement.
     *
     * Note results here only carry per-100g figures — no serving sizes — so
     * entries from search get labelled "per 100 g". Barcode lookups, which use
     * a different endpoint, do return per-serving data.
     */
    suspend fun searchByName(query: String): Outcome = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext Outcome.Success(emptyList())
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://search.openfoodfacts.org/search" +
            "?q=$encoded&page_size=20&fields=$FIELDS"

        try {
            val body = fetch(url)
            val hits = JSONObject(body).optJSONArray("hits")
                ?: return@withContext Outcome.Success(emptyList())

            val results = (0 until hits.length())
                .mapNotNull { parseProduct(hits.getJSONObject(it)) }

            Outcome.Success(results)
        } catch (e: Exception) {
            Outcome.Failure(friendlyError(e))
        }
    }

    suspend fun lookupBarcode(barcode: String): Outcome = withContext(Dispatchers.IO) {
        val code = barcode.trim()
        if (code.isEmpty()) return@withContext Outcome.Success(emptyList())
        val url = "https://world.openfoodfacts.org/api/v2/product/$code.json?fields=$FIELDS"

        try {
            val json = JSONObject(fetch(url))
            if (json.optInt("status", 0) != 1) {
                return@withContext Outcome.Failure("No product found for that barcode.")
            }
            val product = json.optJSONObject("product")
                ?: return@withContext Outcome.Failure("No product found for that barcode.")

            val parsed = parseProduct(product)
                ?: return@withContext Outcome.Failure("That product has no nutrition data.")

            Outcome.Success(listOf(parsed))
        } catch (e: Exception) {
            Outcome.Failure(friendlyError(e))
        }
    }

    /* ---------- internals ---------- */

    private fun fetch(urlString: String): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseProduct(product: JSONObject): FoodSearchResult? {
        val name = product.optString("product_name", "").trim()
        if (name.isEmpty()) return null

        val nutriments = product.optJSONObject("nutriments") ?: return null

        val per100g = readNutriments(nutriments, "100g") ?: return null
        val perServing = readNutriments(nutriments, "serving")

        return FoodSearchResult(
            code = product.optString("code", ""),
            name = name,
            brand = readBrand(product),
            servingSize = product.optString("serving_size", "").trim(),
            per100g = per100g,
            perServing = perServing
        )
    }

    /** Search returns brands as an array; the barcode endpoint as a string. */
    private fun readBrand(product: JSONObject): String {
        product.optJSONArray("brands")?.let { array ->
            if (array.length() == 0) return ""
            return array.optString(0, "").trim()
        }
        return product.optString("brands", "").trim()
    }

    /**
     * Returns null when there's no calorie figure — an entry with no energy
     * value is useless for tracking, so it's better to drop it than to log a
     * zero and quietly skew someone's day.
     */
    private fun readNutriments(nutriments: JSONObject, suffix: String): Nutriments? {
        val calories = nutriments.opt("energy-kcal_$suffix").asDoubleOrNull() ?: return null
        return Nutriments(
            calories = calories.roundToInt(),
            proteinG = (nutriments.opt("proteins_$suffix").asDoubleOrNull() ?: 0.0).roundToInt(),
            fatG = (nutriments.opt("fat_$suffix").asDoubleOrNull() ?: 0.0).roundToInt(),
            carbsG = (nutriments.opt("carbohydrates_$suffix").asDoubleOrNull() ?: 0.0).roundToInt(),
            fiberG = (nutriments.opt("fiber_$suffix").asDoubleOrNull() ?: 0.0).roundToInt()
        )
    }

    // Open Food Facts returns numbers sometimes as numbers, sometimes as strings.
    private fun Any?.asDoubleOrNull(): Double? = when (this) {
        is Number -> toDouble()
        is String -> toDoubleOrNull()
        else -> null
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "No internet connection."
        is java.net.SocketTimeoutException -> "Open Food Facts took too long to respond."
        // Include the detail — a bare "couldn't reach" hides whether it's a
        // server error, a bad response, or something else entirely.
        else -> "Couldn't reach Open Food Facts (${e.message ?: e.javaClass.simpleName})."
    }
}

data class Nutriments(
    val calories: Int,
    val proteinG: Int,
    val fatG: Int,
    val carbsG: Int,
    val fiberG: Int
)

data class FoodSearchResult(
    val code: String,
    val name: String,
    val brand: String,
    val servingSize: String,
    val per100g: Nutriments,
    val perServing: Nutriments?
) {
    /** Name with the brand, when there is one. */
    val displayName: String
        get() = if (brand.isNotEmpty()) "$name ($brand)" else name

    /** Nutrition for an arbitrary gram amount, always computed from the
     * per-100g figures (the only OFF value with a known fixed weight
     * basis — perServing's implied weight is the free-text servingSize
     * string, not reliably parseable into a number). */
    fun nutrition(grams: Double): Nutriments {
        val factor = grams / 100.0
        return Nutriments(
            calories = (per100g.calories * factor).roundToInt(),
            proteinG = (per100g.proteinG * factor).roundToInt(),
            fatG = (per100g.fatG * factor).roundToInt(),
            carbsG = (per100g.carbsG * factor).roundToInt(),
            fiberG = (per100g.fiberG * factor).roundToInt()
        )
    }

    /**
     * Converts to a loggable entry for a specific, user-entered gram amount.
     * [nutrition] should be the result of calling [nutrition] with the same
     * [grams] value — kept as a separate parameter rather than recomputed
     * here so the caller can show the same figures in a preview before
     * confirming.
     *
     * [amountLabel] is the already-formatted, unit-aware amount text (e.g.
     * "250 g" or "8.8 oz") — the caller builds it with `formatAmount` from
     * TodayScreen.kt against the person's current `ServingUnit` preference,
     * so this name suffix always matches what the amount/macro line on the
     * same row shows. It stays baked into the name (rather than being
     * dropped now that there's a separate amount line) because it's the only
     * way the gram amount survives into COACH's share-link format, whose
     * fixed 8-element array has no amountGrams slot — see SHARE-FORMAT.md.
     *
     * Per the gram-based FoodEntry design, servings is pinned to 1.0 and the
     * macro fields already hold the totals for this amount, not per-serving
     * values.
     */
    fun toFoodEntry(date: String, grams: Double, nutrition: Nutriments, amountLabel: String): FoodEntry {
        return FoodEntry(
            name = "$displayName, $amountLabel",
            servings = 1.0,
            amountGrams = grams,
            calories = nutrition.calories,
            proteinG = nutrition.proteinG,
            fatG = nutrition.fatG,
            carbsG = nutrition.carbsG,
            fiberG = nutrition.fiberG,
            date = date
        )
    }
}
