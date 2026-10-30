package com.dugcanlift.macrocalc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dugcanlift.kit.trimZeros
import com.dugcanlift.macrocalc.data.BodyWeightTrend
import com.dugcanlift.macrocalc.data.CoachStore
import com.dugcanlift.macrocalc.data.ExerciseLibraryStore
import com.dugcanlift.macrocalc.data.MuscleWeek
import com.dugcanlift.macrocalc.data.SettingsStore
import com.dugcanlift.macrocalc.data.TrainingYear
import com.dugcanlift.macrocalc.data.WorkoutSession
import com.dugcanlift.macrocalc.data.normalizeDecimalInput
import com.dugcanlift.macrocalc.data.todayKey
import com.dugcanlift.macrocalc.ui.theme.dclAccentText
import com.dugcanlift.macrocalc.ui.theme.dclCardBorder
import com.dugcanlift.macrocalc.ui.theme.dclTextButtonColors
import com.dugcanlift.macrocalc.ui.theme.dclTextFieldColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/*
 * Train's Your year and Muscles this week, and Home's Body weight: ports of LIFT iPhone's
 * `ProgressCards.swift`, word for word. The rules are `data/ProgressRules.kt`. Charts are drawn here
 * on a Canvas, as every chart in this app is.
 */

@Composable
private fun CardTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, color = dclAccentText())
}

/**
 * Train → Your year: every day of the last twelve months as a square, filled by how many sets it
 * held ([TrainingYear]). Counted, never graded: an empty week is an empty column, with nothing said
 * about it.
 */
@Composable
fun TrainingYearCard(sessions: List<WorkoutSession>, modifier: Modifier = Modifier) {
    val today = LocalDate.now()
    val counts = remember(sessions) { TrainingYear.setsByDay(sessions) }
    val weeks = remember(counts, today) { TrainingYear.weeks(counts, today) }
    val total = remember(counts, today) { TrainingYear.trainingDays(counts, today) }
    val levels = yearFills()

    Card(modifier = modifier.fillMaxWidth(), border = dclCardBorder()) {
        Column(modifier = Modifier.padding(16.dp)) {
            CardTitle("Your year")
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = TrainingYear.totalLine(total),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            val scroll = rememberScrollState()
            // Today is at the right-hand end; start there.
            LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
            Row(
                modifier = Modifier
                    .horizontalScroll(scroll)
                    .clearAndSetSemantics {
                        contentDescription = "Training days over the last year"
                        stateDescription = "$total days"
                    },
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                weeks.forEach { week ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        week.forEach { day ->
                            Box(
                                modifier = Modifier
                                    .size(11.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (day.isFuture) Color.Transparent else levels[day.level])
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.clearAndSetSemantics { }
            ) {
                val legend = MaterialTheme.typography.labelSmall
                Text("Fewer sets", style = legend, color = MaterialTheme.colorScheme.onSurfaceVariant)
                levels.forEach { fill ->
                    Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(fill))
                }
                Text("More", style = legend, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Level 0 is the hairline; 1-3 are the accent at the iPhone's three strengths. */
@Composable
private fun yearFills(): List<Color> {
    val accent = MaterialTheme.colorScheme.primary
    return listOf(
        MaterialTheme.colorScheme.outline,
        accent.copy(alpha = 0.35f),
        accent.copy(alpha = 0.65f),
        accent
    )
}

/**
 * Train → Muscles this week: sets per primary muscle, the reader's first weekday to now
 * ([MuscleWeek]). A list with plain bars, not a body drawing: a drawing needs artwork LIFT can
 * license, and the list says the same numbers.
 */
@Composable
fun MusclesThisWeekCard(sessions: List<WorkoutSession>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var lookup by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) {
        ExerciseLibraryStore.load(context)?.let { lookup = MuscleWeek.lookup(it) }
    }
    val today = LocalDate.now()
    val result = remember(sessions, lookup, today) {
        MuscleWeek.tally(
            MuscleWeek.loggedThisWeek(sessions, today, MuscleWeek.weekStart(today)),
            lookup,
            MuscleWeek.allMuscles(lookup)
        )
    }

    Card(modifier = modifier.fillMaxWidth(), border = dclCardBorder()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle("Muscles this week")
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            if (result.trained.isEmpty()) {
                Text("No working sets yet this week.", style = MaterialTheme.typography.bodyMedium, color = muted)
            } else {
                val most = result.trained.first().sets.coerceAtLeast(1)
                val bar = MaterialTheme.colorScheme.primary
                result.trained.forEach { row ->
                    val name = MuscleWeek.display(row.muscle)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
                            contentDescription = name
                            stateDescription = if (row.sets == 1) "1 set" else "${row.sets} sets"
                        }
                    ) {
                        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(120.dp))
                        Box(modifier = Modifier.weight(1f).height(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(row.sets.toFloat() / most)
                                    .widthIn(min = 4.dp)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(bar)
                            )
                        }
                        Text(
                            "${row.sets}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted,
                            modifier = Modifier.widthIn(min = 24.dp)
                        )
                    }
                }
            }
            if (result.untrained.isNotEmpty() && result.trained.isNotEmpty()) {
                Text(
                    "No sets yet: " + result.untrained.joinToString(", ") { MuscleWeek.display(it) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted
                )
            }
            MuscleWeek.unplacedLine(result.unplacedSets)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = muted)
            }
        }
    }
}

/** "Aug 1", the reader's way: the locale's own pattern for a month and a day. */
internal fun shortDay(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    date.format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"), locale))

/**
 * Home → Body weight: weigh-ins logged here, the last ninety days as a line, and a goal line when
 * one is set ([BodyWeightTrend]). Stored as the day-keyed weights the calculator already records,
 * the same ones the backup file (`weights`) and Send to Coach already carry.
 */
@Composable
fun BodyWeightCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coach = remember { CoachStore.get(context) }
    val settings = remember { SettingsStore.get(context) }
    // Bumped by a save, so the card reads the stores again.
    var version by remember { mutableIntStateOf(0) }
    var logging by rememberSaveable { mutableStateOf(false) }
    var showingPhotos by rememberSaveable { mutableStateOf(false) }

    val today = LocalDate.now()
    // Read on every composition (a handful of entries), so a restore or the calculator's own
    // weigh-in shows up too; reading `version` is what makes a save here recompose.
    val points = version.let { BodyWeightTrend.points(coach.bodyweights(), today) }
    val goalLb = version.let { settings.bodyWeightGoalLb }

    Card(modifier = modifier.fillMaxWidth(), border = dclCardBorder()) {
        Column(modifier = Modifier.padding(16.dp)) {
            CardTitle("Body weight")
            Spacer(modifier = Modifier.height(8.dp))
            val line = BodyWeightTrend.summary(points, goalLb) { shortDay(it) }
            if (line != null) {
                Text(line, style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(
                    "No weigh-ins in the last 90 days.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (points.size > 1) {
                Spacer(modifier = Modifier.height(10.dp))
                BodyWeightChart(points, goalLb, today)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { logging = true }, colors = dclTextButtonColors()) { Text("Log weight") }
                TextButton(onClick = { showingPhotos = true }, colors = dclTextButtonColors()) { Text("Photos") }
            }
        }
    }

    if (logging) {
        BodyWeightEntryDialog(
            goalLb = goalLb,
            onSave = { weight, goal ->
                weight?.let { coach.recordBodyweight(it, todayKey()) }
                settings.bodyWeightGoalLb = goal
                version++
                logging = false
            },
            onDismiss = { logging = false }
        )
    }
    if (showingPhotos) {
        ProgressPhotosDialog(onDismiss = { showingPhotos = false })
    }
}

/**
 * The last ninety days as a line on a date axis, the scale fitted to the readings (and the goal)
 * rather than starting at zero, with the goal as a dashed line. Drawn on a Canvas, as every chart
 * here is.
 */
@Composable
private fun BodyWeightChart(points: List<BodyWeightTrend.Point>, goalLb: Double?, today: LocalDate) {
    val line = MaterialTheme.colorScheme.primary
    val guide = MaterialTheme.colorScheme.outline
    val goalColor = MaterialTheme.colorScheme.onSurfaceVariant
    val values = points.map { it.weightLb } + listOfNotNull(goalLb)
    val low = values.min()
    val high = values.max()
    val pad = ((high - low) * 0.1).coerceAtLeast(1.0)
    val bottom = low - pad
    val top = high + pad
    val first = points.first().date
    val span = (today.toEpochDay() - first.toEpochDay()).coerceAtLeast(1).toFloat()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(oneDecimal(high), style = MaterialTheme.typography.labelSmall, color = muted)
            if (goalLb != null) Text("Goal", style = MaterialTheme.typography.labelSmall, color = goalColor)
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .semantics { contentDescription = "Body weight over the last 90 days" }
        ) {
            val w = size.width
            val h = size.height
            fun y(lb: Double) = (h - ((lb - bottom) / (top - bottom) * h)).toFloat()
            fun x(date: LocalDate) = (date.toEpochDay() - first.toEpochDay()) / span * w
            listOf(0f, h).forEach { gy -> drawLine(guide, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f) }
            if (goalLb != null) {
                drawLine(
                    color = goalColor,
                    start = Offset(0f, y(goalLb)),
                    end = Offset(w, y(goalLb)),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                )
            }
            var previous: Offset? = null
            points.forEach { p ->
                val at = Offset(x(p.date), y(p.weightLb))
                previous?.let { drawLine(line, it, at, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round) }
                drawCircle(line, radius = 2.5.dp.toPx(), center = at)
                previous = at
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(oneDecimal(low), style = MaterialTheme.typography.labelSmall, color = muted)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(shortDay(first), style = MaterialTheme.typography.labelSmall, color = muted)
            Text(shortDay(today), style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

private fun oneDecimal(lb: Double): String = ((lb * 10).roundToLong() / 10.0).trimZeros()

/** A weight typed into a field: positive and finite, or nothing. */
internal fun typedWeight(text: String): Double? =
    text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }

@Composable
private fun BodyWeightEntryDialog(
    goalLb: Double?,
    onSave: (weightLb: Double?, goalLb: Double?) -> Unit,
    onDismiss: () -> Unit
) {
    var weightText by rememberSaveable { mutableStateOf("") }
    var goalText by rememberSaveable { mutableStateOf(goalLb?.let(::oneDecimal) ?: "") }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log weight") },
        text = {
            Column {
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = normalizeDecimalInput(it) },
                    label = { Text("Weight (lb)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = dclTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "One weigh-in a day: logging again today replaces today's.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = goalText,
                    onValueChange = { goalText = normalizeDecimalInput(it) },
                    label = { Text("Goal (lb), optional") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = dclTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Drawn as a line on the chart. Leave it blank for none.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(typedWeight(weightText), typedWeight(goalText)) },
                colors = dclTextButtonColors()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = dclTextButtonColors()) { Text("Cancel") }
        }
    )
}
