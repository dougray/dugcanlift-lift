package com.dugcanlift.macrocalc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.dugcanlift.macrocalc.data.FoodEntry
import com.dugcanlift.macrocalc.data.FoodRepository
import com.dugcanlift.macrocalc.data.HealthConnectManager
import com.dugcanlift.macrocalc.data.SettingsStore
import com.dugcanlift.macrocalc.data.WorkoutRepository
import com.dugcanlift.macrocalc.data.WorkoutSession
import com.dugcanlift.macrocalc.data.estimatedOneRepMax
import com.dugcanlift.macrocalc.data.forDate
import com.dugcanlift.macrocalc.data.historyFor
import com.dugcanlift.macrocalc.data.knownExercises
import com.dugcanlift.macrocalc.data.topWeightLb
import com.dugcanlift.macrocalc.data.sessionsForDate
import com.dugcanlift.macrocalc.data.todayKey
import com.dugcanlift.macrocalc.data.totals
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun DashboardScreen(
    goal: MacroResult?,
    onOpenCalculator: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val foods = remember { FoodRepository.get(context) }
    val workouts = remember { WorkoutRepository.get(context) }
    val settings = remember { SettingsStore.get(context) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        foods.load()
        workouts.load()
    }

    var todaySteps by remember { mutableStateOf(0L) }
    var stepGoal by remember { mutableStateOf(settings.stepGoal) }
    var showingStepGoalEditor by remember { mutableStateOf(false) }

    val stepsPermissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(HealthConnectManager.permissions)) {
            scope.launch { todaySteps = HealthConnectManager.todaysStepCount(context) }
        }
    }

    LaunchedEffect(Unit) {
        if (!HealthConnectManager.isAvailable(context)) return@LaunchedEffect
        if (HealthConnectManager.hasPermission(context)) {
            todaySteps = HealthConnectManager.todaysStepCount(context)
        } else {
            stepsPermissionLauncher.launch(HealthConnectManager.permissionsToRequest)
        }
    }

    val allEntries by foods.entries.collectAsState()
    val allSessions by workouts.sessions.collectAsState()

    val today = remember { todayKey() }
    val eaten = allEntries.forDate(today).totals()
    val todaysSessions = allSessions.sessionsForDate(today)

    // Oldest first, so the charts read left to right like a calendar.
    val week = remember { lastSevenDays().reversed() }
    val shortLabels = remember(week) { week.map { shortLabel(it) } }

    val exerciseOptions = remember(allSessions) { allSessions.knownExercises(limit = 20) }
    var selectedExercise by remember(exerciseOptions) {
        mutableStateOf(exerciseOptions.firstOrNull()?.matchKey)
    }

    val weekEntries = allEntries.filter { it.date in week }
    val weekSessions = allSessions.filter { it.date in week }
    val daysLogged = weekEntries.map { it.date }.distinct().size
    val weekCalories = weekEntries.totals().calories
    val weekVolume = weekSessions.sumOf { it.volumeLb }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "LIFT",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(text = "Today", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

        Spacer(modifier = Modifier.height(16.dp))

        if (goal == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "No goal set yet.", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Work out your daily calories and macros to start tracking against them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(onClick = onOpenCalculator) { Text("Set my goal") }
                }
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val remaining = goal.calories - eaten.calories
                    Text(
                        text = if (remaining >= 0) "$remaining kcal left"
                        else "${-remaining} kcal over",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = "${eaten.calories} of ${goal.calories}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    DashboardBar("Protein", eaten.proteinG, goal.proteinG)
                    DashboardBar("Fat", eaten.fatG, goal.fatG)
                    DashboardBar("Carbs", eaten.carbsG, goal.carbsG)
                    DashboardBar("Fiber", eaten.fiberG, goal.fiberG)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Steps", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                DashboardBar("Today", todaySteps.toInt(), stepGoal, unit = "steps")
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = { showingStepGoalEditor = true }) { Text("Edit goal") }
            }
        }

        if (showingStepGoalEditor) {
            StepGoalDialog(
                initial = stepGoal,
                onSave = { value ->
                    stepGoal = value
                    settings.stepGoal = value
                    showingStepGoalEditor = false
                },
                onDismiss = { showingStepGoalEditor = false }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Training", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                if (todaysSessions.isEmpty()) {
                    Text(
                        text = "Nothing logged today.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    todaysSessions.forEach { session ->
                        Text(
                            text = session.name.ifBlank { "Workout" },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "${session.exercises.size} exercises - ${session.setCount} sets" +
                                if (session.volumeLb > 0)
                                    " - ${session.volumeLb.roundToInt()} lb" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Fuel so far today", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                StatRow("Calories", "${eaten.calories} kcal")
                StatRow("Protein", "${eaten.proteinG} g")
                StatRow("Carbs", "${eaten.carbsG} g")
                StatRow("Fat", "${eaten.fatG} g")
                StatRow("Fiber", "${eaten.fiberG} g")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(text = "Last 7 days workouts", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

        Spacer(modifier = Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                StatRow("Workouts", "${weekSessions.size}")
                StatRow(
                    "Total volume",
                    if (weekVolume > 0) "${weekVolume.roundToInt()} lb" else "-"
                )
                StatRow("Total sets", "${weekSessions.sumOf { it.setCount }}")

                Spacer(modifier = Modifier.height(16.dp))

                LineChart(
                    series = listOf(
                        ChartSeries(
                            "Top weight",
                            ChartColors.Weight,
                            week.map { day -> topWeight(allSessions, day) }
                        ),
                        ChartSeries(
                            "Reps",
                            ChartColors.Reps,
                            week.map { day -> totalReps(allSessions, day) }
                        ),
                        ChartSeries(
                            "Sets",
                            ChartColors.Sets,
                            week.map { day -> totalSets(allSessions, day) }
                        )
                    ),
                    labels = shortLabels
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(text = "Exercise progression", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

        Spacer(modifier = Modifier.height(12.dp))

        if (exerciseOptions.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Log a workout and your lifts will chart here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                exerciseOptions.forEach { option ->
                    FilterChip(
                        selected = option.matchKey == selectedExercise,
                        onClick = { selectedExercise = option.matchKey },
                        label = { Text(option.displayName) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val chosen = exerciseOptions.firstOrNull { it.matchKey == selectedExercise }
            if (chosen != null) {
                val history = allSessions.historyFor(chosen.name, chosen.equipment).takeLast(10)

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = chosen.displayName,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val best = history.mapNotNull { it.second.topWeightLb() }.maxOrNull()
                        val latest = history.lastOrNull()?.second?.topWeightLb()
                        val bestE1rm = history.mapNotNull { it.second.estimatedOneRepMax() }.maxOrNull()

                        StatRow("Sessions", "${history.size}")
                        StatRow("Best weight", best?.let { "${it.roundToInt()} lb" } ?: "-")
                        StatRow("Most recent", latest?.let { "${it.roundToInt()} lb" } ?: "-")
                        StatRow("Best est. 1RM", bestE1rm?.let { "${it.roundToInt()} lb" } ?: "-")

                        Spacer(modifier = Modifier.height(16.dp))

                        // Plotted per session, not per calendar day — an exercise
                        // trained twice a week would otherwise be mostly gaps.
                        LineChart(
                            series = listOf(
                                ChartSeries(
                                    "Top weight",
                                    ChartColors.Weight,
                                    history.map { it.second.topWeightLb()?.toFloat() }
                                ),
                                ChartSeries(
                                    "Est. 1RM",
                                    ChartColors.Carbs,
                                    history.map { it.second.estimatedOneRepMax()?.toFloat() }
                                )
                            ),
                            labels = history.map { shortLabel(it.first) }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(text = "Last 7 days fueling", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

        Spacer(modifier = Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                StatRow("Days logged", "$daysLogged of 7")
                // Averaged over days actually logged, not over seven — otherwise
                // skipping a day looks like eating less rather than not tracking.
                StatRow(
                    "Average calories",
                    if (daysLogged == 0) "-" else "${weekCalories / daysLogged} kcal"
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Calories",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                LineChart(
                    series = listOf(
                        ChartSeries(
                            "Calories",
                            ChartColors.Calories,
                            week.map { day -> dayValue(allEntries, day) { it.calories.toFloat() } }
                        )
                    ),
                    labels = shortLabels
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Macros get their own chart: on a shared axis with calories,
                // fibre would sit flat on the floor and tell you nothing.
                Text(
                    text = "Macros (g)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                LineChart(
                    series = listOf(
                        ChartSeries(
                            "Protein",
                            ChartColors.Protein,
                            week.map { day -> dayValue(allEntries, day) { it.proteinG.toFloat() } }
                        ),
                        ChartSeries(
                            "Carbs",
                            ChartColors.Carbs,
                            week.map { day -> dayValue(allEntries, day) { it.carbsG.toFloat() } }
                        ),
                        ChartSeries(
                            "Fat",
                            ChartColors.Fat,
                            week.map { day -> dayValue(allEntries, day) { it.fatG.toFloat() } }
                        ),
                        ChartSeries(
                            "Fiber",
                            ChartColors.Fiber,
                            week.map { day -> dayValue(allEntries, day) { it.fiberG.toFloat() } }
                        )
                    ),
                    labels = shortLabels
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        CoachCard(goal = goal, sessions = allSessions, entries = allEntries)

        if (goal != null) {
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(
                onClick = onOpenCalculator,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Recalculate my goal")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/* ---------- series builders ---------- */

/** Null when nothing was logged, so the chart shows a gap rather than a zero. */
private fun dayValue(
    entries: List<FoodEntry>,
    day: String,
    pick: (com.dugcanlift.macrocalc.data.DayTotals) -> Float
): Float? {
    val forDay = entries.forDate(day)
    if (forDay.isEmpty()) return null
    return pick(forDay.totals())
}

private fun topWeight(sessions: List<WorkoutSession>, day: String): Float? {
    val forDay = sessions.sessionsForDate(day)
    if (forDay.isEmpty()) return null
    return forDay.flatMap { it.exercises }.flatMap { it.sets }
        .mapNotNull { it.weightLb }.maxOrNull()?.toFloat() ?: 0f
}

private fun totalReps(sessions: List<WorkoutSession>, day: String): Float? {
    val forDay = sessions.sessionsForDate(day)
    if (forDay.isEmpty()) return null
    return forDay.flatMap { it.exercises }.flatMap { it.sets }
        .sumOf { it.reps ?: 0 }.toFloat()
}

private fun totalSets(sessions: List<WorkoutSession>, day: String): Float? {
    val forDay = sessions.sessionsForDate(day)
    if (forDay.isEmpty()) return null
    return forDay.sumOf { it.setCount }.toFloat()
}

/* ---------- small pieces ---------- */

@Composable
private fun DashboardBar(name: String, eaten: Int, goal: Int, unit: String = "g") {
    val fraction = if (goal <= 0) 0f else (eaten.toFloat() / goal).coerceIn(0f, 1f)
    val over = goal > 0 && eaten > goal
    val barColor =
        if (over) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary

    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "$eaten / $goal $unit",
                style = MaterialTheme.typography.bodyLarge,
                color = if (over) barColor else MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor)
            )
        }
    }
}

@Composable
private fun StepGoalDialog(initial: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial.toString()) }
    val value = text.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Step Goal") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Steps per day") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { value?.let(onSave) }, enabled = value != null && value > 0) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

/* ---------- dates ---------- */

private fun lastSevenDays(): List<String> {
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val calendar = Calendar.getInstance()
    calendar.time = Date()
    return (0 until 7).map {
        val key = format.format(calendar.time)
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        key
    }
}

private fun shortLabel(key: String): String = try {
    val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(key)
    if (parsed != null) SimpleDateFormat("MMM d", Locale.US).format(parsed) else key
} catch (e: Exception) {
    key
}
