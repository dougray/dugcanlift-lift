package com.dugcanlift.macrocalc.data

/**
 * A meal put together at one place before it is ordered: a sandwich, a side
 * and a milk, totalled against what is left of today ([remainingFor], the
 * number the screen ranks against).
 *
 * **A port of LIFT iPhone's `RoadFoodMeal`**, and `RoadFoodMealTest` is a port
 * of its tests, case for case. Only items that can be logged can be added
 * ([RoadFood.canLog]), and logging the meal logs each item as its own entry,
 * exactly as "Log it" would ([RoadFood.entryFor]), so the day reads the same
 * either way and an item can be removed from it later on its own. The total is
 * rounded per item, as each entry is, so it is the number the day will show.
 *
 * Immutable, so a Compose state holding one changes only by being replaced.
 * Not kept across places: it is the order in front of you, and a new place is
 * a new order.
 */
data class RoadFoodMeal(
    /** Item ids and how many of each, in the order they were first added. */
    val lines: List<Line> = emptyList()
) {
    data class Line(val id: String, val count: Int)

    val isEmpty: Boolean get() = lines.isEmpty()
    val itemCount: Int get() = lines.sumOf { it.count }

    fun count(id: String): Int = lines.firstOrNull { it.id == id }?.count ?: 0

    /** One more of [item]; an item that cannot be logged is not added. */
    fun add(item: RoadFoodItem): RoadFoodMeal {
        if (!RoadFood.canLog(item)) return this
        if (lines.none { it.id == item.id }) return RoadFoodMeal(lines + Line(item.id, 1))
        return RoadFoodMeal(lines.map { if (it.id == item.id) it.copy(count = it.count + 1) else it })
    }

    /** One fewer of [item], and the line goes at zero. */
    fun remove(item: RoadFoodItem): RoadFoodMeal {
        if (lines.none { it.id == item.id }) return this
        return RoadFoodMeal(lines.mapNotNull { line ->
            if (line.id != item.id) line else line.copy(count = line.count - 1).takeIf { it.count > 0 }
        })
    }

    fun cleared(): RoadFoodMeal = RoadFoodMeal()

    /**
     * The items to log, one per serving, in the order they were added. An id
     * this place no longer lists is dropped rather than logged as nothing.
     */
    fun items(menu: List<RoadFoodItem>): List<RoadFoodItem> = lines.flatMap { line ->
        menu.firstOrNull { it.id == line.id }?.let { item -> List(line.count) { item } }.orEmpty()
    }

    data class Total(val kcal: Int, val proteinG: Int)

    /** Rounded per item, as [RoadFood.entryFor] rounds each entry. */
    fun total(menu: List<RoadFoodItem>): Total {
        val items = items(menu)
        return Total(
            kcal = items.sumOf { RoadFood.jsRound(it.kcal ?: 0.0).toInt() },
            proteinG = items.sumOf { RoadFood.jsRound(it.proteinG ?: 0.0).toInt() }
        )
    }

    /**
     * "2 items · 520 kcal · 40 g protein · fits, 380 kcal to spare", or
     * "… · 120 kcal over what is left". Said, never coloured. [remaining] is
     * the screen's own [remainingFor]; with no goal the line stops at protein.
     */
    fun line(menu: List<RoadFoodItem>, remaining: Remaining?): String {
        val total = total(menu)
        val count = itemCount
        val parts = mutableListOf(
            if (count == 1) "1 item" else "$count items",
            "${total.kcal} kcal",
            "${total.proteinG} g protein"
        )
        if (remaining != null) {
            val left = remaining.calories - total.kcal
            parts += when {
                left == 0 -> "uses exactly what is left"
                left > 0 -> "fits, $left kcal to spare"
                else -> "${-left} kcal over what is left"
            }
        }
        return parts.joinToString(" · ")
    }

    /** For `rememberSaveable`: "count:id" a line, ids being free to hold a colon. */
    fun toSaved(): ArrayList<String> = ArrayList(lines.map { "${it.count}:${it.id}" })

    companion object {
        fun fromSaved(saved: List<String>): RoadFoodMeal = RoadFoodMeal(saved.mapNotNull { s ->
            val colon = s.indexOf(':')
            val count = s.take(colon.coerceAtLeast(0)).toIntOrNull() ?: return@mapNotNull null
            Line(s.substring(colon + 1), count).takeIf { count > 0 && it.id.isNotEmpty() }
        })
    }
}
