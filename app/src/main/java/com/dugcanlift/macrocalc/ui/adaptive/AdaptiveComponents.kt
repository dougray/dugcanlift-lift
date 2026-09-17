package com.dugcanlift.macrocalc.ui.adaptive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The insets the app's Scaffold pads its content by; the rail layout pads the same edges itself. */
val AppContentInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/**
 * The rail for medium and expanded windows, replacing the tab row along the top. A rail rather
 * than a permanent drawer at expanded too: a 240 dp drawer would take a card column away to repeat
 * four words the rail already shows. [labels] are the tabs' titles, in tab order.
 */
@Composable
fun LiftNavigationRail(labels: List<String>, selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    NavigationRail(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        windowInsets = AppContentInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Start + WindowInsetsSides.Bottom)
    ) {
        Spacer(Modifier.height(12.dp))
        labels.forEachIndexed { index, label ->
            NavigationRailItem(
                selected = selected == index,
                onClick = { onSelect(index) },
                icon = { Icon(railIcon(index), contentDescription = null) },
                label = { Text(label) }
            )
        }
    }
}

/**
 * Measures the width a page is actually given -- the pane, not the screen -- and hands it on in
 * dp. Every page decides its columns from this rather than from `LocalConfiguration`, which knows
 * nothing about the rail beside it.
 */
@Composable
fun MeasuredPane(modifier: Modifier = Modifier, content: @Composable (paneWidthDp: Float) -> Unit) {
    BoxWithConstraints(modifier) { content(maxWidth.value) }
}

/**
 * [content] as a part of a page that one width places in a column and another beside something
 * else. Composing it at a different call site would start it afresh -- a half-typed food, an open
 * set form, a scrolled chip row all gone on a rotation -- so it is movable content, which keeps its
 * state wherever it lands. It always draws the latest [content], so it still sees fresh data.
 */
@Composable
fun rememberMovablePart(content: @Composable () -> Unit): @Composable () -> Unit {
    val latest = rememberUpdatedState(content)
    return remember { movableContentOf { latest.value() } }
}

/**
 * Items in [columns] equal-width columns, each placed under the currently shortest column, so
 * cards of different heights pack without holes ([masonryColumns]). Placed with [gap] between
 * columns and [verticalGap] between items in a column. Children are laid out in one `Layout`, so
 * each keeps its state whichever column it lands in.
 */
@Composable
fun MasonryColumns(
    columns: Int,
    modifier: Modifier = Modifier,
    gap: Dp = AdaptiveLayout.PANE_GAP_DP.dp,
    verticalGap: Dp = gap,
    content: @Composable () -> Unit
) {
    Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        val cols = columns.coerceAtLeast(1)
        val gapPx = gap.roundToPx()
        val vGapPx = verticalGap.roundToPx()
        val width = constraints.maxWidth
        val columnWidth = ((width - gapPx * (cols - 1)) / cols).coerceAtLeast(0)
        val placeables = measurables.map { it.measure(Constraints(minWidth = columnWidth, maxWidth = columnWidth)) }
        val assigned = masonryColumns(placeables.map { it.height }, cols, vGapPx)
        val bottoms = IntArray(cols)
        val positions = placeables.mapIndexed { i, p ->
            val column = assigned[i]
            if (column < 0) return@mapIndexed null
            val y = if (bottoms[column] == 0) 0 else bottoms[column] + vGapPx
            bottoms[column] = y + p.height
            column * (columnWidth + gapPx) to y
        }
        layout(width, bottoms.maxOrNull() ?: 0) {
            placeables.forEachIndexed { i, p -> positions[i]?.let { (x, y) -> p.place(x, y) } }
        }
    }
}

/**
 * Items in rows of [columns] equal-width cells, each row as tall as its tallest cell. A plain
 * `Row` per line, so it can sit inside the screens' existing scrolling `Column`s next to headings
 * and buttons that span the whole width.
 */
@Composable
fun <T> GridRow(cells: List<T>, columns: Int, modifier: Modifier = Modifier, gap: Float = AdaptiveLayout.GRID_GAP_DP,
                cell: @Composable (T) -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(gap.dp)
    ) {
        cells.forEach { item ->
            Box(Modifier.weight(1f).fillMaxHeight()) { cell(item) }
        }
        repeat(columns - cells.size) { Spacer(Modifier.weight(1f)) }
    }
}

// Drawn here rather than taken from material-icons: four glyphs do not justify a dependency, and
// this app already draws its own charts and routes for the same reason. Coach Android draws its
// rail the same way, and shares the dumbbell and the pot.

private fun railIcon(index: Int): ImageVector = when (index) {
    0 -> HomeIcon
    1 -> FoodIcon
    2 -> CookIcon
    else -> TrainIcon
}

private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

private fun PathBuilder.rect(l: Float, t: Float, r: Float, b: Float) {
    moveTo(l, t); lineTo(r, t); lineTo(r, b); lineTo(l, b); close()
}

private val Ink = SolidColor(Color.Black)

/** A house: a roof and a body with a door cut out. */
private val HomeIcon = icon("Home") {
    path(fill = Ink) {
        moveTo(12f, 3f); lineTo(22f, 11.5f); lineTo(19.5f, 11.5f); lineTo(12f, 5.6f)
        lineTo(4.5f, 11.5f); lineTo(2f, 11.5f); close()
    }
    path(fill = Ink) {
        moveTo(6f, 12f); lineTo(12f, 7.3f); lineTo(18f, 12f); lineTo(18f, 21f); lineTo(14f, 21f)
        lineTo(14f, 15f); lineTo(10f, 15f); lineTo(10f, 21f); lineTo(6f, 21f); close()
    }
}

/** A fork and a knife. */
private val FoodIcon = icon("Food") {
    path(stroke = Ink, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
        moveTo(5f, 3f); lineTo(5f, 8f)
        moveTo(8f, 3f); lineTo(8f, 8f)
        moveTo(11f, 3f); lineTo(11f, 8f)
        moveTo(8f, 10f); lineTo(8f, 21f)
    }
    path(fill = Ink) {
        moveTo(4.1f, 7.5f); lineTo(11.9f, 7.5f); lineTo(11.9f, 8.5f)
        curveTo(11.9f, 10.2f, 10.2f, 11f, 8f, 11f); curveTo(5.8f, 11f, 4.1f, 10.2f, 4.1f, 8.5f); close()
    }
    path(fill = Ink) {
        moveTo(19f, 3f); curveTo(16.5f, 4f, 15f, 7f, 15f, 11f); lineTo(15f, 13f); lineTo(17.2f, 13f)
        lineTo(17.2f, 21f); lineTo(19f, 21f); close()
    }
}

/** A pot with a lid. */
private val CookIcon = icon("Cook") {
    path(fill = Ink) {
        rect(11f, 4.5f, 13f, 6.5f)
        rect(3f, 7.5f, 21f, 9.5f)
        rect(5f, 10.5f, 19f, 19.5f)
        rect(1.5f, 11.5f, 5f, 13f)
        rect(19f, 11.5f, 22.5f, 13f)
    }
}

/** A dumbbell. */
private val TrainIcon = icon("Train") {
    path(fill = Ink) {
        rect(2f, 9.5f, 4f, 14.5f)
        rect(4.5f, 6.5f, 8f, 17.5f)
        rect(8f, 11f, 16f, 13f)
        rect(16f, 6.5f, 19.5f, 17.5f)
        rect(20f, 9.5f, 22f, 14.5f)
    }
}
