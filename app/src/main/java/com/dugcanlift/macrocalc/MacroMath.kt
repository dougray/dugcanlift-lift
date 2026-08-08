package com.dugcanlift.macrocalc

import kotlin.math.roundToInt

enum class Sex(val label: String) {
    MALE("Male"),
    FEMALE("Female")
}

enum class Activity(val label: String, val factor: Double) {
    SEDENTARY("Sedentary", 1.200),
    LIGHT("Light", 1.375),
    MODERATE("Moderate", 1.550),
    HEAVY("Heavy", 1.725),
    ATHLETE("Athlete", 1.900)
}

enum class Goal(val label: String, val multiplier: Double) {
    CUT("Cut", 0.80),
    MAINTAIN("Maintain", 1.00),
    BULK("Bulk", 1.15)
}

data class MacroResult(
    val calories: Int,
    val proteinG: Int,
    val fatG: Int,
    val carbsG: Int,
    val fiberG: Int
)

fun calculateMacros(
    sex: Sex,
    age: Int,
    weightLb: Double,
    heightIn: Double,
    activity: Activity,
    goal: Goal
): MacroResult {
    val kg = weightLb * 0.453592
    val cm = heightIn * 2.54

    val bmr = 10.0 * kg + 6.25 * cm - 5.0 * age + if (sex == Sex.MALE) 5.0 else -161.0
    val calories = bmr * activity.factor * goal.multiplier

    val protein = 2.0 * kg
    val fat = calories * 0.25 / 9.0
    val carbs = ((calories - protein * 4.0 - fat * 9.0) / 4.0).coerceAtLeast(0.0)

    return MacroResult(
        calories = calories.roundToInt(),
        proteinG = protein.roundToInt(),
        fatG = fat.roundToInt(),
        carbsG = carbs.roundToInt(),
        fiberG = (calories / 1000.0 * 14.0).roundToInt()
    )
}