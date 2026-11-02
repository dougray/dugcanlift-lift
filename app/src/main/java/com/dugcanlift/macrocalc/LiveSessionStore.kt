package com.dugcanlift.macrocalc

import android.content.Context
import com.dugcanlift.macrocalc.data.LiveSession
import com.dugcanlift.macrocalc.data.RestStorage
import com.dugcanlift.macrocalc.data.RestTimer
import com.dugcanlift.macrocalc.data.SessionRest
import com.dugcanlift.macrocalc.data.SettingsStore
import com.dugcanlift.macrocalc.data.WorkoutSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The live session and its rest timer, for the whole app: Train draws them, the root keeps the
 * screen on by them ([KeepScreenOnDuringLiveSession]), and [RestOverReceiver] reads the rest when
 * its alarm goes. The rules are [LiveSession], [SessionRest] and [RestTimer]; this only keeps them
 * in `SharedPreferences` (on this phone only, never in the backup file) and hands the timer its
 * notifications.
 */
class LiveSessionStore private constructor(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("dcl_live_session", Context.MODE_PRIVATE)
    private val settings = SettingsStore.get(app)

    private val _keepsScreenOn = MutableStateFlow(settings.keepsScreenOn)
    /** Home → Training settings' "Keep the screen on", watched by the root so a change applies at once. */
    val keepsScreenOn: StateFlow<Boolean> = _keepsScreenOn.asStateFlow()

    fun setKeepsScreenOn(value: Boolean) {
        settings.keepsScreenOn = value
        _keepsScreenOn.value = value
    }

    private val _live = MutableStateFlow(LiveSession.fromJson(prefs.getString(KEY_LIVE, null)))
    val live: StateFlow<LiveSession?> = _live.asStateFlow()

    val timer = RestTimer(
        outlets = RestNotifications(app),
        storage = object : RestStorage {
            override fun read(): String? = prefs.getString(KEY_REST, null)
            override fun write(value: String?) {
                prefs.edit().apply { if (value == null) remove(KEY_REST) else putString(KEY_REST, value) }.apply()
            }
        }
    )

    /** "Start live session" on [session]. Another one live is ended first: one at a time. */
    fun start(session: WorkoutSession, nowMs: Long = System.currentTimeMillis()) {
        if (_live.value != null) timer.sessionEnded()
        write(LiveSession(sessionId = session.id, startedAtMs = nowMs))
    }

    /** "End": the rest goes, the notification goes, and the ticks go with the session they belonged to. */
    fun end() {
        write(null)
        timer.sessionEnded()
    }

    /**
     * The tick on set [setId] of [session]. Ticked, the rest it starts is read off the whole session,
     * because inside a superset the next set is another exercise's ([SessionRest]); unticked, it takes
     * back the rest that set started and only that one.
     */
    fun setTicked(session: WorkoutSession, setId: String, done: Boolean) {
        val live = _live.value?.takeIf { it.sessionId == session.id } ?: return
        val updated = live.ticked(setId, done)
        write(updated)
        if (!done) {
            timer.setUndone(setId)
            return
        }
        val exerciseName = session.exercises.firstOrNull { e -> e.sets.any { it.id == setId } }?.displayName
        val after = SessionRest.after(
            setId = setId,
            exercises = SessionRest.exercises(session, updated.done),
            restSeconds = settings.restSeconds
        )
        timer.setDone(setId, after, exerciseName)
    }

    /** Sets deleted from the log, or an exercise removed: their ticks go, and a rest one of them started. */
    fun setsRemoved(ids: Collection<String>) {
        ids.forEach { timer.setUndone(it) }
        val live = _live.value ?: return
        if (ids.any { it in live.done }) write(live.copy(done = live.done - ids.toSet()))
    }

    /**
     * The live workout was deleted, or is no longer in the log: nothing is live. Called with the log
     * as it stands, so a session restored over by a backup does not stay live forever.
     */
    fun reconcile(sessions: List<WorkoutSession>) {
        val live = _live.value ?: return
        if (sessions.none { it.id == live.sessionId }) end()
    }

    private fun write(live: LiveSession?) {
        _live.value = live
        prefs.edit().apply { if (live == null) remove(KEY_LIVE) else putString(KEY_LIVE, live.toJson()) }.apply()
    }

    companion object {
        private const val KEY_LIVE = "live_session"
        private const val KEY_REST = "rest_countdown"

        @Volatile
        private var instance: LiveSessionStore? = null

        fun get(context: Context): LiveSessionStore =
            instance ?: synchronized(this) {
                instance ?: LiveSessionStore(context).also { instance = it }
            }
    }
}
