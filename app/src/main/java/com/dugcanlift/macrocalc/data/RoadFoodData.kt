package com.dugcanlift.macrocalc.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException

/**
 * One menu item or gas-station product, as `road-food.json` lists it.
 *
 * Every number is null when the file does not list it -- or lists something
 * that is not a finite, non-negative number -- never zero ([roadNumber]).
 * A snack also carries its own [category], [barcode], [checkedOn] and [source];
 * a chain's item takes those from its chain.
 */
data class RoadFoodItem(
    val id: String,
    val name: String,
    val serving: String? = null,
    val kcal: Double? = null,
    val proteinG: Double? = null,
    val fatG: Double? = null,
    val carbsG: Double? = null,
    val fiberG: Double? = null,
    val saturatedFatG: Double? = null,
    val sugarG: Double? = null,
    val sodiumMg: Double? = null,
    val modification: String? = null,
    val category: String? = null,
    val barcode: String? = null,
    val checkedOn: String? = null,
    val source: String? = null
)

/**
 * A chain: its own published nutrition, checked by hand on [checkedOn].
 * [publishedOn] is the date the chain's own document states about itself, and
 * is absent when the document states none -- only as precise as the document
 * is, so "2022-11" where a chart says only "NOVEMBER 2022". [kind] picks its
 * rules.
 */
data class RoadFoodChain(
    val id: String,
    val name: String,
    val kind: String? = null,
    val publishedOn: String? = null,
    val checkedOn: String? = null,
    val source: String? = null,
    val items: List<RoadFoodItem>
)

/**
 * An ordering rule. A plain string in the file has [kinds] null and applies
 * to every chain; `{ "text", "kinds": [...] }` applies only to chains of those
 * kinds (see [RoadFood.rulesFor]).
 */
data class RoadFoodRule(val text: String, val kinds: List<String>? = null)

data class RoadFoodData(
    val chains: List<RoadFoodChain>,
    val snacks: List<RoadFoodItem>,
    val rules: List<RoadFoodRule>
) {
    /** The snack categories in the order the file first lists them. */
    val snackCategories: List<String> get() = snacks.mapNotNull { it.category?.takeIf(String::isNotBlank) }.distinct()
}

/**
 * Reads `road-food.json` -- the spec's "Data format", generated from
 * `dugcanlift-kit/data/road-food.json` and identical on every platform.
 *
 * Lenient where LIFT web's loader is: a chain without an id or an items list
 * is dropped, a snack or item that is not an object is dropped, a rule that is
 * neither a string nor an object with `text` is dropped. Throws only when the
 * file is not a Road Food file at all (no `chains` array).
 */
fun parseRoadFood(json: String): RoadFoodData {
    val root = JSONObject(json)
    val chains = root.optJSONArray("chains") ?: throw IllegalArgumentException("not a Road Food file")
    return RoadFoodData(
        chains = chains.objects().mapNotNull { c ->
            val id = c.optString("id", "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val items = c.optJSONArray("items") ?: return@mapNotNull null
            RoadFoodChain(
                id = id,
                name = c.text("name") ?: id,
                kind = c.text("kind"),
                publishedOn = c.text("publishedOn"),
                checkedOn = c.text("checkedOn"),
                source = c.text("source"),
                items = items.objects().map(::roadItem)
            )
        },
        snacks = root.optJSONArray("snacks")?.objects()?.map(::roadItem) ?: emptyList(),
        rules = root.optJSONArray("rules")?.let { rules ->
            (0 until rules.length()).mapNotNull { i ->
                when (val r = rules.opt(i)) {
                    is String -> RoadFoodRule(r)
                    is JSONObject -> {
                        val text = r.opt("text") as? String ?: return@mapNotNull null
                        val kinds = r.optJSONArray("kinds")?.let { a ->
                            (0 until a.length()).mapNotNull { a.opt(it) as? String }
                        }
                        RoadFoodRule(text, kinds)
                    }
                    else -> null
                }
            }
        } ?: emptyList()
    )
}

private fun roadItem(o: JSONObject): RoadFoodItem = RoadFoodItem(
    id = o.text("id") ?: o.text("name").orEmpty(),
    name = o.text("name").orEmpty(),
    serving = o.text("serving"),
    kcal = roadNumber(o, "kcal"),
    proteinG = roadNumber(o, "proteinG"),
    fatG = roadNumber(o, "fatG"),
    carbsG = roadNumber(o, "carbsG"),
    fiberG = roadNumber(o, "fiberG"),
    saturatedFatG = roadNumber(o, "saturatedFatG"),
    sugarG = roadNumber(o, "sugarG"),
    sodiumMg = roadNumber(o, "sodiumMg"),
    modification = o.text("modification"),
    category = o.text("category"),
    barcode = o.text("barcode"),
    checkedOn = o.text("checkedOn"),
    source = o.text("source")
)

/**
 * `road-food.js`'s `num`: a finite, non-negative number, or null. A numeric
 * string counts, as it does there; a boolean, a blank or anything else is not
 * a number and is not zero.
 */
internal fun roadNumber(o: JSONObject, key: String): Double? {
    if (!o.has(key) || o.isNull(key)) return null
    val n = when (val v = o.opt(key)) {
        is Boolean -> return null
        is Number -> v.toDouble()
        is String -> v.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: return null
        else -> return null
    }
    return n.takeIf { it.isFinite() && it >= 0.0 }
}

private fun JSONObject.text(key: String): String? =
    (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { opt(it) as? JSONObject }

/**
 * The bundled Road Food file, parsed once for the life of the process.
 *
 * Offline by construction: the data is an app asset, read from the APK, and
 * nothing here touches the network. No location of any kind either -- the
 * person picks the chain.
 *
 * **Where the file comes from.** [ASSET] is the real, curated file, copied
 * unchanged from `dugcanlift-kit/data/road-food.json` into
 * `app/src/main/assets/`. Until it is there, a debuggable build falls back to
 * [SAMPLE_ASSET], a fake-name fixture ("Sample Burger Co") that lives only in
 * `app/src/debug/assets/` -- so it is in no release, and nothing fake can
 * ship. A release without the real file has no Road Food entry point
 * ([isBundled]) rather than a screen that says it has no data. Once the real
 * file is in `main`, it wins in every build, debug included.
 */
object RoadFoodStore {
    const val ASSET = "road-food.json"
    const val SAMPLE_ASSET = "road-food-sample.json"

    @Volatile private var cached: RoadFoodData? = null
    @Volatile private var failure: String? = null

    /** Which asset this build carries, or null when it carries neither. */
    fun bundledAsset(context: Context): String? {
        val names = try {
            context.applicationContext.assets.list("")?.toSet().orEmpty()
        } catch (e: Exception) {
            emptySet()
        }
        return listOf(ASSET, SAMPLE_ASSET).firstOrNull { it in names }
    }

    fun isBundled(context: Context): Boolean = bundledAsset(context) != null

    /** The parsed file, or null with [lastError] set when it could not load. */
    suspend fun load(context: Context): RoadFoodData? = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }
        failure?.let { return@withContext null }
        try {
            val asset = bundledAsset(context) ?: throw FileNotFoundException(ASSET)
            val json = context.applicationContext.assets.open(asset).bufferedReader().use { it.readText() }
            parseRoadFood(json).also { cached = it }
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
            null
        }
    }

    val lastError: String? get() = failure
}
