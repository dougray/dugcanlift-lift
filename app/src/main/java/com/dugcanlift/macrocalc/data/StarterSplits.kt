package com.dugcanlift.macrocalc.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Ready-made routines — push/pull/legs, upper/lower, full body — for the
 * person who has not written any yet.
 *
 * These waited on the exercise library for a concrete reason, not a tidiness
 * one: a split is a list of exercise names, and a name that does not match
 * what the app searches produces history that lines up with nothing. Every
 * name in `splits.json` exists verbatim in `exercises.json`, and a test
 * checks all of them rather than trusting that.
 *
 * Adding one **copies** it into your own routines with fresh ids. It is then
 * an ordinary routine: rename it, change the sets, delete it. There is no
 * second kind of routine to maintain, and nothing here stays special after
 * the tap.
 *
 * Copy the asset from `dugcanlift-site/lift/` rather than editing it here.
 */
const val STARTER_SPLITS_ASSET = "splits.json"

/**
 * Reads the bundled splits into ordinary [Routine] values.
 *
 * Ids are minted fresh on every read, so a starter and the copy someone adds
 * are never the same object. A malformed routine is skipped rather than
 * throwing: one bad entry should cost that split, not the whole list.
 */
fun parseStarterSplits(json: String): List<Routine> {
    val rows = JSONObject(json).optJSONArray("routines") ?: return emptyList()
    val out = ArrayList<Routine>(rows.length())
    for (i in 0 until rows.length()) {
        val row = rows.optJSONObject(i) ?: continue
        val name = row.optString("name", "")
        if (name.isBlank()) continue

        val list = row.optJSONArray("exercises")
        val exercises = ArrayList<RoutineExercise>(list?.length() ?: 0)
        for (j in 0 until (list?.length() ?: 0)) {
            val e = list?.optJSONObject(j) ?: continue
            val exerciseName = e.optString("name", "")
            if (exerciseName.isBlank()) continue
            exercises.add(
                RoutineExercise(
                    name = exerciseName,
                    equipment = e.optString("equipment", ""),
                    targetSets = e.optInt("sets", 3),
                    targetReps = if (e.has("reps") && !e.isNull("reps")) e.optInt("reps") else null,
                    targetDurationSec = if (e.has("durationSec") && !e.isNull("durationSec")) {
                        e.optInt("durationSec")
                    } else {
                        null
                    },
                )
            )
        }
        if (exercises.isEmpty()) continue

        out.add(Routine(name = name, folder = row.optString("folder", ""), exercises = exercises))
    }
    return out
}

/**
 * Whether this starter is already in [existing].
 *
 * Matched on folder and name rather than id, because a copy has its own id by
 * design. Someone who renamed their copy gets the starter offered again, which
 * is the right way round: the alternative is a starter that vanishes because
 * of an edit they made to something else.
 */
fun List<Routine>.alreadyHas(starter: Routine): Boolean = any {
    it.name.equals(starter.name, ignoreCase = true) &&
        it.folder.equals(starter.folder, ignoreCase = true)
}

/**
 * Holds the parsed splits for the life of the process. Six routines off a
 * bundled file — cheap, but there is no reason to re-read it on every frame.
 */
object StarterSplitStore {

    @Volatile private var cached: List<Routine>? = null
    @Volatile private var failed = false

    suspend fun load(context: Context): List<Routine> = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }
        if (failed) return@withContext emptyList()

        try {
            val json = context.applicationContext.assets
                .open(STARTER_SPLITS_ASSET)
                .bufferedReader()
                .use { it.readText() }
            parseStarterSplits(json).also { cached = it }
        } catch (e: Exception) {
            // A missing or broken asset costs the starter list and nothing
            // else. Writing your own routines is the path that already worked.
            failed = true
            emptyList()
        }
    }
}
