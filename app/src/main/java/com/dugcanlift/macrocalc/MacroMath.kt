package com.dugcanlift.macrocalc

import kotlin.math.roundToInt

/**
 * Ported to match the calculator at dugcanlift.com exactly, including its
 * rounding order. Any change here should be mirrored on the site, or the two
 * will hand the same person different numbers.
 */

enum class Sex(val label: String) {
    MALE("Male"),
    FEMALE("Female")
}

enum class Activity(val label: String, val description: String, val factor: Double) {
    SEDENTARY("Sedentary", "little to no exercise", 1.200),
    LIGHT("Light", "exercise 1-3 days/week", 1.375),
    MODERATE("Moderate", "exercise 3-5 days/week", 1.550),
    ACTIVE("Active", "exercise 6-7 days/week", 1.725),
    VERY_ACTIVE("Very Active", "hard training + physical job", 1.900)
}

/** Calorie adjustment applied to TDEE, in kcal. Additive, matching the site. */
enum class Goal(val label: String, val adjustKcal: Int) {
    LOSE("Lose Weight", -500),
    MAINTAIN("Maintain", 0),
    GAIN("Gain Weight", 300)
}

enum class ProteinTarget(val label: String, val gramsPerLb: Double) {
    P07("0.7 g/lb", 0.7),
    P08("0.8 g/lb", 0.8),
    P10("1.0 g/lb", 1.0)
}

enum class FatTarget(val label: String, val fraction: Double) {
    F20("20%", 0.20),
    F25("25%", 0.25),
    F30("30%", 0.30),
    F35("35%", 0.35)
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
    goal: Goal,
    proteinTarget: ProteinTarget = ProteinTarget.P08,
    fatTarget: FatTarget = FatTarget.F25
): MacroResult {
    val weightKg = weightLb * 0.453592
    val heightCm = heightIn * 2.54

    // Mifflin-St Jeor
    val bmr = if (sex == Sex.MALE) {
        10.0 * weightKg + 6.25 * heightCm - 5.0 * age + 5.0
    } else {
        10.0 * weightKg + 6.25 * heightCm - 5.0 * age - 161.0
    }

    val tdee = bmr * activity.factor
    val targetCalories = (tdee + goal.adjustKcal).roundToInt()

    // Protein is set from bodyweight, then its calories come off the top.
    val proteinG = (proteinTarget.gramsPerLb * weightLb).roundToInt()
    val proteinCal = proteinG * 4

    // Fat is a share of total calories.
    val fatG = (targetCalories * fatTarget.fraction / 9.0).roundToInt()

    // Carbs take what's left. Note this uses the ROUNDED fat grams, exactly as
    // the site does — using the unrounded value would drift by a gram or two.
    val remainingCal = targetCalories - proteinCal - (fatG * 9)
    val carbsG = (maxOf(remainingCal, 0) / 4.0).roundToInt()

    // Dietary Guidelines: 14 g per 1000 kcal.
    val fiberG = (targetCalories / 1000.0 * 14.0).roundToInt()

    return MacroResult(
        calories = targetCalories,
        proteinG = proteinG,
        fatG = fatG,
        carbsG = carbsG,
        fiberG = fiberG
    )
}
