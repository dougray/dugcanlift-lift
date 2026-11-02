package com.dugcanlift.macrocalc.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A live session: one workout on Train being trained now, with a done tick on each set.
 *
 * LIFT iPhone keeps its live session on the day (`WorkoutDay.liveStartedAt` / `liveEndedAt`) and a
 * set's tick as its `completedAt`, both carried only in the iPhone's own section of the backup file.
 * This build keeps all three **on this phone only**, here, and nowhere in the log: a ticked set is
 * the same [WorkoutSet] it was before the tick, so the backup file, the CSV export, Send to Coach and
 * the watch link read exactly what a log written by hand gives them.
 *
 * One at a time: starting a live session on another workout moves it there. Ending it forgets the
 * ticks, which only ever meant something while it ran.
 */
data class LiveSession(
    val sessionId: String,
    val startedAtMs: Long,
    val done: Set<String> = emptySet()
) {
    fun ticked(setId: String, isDone: Boolean): LiveSession =
        copy(done = if (isDone) done + setId else done - setId)

    fun toJson(): String = JSONObject().apply {
        put("sessionId", sessionId)
        put("startedAt", startedAtMs)
        put("done", JSONArray(done.sorted()))
    }.toString()

    companion object {
        /** Unreadable is no live session, never a crash. */
        fun fromJson(raw: String?): LiveSession? = runCatching {
            val o = JSONObject(raw ?: return null)
            val done = o.optJSONArray("done")
            LiveSession(
                sessionId = o.getString("sessionId"),
                startedAtMs = o.getLong("startedAt"),
                done = if (done == null) emptySet() else (0 until done.length()).map { done.getString(it) }.toSet()
            )
        }.getOrNull()
    }
}

/**
 * The screen stays on during a live session, so a phone propped on a bench does not lock between
 * sets. On by default; Home → Training settings turns it off. A port of LIFT iPhone's
 * `LiveSessionScreen`, and [LiveSessionScreenTest] of its tests, case for case.
 *
 * A session somebody forgot to end must not keep a phone awake on the nightstand. Past eight hours a
 * session no longer holds the screen, whether or not it was ever ended. The day a session started on
 * does not matter, so one that runs past midnight keeps holding.
 */
object LiveSessionScreen {
    const val ENABLED_KEY = "keep_screen_on_during_live_session"
    const val DEFAULT_ENABLED = true
    const val LONGEST_HOLD_MS = 8L * 3600 * 1000

    /** [liveSince] is the start of every session not yet ended. */
    fun holdsScreenOn(liveSince: List<Long>, enabled: Boolean, nowMs: Long): Boolean {
        if (!enabled) return false
        return liveSince.any { start ->
            val elapsed = nowMs - start
            elapsed >= 0 && elapsed < LONGEST_HOLD_MS
        }
    }

    /** When the answer next changes on its own: the earliest moment a session still holding the screen passes [LONGEST_HOLD_MS]. Null when none is. */
    fun nextCutoff(liveSince: List<Long>, nowMs: Long): Long? =
        liveSince.map { it + LONGEST_HOLD_MS }.filter { it > nowMs }.minOrNull()
}
