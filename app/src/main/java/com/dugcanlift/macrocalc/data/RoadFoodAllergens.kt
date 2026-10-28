package com.dugcanlift.macrocalc.data

import java.util.Locale

/**
 * Avoiding allergens at a Road Food place: what is hidden, what is shown, and
 * what is said about an item the chart is silent on.
 *
 * **A port of LIFT iPhone's `RoadFoodAllergens`, function for function**, and
 * `RoadFoodAllergensTest` is a port of its tests, case for case. LIFT web has
 * no allergen filter yet, so the iPhone is the reference here, the way
 * `road-food.js` is for [RoadFood]: the rule and its words change there first.
 *
 * The data is what the chain's own allergen chart says an item **contains**
 * (`dugcanlift-kit/data/README.md` "Allergens"); "may contain" and
 * shared-fryer notes are not in it. So the rules are deliberately one-sided:
 *
 * - An item that contains something avoided is **hidden**, and counted, so a
 *   short list is never mistaken for a short menu.
 * - An item is **never claimed free** of an allergen the chart does not cover
 *   or does not state for it. It stays on the list, and its row says "Milk not
 *   stated" in words. A filter that hid only what it knew about and implied the
 *   rest was safe would be worse than no filter.
 * - Nothing here says "safe". The screen says the chart is the chain's, and
 *   that cross-contact is not covered.
 *
 * Plain Kotlin with no Android in it, because this decides what someone with
 * an allergy is shown, and a rule in a composable's state cannot be tested.
 */
object RoadFoodAllergens {
    /** The US major allergens, in display order -- the order the data lists them in. */
    val ALL = listOf("milk", "egg", "fish", "shellfish", "tree-nuts", "peanuts", "wheat", "soy", "sesame")

    fun label(allergen: String): String = when (allergen) {
        "tree-nuts" -> "Tree nuts"
        else -> allergen.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    /** Known strings only, in display order, once each. */
    fun known(list: Collection<String>): List<String> {
        val set = list.map { it.lowercase(Locale.ROOT) }.toSet()
        return ALL.filter { it in set }
    }

    sealed interface Verdict {
        /** Contains at least one avoided allergen: hidden. */
        data class Contains(val allergens: List<String>) : Verdict

        /** Shown; these avoided allergens are not stated for it. */
        data class NotStated(val allergens: List<String>) : Verdict

        /** Shown; the chart covers every avoided allergen and lists none of them. */
        data object Clear : Verdict
    }

    /**
     * What [item] is, for someone [avoiding] these. [covered] is the chain's
     * [RoadFoodChain.allergensCovered] -- empty for a chain whose chart never
     * says what it covers, and for the gas station.
     */
    fun verdict(item: RoadFoodItem, covered: List<String>, avoiding: Set<String>): Verdict {
        if (avoiding.isEmpty()) return Verdict.Clear
        val avoided = ALL.filter { it in avoiding }
        val stated = item.allergens ?: return Verdict.NotStated(avoided)
        val hit = avoided.filter { it in stated }
        if (hit.isNotEmpty()) return Verdict.Contains(hit)
        val unknown = avoided.filter { it !in covered }
        return if (unknown.isEmpty()) Verdict.Clear else Verdict.NotStated(unknown)
    }

    data class Filtered(
        val shown: List<RoadFoodItem>,
        /** How many were hidden because their chart says they contain something avoided. */
        val hiddenCount: Int
    )

    fun filter(items: List<RoadFoodItem>, covered: List<String>, avoiding: Set<String>): Filtered {
        val shown = items.filter { verdict(it, covered, avoiding) !is Verdict.Contains }
        return Filtered(shown, items.size - shown.size)
    }

    /**
     * "Contains milk, wheat, and soy" / "Contains none of the allergens its
     * chart lists" / null when the chart says nothing about the item.
     */
    fun containsLine(item: RoadFoodItem): String? {
        val stated = item.allergens ?: return null
        if (stated.isEmpty()) return "Contains none of the allergens its chart lists"
        return "Contains " + joined(stated.map { label(it).lowercase(Locale.ROOT) })
    }

    /** "Milk and egg not stated", for a shown row while something is avoided. */
    fun notStatedLine(verdict: Verdict): String? {
        if (verdict !is Verdict.NotStated || verdict.allergens.isEmpty()) return null
        val names = joined(verdict.allergens.map { label(it).lowercase(Locale.ROOT) })
        return names.replaceFirstChar { it.titlecase(Locale.ROOT) } + " not stated"
    }

    /**
     * The sentence under the Avoid row while something is avoided: how many are
     * hidden, then whose chart it is, what it can and cannot clear, and that
     * cross-contact is not covered. [chain] is null for the gas station, which
     * has no chart. Three cases, as LIFT iPhone and LIFT web word them: no chart
     * read; a chart that lists what items contain but never says what it
     * covers, so it can hide and never clear; a chart that says both.
     */
    fun avoidNote(hiddenCount: Int, chain: RoadFoodChain?): String {
        val hidden = when (hiddenCount) {
            0 -> "Nothing here is listed as containing what you avoid. "
            1 -> "1 item is hidden because its chart says it contains something you avoid. "
            else -> "$hiddenCount items are hidden because their chart says they contain something you avoid. "
        }
        val source = when {
            chain?.allergenSource == null ->
                "This place publishes no allergen chart LIFT can read, so nothing is marked either way."
            chain.allergensCovered.isEmpty() ->
                "From the allergen chart ${chain.name} publishes. It says what items contain, not which " +
                    "allergens it covers, so nothing is marked free of anything. It doesn't cover " +
                    "cross-contact either. Check with the restaurant."
            else ->
                "From the allergen chart ${chain.name} publishes, which doesn't cover cross-contact. " +
                    "Check with the restaurant."
        }
        return hidden + source
    }

    /**
     * The avoid list as [SettingsStore.roadFoodAvoid] stores it: one name a
     * line, or null -- no key at all -- when nothing is avoided.
     */
    fun encode(avoiding: Set<String>): String? = known(avoiding).takeIf { it.isNotEmpty() }?.joinToString("\n")

    /** What [encode] wrote, read back; unknown names are dropped. */
    fun decode(stored: String?): Set<String> = known(stored?.split('\n').orEmpty()).toSet()

    /**
     * "milk", "milk and egg", "milk, wheat, and soy": the English list the
     * iPhone's `ListFormatter` writes in an English locale. The sentences
     * around it are English, so the list is too.
     */
    private fun joined(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
    }
}
