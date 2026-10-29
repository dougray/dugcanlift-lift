package com.dugcanlift.macrocalc

import com.dugcanlift.macrocalc.data.Recipe
import com.dugcanlift.macrocalc.data.RecipeDraft
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The words around "Send to LIFT" are the wording every build shares, with {App} = LIFT, word for
 * word. Android has no Safari line and none of the web-only lines. Change them in every build or
 * in none.
 */
class BookmarkletCopyTest {

    @Test fun `the button and panel title`() {
        assertEquals("Send recipes from your browser", SEND_FROM_BROWSER)
    }

    @Test fun `the panel`() {
        assertEquals(
            "Save this as a bookmark once. On any recipe page, tap the bookmark and the recipe opens " +
                "here for you to check. LIFT never visits the page.",
            BookmarkletCopy.INTRO
        )
        assertEquals("Send to LIFT", BookmarkletCopy.LINK_LABEL)
        assertEquals(
            listOf(
                "1. Tap Copy.",
                "2. Bookmark any page.",
                "3. Edit the bookmark. Name it Send to LIFT, and paste over its address.",
                "4. On a recipe page, type Send to LIFT in the address bar and tap the bookmark."
            ),
            BookmarkletCopy.STEPS
        )
        assertEquals("Copy", BookmarkletCopy.COPY)
        assertEquals("Copied", BookmarkletCopy.COPIED)
        assertEquals("Done", BookmarkletCopy.DONE)
    }

    @Test fun `the paste panel's website sentence`() {
        assertEquals(
            "A recipe website's address will not work here. Use Send recipes from your browser for a website instead.",
            PASTE_WEBSITE_NOTE
        )
    }

    private fun draft(stated: Boolean) = RecipeDraft(
        recipe = Recipe(name = "Curry", sourceUrl = "https://www.bbcgoodfood.com/recipes/easy-chicken-curry"),
        servingsStated = stated
    )

    @Test fun `the form note after an import names the site`() {
        assertEquals("From www.bbcgoodfood.com. Check it, then Save.", recipeDraftNote(draft(stated = true)))
    }

    @Test fun `a page with no yield adds the servings line`() {
        assertEquals(
            "From www.bbcgoodfood.com. Check it, then Save. " +
                "The page did not say how many this serves; set it before saving.",
            recipeDraftNote(draft(stated = false))
        )
    }
}
