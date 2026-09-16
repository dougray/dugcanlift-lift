package com.dugcanlift.macrocalc.data

import android.content.Context
import com.dugcanlift.kit.IngredientParser
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
        adoptPreservedOutdoor(context)
        val food = FoodRepository.get(context).entries.value
        val workouts = WorkoutRepository.get(context).sessions.value
        val routines = RoutineRepository.get(context).routines.value
        val coach = CoachStore.get(context)
        val settings = SettingsStore.get(context)
        val goal = GoalStore.get(context).get()
        val cook = RecipeRepository.get(context)

        // Sections another client wrote that this app does not store go in
        // first and are overwritten below by anything this app does store --
        // a preserved copy must never roll back the device's own data.
        val data = JSONObject()
        foreignData(context)?.let { preserved ->
            preserved.keys().forEach { k ->
                if (k !in STORED && k !in NEVER_BACKED_UP) data.put(k, preserved.get(k))
            }
        }
        data.put("recipes", JSONArray().apply { cook.recipesForBackup().forEach { put(it.toJson()) } })
        data.put("plan", JSONArray().apply { cook.planForBackup().forEach { put(it.toJson()) } })

        data.put("food", JSONArray().apply { food.forEach { put(it.toJson()) } })
        data.put("workouts", JSONArray().apply { workouts.forEach { put(it.toJson()) } })
        data.put("routines", JSONArray().apply { routines.forEach { put(it.toJson()) } })
        data.put("outdoor", JSONArray().apply {
            OutdoorActivityRepository.get(context).activitiesForBackup().forEach { put(outdoorForBackup(it)) }
        })

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

        adoptPreservedOutdoor(context)

        var added = 0

        // An older iPhone file carries sugar and sodium only here, keyed by
        // record id; read them when the common field is missing.
        val ios = root.optJSONObject("ext")?.optJSONObject("ios")

        data.optJSONArray("food")?.let { array ->
            val incoming = (0 until array.length()).mapNotNull {
                runCatching {
                    val entry = foodEntryFromJson(array.getJSONObject(it))
                    withIosDetails(entry, iosExtras(ios?.optJSONObject("food"), entry.id))
                }.getOrNull()
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

        data.optJSONArray("outdoor")?.let { array ->
            val incoming = (0 until array.length()).mapNotNull {
                runCatching { outdoorForRestore(array.getJSONObject(it)) }.getOrNull()
            }
            added += OutdoorActivityRepository.get(context).restoreMissing(incoming)
        }

        // Recipes and the meal plan, restored together so a planned meal can be
        // checked against recipes arriving in this same file.
        val incomingRecipes = data.optJSONArray("recipes")?.let { array ->
            (0 until array.length()).mapNotNull {
                runCatching {
                    val recipe = recipeForRestore(array.getJSONObject(it))
                    withIosDetails(recipe, iosExtras(ios?.optJSONObject("recipes"), recipe.id))
                }.getOrNull()
            }
        }.orEmpty()
        val incomingPlan = data.optJSONArray("plan")?.let { array ->
            (0 until array.length()).mapNotNull {
                runCatching { plannedMealFromJson(array.getJSONObject(it)) }.getOrNull()
            }
        }.orEmpty()
        if (incomingRecipes.isNotEmpty() || incomingPlan.isNotEmpty()) {
            added += RecipeRepository.get(context).restoreMissing(incomingRecipes, incomingPlan)
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

        // The same promise for `data`: a section this app does not store --
        // web's `steps`, or one a newer client adds -- is kept and written back
        // out, rather than lost the moment this app re-saves the file.
        val unknown = JSONObject()
        data.keys().forEach { k -> if (k !in STORED && k !in NEVER_BACKED_UP) unknown.put(k, data.get(k)) }
        if (unknown.length() > 0) {
            val merged = foreignData(context) ?: JSONObject()
            unknown.keys().forEach { k -> merged.put(k, unknown.get(k)) }
            prefs(context).edit().putString(KEY_FOREIGN_DATA, merged.toString()).apply()
        }

        return RestoreResult(added, true)
    }

    /**
     * A recipe from a file, with its ingredient lines reparsed rather than
     * trusted. `rawText` is the contract and every client runs the same parser;
     * only the fields the parser does not own -- `optional`, `note` -- are
     * carried across, so a cached quantity cannot outlive a line that no longer
     * parses to it.
     */
    internal fun recipeForRestore(o: JSONObject): Recipe {
        val recipe = recipeFromJson(o)
        return recipe.copy(ingredients = recipe.ingredients.map { cached ->
            IngredientParser.parse(cached.rawText).copy(optional = cached.optional, note = cached.note)
        })
    }

    /**
     * The `ext.ios.<section>` record for [id], matched case-insensitively
     * because iOS writes ids in upper case and a file may have been through a
     * client that lowered them.
     */
    internal fun iosExtras(section: JSONObject?, id: String): JSONObject? {
        if (section == null) return null
        section.optJSONObject(id)?.let { return it }
        val key = section.keys().asSequence().firstOrNull { it.equals(id, ignoreCase = true) } ?: return null
        return section.optJSONObject(key)
    }

    /**
     * Sugar and sodium from an iPhone file's `ext.ios.food` when the common
     * fields are missing. Before BACKUP-FORMAT gave them common names, iOS
     * wrote them only there; the common field wins whenever both exist.
     * iOS never recorded saturated fat, so there is nothing to fall back to.
     */
    internal fun withIosDetails(entry: FoodEntry, extras: JSONObject?): FoodEntry =
        if (extras == null) entry else entry.copy(
            sugarG = entry.sugarG ?: optNutrient(extras, "sugarG"),
            sodiumMg = entry.sodiumMg ?: optNutrient(extras, "sodiumMg")
        )

    /** The same fallback for a recipe, from `ext.ios.recipes`. A recipe with
     *  no macros has nowhere to hold them, so it is left as it is. */
    internal fun withIosDetails(recipe: Recipe, extras: JSONObject?): Recipe {
        val nutrition = recipe.nutritionPerServing
        if (extras == null || nutrition == null) return recipe
        return recipe.copy(nutritionPerServing = nutrition.copy(
            sugarG = nutrition.sugarG ?: optNutrient(extras, "sugarG"),
            sodiumMg = nutrition.sodiumMg ?: optNutrient(extras, "sodiumMg")
        ))
    }

    /**
     * One activity in BACKUP-FORMAT's `outdoor[]` shape: this app's own storage
     * names, and only the fields the format lists. Mirrors LIFT web's
     * `LiftOutdoor.toBackup`.
     *
     * `healthConnectRecordId` is deliberately left out. It says this phone
     * exported the activity to its Health Connect; carried to another phone it
     * would claim an export that never happened there, and the export button
     * would never appear. `activeCalories` (never set by this app) and a point's
     * `verticalAccuracyMeters` (only used for that export) are not in the format.
     */
    internal fun outdoorForBackup(a: OutdoorActivity): JSONObject = JSONObject()
        .put("id", a.id)
        .put("activityType", a.activityType.name)
        .put("startedAtEpochMs", a.startedAtEpochMs)
        .put("endedAtEpochMs", a.endedAtEpochMs)
        .put("distanceMeters", a.distanceMeters)
        .put("elevationGainMeters", a.elevationGainMeters)
        .put("routePoints", JSONArray().also { array ->
            a.routePoints.forEach { p ->
                array.put(JSONObject()
                    .put("latitude", p.latitude)
                    .put("longitude", p.longitude)
                    .put("altitudeMeters", p.altitudeMeters)
                    .put("recordedAtEpochMs", p.recordedAtEpochMs)
                    .put("horizontalAccuracyMeters", p.horizontalAccuracyMeters))
            }
        })

    /**
     * An activity from a file, or null for anything that is not a finished
     * activity of a known type -- the rules of LIFT web's
     * `LiftOutdoor.fromBackup`. A recording with no end is never written, even
     * if a file carries one, and an unknown type is skipped rather than guessed
     * as a run.
     *
     * Never carries `healthConnectRecordId`, even from a file that has one: an
     * activity restored here was not exported from this phone.
     *
     * The format allows a null altitude; this app's points always have one, and
     * 0.0 is what its own tracker records before a fix reports altitude.
     */
    internal fun outdoorForRestore(o: JSONObject): OutdoorActivity? {
        val id = when (val raw = o.opt("id")) {
            null, JSONObject.NULL -> return null
            is String -> raw.takeIf { it.isNotEmpty() } ?: return null
            else -> raw.toString()
        }
        val started = finite(o.opt("startedAtEpochMs")) ?: return null
        val ended = finite(o.opt("endedAtEpochMs")) ?: return null
        val type = OutdoorActivityType.entries.firstOrNull { it.name == o.opt("activityType") } ?: return null

        val array = o.optJSONArray("routePoints")
        val points = if (array == null) emptyList() else (0 until array.length()).mapNotNull { i ->
            val p = array.optJSONObject(i) ?: return@mapNotNull null
            val latitude = finite(p.opt("latitude")) ?: return@mapNotNull null
            val longitude = finite(p.opt("longitude")) ?: return@mapNotNull null
            RoutePoint(
                latitude = latitude,
                longitude = longitude,
                altitudeMeters = finite(p.opt("altitudeMeters")) ?: 0.0,
                recordedAtEpochMs = finite(p.opt("recordedAtEpochMs"))?.toLong() ?: started.toLong(),
                horizontalAccuracyMeters = finite(p.opt("horizontalAccuracyMeters")) ?: 0.0,
                verticalAccuracyMeters = 0.0
            )
        }
        return OutdoorActivity(
            id = id,
            activityType = type,
            startedAtEpochMs = started.toLong(),
            endedAtEpochMs = ended.toLong(),
            distanceMeters = finite(o.opt("distanceMeters")) ?: OutdoorActivityMath.totalDistanceMeters(points),
            elevationGainMeters = finite(o.opt("elevationGainMeters"))
                ?: OutdoorActivityMath.elevationGainMeters(points),
            routePoints = points,
            healthConnectRecordId = null
        )
    }

    private fun finite(value: Any?): Double? =
        (value as? Number)?.toDouble()?.takeIf { it.isFinite() }

    /**
     * Before this app stored `outdoor`, a file's `outdoor` section was kept
     * aside with the other sections it did not read, and written back out
     * untouched. Now that `outdoor` is [STORED], that copy would be filtered out
     * of the next save and lost, so it is moved into the repository instead --
     * under the same restore rules as a file, adding only ids this phone does
     * not have -- and removed from the preserved sections.
     *
     * Done at the start of both [build] and [restore], the only two places the
     * preserved copy is read: before a save so the file carries those
     * activities, and before a restore so a second file's merge sees them. It is
     * a no-op once the copy is gone. Not done at app start, so an update never
     * rewrites a user's stores until they use backups again.
     */
    private fun adoptPreservedOutdoor(context: Context) {
        val preserved = foreignData(context) ?: return
        val section = preserved.optJSONArray("outdoor")
        if (!preserved.has("outdoor")) return
        if (section != null) {
            val incoming = (0 until section.length()).mapNotNull {
                runCatching { section.optJSONObject(it)?.let(::outdoorForRestore) }.getOrNull()
            }
            OutdoorActivityRepository.get(context).restoreMissing(incoming)
        }
        preserved.remove("outdoor")
        prefs(context).edit().apply {
            if (preserved.length() == 0) remove(KEY_FOREIGN_DATA)
            else putString(KEY_FOREIGN_DATA, preserved.toString())
        }.apply()
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences("dcl_backup", Context.MODE_PRIVATE)

    private fun foreignData(context: Context): JSONObject? {
        val raw = prefs(context).getString(KEY_FOREIGN_DATA, null) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()?.takeIf { it.length() > 0 }
    }

    private fun foreignExt(context: Context): JSONObject? {
        val raw = prefs(context).getString(KEY_FOREIGN_EXT, null) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()?.takeIf { it.length() > 0 }
    }

    private const val KEY_FOREIGN_EXT = "foreign_ext"
    private const val KEY_FOREIGN_DATA = "foreign_data"

    /** Sections this app stores and writes from its own data. Anything else in
     *  a file's `data` is preserved. `steps` is deliberately absent: this app
     *  reads steps from Health Connect, so a browser's `steps` is preserved,
     *  not stored. */
    internal val STORED = setOf(
        "food", "workouts", "routines", "goal", "settings", "coach", "profile", "weights",
        "recipes", "plan", "outdoor"
    )

    /** Never written and never preserved, by the format: ticks mark one week's
     *  shop, and restoring last month's would show this week's list as bought. */
    internal val NEVER_BACKED_UP = setOf("shopping")
}
