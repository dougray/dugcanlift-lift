package com.dugcanlift.macrocalc.data

import com.dugcanlift.macrocalc.buildRecipe
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Deflater

/**
 * [RecipeLink] is a port of the kit's `bookmarklet/recipe-link.mjs`, and this is a port of
 * `recipe-link.test.mjs`'s decoder cases, refusal for refusal.
 *
 * `fixtures/recipe-link.txt` and `fixtures/recipe-link-expected.json` are the kit's pair, copied
 * unchanged from `dugcanlift-kit/bookmarklet/fixtures/`. The kit pins the link to what the
 * bookmarklet itself writes for its stand-in page, so agreeing with the pair is agreeing with the
 * bookmarklet people run. Never regenerate either file from Kotlin.
 */
class RecipeLinkTest {

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    private val fixtureLink get() = fixture("recipe-link.txt").trim()

    /** What the bookmarklet does: raw deflate, base64url, no padding. */
    private fun encode(doc: JSONObject): String = encodeText(doc.toString())

    private fun encodeText(json: String): String {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(json.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return "#r1" + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    private fun ok() = JSONObject()
        .put("v", 1)
        .put("url", "https://a.example/r")
        .put("jsonld", JSONArray().put("""{"@type":"Recipe"}"""))

    // MARK: - The kit's pair

    @Test fun `the fixture link decodes to the expected page`() {
        val expected = JSONObject(fixture("recipe-link-expected.json"))
        val page = RecipeLink.decode(fixtureLink)!!
        assertEquals(expected.getString("url"), page.url)
        val blocks = expected.getJSONArray("jsonld")
        assertEquals((0 until blocks.length()).map { blocks.getString(it) }, page.jsonld)
    }

    @Test fun `the fixture is a liftApp link`() {
        assertTrue(fixtureLink.startsWith("dugcanliftlift://recipe#r1"))
    }

    // MARK: - Refusals, as recipe-link.test.mjs lists them

    @Test fun `refusals`() {
        assertNotNull(RecipeLink.decode(encode(ok())))
        assertNull("no fragment", RecipeLink.decode("dugcanliftlift://recipe"))
        assertNull("a log link is not a recipe link", RecipeLink.decode("#1zabc"))
        assertNull("not base64url", RecipeLink.decode("#r1!!!"))
        assertNull("not deflate", RecipeLink.decode("#r1AAAA"))
        assertNull("unknown version", RecipeLink.decode(encode(ok().put("v", 2))))
        assertNull("not http(s)", RecipeLink.decode(encode(ok().put("url", "javascript:alert(1)"))))
        assertNull("url not a string", RecipeLink.decode(encode(ok().put("url", 7))))
        assertNull("jsonld not an array", RecipeLink.decode(encode(ok().put("jsonld", "x"))))
        assertNull("no blocks", RecipeLink.decode(encode(ok().put("jsonld", JSONArray()))))
        assertNull("no string blocks", RecipeLink.decode(encode(ok().put("jsonld", JSONArray().put(3).put(JSONObject.NULL)))))
    }

    @Test fun `refusals the JavaScript reference makes by its types`() {
        assertNull("nothing at all", RecipeLink.decode(null))
        assertNull("an empty payload", RecipeLink.decode("#r1"))
        assertNull("v as text is not v 1", RecipeLink.decode(encode(ok().put("v", "1"))))
        assertNull("no v", RecipeLink.decode(encode(ok().apply { remove("v") })))
        assertNull("a JSON array, not an object", RecipeLink.decode(encodeText("[1]")))
        assertNull("not JSON", RecipeLink.decode(encodeText("not json")))
        assertNull("a truncated stream", RecipeLink.decode(fixtureLink.dropLast(40)))
    }

    @Test fun `a non-string block is dropped, not fatal`() {
        val doc = ok().put("url", "http://a.example/r").put("jsonld", JSONArray().put(5).put("""{"@type":"Recipe"}"""))
        assertEquals(listOf("""{"@type":"Recipe"}"""), RecipeLink.decode(encode(doc))!!.jsonld)
    }

    @Test fun `the scheme of the page address is read in any case`() {
        assertNotNull(RecipeLink.decode(encode(ok().put("url", "HTTPS://A.EXAMPLE/R"))))
    }

    @Test fun `more than MAX_BYTES once inflated is refused, and exactly MAX_BYTES is read`() {
        val big = ok().put("jsonld", JSONArray().put("x".repeat(RecipeLink.MAX_BYTES + 1)))
        assertNull(RecipeLink.decode(encode(big)))

        val shell = """{"v":1,"url":"https://a.example/r","jsonld":[""]}"""
        val fill = "x".repeat(RecipeLink.MAX_BYTES - shell.length)
        val atCap = """{"v":1,"url":"https://a.example/r","jsonld":["$fill"]}"""
        assertEquals(RecipeLink.MAX_BYTES, atCap.toByteArray().size)
        assertEquals(fill, RecipeLink.decode(encodeText(atCap))!!.jsonld.single())
    }

    @Test fun `the fragment alone reads the same as the whole link`() {
        assertEquals(RecipeLink.decode(fixtureLink), RecipeLink.decode(fixtureLink.substringAfter('#')))
    }

    // MARK: - Opening a link: the kit's reader, the first block with a recipe

    @Test fun `the fixture opens the stew for review, filled from the page`() {
        val opened = RecipeLink.open(fixtureLink) as RecipeLink.Opened.Review
        val draft = opened.draft
        val recipe = draft.recipe
        assertEquals("Beef & barley stew — crème fraîche finish 🍲", recipe.name)
        assertTrue("the page states a yield", draft.servingsStated)
        assertEquals(4.0, recipe.servings, 0.0)
        assertEquals(
            listOf(
                "600 g beef chuck, cubed", "150 g pearl barley", "2 carrots, diced",
                "1 onion, chopped", "1.2 L beef stock", "2 tbsp crème fraîche"
            ),
            recipe.ingredients.map { it.rawText }
        )
        assertEquals(600.0, recipe.ingredients.first().grams!!, 0.0)
        assertEquals(
            listOf(
                "Brown the beef in batches.",
                "Add the vegetables, barley and stock; simmer 90 minutes.",
                "Stir in the crème fraîche and serve."
            ),
            recipe.steps
        )
        val nutrition = recipe.nutritionPerServing!!
        assertEquals(520.0, nutrition.calories, 0.0)
        assertEquals(38.0, nutrition.proteinG, 0.0)
        assertEquals(44.0, nutrition.carbsG, 0.0)
        assertEquals(20.0, nutrition.fatG, 0.0)
        assertTrue("a publisher's figure is an estimate", nutrition.estimated)
        assertEquals("https://example.com/recipes/stew", recipe.sourceUrl)
        assertEquals("Ana Núñez", recipe.sourceAuthor)
        assertEquals("example.com", draft.sourceHost)
    }

    @Test fun `the page address is the recipe's source under BACKUP-FORMAT's name, once saved`() {
        val draft = (RecipeLink.open(fixtureLink) as RecipeLink.Opened.Review).draft
        // What the editor's Save builds, from fields exactly as they were filled.
        val saved = buildRecipe(
            existing = null,
            draft = draft.recipe,
            name = draft.recipe.name,
            servings = 4.0,
            totalWeightGrams = null,
            ingredientText = draft.recipe.ingredients.joinToString("\n") { it.rawText },
            stepText = draft.recipe.steps.joinToString("\n"),
            calories = "520", protein = "38", carbs = "44", fat = "20", fiber = "",
            saturatedFat = "", sugar = "", sodium = ""
        )
        assertEquals("https://example.com/recipes/stew", saved.toJson().getString("sourceUrl"))
        assertEquals("Ana Núñez", saved.sourceAuthor)
        assertTrue("still says it is an estimate", saved.nutritionPerServing!!.estimated)
        assertEquals(6, saved.ingredients.size)
    }

    @Test fun `a link that cannot be read fills nothing`() {
        assertEquals(RecipeLink.Opened.Unreadable, RecipeLink.open("dugcanliftlift://recipe#r1garbage!!"))
        assertEquals(RecipeLink.Opened.Unreadable, RecipeLink.open("dugcanliftlift://recipe"))
    }

    @Test fun `a link whose blocks hold no recipe fills nothing`() {
        val breadcrumbs = """{"@context":"https://schema.org","@type":"BreadcrumbList","itemListElement":[]}"""
        val link = encode(ok().put("jsonld", JSONArray().put(breadcrumbs).put("not json, but says recipe")))
        assertEquals(RecipeLink.Opened.NoRecipe, RecipeLink.open(link))
    }

    @Test fun `the first block that holds a recipe is the one read`() {
        val first = """{"@type":"WebPage","name":"Recipes"}"""
        val second = """{"@type":"Recipe","name":"Second","recipeYield":"2"}"""
        val third = """{"@type":"Recipe","name":"Third"}"""
        val link = encode(ok().put("jsonld", JSONArray().put(first).put(second).put(third)))
        assertEquals("Second", (RecipeLink.open(link) as RecipeLink.Opened.Review).draft.recipe.name)
    }

    @Test fun `a page with no yield asks for servings rather than guessing`() {
        val block = """{"@type":"Recipe","name":"Bread","recipeYield":"a crowd","recipeIngredient":["500 g flour"]}"""
        val draft = (RecipeLink.open(encode(ok().put("jsonld", JSONArray().put(block)))) as RecipeLink.Opened.Review).draft
        assertFalse(draft.servingsStated)
        assertNull("no nutrition stated is no macros, not zeros", draft.recipe.nutritionPerServing)
    }

    @Test fun `a recipe link is told apart from a plan link by scheme and host`() {
        assertTrue(RecipeLink.isRecipeLink("dugcanliftlift", "recipe"))
        assertTrue(RecipeLink.isRecipeLink("DugCanLiftLift", "RECIPE"))
        assertFalse(RecipeLink.isRecipeLink("https", "www.dugcanlift.com"))
        assertFalse(RecipeLink.isRecipeLink("dugcanliftcoach", "recipe"))
        assertFalse(RecipeLink.isRecipeLink("dugcanliftlift", "import"))
        assertFalse(RecipeLink.isRecipeLink(null, null))
    }

    // MARK: - The bookmarklet this build offers is the kit's bytes

    private val asset = File("src/main/assets/${Bookmarklet.ASSET}")

    @Test fun `the bundled bookmarklets json is the kit's file, byte for byte`() {
        assertTrue("src/main/assets/${Bookmarklet.ASSET} is missing", asset.exists())
        val pinnedFile = File("src/main/assets/bookmarklets.sha256")
        assertTrue("src/main/assets/bookmarklets.sha256 is missing -- copy it from the kit beside the JSON",
            pinnedFile.exists())
        val pinned = pinnedFile.readText().trim()
        assertTrue("bookmarklets.sha256 should be one bare sha256 and nothing else",
            Regex("^[0-9a-f]{64}$").matches(pinned))
        val actual = MessageDigest.getInstance("SHA-256").digest(asset.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "src/main/assets/${Bookmarklet.ASSET} does not match src/main/assets/bookmarklets.sha256.\n" +
                "Copy dugcanlift-kit/bookmarklet/bookmarklets.json AND bookmarklets.sha256 over together.\n" +
                "Never edit either file here, and never re-write the checksum by hand to make this\n" +
                "pass: the kit writes both with `node bookmarklet/build-bookmarklets.mjs`, and every\n" +
                "other build pins the same one, so a hand-written hash only moves the failure further away.",
            pinned,
            actual,
        )
    }

    @Test fun `this build offers the liftApp bookmarklet, exactly as the file holds it`() {
        val text = asset.readText()
        val offered = Bookmarklet.fromJson(text)!!
        assertEquals(JSONObject(text).getString("liftApp"), offered)
        assertTrue(offered.startsWith("javascript:"))
        assertFalse("a raw # would cut the script short", offered.contains('#'))
        val script = URLDecoder.decode(offered.removePrefix("javascript:"), "UTF-8")
        assertTrue("it opens this app's scheme", script.contains("'dugcanliftlift://recipe' + '#r1'"))
        assertTrue("it names LIFT when a page has no recipe", script.contains("no recipe LIFT can read"))
    }
}
