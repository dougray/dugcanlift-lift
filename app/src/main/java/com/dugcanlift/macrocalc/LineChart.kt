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

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
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

/** Chart line colours. Brand palette first, then distinct additions. */
object ChartColors {
    val Calories = Color(0xFFC1442C)
    val Protein = Color(0xFF7C8B7A)
    val Carbs = Color(0xFF5B8DB8)
    val Fat = Color(0xFFD9A441)
    val Fiber = Color(0xFF8E7CC3)

    val Weight = Color(0xFFC1442C)
    val Reps = Color(0xFF5B8DB8)
    val Sets = Color(0xFF7C8B7A)
}
