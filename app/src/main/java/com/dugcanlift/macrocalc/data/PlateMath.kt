package com.dugcanlift.macrocalc.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The bar a lift is loaded on, which decides what the bar itself weighs. A port of LIFT iPhone's
 * `BarKind`.
 *
 * Read from the name as well as the equipment, because the exercise data files every Smith machine
 * lift under "machine" (and one under "barbell"), some EZ-bar curls under "barbell", and has no trap
 * bar at all.
 */
enum class BarKind(val wire: String, val displayName: String) {
    BARBELL("barbell", "Barbell"),
    EZ_BAR("ezBar", "EZ bar"),
    TRAP_BAR("trapBar", "Trap bar"),
    SMITH("smith", "Smith machine");

    companion object {
        /**
         * Null for anything that is not loaded with plates on both ends of a bar. A landmine, a
         * T-bar row, a long-bar row and a linear jammer are filed under "barbell" but have one end
         * anchored, so "per side" means nothing.
         */
        fun of(name: String, equipment: String?): BarKind? {
            val words = normalised(name + " " + (equipment ?: ""))
            val oneEnded = listOf(" landmine ", " t bar ", " long bar ", " linear jammer ")
            if (oneEnded.any { words.contains(it) }) return null
            if (words.contains(" smith ")) return SMITH
            if (words.contains(" trap bar ") || words.contains(" hex bar ")) return TRAP_BAR
            if (words.contains(" ez bar ") || words.contains(" ez curl ")) return EZ_BAR
            if (normalised(equipment ?: "").contains(" barbell ")) return BARBELL
            return null
        }

        /**
         * Lowercased, punctuation to spaces, padded so a phrase matches whole words only: "EZ-Bar"
         * and "e-z curl bar" both become " ez bar ".
         */
        private fun normalised(text: String): String {
            val out = text.lowercase(Locale.US)
                .replace("e-z", "ez")
                .replace("ezbar", "ez bar")
                .map { if (it.isLetter() || it.isDigit()) it else ' ' }
                .joinToString("")
                .split(' ')
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            return " $out "
        }
    }
}

/**
 * The plates someone owns and what their bars weigh. A port of LIFT iPhone's `PlateSetup`, in
 * pounds only: every weight in this build is pounds, so there is one rack, where the iPhone keeps
 * one per unit. A 45 lb plate is stored as 45, the size stamped on it.
 */
data class PlateSetup(
    /** Heaviest first. */
    val plates: List<Plate>,
    private val bars: Map<String, Double>
) {
    data class Plate(
        val weight: Double,
        /** Pairs, because a plate is loaded on both ends. Zero is "not owned". */
        val pairs: Int
    )

    fun barWeight(bar: BarKind): Double = bars[bar.wire] ?: 0.0

    fun withBar(bar: BarKind, weight: Double): PlateSetup =
        copy(bars = bars + (bar.wire to weight.coerceAtLeast(0.0)))

    fun pairs(of: Double): Int = plates.firstOrNull { it.weight == of }?.pairs ?: 0

    /** A weight not on the rack yet is added to it, in order. */
    fun withPairs(pairs: Int, of: Double): PlateSetup {
        val count = pairs.coerceIn(0, MOST_PAIRS)
        val index = plates.indexOfFirst { it.weight == of }
        return when {
            index >= 0 -> copy(plates = plates.mapIndexed { i, p -> if (i == index) p.copy(pairs = count) else p })
            of > 0 -> copy(plates = (plates + Plate(of, count)).sortedByDescending { it.weight })
            else -> this
        }
    }

    fun toJson(): String = JSONObject().apply {
        put("plates", JSONArray().also { array ->
            plates.forEach { array.put(JSONObject().put("weight", it.weight).put("pairs", it.pairs)) }
        })
        put("bars", JSONObject().also { o -> bars.forEach { (k, v) -> o.put(k, v) } })
    }.toString()

    companion object {
        const val MOST_PAIRS = 20

        /**
         * A commercial gym: plenty of every common plate, none of the fractional ones, which are
         * there to be switched on.
         */
        fun standard(): PlateSetup {
            val plenty = 10
            return PlateSetup(
                plates = listOf(45.0, 35.0, 25.0, 10.0, 5.0, 2.5).map { Plate(it, plenty) } + Plate(1.25, 0),
                bars = mapOf(
                    BarKind.BARBELL.wire to 45.0, BarKind.EZ_BAR.wire to 25.0,
                    BarKind.TRAP_BAR.wire to 45.0, BarKind.SMITH.wire to 15.0
                )
            )
        }

        /**
         * Unread or unreadable is the standard rack, never an empty one: an empty rack would answer
         * every set with "your plates make the bar".
         */
        fun fromJson(raw: String?): PlateSetup {
            if (raw == null) return standard()
            return runCatching {
                val o = JSONObject(raw)
                val array = o.getJSONArray("plates")
                val plates = (0 until array.length()).map {
                    val p = array.getJSONObject(it)
                    Plate(p.getDouble("weight"), p.getInt("pairs"))
                }.sortedByDescending { it.weight }
                val barsJson = o.getJSONObject("bars")
                val bars = barsJson.keys().asSequence().associateWith { barsJson.getDouble(it) }
                PlateSetup(plates, bars)
            }.getOrElse { standard() }
        }
    }
}

/**
 * What to put on the bar for a set: the fewest plates a side that reach the weight from what is
 * owned, or, when nothing owned reaches it exactly, the heaviest load under it and how far short
 * that is. A port of LIFT iPhone's `PlateMath`, and [PlateMathTest] of its tests, case for case.
 *
 * Not greedy. Greedy takes the biggest plate first, and with a limited rack that can miss a load the
 * rack can make (45 + 15 from no 15s, when two 30s were right there).
 */
object PlateMath {

    sealed interface Load {
        data object Nothing : Load
        data object LighterThanTheBar : Load
        data object JustTheBar : Load
        /** [perSide] heaviest first; [total] is what that loads, bar included. */
        data class Plates(override val perSide: List<Double>, val total: Double, val shortBy: Double) : Load

        val perSide: List<Double> get() = emptyList()

        /** The line under a set's weight, or null when there is nothing to say. Pounds, like every weight here. */
        fun line(): String? = when (this) {
            Nothing -> null
            LighterThanTheBar -> "Lighter than the bar"
            JustTheBar -> "Just the bar"
            is Plates -> {
                val plates = if (perSide.isEmpty()) "Just the bar"
                else "Per side: " + perSide.joinToString(" · ") { text(it) }
                if (shortBy > 0) "Your plates make ${text(total)} lb, ${text(shortBy)} short. $plates" else plates
            }
        }
    }

    /** Every plate is a whole number of these (1.25 lb). */
    private const val STEP = 0.25

    /** [total] in pounds, bar included. */
    fun load(total: Double, bar: BarKind, setup: PlateSetup): Load {
        if (!total.isFinite()) return Load.Nothing
        val rounded = (total * 100).roundToLong() / 100.0
        if (rounded <= 0) return Load.Nothing
        val barWeight = setup.barWeight(bar)
        if (rounded < barWeight) return Load.LighterThanTheBar
        if (rounded == barWeight) return Load.JustTheBar

        val owned = setup.plates
            .filter { it.pairs > 0 && it.weight > 0 }
            .map { (it.weight / STEP).roundToInt() to it.pairs }
        // No more than the rack holds, so the search is sized by the plates and not by whatever was
        // typed: 100000000 lb is still a few thousand steps, never gigabytes.
        val capacity = owned.sumOf { (units, pairs) -> units * pairs }
        val wanted = (rounded - barWeight) / 2 / STEP + 1e-6
        val target = if (wanted >= capacity) capacity else floor(wanted).toInt()

        // fewest[v]: the fewest plates making exactly v steps a side, with the heaviest-first list
        // that does it. Bounded by the pairs owned.
        var fewest = arrayOfNulls<List<Int>>(target + 1)
        fewest[0] = emptyList()
        for ((units, pairs) in owned) {
            if (units > target) continue
            val next = fewest.copyOf()
            for (value in 0..target) {
                val base = fewest[value] ?: continue
                for (count in 1..pairs) {
                    val reached = value + count * units
                    if (reached > target) break
                    val candidate = base + List(count) { units }
                    if (better(candidate, next[reached])) next[reached] = candidate
                }
            }
            fewest = next
        }

        // Something is always reachable: nothing at all, which is the bar.
        val reached = (0..target).lastOrNull { fewest[it] != null } ?: 0
        val perSide = (fewest[reached] ?: emptyList()).sortedDescending().map { it * STEP }
        val loaded = barWeight + 2 * reached * STEP
        return Load.Plates(perSide, loaded, ((rounded - loaded) * 100).roundToLong() / 100.0)
    }

    /** Fewer plates, then the heavier plates first. */
    private fun better(candidate: List<Int>, current: List<Int>?): Boolean {
        if (current == null) return true
        if (candidate.size != current.size) return candidate.size < current.size
        val a = candidate.sortedDescending()
        val b = current.sortedDescending()
        for (i in a.indices) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    fun text(value: Double): String {
        if (value == Math.rint(value)) return value.toLong().toString()
        return String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
    }
}
