package com.dugcanlift.macrocalc.data

import android.content.Context
import com.dugcanlift.macrocalc.MacroResult

/**
 * Persists the macro goal the person saved from the calculator.
 *
 * SharedPreferences rather than DataStore: it's five integers, it's already
 * on every Android version, and it needs no dependency.
 */
class GoalStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("dcl_goal", Context.MODE_PRIVATE)

    /** The saved goal, or null if the person hasn't set one yet. */
    fun get(): MacroResult? {
        if (!prefs.getBoolean(KEY_SET, false)) return null
        return MacroResult(
            calories = prefs.getInt(KEY_CALORIES, 0),
            proteinG = prefs.getInt(KEY_PROTEIN, 0),
            fatG = prefs.getInt(KEY_FAT, 0),
            carbsG = prefs.getInt(KEY_CARBS, 0),
            fiberG = prefs.getInt(KEY_FIBER, 0)
        )
    }

    fun save(goal: MacroResult) {
        prefs.edit()
            .putBoolean(KEY_SET, true)
            .putInt(KEY_CALORIES, goal.calories)
            .putInt(KEY_PROTEIN, goal.proteinG)
            .putInt(KEY_FAT, goal.fatG)
            .putInt(KEY_CARBS, goal.carbsG)
            .putInt(KEY_FIBER, goal.fiberG)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_SET = "goal_set"
        private const val KEY_CALORIES = "calories"
        private const val KEY_PROTEIN = "protein"
        private const val KEY_FAT = "fat"
        private const val KEY_CARBS = "carbs"
        private const val KEY_FIBER = "fiber"

        @Volatile
        private var instance: GoalStore? = null

        fun get(context: Context): GoalStore =
            instance ?: synchronized(this) {
                instance ?: GoalStore(context).also { instance = it }
            }
    }
}
