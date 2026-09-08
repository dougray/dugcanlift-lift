package com.dugcanlift.macrocalc.data

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Inflater

data class PlanSet(
    val weightLb: Double? = null,
    val reps: Int? = null,
    val rpe: Double? = null,
    val durationSec: Int? = null,
    val distanceMeters: Double? = null
)

data class PlanWorkoutExercise(
    val name: String,
    val equipment: String = "",
    val note: String = "",
    val sets: List<PlanSet> = emptyList()
)

data class PlanWorkout(
    val name: String,
    val exercises: List<PlanWorkoutExercise> = emptyList()
)

data class PlanRecipe(
    val name: String,
    val servings: Double,
    val nutritionPerServing: RecipeNutrition? = null,
    val ingredients: List<String> = emptyList(),
    val steps: List<String> = emptyList()
)

data class PlanMeal(
    val date: String,
    val mealSlot: Int,
    val recipeIndex: Int,
    val servings: Double
)

data class PlanSession(
    val date: String,
    val workoutIndex: Int
)

data class PlanPayload(
    val coachName: String,
    val recipes: List<PlanRecipe>,
    val meals: List<PlanMeal>,
    val workouts: List<PlanWorkout>,
    val sessions: List<PlanSession>,
    val rawJson: String
)

sealed class PlanDecodeResult {
    data class Success(val payload: PlanPayload) : PlanDecodeResult()
    object NotAddressedToYou : PlanDecodeResult()
    object UnsupportedVersion : PlanDecodeResult()
    object MalformedPayload : PlanDecodeResult()
}

object PlanLinkCodec {

    private const val SUPPORTED_VERSION = 1

    fun decode(fragment: String, expectedLifterId: String): PlanDecodeResult {
        if (fragment.length < 2) return PlanDecodeResult.MalformedPayload
        val version = fragment[0]
        val codec = fragment[1]
        if (version != '1') return PlanDecodeResult.UnsupportedVersion
        val encoded = fragment.substring(2)

        val jsonText = try {
            val bytes = Base64.getUrlDecoder().decode(encoded)
            when (codec) {
                'z' -> String(inflateRaw(bytes), Charsets.UTF_8)
                'u' -> String(bytes, Charsets.UTF_8)
                else -> return PlanDecodeResult.MalformedPayload
            }
        } catch (e: Exception) {
            return PlanDecodeResult.MalformedPayload
        }

        val json = try { JSONObject(jsonText) } catch (e: Exception) {
            return PlanDecodeResult.MalformedPayload
        }

        if (json.optInt("v", -1) != SUPPORTED_VERSION) return PlanDecodeResult.UnsupportedVersion
        if (json.optString("t") != "plan") return PlanDecodeResult.MalformedPayload
        val lifterId = json.optString("l")
        if (lifterId != expectedLifterId) return PlanDecodeResult.NotAddressedToYou

        return try {
            PlanDecodeResult.Success(parsePayload(json, jsonText))
        } catch (e: Exception) {
            PlanDecodeResult.MalformedPayload
        }
    }

    /** Inverse of `CoachShare.deflateRaw`: same raw-DEFLATE, no zlib wrapper. */
    private fun inflateRaw(bytes: ByteArray): ByteArray {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(bytes)
            val buffer = ByteArray(8 * 1024)
            val out = ByteArrayOutputStream(bytes.size * 3)
            while (!inflater.finished()) {
                val written = inflater.inflate(buffer)
                out.write(buffer, 0, written)
                if (written == 0) break  // No progress made, stop to avoid infinite loop
            }
            out.toByteArray()
        } catch (e: DataFormatException) {
            throw e
        } finally {
            inflater.end()
        }
    }

    private fun parsePayload(json: JSONObject, rawJson: String): PlanPayload {
        val recipesJson = json.optJSONArray("r")
        val recipes = if (recipesJson == null) emptyList() else
            (0 until recipesJson.length()).map { i ->
                val o = recipesJson.getJSONObject(i)
                val u = o.optJSONArray("u")
                val nutrition = if (u != null && u.length() >= 5) RecipeNutrition(
                    calories = u.getDouble(0),
                    proteinG = u.getDouble(1),
                    carbsG = u.getDouble(2),
                    fatG = u.getDouble(3),
                    fiberG = u.getDouble(4)
                ) else null
                val ingredients = o.optJSONArray("i")
                val steps = o.optJSONArray("t")
                PlanRecipe(
                    name = o.optString("n", ""),
                    servings = o.optDouble("s", 1.0),
                    nutritionPerServing = nutrition,
                    ingredients = ingredients?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
                    steps = steps?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
                )
            }

        val mealsJson = json.optJSONArray("m")
        val meals = if (mealsJson == null) emptyList() else
            (0 until mealsJson.length()).map { i ->
                val o = mealsJson.getJSONObject(i)
                PlanMeal(
                    date = o.optString("d", ""),
                    mealSlot = o.optInt("s", 2),
                    recipeIndex = o.optInt("x", -1),
                    servings = o.optDouble("q", 1.0)
                )
            }

        val workoutsJson = json.optJSONArray("w")
        val workouts = if (workoutsJson == null) emptyList() else
            (0 until workoutsJson.length()).map { i ->
                val o = workoutsJson.getJSONObject(i)
                val exercisesJson = o.optJSONArray("e")
                val exercises = if (exercisesJson == null) emptyList() else
                    (0 until exercisesJson.length()).map { j ->
                        val eo = exercisesJson.getJSONObject(j)
                        val setsJson = eo.optJSONArray("s")
                        val sets = if (setsJson == null) emptyList() else
                            (0 until setsJson.length()).map { k -> parseSet(setsJson.getJSONArray(k)) }
                        PlanWorkoutExercise(
                            name = eo.optString("n", ""),
                            equipment = eo.optString("q", ""),
                            note = eo.optString("c", ""),
                            sets = sets
                        )
                    }
                PlanWorkout(name = o.optString("n", ""), exercises = exercises)
            }

        val sessionsJson = json.optJSONArray("k")
        val sessions = if (sessionsJson == null) emptyList() else
            (0 until sessionsJson.length()).map { i ->
                val o = sessionsJson.getJSONObject(i)
                PlanSession(date = o.optString("d", ""), workoutIndex = o.optInt("x", -1))
            }

        return PlanPayload(
            coachName = json.optString("n", "Your coach"),
            recipes = recipes,
            meals = meals,
            workouts = workouts,
            sessions = sessions,
            rawJson = rawJson
        )
    }

    /** `[weightLb, reps, rpe, durationSec, distanceMeters]`, trailing nulls trimmed, any prefix may be null. */
    private fun parseSet(array: org.json.JSONArray): PlanSet {
        fun d(i: Int): Double? = if (i < array.length() && !array.isNull(i)) array.getDouble(i) else null
        fun n(i: Int): Int? = if (i < array.length() && !array.isNull(i)) array.getInt(i) else null
        return PlanSet(
            weightLb = d(0),
            reps = n(1),
            rpe = d(2),
            durationSec = n(3),
            distanceMeters = d(4)
        )
    }
}
