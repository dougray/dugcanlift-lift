package com.dugcanlift.macrocalc.data

import android.content.Context

/**
 * App preferences that aren't the macro goal. Currently just the training
 * focus, which decides which set fields the workout UI shows.
 */
class SettingsStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("dcl_settings", Context.MODE_PRIVATE)

    var focus: TrainingFocus
        get() {
            val stored = prefs.getString(KEY_FOCUS, null) ?: return TrainingFocus.BODYBUILDING
            // Falls back rather than crashing if a focus is ever renamed or removed.
            return TrainingFocus.entries.firstOrNull { it.name == stored }
                ?: TrainingFocus.BODYBUILDING
        }
        set(value) {
            prefs.edit().putString(KEY_FOCUS, value.name).apply()
        }

    /** 10,000 is a starting recommendation, not a requirement — editable from the dashboard. */
    var stepGoal: Int
        get() = prefs.getInt(KEY_STEP_GOAL, 10_000)
        set(value) {
            prefs.edit().putInt(KEY_STEP_GOAL, value).apply()
        }

    var servingUnit: ServingUnit
        get() {
            val stored = prefs.getString(KEY_SERVING_UNIT, null) ?: return ServingUnit.GRAMS
            return ServingUnit.entries.firstOrNull { it.name == stored } ?: ServingUnit.GRAMS
        }
        set(value) { prefs.edit().putString(KEY_SERVING_UNIT, value.name).apply() }

    /**
     * Whether this exercise is logged left and right separately, keyed the way
     * the dictionary is (`name|equipment`).
     *
     * Unset means nobody has answered yet, and only then does the name decide
     * ([PerSideLogging.looksUnilateral]). Once the lifter sets it either way
     * that value is stored and the guess is never consulted again — including
     * when they turn it *off* for something that looks unilateral, which is the
     * case a "the guess unless it was turned on" shortcut gets wrong.
     */
    fun logsPerSide(matchKey: String): Boolean {
        val key = PerSideLogging.prefKey(matchKey)
        if (!prefs.contains(key)) return PerSideLogging.looksUnilateral(PerSideLogging.nameOf(matchKey))
        return prefs.getBoolean(key, false)
    }

    fun setLogsPerSide(matchKey: String, value: Boolean) {
        prefs.edit().putBoolean(PerSideLogging.prefKey(matchKey), value).apply()
    }

    companion object {
        private const val KEY_FOCUS = "training_focus"
        private const val KEY_STEP_GOAL = "step_goal"
        private const val KEY_SERVING_UNIT = "serving_unit"

        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context).also { instance = it }
            }
    }
}
