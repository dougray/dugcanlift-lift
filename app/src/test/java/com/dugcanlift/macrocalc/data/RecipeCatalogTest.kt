package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * A port of dugcanlift-kit's `RecipeCatalogTests.swift`, case for case, read
 * against the bundled `recipes.json` rather than `recipes.db` -- plus what only
 * this build has: the asset's checksum and the search that replaces FTS5.
 */
class RecipeCatalogTest {

    // Gradle runs unit tests from the module directory, and these are the bytes
    // packaged into the APK.
    private val asset = File("src/main/assets/${RecipeCatalogStore.ASSET}")
    private val catalogue: List<CatalogRecipe> by lazy { parseRecipeCatalog(asset.readText()) }

    private fun carbonara() = RecipeCatalog.search(catalogue, "carbonara").first()
    private fun wholeDish() = RecipeCatalog.browse(catalogue).first { it.needsServings }

    // MARK: - The bundled file

    @Test fun `the catalogue is bundled and populated`() {
        assertTrue(asset.exists())
        assertEquals(622, catalogue.size)
        assertEquals("every id is distinct", catalogue.size, catalogue.map { it.id }.toSet().size)
    }

    @Test fun `every recipe carries its own licence and attribution`() {
        // CC BY-SA 4.0 requires the credit be given, and the public-domain rows
        // must stay identifiable as public domain.
        for (r in catalogue) {
            assertTrue("${r.id} has no licence", r.license.isNotBlank())
            assertTrue("${r.id} has no attribution", r.attribution.isNotBlank())
        }
        val credits = RecipeCatalog.attributions(catalogue)
        assertTrue(credits.any { it.contains("CC BY-SA 4.0") })
        assertTrue(credits.any { it.contains("Public domain") })
        assertEquals(credits.sorted(), credits)
        assertEquals(credits.distinct(), credits)
    }

    // MARK: - Search

    @Test fun `search finds recipes by name`() {
        val hits = RecipeCatalog.search(catalogue, "carbonara")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.any { it.name.contains("carbonara", ignoreCase = true) })
    }

    @Test fun `search returns ingredients with each hit`() {
        assertTrue("a hit with no ingredients cannot build a shopping list", carbonara().ingredients.isNotEmpty())
    }

    @Test fun `a one-letter query returns nothing rather than everything`() {
        assertTrue(RecipeCatalog.search(catalogue, "c").isEmpty())
        assertTrue(RecipeCatalog.search(catalogue, "  c  ").isEmpty())
    }

    @Test fun `every query word must begin a word, in any order`() {
        val hits = RecipeCatalog.search(catalogue, "carb spag")
        assertEquals("Spaghetti carbonara", hits.first().name)
        assertTrue(RecipeCatalog.search(catalogue, "carbonara zzzz").isEmpty())
        // A prefix of a word, never the middle of one.
        assertTrue(RecipeCatalog.search(catalogue, "arbonara").isEmpty())
    }

    @Test fun `search reads the summary and the country, as the iPhone's index does`() {
        // "guanciale" is in carbonara's summary and ingredients, not its name.
        assertTrue(RecipeCatalog.search(catalogue, "guanciale").any { it.id == "unitools:spaghetti-carbonara" })
        val italian = RecipeCatalog.search(catalogue, "it", limit = 1000).filter { it.country == "IT" }
        assertEquals(catalogue.count { it.country == "IT" }, italian.size)
    }

    @Test fun `case and accents are ignored`() {
        assertEquals(
            RecipeCatalog.search(catalogue, "carbonara").map { it.id },
            RecipeCatalog.search(catalogue, "CARBONÁRA").map { it.id }
        )
    }

    @Test fun `a plural query finds the singular`() {
        val withPotato = catalogue.filter { r -> RecipeCatalog.words(r.name).any { it == "potato" } }
        if (withPotato.isEmpty()) return
        val hits = RecipeCatalog.search(catalogue, "potatoes", limit = 1000).map { it.id }.toSet()
        assertTrue(withPotato.all { it.id in hits })
    }

    @Test fun `search is capped at forty and name hits rank first`() {
        val hits = RecipeCatalog.search(catalogue, "chicken")
        assertTrue(hits.size <= RecipeCatalog.SEARCH_LIMIT)
        val firstMiss = hits.indexOfFirst { !it.name.contains("chicken", ignoreCase = true) }
        if (firstMiss >= 0) {
            assertTrue(hits.drop(firstMiss).none { it.name.contains("chicken", ignoreCase = true) })
        }
    }

    @Test fun `browse is every recipe, interleaving both collections by name`() {
        val page = RecipeCatalog.browse(catalogue)
        assertEquals(catalogue.size, page.size)
        assertEquals(page.map { it.name }.sorted(), page.map { it.name })
        assertTrue(page.take(300).map { it.source }.toSet().size > 1)
    }

    // MARK: - Nutrition honesty

    @Test fun `a published per-serving figure is not rescaled`() {
        val recipe = carbonara()
        assertNotNull(recipe.caloriesPerServing)
        val atStated = recipe.nutrition(recipe.servings ?: 1.0)
        val atDouble = recipe.nutrition((recipe.servings ?: 1.0) * 2)
        assertEquals("a published per-serving figure must not move with the stepper",
            atStated?.calories, atDouble?.calories)
        assertEquals(690.0, atStated!!.calories, 0.0)
    }

    @Test fun `a whole-dish recipe divides by the servings given`() {
        val entry = wholeDish()
        val whole = entry.caloriesTotal!!
        assertEquals(whole / 4, entry.nutrition(4.0)!!.calories, 0.001)
        assertEquals(entry.proteinTotal!! / 6, entry.nutrition(6.0)!!.proteinG, 0.001)
    }

    @Test fun `a yieldless recipe has no per-serving figure of its own`() {
        // Storing whole-pot macros in a per-serving column would silently claim
        // the pot feeds one.
        val yieldless = catalogue.filter { it.needsServings }
        assertTrue(yieldless.isNotEmpty())
        for (r in yieldless) {
            assertNull(r.caloriesPerServing)
            assertNull(r.servings)
        }
    }

    @Test fun `every null-servings recipe is a gutenberg book, and every gutenberg book states no yield`() {
        for (r in catalogue) assertEquals(r.id, r.id.startsWith("gutenberg-"), r.servings == null)
    }

    @Test fun `exactly one nutrition set is ever populated`() {
        for (r in catalogue) {
            assertFalse("${r.name} carries both per-serving and whole-dish macros",
                r.caloriesPerServing != null && r.caloriesTotal != null)
        }
    }

    @Test fun `a recipe with no costable ingredients says why rather than showing zero`() {
        val bare = catalogue.first { it.caloriesPerServing == null && it.caloriesTotal == null }
        assertNull("no macros must stay null, never zeros", bare.nutrition(4.0))
        assertNotNull("a gap the reader can see needs to say what it is", bare.nutritionNote)
    }

    // MARK: - Copying into the library

    @Test fun `makeRecipe parses ingredients and keeps the attribution`() {
        val entry = carbonara()
        val recipe = entry.makeRecipe(2.0)

        assertEquals(entry.name, recipe.name)
        assertEquals(2.0, recipe.servings, 0.0)
        assertEquals(entry.ingredients.size, recipe.ingredients.size)
        assertTrue(recipe.nutritionPerServing!!.estimated)
        assertEquals(entry.steps, recipe.steps)
        assertEquals(entry.prepMinutes, recipe.prepMinutes)
        assertEquals(entry.cookMinutes, recipe.cookMinutes)
        assertEquals(entry.sourceUrl, recipe.sourceUrl)
        assertEquals(entry.attribution, recipe.sourceAuthor)

        // BY-SA credit travels with the copy.
        val transcript = recipe.sourceTranscript!!
        assertTrue(transcript.contains(entry.attribution))
        assertTrue(transcript.contains(entry.license))
    }

    @Test fun `copying a yieldless recipe uses the servings the reader chose`() {
        val entry = wholeDish()
        val recipe = entry.makeRecipe(6.0)
        assertEquals(6.0, recipe.servings, 0.0)
        assertEquals(entry.caloriesTotal!! / 6, recipe.nutritionPerServing!!.calories, 0.001)
        // Per serving times servings is the dish again: nothing was lost or invented.
        assertEquals(entry.caloriesTotal, recipe.totalNutrition!!.calories, 0.001)
        // The caveat that the figure is short travels with it.
        assertTrue(recipe.sourceTranscript!!.contains(entry.nutritionNote!!))
    }

    @Test fun `a recipe with no macros copies with no macros`() {
        val bare = catalogue.first { it.caloriesPerServing == null && it.caloriesTotal == null }
        assertNull(bare.makeRecipe(4.0).nutritionPerServing)
    }

    @Test fun `ingredient lines survive the copy verbatim, through the editor's parser`() {
        // The raw line is the contract: reparsing is how every client stays in
        // agreement about what it means, so the text must not be rewritten.
        val entry = carbonara()
        val recipe = entry.makeRecipe(2.0)
        assertEquals(entry.ingredients, recipe.ingredients.map { it.rawText })
        assertEquals(ingredientsFromLines(entry.ingredients), recipe.ingredients)
        val spaghetti = recipe.ingredients.first()
        assertEquals(200.0, spaghetti.qty!!, 0.0)
        assertEquals("g", spaghetti.unit)
    }

    @Test fun `a copy round-trips through the library's own file format`() {
        val recipe = wholeDish().makeRecipe(5.0)
        assertEquals(recipe, recipeFromJson(recipe.toJson()))
    }

    // MARK: - The copy is the kit's bytes

    /**
     * recipes.json is exported once in `dugcanlift-kit/data/` from
     * LiftReference's `recipes.db` and copied byte for byte into every build
     * that cannot open the database, with `recipes.sha256` beside it -- the
     * arrangement `road-food.json` has (see RoadFoodTest). Every other check
     * here is on what the data *means*, which a stale copy passes cleanly.
     */
    @Test fun `the bundled recipes json is the kit's file, byte for byte`() {
        assertTrue("src/main/assets/${RecipeCatalogStore.ASSET} is missing", asset.exists())
        val pinnedFile = File("src/main/assets/recipes.sha256")
        assertTrue("src/main/assets/recipes.sha256 is missing -- copy it from the kit beside the JSON",
            pinnedFile.exists())
        val pinned = pinnedFile.readText().trim()
        assertTrue("recipes.sha256 should be one bare sha256 and nothing else",
            Regex("^[0-9a-f]{64}$").matches(pinned))
        val actual = MessageDigest.getInstance("SHA-256").digest(asset.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "src/main/assets/${RecipeCatalogStore.ASSET} does not match src/main/assets/recipes.sha256.\n" +
                "Copy dugcanlift-kit/data/recipes.json AND data/recipes.sha256 over together.\n" +
                "Never edit either file here, and never re-write the checksum by hand to make this\n" +
                "pass: the kit writes both with `Tools/export_recipes_json.py`, and every other build\n" +
                "pins the same one, so a hand-written hash only moves the failure further away.",
            pinned,
            actual,
        )
    }
}
