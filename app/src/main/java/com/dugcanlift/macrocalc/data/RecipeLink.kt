package com.dugcanlift.macrocalc.data

import com.dugcanlift.kit.ImportedRecipe
import com.dugcanlift.kit.RecipeJsonLd
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * A recipe link from the "Send to LIFT" bookmarklet: `dugcanliftlift://recipe#r1…`.
 *
 * The contract is the kit's `bookmarklet/README.md`, and [decode] is a port of its reference
 * decoder, `bookmarklet/recipe-link.mjs`, refusal for refusal. The bookmarklet runs on a page
 * the person already has open and hands over exactly what Safari's share extension hands LIFT
 * iPhone: the page's address and the raw text of its `ld+json` blocks. Nothing here requests
 * the page, or anything else.
 *
 * Unwrapping is all this file does with the link. The blocks go to the kit's own
 * [RecipeJsonLd], the reader every recipe import uses, so there is no second JSON-LD reader.
 */
object RecipeLink {
    /** The same scheme LIFT iPhone owns, so one bookmark works on either phone. */
    const val SCHEME = "dugcanliftlift"
    const val HOST = "recipe"

    const val PREFIX = "r1"

    /** More than this once inflated is refused, as `recipe-link.mjs`'s `MAX_BYTES`. */
    const val MAX_BYTES = 512 * 1024

    private val BASE64URL = Regex("^[A-Za-z0-9_-]+$")
    private val HTTP = Regex("^https?://", RegexOption.IGNORE_CASE)

    /** What a link carries once unwrapped: the page's address and its recipe blocks. */
    data class Page(val url: String, val jsonld: List<String>)

    /** Whether a VIEW intent's scheme and host make it a recipe link rather than a plan link. */
    fun isRecipeLink(scheme: String?, host: String?): Boolean =
        scheme.equals(SCHEME, ignoreCase = true) && host.equals(HOST, ignoreCase = true)

    /**
     * Unwraps a link, or refuses with null: no `r1` prefix, bad base64url, bad deflate, more than
     * [MAX_BYTES] inflated, JSON that is not `{v: 1, url: http(s), jsonld: [...]}`, or no string
     * blocks. A non-string block is dropped, not fatal. Takes the whole link or the fragment alone.
     */
    fun decode(text: String?): Page? {
        if (text == null) return null
        val hash = text.indexOf('#')
        val fragment = if (hash >= 0) text.substring(hash + 1) else text
        if (!fragment.startsWith(PREFIX)) return null
        val data = fragment.substring(PREFIX.length)
        if (!BASE64URL.matches(data)) return null

        val json = try {
            inflateRaw(Base64.getUrlDecoder().decode(data))
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null

        val doc = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }
        val version = doc.opt("v")
        if (version !is Number || version.toDouble() != 1.0) return null
        val url = doc.opt("url") as? String ?: return null
        val blocks = doc.opt("jsonld") as? JSONArray ?: return null
        if (!HTTP.containsMatchIn(url)) return null
        val jsonld = (0 until blocks.length()).mapNotNull { blocks.opt(it) as? String }
        if (jsonld.isEmpty()) return null
        return Page(url = url, jsonld = jsonld)
    }

    /**
     * Raw deflate (no zlib header), what the bookmarklet's `CompressionStream('deflate-raw')`
     * writes. Null for anything that is not a complete deflate stream, or that inflates past
     * [MAX_BYTES]: the cap is checked as bytes come out, so a small link cannot make a big buffer.
     */
    private fun inflateRaw(bytes: ByteArray): String? {
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes)
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val count = try {
                    inflater.inflate(buffer)
                } catch (e: DataFormatException) {
                    return null
                }
                // With all the input given at once, no output and no end means the stream
                // stopped short (or wants a dictionary): truncated, so refused.
                if (count == 0 && !inflater.finished()) return null
                out.write(buffer, 0, count)
                if (out.size() > MAX_BYTES) return null
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            inflater.end()
        }
    }

    /** What opening a link leads to: a recipe to check, or a plain message and nothing filled. */
    sealed interface Opened {
        data class Review(val draft: RecipeDraft) : Opened
        /** The link itself could not be read. */
        data object Unreadable : Opened
        /** The link was fine, but no block in it held a recipe the reader could use. */
        data object NoRecipe : Opened
    }

    /** Decodes [text] and reads the first block that holds a recipe. */
    fun open(text: String?): Opened {
        val page = decode(text) ?: return Opened.Unreadable
        val recipe = page.jsonld.firstNotNullOfOrNull { RecipeJsonLd.recipeFromJson(it) }
            ?: return Opened.NoRecipe
        return Opened.Review(RecipeDraft.from(recipe, page.url))
    }
}

/**
 * A recipe read off a page, before anyone has looked at it. It fills the recipe editor and is
 * saved only by Save there, through the same path as a recipe typed by hand.
 *
 * [recipe] carries what the editor has no field for -- the page address as [Recipe.sourceUrl]
 * (BACKUP-FORMAT's `sourceUrl`), the author, the block's text as the transcript, the times --
 * and the editor's own fields override the rest on save.
 */
data class RecipeDraft(
    val recipe: Recipe,
    /**
     * Whether the page stated a yield. When it did not, [Recipe.servings] is only the model's
     * placeholder and the editor leaves the field empty for the person to fill: a guessed count
     * would divide every macro on the recipe by a number nobody chose.
     */
    val servingsStated: Boolean
) {
    /** The page's host, for the line that says where this came from. */
    val sourceHost: String?
        get() = recipe.sourceUrl?.let { runCatching { java.net.URI(it).host }.getOrNull() }

    companion object {
        fun from(imported: ImportedRecipe, url: String): RecipeDraft = RecipeDraft(
            recipe = Recipe(
                name = imported.name,
                servings = imported.servings ?: 1.0,
                // Raw lines, reparsed on save by the editor from whatever is in the box then.
                ingredients = ingredientsFromLines(imported.ingredientLines),
                steps = imported.steps,
                // The page's own per-serving figures, flagged estimated by the reader: the
                // publisher's claim. Nothing is costed, and no figure means no macros.
                nutritionPerServing = imported.nutritionPerServing,
                sourceUrl = url,
                sourceAuthor = imported.author,
                sourceTranscript = imported.sourceTranscript,
                prepMinutes = imported.prepMinutes,
                cookMinutes = imported.cookMinutes
            ),
            servingsStated = imported.servings != null
        )
    }
}
