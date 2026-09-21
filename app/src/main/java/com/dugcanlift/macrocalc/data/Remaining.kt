package com.dugcanlift.macrocalc.data

import com.dugcanlift.macrocalc.MacroResult

/**
 * What is left of the goal on a day: the "kcal left" Home and Food show, and
 * what Road Food ranks against. One place, so the three can never disagree --
 * LIFT web's `remainingFor(day)`.
 *
 * Negative when the day is over. [eaten] is the day's totals it was worked out
 * from, for the screens that also say "1,560 of 2,200".
 */
data class Remaining(val calories: Int, val proteinG: Int, val eaten: DayTotals)

/** [goal] less what [entries] add up to, or null with no goal set. */
@JvmName("remainingForOptionalGoal")
fun remainingFor(goal: MacroResult?, entries: List<FoodEntry>): Remaining? =
    goal?.let { remainingFor(it, entries) }

/** [goal] less what [entries] add up to. */
fun remainingFor(goal: MacroResult, entries: List<FoodEntry>): Remaining {
    val eaten = entries.totals()
    return Remaining(
        calories = goal.calories - eaten.calories,
        proteinG = goal.proteinG - eaten.proteinG,
        eaten = eaten
    )
}
