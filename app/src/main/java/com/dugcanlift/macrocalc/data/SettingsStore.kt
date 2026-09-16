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
     * Whether the dashboard has already asked for Health Connect steps access
     * on its own. It asks once; after that only the Steps card's button asks,
     * so someone who declines is not prompted every time Home opens.
     */
    var askedForSteps: Boolean
        get() = prefs.getBoolean(KEY_ASKED_FOR_STEPS, false)
        set(value) { prefs.edit().putBoolean(KEY_ASKED_FOR_STEPS, value).apply() }

    companion object {
        private const val KEY_ASKED_FOR_STEPS = "asked_for_steps"
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
