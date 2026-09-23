package com.dugcanlift.macrocalc.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A coach's road picks: the Road Food items they marked in their own app and
 * sent in the plan link.
 *
 * On the wire this is `rf`, a flat list of Road Food item ids and nothing else
 * -- no chain ids, no macros, no "all of Wendy's" (coach/PLAN-FORMAT.md "Road
 * picks"). A pick says "this fits how I want you eating on the road" and says
 * nothing about calories: LIFT already ranks Road Food against what is left of
 * today, and a pick must not put a coach's tick in front of that arithmetic.
 *
 * **What arrives replaces what is held, whole; a plan with no `rf` changes
 * nothing.** A link carrying picks is the coach's current answer. A link
 * without the key is silent about picks rather than a retraction -- that is
 * also what every older Coach and every "here is a recipe" send looks like --
 * so clearing them is something this phone does, on the Road Food screen.
 *
 * **An id this build's `road-food.json` does not have is skipped, silently**,
 * everywhere it would be drawn or counted ([RoadFood.withPicks]). The coach's
 * copy of the file and this one are two builds of two apps, updated at
 * different times; an item withdrawn since the plan was sent is not a broken
 * row, and it reappears by itself if the item does. Nothing is thrown away on
 * arrival for that reason.
 *
 * Tracked and shown, never targeted, like saturated fat and the imbalance
 * figure: a pick is drawn as a pick, and nothing anywhere judges what was
 * eaten against it.
 *
 * Kept on this phone only ([SettingsStore.roadPicks]) and deliberately not in
 * the backup, the rule `roadRecent` follows -- LIFT web's `lift.roadPicks` is
 * not in its `STORED` list either.
 */
data class RoadPicks(
    val ids: List<String>,
    /** The coach's own name, as the plan's `n` gave it; blank when it gave none. */
    val from: String = "",
    /** When the plan that carried them arrived, in epoch milliseconds. */
    val at: Long = 0L
) {
    private val who: String get() = from.trim()

    /**
     * "Doug's picks" when the coach named themselves, "Your coach's picks"
     * otherwise. One function, so the heading on the picker, the line above a
     * list and the label on a row cannot drift apart.
     */
    fun label(suffix: String): String =
        if (who.isNotEmpty()) "$who’s $suffix" else "Your coach’s $suffix"

    fun toJson(): String = JSONObject()
        .put("ids", JSONArray(ids))
        .put("from", from)
        .put("at", at)
        .toString()

    companion object {
        /**
         * The stored shape: trimmed strings, no blanks, no duplicates, in the
         * order they arrived. Anything else in the list is dropped rather than
         * failing -- a picks list is never worth refusing a plan over.
         */
        fun normalise(ids: List<String?>): List<String> {
            val out = LinkedHashSet<String>()
            ids.forEach { raw ->
                val id = raw?.trim().orEmpty()
                if (id.isNotEmpty()) out.add(id)
            }
            return out.toList()
        }

        private fun strings(array: JSONArray?): List<String?> {
            if (array == null) return emptyList()
            return (0 until array.length()).map { array.opt(it) as? String }
        }

        /**
         * A plan's picks, or null when it carried none.
         *
         * Null is "this plan says nothing about picks", which is what an absent
         * `rf`, an empty one and a junk one all mean: the caller leaves what is
         * stored exactly as it was. `rf` is read straight off the payload's own
         * JSON because the shared decoder models no picks -- see
         * `PlanRoadPicksOldDecoderTest`.
         */
        fun fromPlan(rawJson: String, at: Long = System.currentTimeMillis()): RoadPicks? {
            val root = try { JSONObject(rawJson) } catch (e: Exception) { return null }
            val ids = normalise(strings(root.optJSONArray("rf")))
            if (ids.isEmpty()) return null
            return RoadPicks(ids = ids, from = (root.opt("n") as? String).orEmpty(), at = at)
        }

        /** Reads what [toJson] wrote; null for anything that is not that, including no picks. */
        fun fromJson(text: String?): RoadPicks? {
            if (text.isNullOrBlank()) return null
            val root = try { JSONObject(text) } catch (e: Exception) { return null }
            val ids = normalise(strings(root.optJSONArray("ids")))
            if (ids.isEmpty()) return null
            return RoadPicks(
                ids = ids,
                from = (root.opt("from") as? String).orEmpty(),
                at = root.optLong("at", 0L)
            )
        }
    }
}
