package com.dugcanlift.macrocalc

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dugcanlift.macrocalc.data.LoggedExercise
import com.dugcanlift.macrocalc.data.SettingsStore
import com.dugcanlift.macrocalc.data.TrainingFocus
import com.dugcanlift.macrocalc.data.WorkoutRepository
import com.dugcanlift.macrocalc.data.WorkoutSession
import com.dugcanlift.macrocalc.data.WorkoutSet
import com.dugcanlift.macrocalc.data.knownExercises
import com.dugcanlift.macrocalc.data.lastPerformed
import com.dugcanlift.macrocalc.data.sessionsForDate
import com.dugcanlift.macrocalc.data.todayKey
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun WorkoutScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = remember { WorkoutRepository.get(context) }
    val settings = remember { SettingsStore.get(context) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { repo.load() }

    val sessions by repo.sessions.collectAsState()
    var selectedDate by remember { mutableStateOf(todayKey()) }
    var focus by remember { mutableStateOf(settings.focus) }

    val daysSessions = sessions.sessionsForDate(selectedDate)
    val known = remember(sessions) { sessions.knownExercises() }

    fun persist(session: WorkoutSession) {
        scope.launch { repo.save(session) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        WorkoutDateNavigator(
            date = selectedDate,
            onPrevious = { selectedDate = shiftWorkoutDate(selectedDate, -1) },
            onNext = { selectedDate = shiftWorkoutDate(selectedDate, 1) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(text = "Focus", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            TrainingFocus.entries.forEach { option ->
                FilterChip(
                    selected = option == focus,
                    onClick = {
                        focus = option
                        settings.focus = option
                    },
                    label = { Text(option.label) }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        daysSessions.forEach { session ->
            SessionCard(
                session = session,
                focus = focus,
                known = known,
                lastFor = { name -> sessions.lastPerformed(name) },
                onChange = { persist(it) },
                onDelete = { scope.launch { repo.delete(session.id) } }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = { persist(WorkoutSession(date = selectedDate)) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (daysSessions.isEmpty()) "Start workout" else "Add another workout")
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SessionCard(
    session: WorkoutSession,
    focus: TrainingFocus,
    known: List<String>,
    lastFor: (String) -> LoggedExercise?,
    onChange: (WorkoutSession) -> Unit,
    onDelete: () -> Unit
) {
    var addingExercise by remember(session.id) { mutableStateOf(false) }
    var newExerciseName by remember(session.id) { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            NameField(
                value = session.name,
                onValueChange = { onChange(session.copy(name = it)) },
                label = "Workout name"
            )

            if (session.setCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${session.setCount} sets" +
                        if (session.volumeLb > 0) " - ${session.volumeLb.roundToInt()} lb volume" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            session.exercises.forEach { exercise ->
                ExerciseBlock(
                    exercise = exercise,
                    focus = focus,
                    previous = lastFor(exercise.name).takeIf { it?.id != exercise.id },
                    onChange = { updated ->
                        onChange(
                            session.copy(
                                exercises = session.exercises.map {
                                    if (it.id == updated.id) updated else it
                                }
                            )
                        )
                    },
                    onRemove = {
                        onChange(
                            session.copy(
                                exercises = session.exercises.filterNot { it.id == exercise.id }
                            )
                        )
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (addingExercise) {
                NameField(
                    value = newExerciseName,
                    onValueChange = { newExerciseName = it },
                    label = "Exercise"
                )

                if (known.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        known.forEach { name ->
                            AssistChip(
                                onClick = { newExerciseName = name },
                                label = { Text(name) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            onChange(
                                session.copy(
                                    exercises = session.exercises +
                                        LoggedExercise(name = newExerciseName.trim())
                                )
                            )
                            newExerciseName = ""
                            addingExercise = false
                        },
                        enabled = newExerciseName.isNotBlank()
                    ) {
                        Text("Add")
                    }
                    OutlinedButton(onClick = {
                        newExerciseName = ""
                        addingExercise = false
                    }) {
                        Text("Cancel")
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { addingExercise = true }) {
                        Text("Add exercise")
                    }
                    TextButton(onClick = onDelete) { Text("Delete workout") }
                }
            }
        }
    }
}

@Composable
private fun ExerciseBlock(
    exercise: LoggedExercise,
    focus: TrainingFocus,
    previous: LoggedExercise?,
    onChange: (LoggedExercise) -> Unit,
    onRemove: () -> Unit
) {
    var addingSet by remember(exercise.id) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = exercise.name, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onRemove) { Text("Remove") }
        }

        // Showing last time's numbers is the single most useful thing when
        // you're standing at the rack deciding what to load.
        previous?.let { last ->
            if (last.sets.isNotEmpty()) {
                Text(
                    text = "Last time: " + last.sets.joinToString("  ") { formatSet(it) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        exercise.sets.forEachIndexed { index, set ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${index + 1}.  ${formatSet(set)}",
                    style = MaterialTheme.typography.bodyLarge
                )
                TextButton(onClick = {
                    onChange(exercise.copy(sets = exercise.sets.filterNot { it.id == set.id }))
                }) {
                    Text("x")
                }
            }
        }

        if (addingSet) {
            SetForm(
                focus = focus,
                previousSet = exercise.sets.lastOrNull() ?: previous?.sets?.lastOrNull(),
                onAdd = { newSet ->
                    onChange(exercise.copy(sets = exercise.sets + newSet))
                    addingSet = false
                },
                onCancel = { addingSet = false }
            )
        } else {
            OutlinedButton(onClick = { addingSet = true }) { Text("Add set") }
        }
    }
}

@Composable
private fun SetForm(
    focus: TrainingFocus,
    previousSet: WorkoutSet?,
    onAdd: (WorkoutSet) -> Unit,
    onCancel: () -> Unit
) {
    // Prefilled from the previous set, since most sets repeat the one before.
    var weight by remember { mutableStateOf(previousSet?.weightLb?.trimZero() ?: "") }
    var reps by remember { mutableStateOf(previousSet?.reps?.toString() ?: "") }
    var rpe by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }
    var distance by remember { mutableStateOf(previousSet?.distanceMeters?.trimZero() ?: "") }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        if (focus.showWeight) {
            NumberField(value = weight, onValueChange = { weight = it }, label = "Weight (lb)")
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (focus.showReps) {
            NumberField(value = reps, onValueChange = { reps = it }, label = "Reps")
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (focus.showRpe) {
            NumberField(value = rpe, onValueChange = { rpe = it }, label = "RPE")
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (focus.showTime) {
            NameField(value = time, onValueChange = { time = it }, label = "Time (mm:ss or seconds)")
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (focus.showDistance) {
            NumberField(value = distance, onValueChange = { distance = it }, label = "Distance (m)")
            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                val set = WorkoutSet(
                    weightLb = weight.toDoubleOrNull(),
                    reps = reps.toIntOrNull(),
                    rpe = rpe.toDoubleOrNull(),
                    durationSec = parseDuration(time),
                    distanceMeters = distance.toDoubleOrNull()
                )
                if (!set.isEmpty) onAdd(set)
            }) {
                Text("Add set")
            }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/* ---------- formatting ---------- */

private fun formatSet(set: WorkoutSet): String {
    val parts = mutableListOf<String>()
    if (set.weightLb != null && set.reps != null) {
        parts += "${set.weightLb.trimZero()} x ${set.reps}"
    } else {
        set.weightLb?.let { parts += "${it.trimZero()} lb" }
        set.reps?.let { parts += "$it reps" }
    }
    set.rpe?.let { parts += "@${it.trimZero()}" }
    set.durationSec?.let { parts += formatDuration(it) }
    set.distanceMeters?.let { parts += "${it.trimZero()} m" }
    return if (parts.isEmpty()) "-" else parts.joinToString(" ")
}

private fun formatDuration(seconds: Int): String =
    if (seconds >= 60) "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    else "${seconds}s"

/** Accepts "12:30" or plain seconds, so both a sled push and a 5k feel natural. */
private fun parseDuration(input: String): Int? {
    val text = input.trim()
    if (text.isEmpty()) return null
    if (text.contains(":")) {
        val bits = text.split(":")
        val minutes = bits.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val seconds = bits.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        return minutes * 60 + seconds
    }
    return text.toIntOrNull()
}

private fun Double.trimZero(): String =
    if (this == this.roundToInt().toDouble()) this.roundToInt().toString() else this.toString()

private fun Int.toStringOrEmpty(): String = this.toString()

/* ---------- date helpers ---------- */

private fun workoutFormatter() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

private fun shiftWorkoutDate(key: String, days: Int): String {
    val fmt = workoutFormatter()
    val calendar = Calendar.getInstance()
    calendar.time = try {
        fmt.parse(key) ?: Date()
    } catch (e: Exception) {
        Date()
    }
    calendar.add(Calendar.DAY_OF_YEAR, days)
    return fmt.format(calendar.time)
}

private fun workoutDateLabel(key: String): String {
    val today = todayKey()
    return when (key) {
        today -> "Today"
        shiftWorkoutDate(today, -1) -> "Yesterday"
        else -> try {
            val parsed = workoutFormatter().parse(key)
            if (parsed != null) SimpleDateFormat("EEE, MMM d", Locale.US).format(parsed) else key
        } catch (e: Exception) {
            key
        }
    }
}

@Composable
private fun WorkoutDateNavigator(
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
        Text(text = workoutDateLabel(date), style = MaterialTheme.typography.headlineSmall)
        TextButton(onClick = onNext, enabled = !isToday) { Text("Next") }
    }
}
