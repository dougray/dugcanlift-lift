package com.dugcanlift.macrocalc.data

import android.content.Context
import org.json.JSONObject

/**
 * The "Send to LIFT" bookmarklet this build offers, read from `assets/bookmarklets.json`.
 *
 * That file and `bookmarklets.sha256` are the kit's (`dugcanlift-kit/bookmarklet/`), copied
 * byte for byte and pinned by `RecipeLinkTest` the way `recipes.json` is: never edit either
 * here. This build shows the `liftApp` string, whose target is `dugcanliftlift://recipe` --
 * the link [RecipeLink] reads.
 */
object Bookmarklet {
    const val ASSET = "bookmarklets.json"
    const val KEY = "liftApp"

    /** The `javascript:` URL from the file's text, or null if it is not there. */
    fun fromJson(json: String): String? =
        runCatching { JSONObject(json).optString(KEY, "") }.getOrNull()?.takeIf { it.startsWith("javascript:") }

    @Volatile private var cached: String? = null

    fun load(context: Context): String? {
        cached?.let { return it }
        return runCatching {
            context.applicationContext.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrNull()?.let(::fromJson)?.also { cached = it }
    }
}
