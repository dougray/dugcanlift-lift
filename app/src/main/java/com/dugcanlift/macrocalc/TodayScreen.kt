package com.dugcanlift.macrocalc

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dugcanlift.macrocalc.data.DayTotals
import com.dugcanlift.macrocalc.data.FoodEntry
import com.dugcanlift.macrocalc.data.FoodRepository
import com.dugcanlift.macrocalc.data.FoodSearchResult
import com.dugcanlift.macrocalc.data.forDate
import com.dugcanlift.macrocalc.data.todayKey
import com.dugcanlift.macrocalc.data.totals
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun TodayScreen(
    goal: MacroResult?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repo = remember { FoodRepository.get(context) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { repo.load() }

    val allEntries by repo.entries.collectAsState()

    var selectedDate by remember { mutableStateOf(todayKey()) }
    val entries = allEntries.forDate(selectedDate)
    val eaten = entries.totals()

    // Most people eat the same handful of things. Anything logged before can be
    // re-logged in one tap, which removes most of the manual entry pain.
    val recent = remember(allEntries) {
        allEntries
            .sortedByDescending { it.loggedAt }
            .distinctBy { it.name.trim().lowercase(Locale.US) }
            .take(10)
    }

    var panel by remember { mutableStateOf(Panel.NONE) }
    var prefill by remember { mutableStateOf<FoodEntry?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        DateNavigator(
            date = selectedDate,
            onPrevious = { selectedDate = shiftDate(selectedDate, -1) },
            onNext = { selectedDate = shiftDate(selectedDate, 1) }
        )

        Spacer(modifier = Modifier.height(20.dp))

        if (goal == null) {
            Text(
                text = "Set a goal on the Calculator tab and it'll show up here.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            SummaryCard(goal = goal, eaten = eaten)
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (panel == Panel.FORM) {
            AddFoodForm(
                onAdd = { entry ->
                    scope.launch { repo.add(entry) }
                    panel = Panel.NONE
                    prefill = null
                },
                onCancel = {
                    panel = Panel.NONE
                    prefill = null
                },
                date = selectedDate,
                initial = prefill
            )
        } else if (panel == Panel.SEARCH) {
            FoodSearchPanel(
                onPick = { result ->
                    prefill = result.toFoodEntry(selectedDate)
                    panel = Panel.FORM
                },
                onCancel = { panel = Panel.NONE }
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        prefill = null
                        panel = Panel.FORM
                    },
                    modifier = Modifier.fillMaxWidth(0.5f)
                ) {
                    Text("Add food")
                }
                OutlinedButton(
                    onClick = { panel = Panel.SEARCH },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Search")
                }
            }

            if (recent.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))

                Text(text = "Recent", style = MaterialTheme.typography.labelLarge)

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    recent.forEach { item ->
                        AssistChip(
                            onClick = {
                                scope.launch {
                                    repo.add(
                                        item.copy(
                                            id = java.util.UUID.randomUUID().toString(),
                                            date = selectedDate,
                                            loggedAt = System.currentTimeMillis()
                                        )
                                    )
                                }
                            },
                            label = { Text(item.name) }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (entries.isEmpty()) {
            Text(
                text = "Nothing logged on this day.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            entries.forEach { entry ->
                EntryRow(
                    entry = entry,
                    onDelete = { scope.launch { repo.delete(entry.id) } }
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun DateNavigator(
    date: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val isToday = date == todayKey()

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TextButton(onClick = onPrevious) { Text("Previous") }

        Text(
            text = dateLabel(date),
            style = MaterialTheme.typography.headlineSmall
        )

        TextButton(onClick = onNext, enabled = !isToday) { Text("Next") }
    }
}

@Composable
private fun SummaryCard(goal: MacroResult, eaten: DayTotals) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            val remaining = goal.calories - eaten.calories
            Text(
                text = if (remaining >= 0) "$remaining kcal left" else "${-remaining} kcal over",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "${eaten.calories} of ${goal.calories}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            MacroProgress("Protein", eaten.proteinG, goal.proteinG)
            MacroProgress("Fat", eaten.fatG, goal.fatG)
            MacroProgress("Carbs", eaten.carbsG, goal.carbsG)
            MacroProgress("Fiber", eaten.fiberG, goal.fiberG)
        }
    }
}

@Composable
private fun MacroProgress(name: String, eaten: Int, goal: Int) {
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
private fun EntryRow(entry: FoodEntry, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.fillMaxWidth(0.75f)) {
            Text(
                text = if (entry.servings == 1.0) entry.name
                else "${entry.name} x${formatServings(entry.servings)}",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "${entry.totalCalories} kcal - " +
                    "P ${entry.totalProteinG} - F ${entry.totalFatG} - " +
                    "C ${entry.totalCarbsG} - Fib ${entry.totalFiberG}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.fillMaxWidth(0.6f))
        TextButton(onClick = onDelete) { Text("Remove") }
    }
}

@Composable
private fun AddFoodForm(
    onAdd: (FoodEntry) -> Unit,
    onCancel: () -> Unit,
    date: String,
    initial: FoodEntry? = null
) {
    // Keyed on the prefill so picking a different search result refills the
    // fields rather than keeping the previous one's numbers.
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var servings by remember(initial) { mutableStateOf("1") }
    var calories by remember(initial) { mutableStateOf(initial?.calories?.toString() ?: "") }
    var protein by remember(initial) { mutableStateOf(initial?.proteinG?.toString() ?: "") }
    var fat by remember(initial) { mutableStateOf(initial?.fatG?.toString() ?: "") }
    var carbs by remember(initial) { mutableStateOf(initial?.carbsG?.toString() ?: "") }
    var fiber by remember(initial) { mutableStateOf(initial?.fiberG?.toString() ?: "") }

    val valid = name.isNotBlank() && calories.toIntOrNull() != null

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Add food", style = MaterialTheme.typography.titleMedium)

            Spacer(modifier = Modifier.height(12.dp))

            NameField(value = name, onValueChange = { name = it }, label = "Name")

            Spacer(modifier = Modifier.height(12.dp))

            NumberField(value = servings, onValueChange = { servings = it }, label = "Servings")

            Spacer(modifier = Modifier.height(12.dp))

            NumberField(value = calories, onValueChange = { calories = it }, label = "Calories per serving")

            Spacer(modifier = Modifier.height(12.dp))

            NumberField(value = protein, onValueChange = { protein = it }, label = "Protein (g)")

            Spacer(modifier = Modifier.height(12.dp))

            NumberField(value = fat, onValueChange = { fat = it }, label = "Fat (g)")

            Spacer(modifier = Modifier.height(12.dp))

            NumberField(value = carbs, onValueChange = { carbs = it }, label = "Carbs (g)")

            Spacer(modifier = Modifier.height(12.dp))

            NumberField(value = fiber, onValueChange = { fiber = it }, label = "Fiber (g)")

            Spacer(modifier = Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        onAdd(
                            FoodEntry(
                                name = name.trim(),
                                servings = servings.toDoubleOrNull() ?: 1.0,
                                calories = calories.toIntOrNull() ?: 0,
                                proteinG = protein.toIntOrNull() ?: 0,
                                fatG = fat.toIntOrNull() ?: 0,
                                carbsG = carbs.toIntOrNull() ?: 0,
                                fiberG = fiber.toIntOrNull() ?: 0,
                                date = date
                            )
                        )
                    },
                    enabled = valid
                ) {
                    Text("Save")
                }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

private enum class Panel { NONE, FORM, SEARCH }

/* ---------- date helpers ---------- */

private fun formatter() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

private fun shiftDate(key: String, days: Int): String {
    val fmt = formatter()
    val calendar = Calendar.getInstance()
    calendar.time = try {
        fmt.parse(key) ?: Date()
    } catch (e: Exception) {
        Date()
    }
    calendar.add(Calendar.DAY_OF_YEAR, days)
    return fmt.format(calendar.time)
}

private fun dateLabel(key: String): String {
    val today = todayKey()
    return when (key) {
        today -> "Today"
        shiftDate(today, -1) -> "Yesterday"
        else -> try {
            val parsed = formatter().parse(key)
            if (parsed != null) SimpleDateFormat("EEE, MMM d", Locale.US).format(parsed) else key
        } catch (e: Exception) {
            key
        }
    }
}

private fun formatServings(value: Double): String =
    if (value == value.roundToInt().toDouble()) value.roundToInt().toString()
    else value.toString()
