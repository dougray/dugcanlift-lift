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

    suspend fun searchByName(query: String): Outcome = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext Outcome.Success(emptyList())
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://world.openfoodfacts.org/cgi/search.pl" +
            "?search_terms=$encoded&search_simple=1&action=process&json=1" +
            "&page_size=20&fields=$FIELDS"

        try {
            val body = fetch(url)
            val products = JSONObject(body).optJSONArray("products")
                ?: return@withContext Outcome.Success(emptyList())

            val results = (0 until products.length())
                .mapNotNull { parseProduct(products.getJSONObject(it)) }

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
            brand = product.optString("brands", "").trim(),
            servingSize = product.optString("serving_size", "").trim(),
            per100g = per100g,
            perServing = perServing
        )
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
        else -> "Couldn't reach Open Food Facts."
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

    /**
     * Converts to a loggable entry.
     *
     * Prefers the per-serving figures when the product has them. Otherwise
     * falls back to per-100g and says so in the name, so nobody logs "Rice"
     * assuming it was one bowl when it was 100 grams.
     */
    fun toFoodEntry(date: String): FoodEntry {
        val useServing = perServing != null
        val values = perServing ?: per100g
        val label = when {
            useServing && servingSize.isNotEmpty() -> "$displayName, $servingSize"
            useServing -> displayName
            else -> "$displayName, per 100 g"
        }
        return FoodEntry(
            name = label,
            servings = 1.0,
            calories = values.calories,
            proteinG = values.proteinG,
            fatG = values.fatG,
            carbsG = values.carbsG,
            fiberG = values.fiberG,
            date = date
        )
    }
}
