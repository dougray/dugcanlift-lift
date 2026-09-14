package com.dugcanlift.macrocalc.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The 873-exercise reference library from free-exercise-db — the same
 * `exercises.json` the browser build ships and the same 873 rows the iPhone
 * app carries in its `exercises.db`.
 *
 * It is one file on purpose. A coach prescribing "Barbell Squat" and a client
 * logging it are then naming the same lift, which is what makes a history line
 * up across the three versions. Copy it from `dugcanlift-site/lift/` rather
 * than editing it here; the two are checked byte-for-byte.
 *
 * The wire shape is compact — each exercise is a name followed by four indices
 * into the shared `muscles` / `equipment` / `categories` / `levels` arrays:
 *
 *     ["3/4 Sit-Up", 0, 0, 0, 0]
 *
 * Bundled as an asset, so search is local, instant, and works offline by
 * construction. Nothing here touches the network.
 */
data class LibraryExercise(
    val name: String,
    /** Raw, as the file spells it: "abdominals", lowercase. */
    val muscle: String,
    /** Raw, as the file spells it: "body only", lowercase. Filters match this. */
    val equipment: String,
    val category: String,
    val level: String,
) {
    /**
     * The equipment as it gets **stored** when this exercise is logged —
     * "Body Only", not "body only".
     *
     * This is the browser's spelling, and matching it is the point: a lift
     * logged here and the same lift logged in the browser have to be the same
     * lift. Note the iPhone app stores the raw lowercase value instead, so the
     * browser and the phone already disagree on this; that predates the
     * Android library and is not a thing to fix by drifting further.
     */
    val storedEquipment: String get() = titleCaseAscii(equipment)

    /** Matches [LoggedExercise.matchKey], so history can be found by identity. */
    val matchKey: String
        get() = "${name.trim()}|${storedEquipment.trim()}".lowercase(Locale.US)

    /** "Barbell Squat - quadriceps, barbell", the browser's row text. */
    val resultLabel: String
        get() = buildString {
            append(name)
            append(" - ")
            append(muscle)
            if (equipment.isNotBlank()) {
                append(", ")
                append(equipment)
            }
        }
}

/** The equipment chips over the results, in the browser's order. */
val EQUIPMENT_FILTERS = listOf(
    "barbell", "dumbbell", "machine", "cable", "body only", "kettlebells"
)

/** How many rows the picker shows at once. The browser's cap, kept. */
const val EXERCISE_SEARCH_LIMIT = 40

/** Where the bundled copy lives. */
const val EXERCISE_LIBRARY_ASSET = "exercises.json"

/**
 * Reads the compact array-of-arrays form into something with names on it.
 *
 * An index the file doesn't have resolves to an empty string rather than
 * throwing — the browser's `raw.muscles[i] || ''`. One malformed row should
 * cost that row's label, not the whole library.
 */
fun parseExerciseLibrary(json: String): List<LibraryExercise> {
    val root = JSONObject(json)
    val muscles = root.stringList("muscles")
    val equipment = root.stringList("equipment")
    val categories = root.stringList("categories")
    val levels = root.stringList("levels")

    val rows = root.optJSONArray("exercises") ?: return emptyList()
    val out = ArrayList<LibraryExercise>(rows.length())
    for (i in 0 until rows.length()) {
        val row = rows.optJSONArray(i) ?: continue
        val name = row.optString(0, "")
        if (name.isBlank()) continue
        out.add(
            LibraryExercise(
                name = name,
                muscle = muscles.getOrEmpty(row.optInt(1, -1)),
                equipment = equipment.getOrEmpty(row.optInt(2, -1)),
                category = categories.getOrEmpty(row.optInt(3, -1)),
                level = levels.getOrEmpty(row.optInt(4, -1)),
            )
        )
    }
    return out
}

/**
 * The picker's results: filtered by equipment, matched on name or muscle,
 * lifts you have logged before floated to the top.
 *
 * Two deliberate departures from the browser, both because this one has the
 * history to hand:
 *
 * - **Ranking happens before the cap, not after.** The browser slices to 40
 *   and never reorders, so its results are alphabetical. Capping first here
 *   would hide exactly the row the ranking exists to surface — your own
 *   bench press, sitting at position 300 of the alphabet.
 * - **Recency decides ties**, the iPhone app's stable partition: previously
 *   logged exercises come back in most-recent-first order, everything else
 *   keeps the file's ordering.
 *
 * [recentKeys] is [LoggedExercise.matchKey] values, newest first.
 */
fun searchExerciseLibrary(
    library: List<LibraryExercise>,
    query: String,
    equipmentFilter: String = "",
    recentKeys: List<String> = emptyList(),
    limit: Int = EXERCISE_SEARCH_LIMIT,
): List<LibraryExercise> {
    val needle = query.trim().lowercase(Locale.US)

    val hits = library.filter { candidate ->
        (equipmentFilter.isBlank() || candidate.equipment == equipmentFilter) &&
            (needle.isEmpty() ||
                candidate.name.lowercase(Locale.US).contains(needle) ||
                candidate.muscle.lowercase(Locale.US).contains(needle))
    }

    return rankByHistory(hits, recentKeys).take(limit)
}

/**
 * Stable partition: things they have logged before, most recent first, then
 * everything else in the file's own order.
 *
 * Someone who benches every week gets their bench press first; someone who
 * does dips gets dips. Neither ordering is correct in the abstract, which is
 * why no amount of tuning the reference data can substitute for this.
 */
internal fun rankByHistory(
    hits: List<LibraryExercise>,
    recentKeys: List<String>,
): List<LibraryExercise> {
    if (recentKeys.isEmpty()) return hits

    val ranks = HashMap<String, Int>(recentKeys.size)
    recentKeys.forEachIndexed { index, key -> ranks.putIfAbsent(key, index) }

    val used = ArrayList<Pair<Int, LibraryExercise>>()
    val unused = ArrayList<LibraryExercise>(hits.size)
    for (hit in hits) {
        val rank = ranks[hit.matchKey]
        if (rank != null) used.add(rank to hit) else unused.add(hit)
    }
    if (used.isEmpty()) return hits

    return used.sortedBy { it.first }.map { it.second } + unused
}

/**
 * The browser's `titleCase`, transcribed: uppercase any lowercase letter that
 * starts a word. "body only" becomes "Body Only", "e-z curl bar" becomes
 * "E-Z Curl Bar" — the hyphen is a word boundary there, as it is in the
 * JavaScript `\b`.
 *
 * Word characters are the ASCII set JavaScript's `\w` means, not Unicode's,
 * so the two agree on every row this file holds.
 */
internal fun titleCaseAscii(text: String): String {
    val out = StringBuilder(text.length)
    var previousWasWord = false
    for (c in text) {
        val isWord = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
        out.append(if (!previousWasWord && c in 'a'..'z') c.uppercaseChar() else c)
        previousWasWord = isWord
    }
    return out.toString()
}

private fun JSONObject.stringList(key: String): List<String> {
    val array: JSONArray = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).map { array.optString(it, "") }
}

private fun List<String>.getOrEmpty(index: Int): String =
    if (index in indices) this[index] else ""

/**
 * Holds the parsed library for the life of the process.
 *
 * Loaded the first time the picker is opened rather than at startup: someone
 * who only tracks food never pays for it, which is the browser's rule too.
 * A failure is remembered as well as a success — a missing asset should
 * produce one message and a working type-it-yourself field, not a retry on
 * every keystroke.
 */
object ExerciseLibraryStore {

    @Volatile private var cached: List<LibraryExercise>? = null
    @Volatile private var failure: String? = null

    /** The parsed library, or null with [lastError] set when it could not load. */
    suspend fun load(context: Context): List<LibraryExercise>? = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }
        failure?.let { return@withContext null }

        try {
            val json = context.applicationContext.assets
                .open(EXERCISE_LIBRARY_ASSET)
                .bufferedReader()
                .use { it.readText() }
            val parsed = parseExerciseLibrary(json)
            if (parsed.isEmpty()) {
                failure = "the file is empty"
                null
            } else {
                cached = parsed
                parsed
            }
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
            null
        }
    }

    val lastError: String? get() = failure
}
