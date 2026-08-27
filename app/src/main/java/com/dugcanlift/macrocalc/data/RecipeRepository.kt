package com.dugcanlift.macrocalc.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * Stores recipes, the meal plan and shopping-list tick-offs as JSON arrays in
 * the app's private files directory. Nothing leaves the device.
 *
 * Mirrors [FoodRepository] deliberately: same temp-file-then-rename write, same
 * "corrupt file starts empty rather than crashing" read, same singleton. If
 * that one ever moves to Room, this moves with it.
 */
class RecipeRepository private constructor(context: Context) {

    private val dir = context.applicationContext.filesDir
    private val recipeFile = File(dir, RECIPES_FILE)
    private val planFile = File(dir, PLAN_FILE)
    private val checkedFile = File(dir, CHECKED_FILE)

    private val _recipes = MutableStateFlow<List<Recipe>>(emptyList())
    val recipes: StateFlow<List<Recipe>> = _recipes.asStateFlow()

    private val _plan = MutableStateFlow<List<PlannedMeal>>(emptyList())
    val plan: StateFlow<List<PlannedMeal>> = _plan.asStateFlow()

    /** Lowercased item names already in the basket. */
    private val _checked = MutableStateFlow<Set<String>>(emptySet())
    val checked: StateFlow<Set<String>> = _checked.asStateFlow()

    /** Call once on startup. Safe to call again. */
    suspend fun load() = withContext(Dispatchers.IO) {
        _recipes.value = readRecipes()
        _plan.value = readPlan()
        _checked.value = readChecked()
    }

    /* ---------- recipes ---------- */

    suspend fun addRecipe(recipe: Recipe) = withContext(Dispatchers.IO) {
        val updated = readRecipes() + recipe
        writeRecipes(updated)
        _recipes.value = updated
    }

    suspend fun updateRecipe(recipe: Recipe) = withContext(Dispatchers.IO) {
        val updated = readRecipes().map { if (it.id == recipe.id) recipe else it }
        writeRecipes(updated)
        _recipes.value = updated
    }

    /**
     * Deletes a recipe and any *unlogged* plan entries pointing at it.
     *
     * Logged entries are left alone: the [FoodEntry] they produced carries its
     * own copy of the numbers, and history must not change because a recipe was
     * tidied up later.
     */
    suspend fun deleteRecipe(id: String) = withContext(Dispatchers.IO) {
        val updatedRecipes = readRecipes().filterNot { it.id == id }
        writeRecipes(updatedRecipes)
        _recipes.value = updatedRecipes

        val updatedPlan = readPlan().filterNot { it.recipeId == id && !it.isLogged }
        writePlan(updatedPlan)
        _plan.value = updatedPlan
    }

    /* ---------- meal plan ---------- */

    suspend fun plan(recipe: Recipe, date: String, meal: Meal, servings: Double = 1.0) =
        withContext(Dispatchers.IO) {
            val entry = PlannedMeal(
                recipeId = recipe.id,
                date = date,
                meal = meal.name,
                servings = servings,
                recipeName = recipe.name,
                // Snapshot at plan time, per serving. PlannedMeal.toFoodEntry
                // passes servings through, so this must NOT be pre-scaled.
                snapshotNutrition = recipe.nutritionPerServing
            )
            val updated = readPlan() + entry
            writePlan(updated)
            _plan.value = updated
        }

    suspend fun unplan(id: String) = withContext(Dispatchers.IO) {
        val updated = readPlan().filterNot { it.id == id }
        writePlan(updated)
        _plan.value = updated
    }

    /**
     * Marks a planned meal as logged. Call after the [FoodEntry] is actually
     * written, never before — this flag is what stops a second log.
     */
    suspend fun markLogged(planId: String, foodEntryId: String) = withContext(Dispatchers.IO) {
        val updated = readPlan().map {
            if (it.id == planId) it.copy(loggedFoodEntryId = foodEntryId) else it
        }
        writePlan(updated)
        _plan.value = updated
    }

    /* ---------- shopping list ---------- */

    suspend fun setChecked(itemKey: String, isChecked: Boolean) = withContext(Dispatchers.IO) {
        val key = itemKey.trim().lowercase()
        val updated = readChecked().toMutableSet().apply {
            if (isChecked) add(key) else remove(key)
        }
        writeChecked(updated)
        _checked.value = updated
    }

    suspend fun clearChecked() = withContext(Dispatchers.IO) {
        writeChecked(emptySet())
        _checked.value = emptySet()
    }

    /* ---------- file access ---------- */

    @Synchronized
    private fun readRecipes(): List<Recipe> =
        readArray(recipeFile) { recipeFromJson(it) }

    @Synchronized
    private fun readPlan(): List<PlannedMeal> =
        readArray(planFile) { plannedMealFromJson(it) }

    @Synchronized
    private fun readChecked(): Set<String> {
        if (!checkedFile.exists()) return emptySet()
        return try {
            val array = JSONArray(checkedFile.readText())
            (0 until array.length()).map { array.optString(it, "") }
                .filter { it.isNotBlank() }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun <T> readArray(file: File, transform: (org.json.JSONObject) -> T): List<T> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map(transform)
        } catch (e: Exception) {
            // Corrupt or truncated file shouldn't crash the app on launch.
            emptyList()
        }
    }

    @Synchronized
    private fun writeRecipes(items: List<Recipe>) =
        writeAtomically(recipeFile, JSONArray().also { a -> items.forEach { a.put(it.toJson()) } })

    @Synchronized
    private fun writePlan(items: List<PlannedMeal>) =
        writeAtomically(planFile, JSONArray().also { a -> items.forEach { a.put(it.toJson()) } })

    @Synchronized
    private fun writeChecked(keys: Set<String>) =
        writeAtomically(checkedFile, JSONArray().also { a -> keys.forEach { a.put(it) } })

    /** Temp file then rename, so an interrupted write can't truncate the real one. */
    private fun writeAtomically(file: File, array: JSONArray) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(array.toString())
        temp.renameTo(file)
    }

    companion object {
        private const val RECIPES_FILE = "recipes.json"
        private const val PLAN_FILE = "meal_plan.json"
        private const val CHECKED_FILE = "shopping_checked.json"

        @Volatile
        private var instance: RecipeRepository? = null

        fun get(context: Context): RecipeRepository =
            instance ?: synchronized(this) {
                instance ?: RecipeRepository(context).also { instance = it }
            }
    }
}

/** Planned meals for one day, in meal order. */
fun List<PlannedMeal>.planForDate(date: String): List<PlannedMeal> =
    filter { it.date == date }.sortedBy { it.mealOrDefault.ordinal }

/** Planned meals across an inclusive "yyyy-MM-dd" range — what the list is built from. */
fun List<PlannedMeal>.planBetween(startDate: String, endDate: String): List<PlannedMeal> =
    filter { it.date >= startDate && it.date <= endDate }.sortedBy { it.date }
