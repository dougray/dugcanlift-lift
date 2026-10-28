package com.dugcanlift.macrocalc.data

import android.content.Context

/**
 * App preferences that aren't the macro goal: the training focus, which decides
 * which set fields the workout UI shows, and the smaller choices beside it.
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

    /**
     * Road Food's recently opened chains, most recent first, as chain ids
     * ([RoadFood.remember] keeps the list). A convenience about one screen, not
     * a record, so it stays on this phone and is not in the backup -- LIFT
     * web's `lift.roadRecent` follows the same rule.
     */
    var roadFoodRecent: List<String>
        get() = prefs.getString(KEY_ROAD_RECENT, null)
            ?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(value) { prefs.edit().putString(KEY_ROAD_RECENT, value.joinToString("\n")).apply() }

    /**
     * The allergens Road Food hides items for, shared by every place
     * ([RoadFoodAllergens]). Avoiding nothing removes the key, as LIFT
     * iPhone's `roadFoodAvoid` does. A preference about one screen, so on this
     * phone only, like [roadFoodRecent].
     */
    var roadFoodAvoid: Set<String>
        get() = RoadFoodAllergens.decode(prefs.getString(KEY_ROAD_AVOID, null))
        set(value) {
            val stored = RoadFoodAllergens.encode(value)
            prefs.edit().apply {
                if (stored == null) remove(KEY_ROAD_AVOID) else putString(KEY_ROAD_AVOID, stored)
            }.apply()
        }

    /**
     * The road picks a coach's plan brought, or null when no plan has brought
     * any (and after "Clear these picks").
     *
     * Replaced whole by the next plan that carries some, never merged: a link
     * carrying picks is the coach's current answer (see [RoadPicks]). On this
     * phone only, like [roadFoodRecent] and for the same reason -- LIFT web
     * keeps `lift.roadPicks` out of its backup's `STORED` list too, and a coach
     * re-sending is how they come back.
     */
    var roadPicks: RoadPicks?
        get() = RoadPicks.fromJson(prefs.getString(KEY_ROAD_PICKS, null))
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_ROAD_PICKS) else putString(KEY_ROAD_PICKS, value.toJson())
            }.apply()
        }

    /**
     * The equipment this lifter does **not** have, as [MyEquipment] spells it. Empty -- and no key
     * at all -- means everything is owned, so an untouched phone hides nothing. On this phone only,
     * like [roadFoodRecent].
     */
    var equipmentNotOwned: Set<String>
        get() = MyEquipment.load(prefs)
        set(value) = MyEquipment.save(value, prefs)

    /** Whether Train suggests the next weight ([ProgressionRule]). On unless turned off. */
    var suggestsNextWeight: Boolean
        get() = prefs.getBoolean(ProgressionRule.ENABLED_KEY, true)
        set(value) { prefs.edit().putBoolean(ProgressionRule.ENABLED_KEY, value).apply() }

    companion object {
        private const val KEY_ROAD_RECENT = "road_food_recent"
        private const val KEY_ROAD_PICKS = "road_food_picks"
        private const val KEY_ROAD_AVOID = "road_food_avoid"
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
