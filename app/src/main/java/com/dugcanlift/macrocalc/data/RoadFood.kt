package com.dugcanlift.macrocalc.data

import java.text.Collator
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID

/**
 * Road Food: what at this chain fits what is left of today.
 *
 * **A port of LIFT web's `lift/road-food.js`, function for function** --
 * `proteinPer100`, `compare`, `rank`, `isStale`, `rulesFor`, `orderChains`,
 * `remember` and `entryFor` -- the way [SideBalance] is a port of `sides.js`.
 * Three platforms ranking one menu differently is worse than any of them
 * ranking it slightly better, so **a rule changes in `road-food.js` first and
 * is ported again**; it is never improved here. `RoadFoodTest` is a port of
 * `road-food.test.mjs`.
 *
 * The ranking, as the spec has it:
 *
 *   1. Fits: calories at or under what is left today. Below those, a separate
 *      "A little over" group holds items no more than 10% over. Anything past
 *      that is not shown at all -- hidden, not greyed, so the list stays short.
 *   2. Within each group, protein per 100 kcal, highest first.
 *   3. Ties go to the lower sodium. Sodium is otherwise only shown, never
 *      scored, and nothing here colours, badges or warns about it.
 *
 * With no goal set there is nothing to fit, so every item is ranked by protein
 * per 100 kcal alone, and the screen says that is what it is doing.
 *
 * Blank stays blank. An item whose protein is not listed has no protein
 * density, not a density of zero: it ranks after every item whose protein is
 * known, and the screen says "protein not listed". Unknown sodium likewise
 * loses a tie to any known sodium rather than winning it as a zero. An item
 * with no calorie figure cannot be said to fit, so it is left out when there
 * is a goal and ranked last when there is not.
 *
 * Zero-calorie drinks are real items (drinks are in; combos are not). With no
 * protein they rank at the bottom of the fits group, where a diet soda belongs.
 *
 * A coach's road picks ([withPicks]) sort to the top of a group and change
 * nothing else: not the order underneath them, not which items fit, not what
 * is hidden. A pick is an opinion sitting beside the numbers, never in front
 * of them, and a pick that is "a little over" stays in the little-over group
 * where the arithmetic put it. See [RoadPicks] and coach/PLAN-FORMAT.md
 * "Road picks".
 */
object RoadFood {

    enum class Mode { GOAL, NO_GOAL }

    data class Ranked(val mode: Mode, val fits: List<RoadFoodItem>, val over: List<RoadFoodItem>)

    /**
     * A [Ranked] with the coach's picks floated to the top of each group.
     *
     * [picked] is the ids that are actually on screen, so [count] is a number a
     * card may promise; an id nothing here knows is not in it. Nothing is added
     * to a group and nothing is taken out.
     */
    data class Picked(
        val mode: Mode,
        val fits: List<RoadFoodItem>,
        val over: List<RoadFoodItem>,
        val picked: Set<String>
    ) {
        val count: Int get() = picked.size
        operator fun contains(item: RoadFoodItem): Boolean = item.id in picked
    }

    /**
     * Grams of protein per 100 kcal, or null when it cannot honestly be said.
     * Zero calories and zero protein is 0 (a diet drink); zero calories with
     * protein is infinity, which ranks it first, as it should.
     */
    fun proteinPer100(item: RoadFoodItem): Double? {
        val kcal = item.kcal ?: return null
        val protein = item.proteinG ?: return null
        if (kcal == 0.0) return if (protein == 0.0) 0.0 else Double.POSITIVE_INFINITY
        return protein / kcal * 100
    }

    private val collator: Collator = Collator.getInstance(Locale.ROOT)

    /**
     * Known before unknown, then higher density, then lower sodium, then name,
     * so the order is the same on every run and every device.
     */
    val order: Comparator<RoadFoodItem> = Comparator { a, b ->
        val da = proteinPer100(a)
        val db = proteinPer100(b)
        if ((da == null) != (db == null)) return@Comparator if (da == null) 1 else -1
        if (da != null && db != null && da != db) return@Comparator db.compareTo(da)
        val sa = a.sodiumMg
        val sb = b.sodiumMg
        if ((sa == null) != (sb == null)) return@Comparator if (sa == null) 1 else -1
        if (sa != null && sb != null && sa != sb) return@Comparator sa.compareTo(sb)
        collator.compare(a.name, b.name)
    }

    /**
     * [remainingKcal] is today's calories left ([Remaining.calories]), or null
     * with no goal.
     *
     * Mode [Mode.GOAL]: fits = at or under what is left; over = more than that
     * but no more than 10% over it. Mode [Mode.NO_GOAL]: fits = every item,
     * ranked; over is empty.
     *
     * The 10% line is compared in whole numbers (kcal x 10 <= left x 11), so
     * 704 over 640 is in and 705 is out, with no floating-point edge. With
     * nothing left, 10% of nothing is nothing: an item has to be free to fit.
     * A day already past its calories has none left.
     */
    fun rank(items: List<RoadFoodItem>, remainingKcal: Int?): Ranked {
        if (remainingKcal == null) return Ranked(Mode.NO_GOAL, items.sortedWith(order), emptyList())
        val left = remainingKcal.coerceAtLeast(0).toDouble()
        val fits = mutableListOf<RoadFoodItem>()
        val over = mutableListOf<RoadFoodItem>()
        items.forEach { item ->
            val kcal = item.kcal ?: return@forEach
            if (kcal <= left) fits += item
            else if (kcal * 10 <= left * 11) over += item
        }
        return Ranked(Mode.GOAL, fits.sortedWith(order), over.sortedWith(order))
    }

    /* ---------------- a coach's picks ---------------- */

    /**
     * [ids] as a lookup, keeping only what this copy of the data has.
     *
     * An id nothing here knows is **skipped, silently**: the coach's Road Food
     * file and this one are two builds updated at different times, and an item
     * withdrawn since the plan was sent must leave no row, no gap and no error.
     */
    private fun pickedIn(items: List<RoadFoodItem>, ids: List<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val wanted = ids.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return emptySet()
        return items.mapNotNull { it.id.takeIf { id -> id in wanted } }.toSet()
    }

    /**
     * A stable partition: the picked ones first, each part in the order it
     * already had. Sorting by a "picked" key would do the same thing and is
     * not written that way on purpose -- what is promised here is that the
     * nutrition order underneath is untouched, and a partition cannot quietly
     * stop keeping it.
     */
    private fun pickedFirst(list: List<RoadFoodItem>, picked: Set<String>): List<RoadFoodItem> {
        if (picked.isEmpty()) return list
        val (first, rest) = list.partition { it.id in picked }
        return first + rest
    }

    /**
     * [ranked] with a coach's picks floated to the top of each group.
     *
     * Nothing else changes: the same items fit, in the same order among
     * themselves, and an item more than 10% over what is left stays hidden
     * whether or not it was picked -- the pick is about the food, and what is
     * left of the day is the lifter's own arithmetic.
     */
    fun withPicks(ranked: Ranked, ids: List<String>): Picked {
        val picked = pickedIn(ranked.fits + ranked.over, ids)
        return Picked(
            mode = ranked.mode,
            fits = pickedFirst(ranked.fits, picked),
            over = pickedFirst(ranked.over, picked),
            picked = picked
        )
    }

    /** How many of [items] are picked -- for a chain card, which draws no list. */
    fun pickCount(items: List<RoadFoodItem>, ids: List<String>): Int = pickedIn(items, ids).size

    /* ---------------- how old the numbers are ---------------- */

    private val dayPattern = Regex("""^\d{4}-\d{2}-\d{2}$""")

    /** "2026-09-20" as a date, or null for anything that is not one (2026-02-30 included). */
    fun parseDay(text: String?): LocalDate? {
        if (text == null || !dayPattern.matches(text)) return null
        return try {
            LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private val monthPattern = Regex("""^\d{4}-\d{2}$""")

    /**
     * A document's own date as a date, or null. `publishedOn` is only as precise
     * as the document is, so it may be "2022-11" where the chart says only
     * "NOVEMBER 2022"; a month-only date is read as the first of that month,
     * which can only make a document look older, never fresher.
     */
    fun parseDocDay(text: String?): LocalDate? =
        parseDay(if (text != null && monthPattern.matches(text)) "$text-01" else text)

    /**
     * The date the "these numbers are old" warning keys off: the document's own
     * date when the chain states one, and the day a person read it when it does
     * not. Different facts -- [RoadFoodChain.publishedOn] is when the chain
     * wrote the chart, [RoadFoodChain.checkedOn] is when someone read it -- and
     * only the first can say a chart is from 2021.
     */
    fun ageDate(chain: RoadFoodChain?): String? = chain?.publishedOn ?: chain?.checkedOn

    /**
     * Whether [day] is more than six calendar months before [today]. Calendar
     * months, not 182 days: "six months old" is what the screen says, so it is
     * what gets measured. [day] is "YYYY-MM-DD", or "YYYY-MM" for a document
     * that names only a month. 31 March plus six months is 30 September,
     * clamped to the month's end as `road-food.js` clamps it. Null when the
     * date is missing or not a date, which the screen also says.
     */
    fun isStale(day: String?, today: String): Boolean? {
        val checked = parseDocDay(day) ?: return null
        val now = parseDay(today) ?: return null
        return now.isAfter(checked.plusMonths(6))
    }

    /* ---------------- ordering rules ---------------- */

    /**
     * The ordering tips for one chain. A plain rule (no kinds) applies
     * everywhere; one with kinds applies to chains whose `kind` is listed.
     */
    fun rulesFor(rules: List<RoadFoodRule>, kind: String?): List<String> = rules.mapNotNull { r ->
        when {
            r.kinds.isNullOrEmpty() -> r.text
            kind != null && kind in r.kinds -> r.text
            else -> null
        }
    }

    /**
     * The gas station's rules: only those written as objects with kinds, and of
     * those the ones whose kinds include "snacks" (web's
     * `rulesFor(rules.filter(r => Array.isArray(r.kinds)), 'snacks')`). Plain
     * rules are about restaurants.
     */
    fun snackRules(rules: List<RoadFoodRule>): List<String> = rulesFor(rules.filter { it.kinds != null }, SNACKS_KIND)

    const val SNACKS_KIND = "snacks"

    /* ---------------- the chain picker ---------------- */

    data class ChainOrder(val recent: List<RoadFoodChain>, val rest: List<RoadFoodChain>)

    /**
     * Chains with the recently used ones first, most recent first, then the
     * rest by name. [recent] is a list of chain ids; unknown ids are ignored.
     */
    fun orderChains(chains: List<RoadFoodChain>, recent: List<String>): ChainOrder {
        val byId = chains.associateBy { it.id }
        val seen = mutableSetOf<String>()
        val first = recent.mapNotNull { id -> byId[id]?.takeIf { seen.add(id) } }
        val rest = chains.filter { it.id !in seen }.sortedWith { a, b -> collator.compare(a.name, b.name) }
        return ChainOrder(first, rest)
    }

    /** [recent] with [id] moved to the front, kept to [max]. */
    fun remember(recent: List<String>, id: String, max: Int = 5): List<String> =
        (listOf(id) + recent.filter { it != id }).take(max)

    /* ---------------- what the screen says ---------------- */

    /**
     * The line at the top of a chain, from [Remaining] -- the same numbers
     * Home's "kcal left" reads: "Fits your remaining 640 kcal · 55 g protein".
     * A day past its calories says 0 kcal; protein already met says so rather
     * than "0 g" or a negative. Null with no goal, where the screen says it is
     * ranking by protein per 100 kcal alone instead.
     */
    fun fitHeadline(remaining: Remaining?): String? {
        if (remaining == null) return null
        val kcal = remaining.calories.coerceAtLeast(0)
        val protein = if (remaining.proteinG > 0) " · ${remaining.proteinG} g protein" else " · protein goal met"
        return "Fits your remaining $kcal kcal$protein"
    }

    /** "12.5 g protein per 100 kcal", or null where density is unknown or meaningless (zero kcal). */
    fun densityText(item: RoadFoodItem): String? {
        val d = proteinPer100(item) ?: return null
        if (!d.isFinite() || (item.kcal ?: 0.0) <= 0.0) return null
        val tenths = jsRound(d * 10)
        val text = if (tenths % 10 == 0L) (tenths / 10).toString() else (tenths / 10.0).toString()
        return "$text g protein per 100 kcal"
    }

    /** "370 kcal · P 34 g · C 37 g · F 10 g · Fib 2 g", naming what is not listed rather than showing 0. */
    fun macroLine(item: RoadFoodItem): String = listOfNotNull(
        item.kcal?.let { "${jsRound(it)} kcal" } ?: "kcal not listed",
        item.proteinG?.let { "P ${jsRound(it)} g" } ?: "protein not listed",
        item.carbsG?.let { "C ${jsRound(it)} g" },
        item.fatG?.let { "F ${jsRound(it)} g" },
        item.fiberG?.let { "Fib ${jsRound(it)} g" },
    ).joinToString(" · ")

    /* ---------------- logging ---------------- */

    /**
     * An ordinary food entry for one item, in the shape every other entry has:
     * servings 1, the item's own numbers as the totals, "Grilled Chicken
     * Sandwich (Wendy's)" as its name.
     *
     * Saturated fat, sugar and sodium the item does not list are left null,
     * never written as zero, so a coach and the day's nutrient coverage both
     * see a gap as a gap. The four macros and fibre are whole numbers on every
     * [FoodEntry] -- an entry has no "not recorded" for them, on any platform --
     * so an unlisted one counts as 0 there exactly as web's totals count its
     * absent field. Rounding is web's: macros whole, grams to one decimal,
     * sodium whole.
     *
     * Only an item with a calorie figure can be logged ([canLog]); every entry
     * in LIFT has one.
     */
    fun entryFor(
        item: RoadFoodItem,
        placeName: String?,
        meal: Meal,
        date: String = todayKey(),
        loggedAt: Long = System.currentTimeMillis(),
        id: String = UUID.randomUUID().toString()
    ): FoodEntry = FoodEntry(
        id = id,
        name = if (!placeName.isNullOrEmpty()) "${item.name} ($placeName)" else item.name,
        servings = 1.0,
        calories = jsRound(item.kcal ?: 0.0).toInt(),
        proteinG = jsRound(item.proteinG ?: 0.0).toInt(),
        fatG = jsRound(item.fatG ?: 0.0).toInt(),
        carbsG = jsRound(item.carbsG ?: 0.0).toInt(),
        fiberG = jsRound(item.fiberG ?: 0.0).toInt(),
        date = date,
        loggedAt = loggedAt,
        meal = meal.name,
        saturatedFatG = item.saturatedFatG?.let { jsRound(it * 10) / 10.0 },
        sugarG = item.sugarG?.let { jsRound(it * 10) / 10.0 },
        sodiumMg = item.sodiumMg?.let { jsRound(it).toDouble() }
    )

    fun canLog(item: RoadFoodItem): Boolean = item.kcal != null

    /** JavaScript's `Math.round`: halves go up, as `road-food.js` rounds them. */
    private fun jsRound(v: Double): Long = Math.floor(v + 0.5).toLong()
}
