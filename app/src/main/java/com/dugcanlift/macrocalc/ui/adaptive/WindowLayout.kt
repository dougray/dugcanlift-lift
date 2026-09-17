package com.dugcanlift.macrocalc.ui.adaptive

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Material 3's window width size classes, decided by the width of the *window* in dp -- never by
 * what kind of device this is. A tablet in a narrow split-screen pane is compact; a phone turned
 * landscape is expanded. Compact is the phone layout, and it must stay exactly as it was.
 */
enum class WindowWidth {
    COMPACT, MEDIUM, EXPANDED;

    companion object {
        const val MEDIUM_MIN_DP = 600f
        const val EXPANDED_MIN_DP = 840f

        fun fromDp(widthDp: Float): WindowWidth = when {
            widthDp < MEDIUM_MIN_DP -> COMPACT
            widthDp < EXPANDED_MIN_DP -> MEDIUM
            else -> EXPANDED
        }
    }
}

val LocalWindowWidth = compositionLocalOf { WindowWidth.COMPACT }

/** Measures the window once, at the root, and provides [LocalWindowWidth] to everything below. */
@Composable
fun ProvideWindowLayout(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier) {
        CompositionLocalProvider(LocalWindowWidth provides WindowWidth.fromDp(maxWidth.value)) {
            content()
        }
    }
}

/**
 * Every width-to-layout decision the app makes, as plain functions with no Compose in them, so the
 * mapping is unit tested rather than only ever seen on a screen.
 *
 * "Pane" is the width a tab is drawn in: the window, less the navigation rail when there is one.
 * "Content" is the pane inside the page's gutters, capped. Every rule that splits a screen reads
 * the content width, and every one of them answers "one column" below 600 dp of it -- which is
 * every phone in portrait, so the phone keeps the layout it was designed for.
 */
object AdaptiveLayout {
    /** The page gutter every screen already uses on the phone. */
    const val PAGE_GUTTER_DP = 16f

    /** A page stops growing here and centres: a card 1,500 dp wide is a banner, not a card. */
    const val MAX_CONTENT_DP = 1200f

    /** Forms, editors and single lists: past this their lines are too long to read. */
    const val READABLE_DP = 700f

    /** The narrowest a side-by-side pane may get -- a set form or a food form still fits. */
    const val MIN_PANE_DP = 320f
    const val PANE_GAP_DP = 16f

    /** A primary button in a wide layout; full width at 1,200 dp is a banner, not a button. */
    const val MAX_WIDE_BUTTON_DP = 420f

    const val MIN_CARD_DP = 280f
    const val GRID_GAP_DP = 12f
    const val MAX_CARD_COLUMNS = 3

    /** The Cook plan shows the whole week as seven columns from here, as LIFT iOS does. */
    const val WEEK_COLUMNS_MIN_DP = 1000f

    /** Home, Food, Cook and Train: tabs along the top on a phone, a navigation rail from medium up. */
    fun usesNavigationRail(width: WindowWidth): Boolean = width != WindowWidth.COMPACT

    /**
     * The horizontal gutter that caps a page at [maxContentDp] and centres it. Never less than the
     * phone's 16 dp, so on a phone it *is* the phone's gutter.
     */
    fun sideGutter(paneWidthDp: Float, maxContentDp: Float = MAX_CONTENT_DP): Float =
        maxOf(PAGE_GUTTER_DP, (paneWidthDp - maxContentDp) / 2f)

    /** The width a page's content gets inside [sideGutter]. */
    fun contentWidth(paneWidthDp: Float, maxContentDp: Float = MAX_CONTENT_DP): Float =
        maxOf(0f, paneWidthDp - 2f * sideGutter(paneWidthDp, maxContentDp))

    private fun twoPanes(contentWidthDp: Float): Boolean =
        contentWidthDp >= WindowWidth.MEDIUM_MIN_DP && contentWidthDp >= 2 * MIN_PANE_DP + PANE_GAP_DP

    /** Home's cards two-up, once two phone-width columns fit. */
    fun homeColumns(contentWidthDp: Float): Int = if (twoPanes(contentWidthDp)) 2 else 1

    /** Food: the day's totals and the add/edit forms beside the meal list. */
    fun foodIsTwoPane(contentWidthDp: Float): Boolean = twoPanes(contentWidthDp)

    /** Train: the day's lifting beside Outdoor. */
    fun trainIsTwoPane(contentWidthDp: Float): Boolean = twoPanes(contentWidthDp)

    /**
     * Train's Last route and Personal bests side by side, the map drawn larger. Needs the Outdoor
     * pane itself (not the page) to hold two cards.
     */
    fun outdoorHighlightsSideBySide(paneWidthDp: Float): Boolean = paneWidthDp >= 2 * MIN_CARD_DP + PANE_GAP_DP

    /** Recording and reviewing a route: the map beside the numbers rather than above them. */
    fun routeBesideStats(paneWidthDp: Float): Boolean = paneWidthDp >= WindowWidth.MEDIUM_MIN_DP

    /** Recipe and routine cards. One column below 600 dp, which is the phone layout. */
    fun cardColumns(contentWidthDp: Float): Int =
        if (contentWidthDp < WindowWidth.MEDIUM_MIN_DP) 1
        else fit(contentWidthDp, MIN_CARD_DP).coerceIn(2, MAX_CARD_COLUMNS)

    /** The Cook plan's days: one on a phone, two side by side, then the whole week. */
    fun planDayColumns(contentWidthDp: Float): Int = when {
        contentWidthDp < WindowWidth.MEDIUM_MIN_DP -> 1
        contentWidthDp < WEEK_COLUMNS_MIN_DP -> 2
        else -> 7
    }

    /** The shopping list: one column on a phone, two otherwise. */
    fun shoppingColumns(contentWidthDp: Float): Int =
        if (contentWidthDp < WindowWidth.MEDIUM_MIN_DP) 1 else 2

    private fun fit(width: Float, min: Float): Int = floor((width + GRID_GAP_DP) / (min + GRID_GAP_DP)).toInt()
}

/** [items] in rows of [columns], left to right then down -- cards, which read as a set. */
fun <T> rowMajor(items: List<T>, columns: Int): List<List<T>> = items.chunked(columns.coerceAtLeast(1))

/**
 * [items] in rows of [columns] where each *column* is read top to bottom -- a list, like shopping,
 * that is still meant to be read in order. Earlier columns take the extra item.
 */
fun <T> columnMajor(items: List<T>, columns: Int): List<List<T>> {
    val cols = columns.coerceAtLeast(1)
    if (items.isEmpty()) return emptyList()
    val perColumn = ceil(items.size / cols.toDouble()).toInt()
    val split = items.chunked(perColumn)
    return (0 until perColumn).map { row -> split.mapNotNull { it.getOrNull(row) } }
}

/**
 * Which column each of [heights] goes in when every item is placed under the currently shortest
 * column -- cards of very different heights (the macro card beside the steps card) leave no holes.
 * Zero-height items take no slot. [gap] is added between items in a column.
 */
fun masonryColumns(heights: List<Int>, columns: Int, gap: Int): List<Int> {
    val cols = columns.coerceAtLeast(1)
    val bottoms = IntArray(cols)
    return heights.map { h ->
        if (h <= 0) return@map -1
        val column = bottoms.indices.minBy { bottoms[it] }
        bottoms[column] += (if (bottoms[column] == 0) 0 else gap) + h
        column
    }
}
