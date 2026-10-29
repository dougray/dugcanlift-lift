package com.dugcanlift.macrocalc

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.dugcanlift.macrocalc.data.RecipeLink
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.w3c.dom.Element

/**
 * A "Send to LIFT" link reaches [MainActivity] as a VIEW intent, beside the plan links that
 * already do. Each goes to its own prompt and only its own, both are read once by the same rule,
 * and the manifest gives the custom scheme a filter of its own so the https App Link filter is
 * exactly what it was.
 */
@RunWith(RobolectricTestRunner::class)
class RecipeLinkIntentTest {

    private val fixtureLink: String
        get() = javaClass.classLoader!!.getResource("fixtures/recipe-link.txt")!!.readText().trim()

    private fun view(link: String) = Intent(Intent.ACTION_VIEW, Uri.parse(link))

    @Test
    fun `a recipe link goes to the recipe prompt whole, and never to the plan prompt`() {
        val intent = view(fixtureLink)
        assertEquals(fixtureLink, RecipeLinkPrompt().onCreate(intent, null))
        assertNull("a recipe is not a plan", PlanLinkPrompt().onCreate(intent, null))
        assertNull(PlanLinkPrompt().onNewIntent(intent))
    }

    @Test
    fun `a plan link goes to the plan prompt as before, and never to the recipe prompt`() {
        val intent = view("https://www.dugcanlift.com/lift/#1zRiley")
        assertEquals("1zRiley", PlanLinkPrompt().onCreate(intent, null))
        assertNull(RecipeLinkPrompt().onCreate(intent, null))
        assertNull(RecipeLinkPrompt().onNewIntent(intent))
    }

    @Test
    fun `the link Android hands over still decodes to the stew`() {
        val link = RecipeLinkPrompt().onNewIntent(view(fixtureLink))
        val opened = RecipeLink.open(link) as RecipeLink.Opened.Review
        assertTrue(opened.draft.recipe.name.startsWith("Beef & barley stew"))
    }

    @Test
    fun `a recipe link with nothing after it is still answered, by its message`() {
        val link = RecipeLinkPrompt().onCreate(view("dugcanliftlift://recipe"), null)
        assertEquals(RecipeLink.Opened.Unreadable, RecipeLink.open(link))
    }

    @Test
    fun `a saved or cancelled recipe is not opened again when the activity is recreated`() {
        val launch = view(fixtureLink)
        val prompt = RecipeLinkPrompt()
        prompt.onCreate(launch, null)
        prompt.answered()
        val state = Bundle().also(prompt::save)
        assertNull(RecipeLinkPrompt().onCreate(launch, state))
    }

    @Test
    fun `an unanswered recipe comes back after a recreation`() {
        val launch = view(fixtureLink)
        val prompt = RecipeLinkPrompt()
        prompt.onCreate(launch, null)
        val state = Bundle().also(prompt::save)
        assertEquals(fixtureLink, RecipeLinkPrompt().onCreate(launch, state))
    }

    @Test
    fun `the two prompts keep separate saved state`() {
        val plan = PlanLinkPrompt().apply { onNewIntent(view("https://www.dugcanlift.com/lift/#1zRiley")) }
        val recipe = RecipeLinkPrompt().apply { onNewIntent(view(fixtureLink)) }
        val state = Bundle().also(plan::save).also(recipe::save)
        assertEquals("1zRiley", PlanLinkPrompt().onCreate(view(fixtureLink), state))
        assertEquals(fixtureLink, RecipeLinkPrompt().onCreate(view(fixtureLink), state))
    }

    @Test
    fun `a recipe link replayed from recents is not opened again`() {
        val replay = view(fixtureLink).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(RecipeLinkPrompt().onCreate(replay, null))
    }

    @Test
    fun `the messages are the shared wording, with LIFT`() {
        assertEquals(
            "That recipe link could not be read, so nothing was added. " +
                "Open the recipe page and tap Send to LIFT again.",
            recipeLinkMessage(RecipeLink.Opened.Unreadable)
        )
        assertEquals(
            "That page has no recipe LIFT can read. Copy the recipe text and use Paste a recipe instead.",
            recipeLinkMessage(RecipeLink.Opened.NoRecipe)
        )
    }

    // MARK: - The manifest

    private val android = "http://schemas.android.com/apk/res/android"

    private val mainActivity: Element by lazy {
        val file = File("src/main/AndroidManifest.xml")
        val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file).documentElement
        elements(root, "activity").single { it.attr("name") == ".MainActivity" }
    }

    private fun elements(parent: Element, tag: String): List<Element> {
        val nodes = parent.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.attr(name: String) = getAttributeNS(android, name)

    private fun filters() = elements(mainActivity, "intent-filter")

    @Test
    fun `the recipe scheme has a browsable VIEW filter of its own`() {
        val filter = filters().single { f -> elements(f, "data").any { it.attr("scheme") == RecipeLink.SCHEME } }
        assertEquals(listOf("android.intent.action.VIEW"), elements(filter, "action").map { it.attr("name") })
        assertEquals(
            setOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"),
            elements(filter, "category").map { it.attr("name") }.toSet()
        )
        val data = elements(filter, "data").single()
        assertEquals(RecipeLink.HOST, data.attr("host"))
        assertFalse("a custom scheme never sits in an autoVerify filter", filter.attr("autoVerify") == "true")
    }

    @Test
    fun `the https App Link filter is unchanged`() {
        val filter = filters().single { it.attr("autoVerify") == "true" }
        val data = elements(filter, "data").single()
        assertEquals("https", data.attr("scheme"))
        assertEquals("www.dugcanlift.com", data.attr("host"))
        assertEquals("/lift/", data.attr("pathPrefix"))
    }
}
