package com.dugcanlift.macrocalc

import android.content.Intent
import android.os.Bundle
import com.dugcanlift.macrocalc.data.RecipeLink

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
internal class PlanLinkPrompt : LinkPrompt(
    stateKey = "unanswered_plan_fragment",
    // A recipe link is not a plan: it has a prompt of its own, [RecipeLinkPrompt]. Every other
    // link reads exactly as it always has.
    linkOf = { intent ->
        val data = intent.data
        if (data == null || RecipeLink.isRecipeLink(data.scheme, data.host)) null else data.fragment
    }
)

/**
 * The same read-once rule for a "Send to LIFT" recipe link (`dugcanliftlift://recipe#r1…`), so a
 * recreation or a relaunch from recents never opens the same recipe a second time once it was
 * saved or cancelled, and an unanswered one comes back. Holds the whole link: an empty or
 * unreadable one still has to be answered, by its message.
 */
internal class RecipeLinkPrompt : LinkPrompt(
    stateKey = "unanswered_recipe_link",
    linkOf = { intent ->
        val data = intent.data
        if (data != null && RecipeLink.isRecipeLink(data.scheme, data.host)) intent.dataString else null
    }
)

/** One link, asked about once. See [PlanLinkPrompt]. */
internal open class LinkPrompt(
    private val stateKey: String,
    private val linkOf: (Intent) -> String?
) {

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
            savedInstanceState.getString(stateKey)
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
        unanswered?.let { outState.putString(stateKey, it) }
    }

    private fun fragmentOf(intent: Intent?): String? = intent?.let(linkOf)
}
