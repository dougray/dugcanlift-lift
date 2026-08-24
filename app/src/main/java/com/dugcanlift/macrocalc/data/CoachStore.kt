package com.dugcanlift.macrocalc.data

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/**
 * Who to send logs to, and what to say about the person sending them.
 *
 * Kept apart from [SettingsStore] because this is the only state in the app
 * that describes a relationship with someone else rather than a preference.
 */
data class LifterProfile(
    val sex: String,
    val age: Int,
    val heightIn: Double
)

class CoachStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("dcl_coach", Context.MODE_PRIVATE)

    var email: String
        get() = prefs.getString(KEY_EMAIL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EMAIL, value.trim()).apply()

    /** What the coach should see this log is from. */
    var lifterName: String
        get() = prefs.getString(KEY_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NAME, value.trim()).apply()

    /**
     * Identifies this person to the coach app across every link they ever
     * send. Generated once and never regenerated — a new id would land them in
     * the coach's roster a second time, as a stranger with the same name.
     */
    val lifterId: String
        get() {
            prefs.getString(KEY_ID, null)?.let { return it }
            val fresh = UUID.randomUUID().toString().take(8)
            prefs.edit().putString(KEY_ID, fresh).apply()
            return fresh
        }

    /**
     * Only ever sets the id if this device has not already got one. Restoring a
     * backup should carry the lifter's identity across so their coach sees the
     * same person, but it must never renumber someone who is already logging
     * here — that would split their history in the coach's roster.
     */
    fun restoreLifterId(id: String) {
        if (prefs.getString(KEY_ID, null) == null) {
            prefs.edit().putString(KEY_ID, id).apply()
        }
    }

    var weeks: Int
        get() = prefs.getInt(KEY_WEEKS, 8)
        set(value) = prefs.edit().putInt(KEY_WEEKS, value).apply()

    /** Send every food entry rather than a day's totals. Much bigger links. */
    var itemisedFood: Boolean
        get() = prefs.getBoolean(KEY_ITEMISED, false)
        set(value) = prefs.edit().putBoolean(KEY_ITEMISED, value).apply()

    val isConfigured: Boolean get() = email.isNotBlank()

    /* ---------- profile ---------- */

    /**
     * Saved when the calculator runs, because a coach reading a 2,400 kcal
     * target wants to know whose 2,400 it is.
     */
    var profile: LifterProfile?
        get() {
            val raw = prefs.getString(KEY_PROFILE, null) ?: return null
            return try {
                val o = JSONObject(raw)
                LifterProfile(
                    sex = o.optString("sex", ""),
                    age = o.optInt("age", 0),
                    heightIn = o.optDouble("heightIn", 0.0)
                )
            } catch (e: Exception) {
                null
            }
        }
        set(value) {
            if (value == null) {
                prefs.edit().remove(KEY_PROFILE).apply()
                return
            }
            val o = JSONObject()
                .put("sex", value.sex)
                .put("age", value.age)
                .put("heightIn", value.heightIn)
            prefs.edit().putString(KEY_PROFILE, o.toString()).apply()
        }

    /* ---------- bodyweight ---------- */

    /**
     * Weights by date, from the calculator. The app has no weigh-in screen, so
     * these are the only real readings it has — recording the date they were
     * entered is what turns a number into a trend the coach can read.
     */
    fun bodyweights(): Map<String, Double> {
        val raw = prefs.getString(KEY_WEIGHTS, null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.getDouble(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun recordBodyweight(lb: Double, date: String = todayKey()) {
        val o = JSONObject()
        bodyweights().forEach { (key, value) -> o.put(key, value) }
        o.put(date, lb)
        prefs.edit().putString(KEY_WEIGHTS, o.toString()).apply()
    }

    companion object {
        private const val KEY_EMAIL = "coach_email"
        private const val KEY_NAME = "lifter_name"
        private const val KEY_ID = "lifter_id"
        private const val KEY_WEEKS = "weeks"
        private const val KEY_ITEMISED = "itemised_food"
        private const val KEY_PROFILE = "profile"
        private const val KEY_WEIGHTS = "bodyweights"

        @Volatile
        private var instance: CoachStore? = null

        fun get(context: Context): CoachStore =
            instance ?: synchronized(this) {
                instance ?: CoachStore(context).also { instance = it }
            }
    }
}
