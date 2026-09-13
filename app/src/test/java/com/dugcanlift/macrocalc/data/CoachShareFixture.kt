package com.dugcanlift.macrocalc.data

import android.content.Context
import com.dugcanlift.macrocalc.MacroResult
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Calendar
import java.util.zip.Inflater

/**
 * Deterministic inputs for [CoachShare.buildLink], captured once as the
 * byte-identity oracle for the codec extraction (Task 6 of the Coach Android
 * plan). This object is reused unchanged there, against the extracted
 * library's encoder, so every input lives here explicitly rather than being
 * assembled inline in a test.
 *
 * Dates are computed from `todayKey()` at call time, via the same
 * [dateKey]/[Calendar] arithmetic `CoachShare` itself uses, so the fixture
 * always lands inside the lookback window regardless of which day this runs.
 * That also means the payload's `t` (today) and `z` (capture instant) fields
 * vary run to run — [canonical] strips both before comparison.
 */
object CoachShareFixture {

    private fun daysAgo(days: Int): String {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -days)
        return dateKey(calendar.timeInMillis)
    }

    /** Ramping back squat: 225/225/245 for 5/5/3. */
    private val squatSession = WorkoutSession(
        id = "fixture-session-squat",
        date = daysAgo(3),
        name = "Squat Day",
        exercises = listOf(
            LoggedExercise(
                id = "fixture-exercise-squat",
                name = "Back Squat",
                equipment = "Barbell",
                sets = listOf(
                    WorkoutSet(id = "fixture-set-1", weightLb = 225.0, reps = 5),
                    WorkoutSet(id = "fixture-set-2", weightLb = 225.0, reps = 5),
                    WorkoutSet(id = "fixture-set-3", weightLb = 245.0, reps = 3)
                )
            )
        )
    )

    /** A light warmup set alongside a set logged with only an RPE. */
    private val accessorySession = WorkoutSession(
        id = "fixture-session-accessory",
        date = daysAgo(1),
        name = "Accessory Day",
        exercises = listOf(
            LoggedExercise(
                id = "fixture-exercise-row",
                name = "Cable Row",
                equipment = "Cable",
                sets = listOf(
                    WorkoutSet(id = "fixture-set-warmup", weightLb = 90.0, reps = 12),
                    WorkoutSet(id = "fixture-set-rpe-only", rpe = 8.5)
                )
            )
        )
    )

    val sessions = listOf(squatSession, accessorySession)

    val entries = listOf(
        FoodEntry(
            id = "fixture-food-chicken",
            name = "Chicken Breast",
            servings = 2.0,
            calories = 165,
            proteinG = 31,
            fatG = 4,
            carbsG = 0,
            fiberG = 0,
            date = daysAgo(1),
            meal = Meal.LUNCH.name
        ),
        FoodEntry(
            id = "fixture-food-rice",
            name = "Brown Rice",
            servings = 2.0,
            calories = 216,
            proteinG = 5,
            fatG = 2,
            carbsG = 45,
            fiberG = 3,
            date = daysAgo(2),
            meal = Meal.DINNER.name
        ),
        FoodEntry(
            id = "fixture-food-yogurt",
            name = "Greek Yogurt",
            servings = 2.0,
            calories = 100,
            proteinG = 17,
            fatG = 0,
            carbsG = 6,
            fiberG = 0,
            date = daysAgo(3),
            meal = Meal.BREAKFAST.name
        )
    )

    val goal = MacroResult(calories = 2400, proteinG = 180, fatG = 70, carbsG = 220, fiberG = 35)

    /** Builds today's link from the fixed inputs above, using the real encoder. */
    fun buildToday(context: Context): String {
        val store = CoachStore.get(context)
        store.restoreLifterId("a1b2c3d4")
        store.lifterName = "Doug"
        store.weeks = 4
        store.itemisedFood = true

        val settings = SettingsStore.get(context)

        return CoachShare.buildLink(store, settings, goal, sessions, entries, emptyMap())
    }

    /**
     * Inflates a captured fragment (the part after `#`, `1z...`), parses it,
     * and drops the two fields that legitimately vary run to run (`t`, the
     * day the link was built, and `z`, the capture instant) so two captures
     * from different moments can still be compared for equality.
     */
    fun canonical(fragment: String): String {
        val json = JSONObject(inflate(fragment))
        json.remove("t")
        json.remove("z")
        return json.toString()
    }

    private fun inflate(fragment: String): String {
        val body = fragment.removePrefix("1z")
        val bytes = Base64.getUrlDecoder().decode(body)
        val inflater = Inflater(true)
        inflater.setInput(bytes)
        val buffer = ByteArray(8 * 1024)
        val out = ByteArrayOutputStream()
        while (!inflater.finished()) {
            val written = inflater.inflate(buffer)
            if (written == 0 && inflater.needsInput()) break
            out.write(buffer, 0, written)
        }
        inflater.end()
        return out.toString(Charsets.UTF_8.name())
    }
}
