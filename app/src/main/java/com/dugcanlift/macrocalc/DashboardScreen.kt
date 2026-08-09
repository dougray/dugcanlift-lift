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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dugcanlift.macrocalc.data.FoodRepository
import com.dugcanlift.macrocalc.data.WorkoutRepository
import com.dugcanlift.macrocalc.data.forDate
import com.dugcanlift.macrocalc.data.sessionsForDate
import com.dugcanlift.macrocalc.data.todayKey
import com.dugcanlift.macrocalc.data.totals
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Home screen. Pulls together the two halves of the app — what went in and
 * what got done — so the daily question ("where am I?") is answered without
 * visiting two tabs.
 */
@Composable
fun DashboardScreen(
    goal: MacroResult?,
    onOpenCalculator: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val foods = remember { FoodRepository.get(context) }
    val workouts = remember { WorkoutRepository.get(context) }

    LaunchedEffect(Unit) {
        foods.load()
        workouts.load()
    }

    val allEntries by foods.entries.collectAsState()
    val allSessions by workouts.sessions.collectAsState()

    val today = remember { todayKey() }
    val eaten = allEntries.forDate(today).totals()
    val todaysSessions = allSessions.sessionsForDate(today)

    val week = remember { lastSevenDays() }
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
        Text(text = "Today", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(16.dp))

        if (goal == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "No goal set yet.",
                        style = MaterialTheme.typography.bodyLarge
                    )
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
                Text(text = "Training", style = MaterialTheme.typography.titleMedium)
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

        Spacer(modifier = Modifier.height(24.dp))

        Text(text = "Last 7 days", style = MaterialTheme.typography.titleMedium)

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
                StatRow("Workouts", "${weekSessions.size}")
                StatRow(
                    "Total volume",
                    if (weekVolume > 0) "${weekVolume.roundToInt()} lb" else "-"
                )
            }
        }

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

@Composable
private fun DashboardBar(name: String, eaten: Int, goal: Int) {
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
                text = "$eaten / $goal g",
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

/** Today plus the previous six days, as date keys. */
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
