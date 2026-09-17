package com.dugcanlift.macrocalc

import android.content.Intent
import android.os.Bundle

/**
 * Which coach's plan link [MainActivity] should ask about, read once per link.
 *
 * A plan link opens the activity with a VIEW intent, and that intent stays the
 * activity's `intent` for as long as it lives. A theme or density change
 * recreates the activity (neither is in `configChanges`), and `onCreate` used to
 * decode `intent` again -- so a plan already accepted asked "Plan from Coach" a
 * second time. The launch intent is now read on a fresh start only; a
 * recreation brings the question back only if it was still unanswered.
 */
internal class PlanLinkPrompt {

    /** The link being asked about, until it is answered. */
    var unanswered: String? = null
        private set

    /**
     * The link to ask about from `onCreate`, or null.
     *
     * Relaunching from recents after the saved state is gone replays the
     * original intent too, flagged as from history; that link was asked about
     * when it first arrived.
     */
    fun onCreate(intent: Intent?, savedInstanceState: Bundle?): String? {
        val link = if (savedInstanceState != null) {
            savedInstanceState.getString(STATE_UNANSWERED)
        } else if (intent != null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            fragmentOf(intent)
        } else {
            null
        }
        unanswered = link
        return link
    }

    /** A link arriving while the app runs is always new, and always asked. */
    fun onNewIntent(intent: Intent?): String? {
        val link = fragmentOf(intent) ?: return null
        unanswered = link
        return link
    }

    /** Declined, dismissed, or accepted: a recreation now asks nothing. */
    fun answered() {
        unanswered = null
    }

    fun save(outState: Bundle) {
        unanswered?.let { outState.putString(STATE_UNANSWERED, it) }
    }

    private fun fragmentOf(intent: Intent?): String? = intent?.data?.fragment

    private companion object {
        const val STATE_UNANSWERED = "unanswered_plan_fragment"
    }
}
