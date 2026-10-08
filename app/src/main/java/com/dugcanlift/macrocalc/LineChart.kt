package com.dugcanlift.macrocalc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.dugcanlift.kit.DclPalette
import com.dugcanlift.macrocalc.ui.theme.LocalDclDark
import java.util.Locale
import kotlin.math.roundToInt

/** A named line. Null values mean "no data that day" and leave a gap. */
data class ChartSeries(
    val label: String,
    val color: Color,
    val values: List<Float?>
)

/**
 * Small multi-line chart drawn directly on a Canvas — no plotting library.
 *
 * All series share one Y axis, so only put comparable quantities on the same
 * chart. Calories and fibre together would leave fibre flat on the floor.
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 160.dp
) {
    val maxValue = series
        .flatMap { it.values }
        .filterNotNull()
        .maxOrNull() ?: 0f

    val gridColor = MaterialTheme.colorScheme.outline
    val hasData = maxValue > 0f

    Column(modifier = modifier.fillMaxWidth()) {
        if (!hasData) {
            Text(
                text = "Not enough logged yet to chart.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = maxValue.roundToInt().toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // The lines are pixels to TalkBack, so the canvas says what they show.
        val summary = chartSummary(series, labels)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = summary }
        ) {
            val w = size.width
            val h = size.height
            val count = labels.size
            if (count < 2) return@Canvas

            val stepX = w / (count - 1)

            // Horizontal guides at 0, 50, 100% of the max.
            listOf(0f, 0.5f, 1f).forEach { fraction ->
                val y = h - (h * fraction)
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f
                )
            }

            series.forEach { line ->
                var previous: Offset? = null
                line.values.forEachIndexed { index, value ->
                    if (value == null) {
                        // Break the line rather than dropping to zero — a day
                        // with no log isn't a day with no intake.
                        previous = null
                        return@forEachIndexed
                    }
                    val x = stepX * index
                    val y = h - (value / maxValue * h)
                    val point = Offset(x, y)

                    previous?.let {
                        drawLine(
                            color = line.color,
                            start = it,
                            end = point,
                            strokeWidth = 4f,
                            cap = StrokeCap.Round
                        )
                    }
                    drawCircle(color = line.color, radius = 5f, center = point)
                    previous = point
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = labels.firstOrNull().orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = labels.lastOrNull().orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            series.forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(line.color)
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = line.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * What a chart shows, in words: per series its range and its latest value, over
 * the span of [labels]. "Calories, Mon to Sun: 1,850 to 2,400, latest 2,100."
 */
internal fun chartSummary(series: List<ChartSeries>, labels: List<String>): String {
    val span = if (labels.size >= 2) "${labels.first()} to ${labels.last()}" else labels.firstOrNull().orEmpty()
    return series.joinToString(" ") { line ->
        val known = line.values.filterNotNull()
        val head = if (span.isEmpty()) line.label else "${line.label}, $span"
        if (known.isEmpty()) {
            "$head: nothing logged."
        } else {
            val min = known.minOrNull()!!
            val max = known.maxOrNull()!!
            val range = if (min == max) chartNumber(min) else "${chartNumber(min)} to ${chartNumber(max)}"
            "$head: $range, latest ${chartNumber(known.last())}."
        }
    }
}

private fun chartNumber(value: Float): String =
    if (value < 10f && value != value.roundToInt().toFloat()) {
        String.format(Locale.US, "%.1f", value)
    } else {
        String.format(Locale.US, "%,d", value.roundToInt())
    }

/**
 * Chart line colours, one set per scheme. Every line is at least 3:1 against
 * the card it is drawn on (WCAG non-text contrast), measured on SURFACE:
 *
 * - Dark (#242220): rust #E0674D 4.7, sage #7C8B7A 4.4, blue #5B8DB8 4.5,
 *   gold #D9A441 7.1, violet #8E7CC3 4.4. Rust is the kit's ACCENT_TEXT and sage
 *   its ACCENT2; the brand ACCENT itself is 3.1.
 * - Light (#FFFCF7): rust #B23C25 5.8, sage #56664F 6.0, blue #3F6E96 5.3,
 *   ochre #8C6418 5.2, violet #6B58A8 5.7. The dark set fell to 2.2 (gold) and
 *   about 3.5 (the rest) on parchment. Rust and sage are ACCENT_TEXT_LIGHT and
 *   ACCENT2_LIGHT; blue, gold/ochre and violet have no kit token yet.
 */
object ChartColors {
    private fun pick(dark: Long, light: Long): @Composable () -> Color = {
        Color(if (LocalDclDark.current) dark else light)
    }

    private val rust = pick(DclPalette.ACCENT_TEXT, DclPalette.ACCENT_TEXT_LIGHT)
    private val sage = pick(DclPalette.ACCENT2, DclPalette.ACCENT2_LIGHT)
    private val blue = pick(0xFF5B8DB8, 0xFF3F6E96)
    private val gold = pick(0xFFD9A441, 0xFF8C6418)
    private val violet = pick(0xFF8E7CC3, 0xFF6B58A8)

    val Calories: Color @Composable get() = rust()
    val Protein: Color @Composable get() = sage()
    val Carbs: Color @Composable get() = blue()
    val Fat: Color @Composable get() = gold()
    val Fiber: Color @Composable get() = violet()

    val Weight: Color @Composable get() = rust()
    val Reps: Color @Composable get() = blue()
    val Sets: Color @Composable get() = sage()
}
