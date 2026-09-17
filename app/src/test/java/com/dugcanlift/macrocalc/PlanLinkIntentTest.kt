package com.dugcanlift.macrocalc

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [PlanLinkPrompt] across the activity's lifecycle. Each "recreation" here does
 * what Android does on a theme or density change: saves state into a Bundle, then
 * builds a fresh activity whose `onCreate` sees the *same* launch intent and that
 * Bundle. Before the fix, `onCreate` decoded the intent again and an accepted
 * plan asked "Plan from Coach" a second time.
 */
@RunWith(RobolectricTestRunner::class)
class PlanLinkIntentTest {

    private fun link(fragment: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.dugcanlift.com/lift/#$fragment"))

    /** Saves [prompt] and returns a new activity's prompt created from [intent]. */
    private fun recreate(prompt: PlanLinkPrompt, intent: Intent): Pair<PlanLinkPrompt, String?> {
        val state = Bundle().also(prompt::save)
        val next = PlanLinkPrompt()
        return next to next.onCreate(intent, state)
    }

    @Test
    fun `a fresh launch from a link asks about it`() {
        assertEquals("1zRiley", PlanLinkPrompt().onCreate(link("1zRiley"), null))
    }

    @Test
    fun `an answered plan is not asked again when the activity is recreated`() {
        val launch = link("1zRiley")
        val prompt = PlanLinkPrompt()
        prompt.onCreate(launch, null)
        prompt.answered()

        val (_, asked) = recreate(prompt, launch)

        assertNull("the launch link was already answered", asked)
    }

    @Test
    fun `an unanswered plan is still asked after a recreation, and only until answered`() {
        val launch = link("1zRiley")
        val first = PlanLinkPrompt()
        first.onCreate(launch, null)

        val (second, asked) = recreate(first, launch)
        assertEquals("1zRiley", asked)

        second.answered()
        assertNull(recreate(second, launch).second)
    }

    @Test
    fun `a new link while running still asks, and is consumed once too`() {
        val launch = link("1zRiley")
        val prompt = PlanLinkPrompt()
        prompt.onCreate(launch, null)
        prompt.answered()

        assertEquals("1zSam", prompt.onNewIntent(link("1zSam")))
        prompt.answered()

        // setIntent() made the new link the activity's intent.
        assertNull(recreate(prompt, link("1zSam")).second)
    }

    @Test
    fun `a link replayed from recents is not asked again`() {
        val replay = link("1zRiley").addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(PlanLinkPrompt().onCreate(replay, null))
    }

    @Test
    fun `an intent with no link asks nothing`() {
        assertNull(PlanLinkPrompt().onCreate(Intent(Intent.ACTION_MAIN), null))
        assertNull(PlanLinkPrompt().onNewIntent(Intent(Intent.ACTION_MAIN)))
    }
}
