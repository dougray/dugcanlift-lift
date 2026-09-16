package com.dugcanlift.macrocalc.data

import com.dugcanlift.kit.DayKey
import com.dugcanlift.kit.NutrientDetails
import com.dugcanlift.kit.ShareNutrientTotals
import com.dugcanlift.kit.ShareNutrients
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

/**
 * A single logged food. Macro values are PER SERVING; multiply by [servings]
 * for what the person actually ate. The totals below do that for you.
 *
 * `date` is a plain "yyyy-MM-dd" string rather than java.time.LocalDate,
 * because LocalDate needs API 26 and minSdk here is 24.
 */
enum class Meal(val label: String) {
    BREAKFAST("Breakfast"),
    LUNCH("Lunch"),
    DINNER("Dinner"),
    SNACK("Snack")
}

/** Best guess at which meal a given time of day belongs to. */
fun mealForHour(hour: Int): Meal = when {
    hour < 11 -> Meal.BREAKFAST
    hour < 15 -> Meal.LUNCH
    hour < 21 -> Meal.DINNER
    else -> Meal.SNACK
}

data class FoodEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val servings: Double = 1.0,
    /** The authoritative gram amount, when logged via the gram/ounce
     * entry flow. Null for legacy entries. When set, `servings` is always
     * 1.0 and calories/proteinG/etc are already the totals for this
     * amount — see the plan's Global Constraints for why. */
    val amountGrams: Double? = null,
    val calories: Int,
    val proteinG: Int,
    val fatG: Int,
    val carbsG: Int,
    val fiberG: Int = 0,
    val date: String = todayKey(),
    val loggedAt: Long = System.currentTimeMillis(),
    val meal: String = "",
    /**
     * Saturated fat, sugar and sodium, on the same basis as the macros above:
     * per serving, or the totals for [amountGrams] when that is set. Tracked
     * and shown, never targeted. Null is "not recorded", never zero -- most
     * foods typed by hand will not have them, and a day's total must not read
     * those as foods with no sodium in them.
     */
    val saturatedFatG: Double? = null,
    val sugarG: Double? = null,
    val sodiumMg: Double? = null
) {
    val details: NutrientDetails get() = NutrientDetails(saturatedFatG, sugarG, sodiumMg)

    /**
     * The meal this belongs to. Falls back to the time it was logged, so
     * entries saved before meals existed still sort sensibly.
     */
    val mealOrDefault: Meal
        get() = Meal.entries.firstOrNull { it.name == meal } ?: run {
            if (loggedAt <= 0L) Meal.SNACK
            else {
                val calendar = java.util.Calendar.getInstance()
                calendar.timeInMillis = loggedAt
                mealForHour(calendar.get(java.util.Calendar.HOUR_OF_DAY))
            }
        }

    val totalCalories: Int get() = (calories * servings).roundToInt()
    val totalProteinG: Int get() = (proteinG * servings).roundToInt()
    val totalFatG: Int get() = (fatG * servings).roundToInt()
    val totalCarbsG: Int get() = (carbsG * servings).roundToInt()
    val totalFiberG: Int get() = (fiberG * servings).roundToInt()
}

/** Running totals for a set of entries — what gets compared against the goal. */
data class DayTotals(
    val calories: Int = 0,
    val proteinG: Int = 0,
    val fatG: Int = 0,
    val carbsG: Int = 0,
    val fiberG: Int = 0
)

fun List<FoodEntry>.totals(): DayTotals = DayTotals(
    calories = sumOf { it.totalCalories },
    proteinG = sumOf { it.totalProteinG },
    fatG = sumOf { it.totalFatG },
    carbsG = sumOf { it.totalCarbsG },
    fiberG = sumOf { it.totalFiberG }
)

/**
 * A day's saturated fat, sugar and sodium, each totalled over only the foods
 * that recorded it, with how many did. Null when none recorded any. Built by
 * the kit's [ShareNutrients.dayTotals], so what the screen says and what a
 * coach's link carries as `fx` are the same numbers, rounded the same way.
 */
fun List<FoodEntry>.nutrientTotals(): ShareNutrientTotals? =
    ShareNutrients.dayTotals(map { it.servings to it.details })

fun todayKey(): String = DayKey.today()

fun dateKey(millis: Long): String = DayKey.make(millis)

/* ---------- JSON ---------- */

internal fun FoodEntry.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("servings", servings)
    amountGrams?.let { put("amountGrams", it) }
    put("calories", calories)
    put("proteinG", proteinG)
    put("fatG", fatG)
    put("carbsG", carbsG)
    put("fiberG", fiberG)
    put("date", date)
    put("loggedAt", loggedAt)
    put("meal", meal)
    saturatedFatG?.let { put("saturatedFatG", it) }
    sugarG?.let { put("sugarG", it) }
    sodiumMg?.let { put("sodiumMg", it) }
}

/**
 * Reads an entry back. Every field falls back to a default, so a file written
 * by an older version of the app still loads after new fields are added.
 */
internal fun foodEntryFromJson(o: JSONObject): FoodEntry = FoodEntry(
    id = o.optString("id", UUID.randomUUID().toString()),
    name = o.optString("name", ""),
    servings = o.optDouble("servings", 1.0),
    amountGrams = if (o.has("amountGrams")) o.optDouble("amountGrams") else null,
    calories = o.optInt("calories", 0),
    proteinG = o.optInt("proteinG", 0),
    fatG = o.optInt("fatG", 0),
    carbsG = o.optInt("carbsG", 0),
    fiberG = o.optInt("fiberG", 0),
    date = o.optString("date", todayKey()),
    loggedAt = o.optLong("loggedAt", 0L),
    meal = o.optString("meal", ""),
    saturatedFatG = optNutrient(o, "saturatedFatG"),
    sugarG = optNutrient(o, "sugarG"),
    sodiumMg = optNutrient(o, "sodiumMg")
)

/**
 * One of saturated fat, sugar or sodium from a stored or backed-up record.
 * Absent, null, negative or not a finite number all read as not recorded --
 * never as zero.
 */
internal fun optNutrient(o: JSONObject, key: String): Double? {
    if (!o.has(key) || o.isNull(key)) return null
    return (o.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }
}
