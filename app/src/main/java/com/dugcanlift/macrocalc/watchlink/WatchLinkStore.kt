package com.dugcanlift.macrocalc.watchlink

import android.content.Context
import org.json.JSONObject

/**
 * The paired watch, and what has been exchanged with it, in SharedPreferences (`dcl_watch`).
 *
 * SharedPreferences rather than a JSON file beside the logs, for the reason `GoalStore` gives: it is
 * an address, a name and two small maps, and it needs no dependency. It is deliberately **not** in
 * `BackupStore`: a Bluetooth pairing belongs to this phone and that watch, and restoring it onto a
 * new phone would remember a bond the new phone does not have.
 *
 * One watch at a time. Pairing a second replaces the first.
 */
class WatchLinkStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("dcl_watch", Context.MODE_PRIVATE)

    val pairedAddress: String? get() = prefs.getString(KEY_ADDRESS, null)
    val pairedName: String? get() = prefs.getString(KEY_NAME, null)
    val isPaired: Boolean get() = pairedAddress != null

    fun isPaired(address: String?): Boolean =
        address != null && pairedAddress?.equals(address, ignoreCase = true) == true

    fun remember(address: String, name: String) {
        prefs.edit().putString(KEY_ADDRESS, address).putString(KEY_NAME, name).apply()
    }

    /** Forget the watch. Sessions it already sent stay in the log — they are the lifter's, not the
     *  link's — but the revision records go, so a re-paired watch starts clean. */
    fun forget() {
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_NAME).remove(KEY_SESSIONS).remove(KEY_PLANS).apply()
    }

    /** What this phone last stored for a session the watch sent. See [WatchPlanMapper.reconcile]. */
    fun appliedSession(sessionId: String): AppliedRevision? = read(KEY_SESSIONS, sessionId)

    fun recordSession(sessionId: String, applied: AppliedRevision) = write(KEY_SESSIONS, sessionId, applied)

    /** The revision and content hash of the last plan pushed under an id. */
    fun pushedPlan(planId: String): AppliedRevision? = read(KEY_PLANS, planId)

    fun recordPlan(planId: String, pushed: AppliedRevision) = write(KEY_PLANS, planId, pushed)

    private fun read(key: String, id: String): AppliedRevision? {
        val map = runCatching { JSONObject(prefs.getString(key, null) ?: return null) }.getOrNull() ?: return null
        val entry = map.optJSONObject(id) ?: return null
        if (!entry.has("r") || !entry.has("h")) return null
        return AppliedRevision(entry.getInt("r"), entry.getInt("h"))
    }

    private fun write(key: String, id: String, value: AppliedRevision) {
        val map = runCatching { JSONObject(prefs.getString(key, null) ?: "{}") }.getOrNull() ?: JSONObject()
        map.put(id, JSONObject().put("r", value.revision).put("h", value.storedHash))
        // Bounded, oldest out: one entry per session or plan the watch has ever exchanged would
        // otherwise grow for the life of the install. A few hundred covers any real training block.
        while (map.length() > MAX_ENTRIES) map.remove(map.keys().next())
        prefs.edit().putString(key, map.toString()).apply()
    }

    companion object {
        private const val KEY_ADDRESS = "paired_watch_address"
        private const val KEY_NAME = "paired_watch_name"
        private const val KEY_SESSIONS = "applied_sessions"
        private const val KEY_PLANS = "pushed_plans"
        private const val MAX_ENTRIES = 400

        @Volatile private var instance: WatchLinkStore? = null

        fun get(context: Context): WatchLinkStore =
            instance ?: synchronized(this) { instance ?: WatchLinkStore(context).also { instance = it } }
    }
}
