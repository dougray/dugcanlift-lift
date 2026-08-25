package com.dugcanlift.macrocalc.data

import android.content.Context
import com.dugcanlift.macrocalc.MacroResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads and writes the backup file the user saves from the dashboard.
 *
 * The format is deliberately the same one the web version of LIFT writes, and
 * the record shapes already match field for field, so a backup taken in a
 * browser restores here and vice versa. That is the only migration path there
 * is: with no account and no server, moving to a new phone means carrying a
 * file across.
 *
 * Restoring is additive. It adds what is missing and never overwrites what is
 * already on the device, so restoring last month's file onto a working phone
 * cannot cost you today's session. The trade is that a restore cannot undo a
 * deletion, which is the safer way round.
 */
object BackupStore {

    private const val VERSION = 1
    private const val APP = "lift"

    data class RestoreResult(val added: Int, val ok: Boolean, val problem: String? = null)

    fun build(context: Context): String {
        val food = FoodRepository.get(context).entries.value
        val workouts = WorkoutRepository.get(context).sessions.value
        val routines = RoutineRepository.get(context).routines.value
        val coach = CoachStore.get(context)
        val settings = SettingsStore.get(context)
        val goal = GoalStore.get(context).get()

        val data = JSONObject()

        data.put("food", JSONArray().apply { food.forEach { put(it.toJson()) } })
        data.put("workouts", JSONArray().apply { workouts.forEach { put(it.toJson()) } })
        data.put("routines", JSONArray().apply { routines.forEach { put(it.toJson()) } })

        goal?.let {
            data.put("goal", JSONObject()
                .put("calories", it.calories)
                .put("proteinG", it.proteinG)
                .put("fatG", it.fatG)
                .put("carbsG", it.carbsG)
                .put("fiberG", it.fiberG))
        }

        data.put("settings", JSONObject()
            .put("focus", settings.focus.name)
            .put("stepGoal", settings.stepGoal))

        // The coach block carries the lifter id on purpose. Without it a
        // restored phone introduces itself to the coach as a new person and
        // turns up in their roster twice.
        data.put("coach", JSONObject()
            .put("email", coach.email)
            .put("you", coach.lifterName)
            .put("id", coach.lifterId)
            .put("weeks", coach.weeks)
            .put("itemised", coach.itemisedFood))

        coach.profile?.let {
            data.put("profile", JSONObject()
                .put("sex", it.sex)
                .put("age", it.age)
                .put("heightIn", it.heightIn))
        }

        val weights = JSONObject()
        coach.bodyweights().forEach { (date, lb) -> weights.put(date, lb) }
        data.put("weights", weights)

        // Steps are not here. They are read from Health Connect at the moment
        // they are needed rather than stored, so this app has nothing of its
        // own to hand over — Health Connect keeps its own history.

        val root = JSONObject()
            .put("v", VERSION)
            .put("app", APP)
            .put("saved", todayKey())
            .put("data", data)

        // Hand back whatever another platform recorded that this one has no
        // field for. Dropping it would mean an iPhone's backup came through
        // here and lost its warmup flags on the way out.
        foreignExt(context)?.let { root.put("ext", it) }

        return root.toString(1)
    }

    fun restore(context: Context, text: String): RestoreResult {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return RestoreResult(0, false, "That file isn't a LIFT backup.")
        }

        if (root.optString("app") != APP) {
            return RestoreResult(0, false, "That file isn't a LIFT backup.")
        }
        val data = root.optJSONObject("data")
            ?: return RestoreResult(0, false, "That file isn't a LIFT backup.")

        var added = 0

        data.optJSONArray("food")?.let { array ->
            val incoming = (0 until array.length()).mapNotNull {
                runCatching { foodEntryFromJson(array.getJSONObject(it)) }.getOrNull()
            }
            added += FoodRepository.get(context).restoreMissing(incoming)
        }

        data.optJSONArray("workouts")?.let { array ->
            val incoming = (0 until array.length()).mapNotNull {
                runCatching { workoutSessionFromJson(array.getJSONObject(it)) }.getOrNull()
            }
            added += WorkoutRepository.get(context).restoreMissing(incoming)
        }

        data.optJSONArray("routines")?.let { array ->
            val incoming = (0 until array.length()).mapNotNull {
                runCatching { routineFromJson(array.getJSONObject(it)) }.getOrNull()
            }
            added += RoutineRepository.get(context).restoreMissing(incoming)
        }

        val goals = GoalStore.get(context)
        if (goals.get() == null) {
            data.optJSONObject("goal")?.let {
                goals.save(MacroResult(
                    calories = it.optInt("calories"),
                    proteinG = it.optInt("proteinG"),
                    fatG = it.optInt("fatG"),
                    carbsG = it.optInt("carbsG"),
                    fiberG = it.optInt("fiberG")))
            }
        }

        val coach = CoachStore.get(context)
        data.optJSONObject("coach")?.let {
            if (coach.email.isBlank()) coach.email = it.optString("email")
            if (coach.lifterName.isBlank()) coach.lifterName = it.optString("you")
            it.optString("id").takeIf { id -> id.isNotBlank() }?.let(coach::restoreLifterId)
        }

        if (coach.profile == null) {
            data.optJSONObject("profile")?.let {
                coach.profile = LifterProfile(
                    sex = it.optString("sex"),
                    age = it.optInt("age"),
                    heightIn = it.optDouble("heightIn"))
            }
        }

        data.optJSONObject("weights")?.let { incoming ->
            val existing = coach.bodyweights()
            incoming.keys().forEach { date ->
                if (!existing.containsKey(date)) {
                    coach.recordBodyweight(incoming.optDouble(date), date)
                }
            }
        }

        // Keep the parts of the file this app cannot read, so saving again
        // returns them intact. See coach/BACKUP-FORMAT.md in the site repo.
        root.optJSONObject("ext")?.let { ext ->
            if (ext.length() > 0) {
                prefs(context).edit().putString(KEY_FOREIGN_EXT, ext.toString()).apply()
            }
        }

        return RestoreResult(added, true)
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences("dcl_backup", Context.MODE_PRIVATE)

    private fun foreignExt(context: Context): JSONObject? {
        val raw = prefs(context).getString(KEY_FOREIGN_EXT, null) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()?.takeIf { it.length() > 0 }
    }

    private const val KEY_FOREIGN_EXT = "foreign_ext"
}
