package com.dugcanlift.macrocalc

import androidx.activity.compose.BackHandler
import com.dugcanlift.macrocalc.ui.theme.dclCardBorder
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dugcanlift.macrocalc.data.COMMON_EQUIPMENT
import com.dugcanlift.macrocalc.data.focusSummary
import com.dugcanlift.macrocalc.data.LoggedExercise
import com.dugcanlift.macrocalc.data.OutdoorActivity
import com.dugcanlift.macrocalc.data.OutdoorActivityRepository
import com.dugcanlift.macrocalc.data.OutdoorActivityType
import com.dugcanlift.macrocalc.data.Routine
import com.dugcanlift.macrocalc.data.StarterSplitStore
import com.dugcanlift.macrocalc.data.alreadyHas
import com.dugcanlift.macrocalc.data.RoutineRepository
import com.dugcanlift.macrocalc.data.ScheduledSessionRepository
import com.dugcanlift.macrocalc.data.SettingsStore
import com.dugcanlift.macrocalc.data.TrainingFocus
import com.dugcanlift.macrocalc.data.WorkoutRepository
import com.dugcanlift.macrocalc.data.WorkoutSession
import com.dugcanlift.macrocalc.data.WorkoutSet
import com.dugcanlift.macrocalc.data.byFolder
import com.dugcanlift.macrocalc.data.formattedDistanceMiles
import com.dugcanlift.macrocalc.data.formattedDuration
import com.dugcanlift.macrocalc.data.knownEquipment
import com.dugcanlift.macrocalc.data.knownExercises
import com.dugcanlift.macrocalc.data.lastPerformed
import com.dugcanlift.macrocalc.data.onDate
import com.dugcanlift.macrocalc.data.sessionsForDate
import com.dugcanlift.macrocalc.data.toRoutine
import com.dugcanlift.macrocalc.data.toSession
import com.dugcanlift.macrocalc.data.todayKey
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** How many recent outdoor activities show before "See all" is needed. */
private const val OUTDOOR_HISTORY_PREVIEW_COUNT = 3

@Composable
fun WorkoutScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val workouts = remember { WorkoutRepository.get(context) }
    val routineRepo = remember { RoutineRepository.get(context) }
    val settings = remember { SettingsStore.get(context) }
    val scheduledSessionRepo = remember { ScheduledSessionRepository.get(context) }
    val outdoorRepo = remember { OutdoorActivityRepository.get(context) }
    val tracker = remember { LocationTracker.get(context) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        workouts.load()
        routineRepo.load()
        scheduledSessionRepo.load()
        outdoorRepo.load()
    }

    val sessions by workouts.sessions.collectAsState()
    val routines by routineRepo.routines.collectAsState()

    // Six routines off a bundled file, for someone who has not written any.
    var starters by remember { mutableStateOf<List<Routine>>(emptyList()) }
    LaunchedEffect(Unit) { starters = StarterSplitStore.load(context) }
    val scheduledSessions by scheduledSessionRepo.sessions.collectAsState()
    val outdoorActivities by outdoorRepo.activities.collectAsState()

    var selectedDate by remember { mutableStateOf(todayKey()) }
    var focus by remember { mutableStateOf(settings.focus) }

    // Full-screen takeovers for recording/reviewing a Run or Hike, following the
    // same state-based screen-swap pattern MainActivity uses for showCalculator
    // (no NavController anywhere in this app). rememberSaveable (not plain
    // remember) so this state survives a configuration change or process
    // restart. It does NOT survive a bottom-tab switch on its own — there's
    // no SaveableStateHolder around MainActivity's `when(selectedTab)`, so
    // WorkoutScreen (one branch of that `when`) is fully disposed by the
    // others and this state is lost along with it. The tracker fallback
    // just below (`effectiveRecordingType`) is what actually keeps the
    // recording screen visible across a tab switch, using the tracker's own
    // isRecording/activityType as the ultimate source of truth instead of
    // relying on this Compose state surviving — see C-2 in the final-review
    // fix wave.
    var recordingActivityType by rememberSaveable { mutableStateOf<OutdoorActivityType?>(null) }
    var reviewingActivityId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAllOutdoorHistory by rememberSaveable { mutableStateOf(false) }
    var showDiscardRecordingConfirmation by remember { mutableStateOf(false) }

    val reviewingActivity = outdoorActivities.find { it.id == reviewingActivityId }

    // Belt-and-braces on top of the rememberSaveable fix above: the tracker's
    // own isRecording/activityType are the ultimate source of truth for "is
    // there a live recording", so even if recordingActivityType were ever
    // lost some other way, GPS-active still implies the recording screen
    // stays visible instead of orphaning the foreground service.
    val isTrackerRecording by tracker.isRecording.collectAsState()
    val trackerActivityType by tracker.activityType.collectAsState()
    val effectiveRecordingType = recordingActivityType
        ?: trackerActivityType.takeIf { isTrackerRecording }

    BackHandler(enabled = effectiveRecordingType != null || reviewingActivity != null) {
        when {
            reviewingActivity != null -> reviewingActivityId = null
            isTrackerRecording -> showDiscardRecordingConfirmation = true
            else -> recordingActivityType = null
        }
    }

    if (showDiscardRecordingConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardRecordingConfirmation = false },
            title = { Text("Discard this run?") },
            text = {
                Text("Recording is still in progress. Going back will stop it and discard the route.")
            },
            confirmButton = {
                TextButton(onClick = {
                    tracker.stop()
                    recordingActivityType = null
                    showDiscardRecordingConfirmation = false
                }) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardRecordingConfirmation = false }) {
                    Text("Keep recording")
                }
            }
        )
    }

    if (effectiveRecordingType != null) {
        OutdoorRecordingScreen(
            activityType = effectiveRecordingType,
            modifier = modifier,
            onDiscard = { recordingActivityType = null },
            onFinished = { finished ->
                recordingActivityType = null
                reviewingActivityId = finished.id
            }
        )
        return
    }

    reviewingActivity?.let { activity ->
        OutdoorReviewScreen(
            activity = activity,
            modifier = modifier,
            onDiscard = { reviewingActivityId = null },
            onDone = { reviewingActivityId = null }
        )
        return
    }

    val daysSessions = sessions.sessionsForDate(selectedDate)
    val known = remember(sessions) { sessions.knownExercises() }
    val equipmentOptions = remember(sessions) {
        (sessions.knownEquipment() + COMMON_EQUIPMENT).distinctBy { it.lowercase(Locale.US) }
    }
    val sortedOutdoorActivities = remember(outdoorActivities) {
        outdoorActivities.sortedByDescending { it.startedAtEpochMs }
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
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
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

        scheduledSessions.onDate(selectedDate).forEach { session ->
            val scheduledRoutine = routines.firstOrNull { it.id == session.routineId }
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), border = dclCardBorder()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Coach scheduled: ${session.routineName}", modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            scheduledRoutine?.let { routine ->
                                scope.launch { workouts.save(routine.toSession(selectedDate)) }
                            }
                        },
                        enabled = scheduledRoutine != null
                    ) { Text("Start") }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        Text(text = "Outdoor", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { recordingActivityType = OutdoorActivityType.RUN },
                modifier = Modifier.weight(1f)
            ) {
                Text("Start Run")
            }
            Button(
                onClick = { recordingActivityType = OutdoorActivityType.HIKE },
                modifier = Modifier.weight(1f)
            ) {
                Text("Start Hike")
            }
        }

        if (sortedOutdoorActivities.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            val visibleOutdoorActivities = if (showAllOutdoorHistory) {
                sortedOutdoorActivities
            } else {
                sortedOutdoorActivities.take(OUTDOOR_HISTORY_PREVIEW_COUNT)
            }
            visibleOutdoorActivities.forEach { activity ->
                OutdoorActivityRow(
                    activity = activity,
                    onClick = { reviewingActivityId = activity.id }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (sortedOutdoorActivities.size > OUTDOOR_HISTORY_PREVIEW_COUNT) {
                TextButton(onClick = { showAllOutdoorHistory = !showAllOutdoorHistory }) {
                    Text(
                        if (showAllOutdoorHistory) {
                            "Show less"
                        } else {
                            "See all (${sortedOutdoorActivities.size})"
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (routines.isNotEmpty()) {
            Text(text = "Routines", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))

            routines.byFolder().forEach { (folder, list) ->
                Text(
                    text = "$folder (${list.size})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                list.forEach { routine ->
                    RoutineCard(
                        routine = routine,
                        onStart = {
                            scope.launch { workouts.save(routine.toSession(selectedDate)) }
                        },
                        onDelete = { scope.launch { routineRepo.delete(routine.id) } }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        val unclaimed = starters.filterNot { routines.alreadyHas(it) }
        if (unclaimed.isNotEmpty()) {
            Text(text = "Ready-made", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (routines.isEmpty()) {
                    "Splits to start from, until you have written your own."
                } else {
                    "Splits you have not added yet."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            unclaimed.groupBy { it.folder }.forEach { (folder, list) ->
                Text(
                    text = folder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                list.forEach { starter ->
                    StarterSplitCard(
                        routine = starter,
                        // Copied, not referenced: from here on it is an
                        // ordinary routine of theirs, editable and deletable,
                        // and nothing about it stays special.
                        onAdd = { scope.launch { routineRepo.save(starter) } }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        daysSessions.forEach { session ->
            SessionCard(
                session = session,
                focus = focus,
                known = known,
                equipmentOptions = equipmentOptions,
                lastFor = { name, equipment -> sessions.lastPerformed(name, equipment) },
                onChange = { scope.launch { workouts.save(it) } },
                onDelete = { scope.launch { workouts.delete(session.id) } },
                onSaveAsRoutine = { name, folder ->
                    scope.launch { routineRepo.save(session.toRoutine(name, folder)) }
                }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = { scope.launch { workouts.save(WorkoutSession(date = selectedDate)) } },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (daysSessions.isEmpty()) "Start empty workout" else "Add another workout")
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun RoutineCard(
    routine: Routine,
    onStart: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), border = dclCardBorder()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = routine.name, style = MaterialTheme.typography.titleMedium)
            if (routine.preview.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = routine.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onStart) { Text("Start routine") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun OutdoorActivityRow(
    activity: OutdoorActivity,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick), border = dclCardBorder()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = activity.activityType.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = outdoorActivityDateLabel(activity.startedAtEpochMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = activity.formattedDistanceMiles(), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = activity.formattedDuration(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun outdoorActivityDateLabel(startedAtEpochMs: Long): String =
    SimpleDateFormat("EEE, MMM d", Locale.US).format(Date(startedAtEpochMs))

@Composable
private fun SessionCard(
    session: WorkoutSession,
    focus: TrainingFocus,
    known: List<LoggedExercise>,
    equipmentOptions: List<String>,
    lastFor: (String, String) -> LoggedExercise?,
    onChange: (WorkoutSession) -> Unit,
    onDelete: () -> Unit,
    onSaveAsRoutine: (String, String) -> Unit
) {
    var addingExercise by remember(session.id) { mutableStateOf(false) }

    var savingRoutine by remember(session.id) { mutableStateOf(false) }
    var routineName by remember(session.id) { mutableStateOf("") }
    var routineFolder by remember(session.id) { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth(), border = dclCardBorder()) {
        Column(modifier = Modifier.padding(16.dp)) {
            NameField(
                value = session.name,
                onValueChange = { onChange(session.copy(name = it)) },
                label = "Workout name"
            )

            if (session.setCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = focusSummary(session, focus),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            session.exercises.forEach { exercise ->
                ExerciseBlock(
                    exercise = exercise,
                    focus = focus,
                    previous = lastFor(exercise.name, exercise.equipment)
                        .takeIf { it?.id != exercise.id },
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
                ExercisePickerPanel(
                    known = known,
                    equipmentOptions = equipmentOptions,
                    onAdd = { name, equipment ->
                        onChange(
                            session.copy(
                                exercises = session.exercises + LoggedExercise(
                                    name = name.trim(),
                                    equipment = equipment.trim()
                                )
                            )
                        )
                        addingExercise = false
                    },
                    onCancel = { addingExercise = false }
                )
            } else if (savingRoutine) {
                NameField(
                    value = routineName,
                    onValueChange = { routineName = it },
                    label = "Routine name"
                )
                Spacer(modifier = Modifier.height(12.dp))
                NameField(
                    value = routineFolder,
                    onValueChange = { routineFolder = it },
                    label = "Folder (optional)"
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            onSaveAsRoutine(routineName.trim(), routineFolder.trim())
                            routineName = ""
                            routineFolder = ""
                            savingRoutine = false
                        },
                        enabled = routineName.isNotBlank() && session.exercises.isNotEmpty()
                    ) {
                        Text("Save routine")
                    }
                    OutlinedButton(onClick = { savingRoutine = false }) { Text("Cancel") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { addingExercise = true }) { Text("Add exercise") }
                    if (session.exercises.isNotEmpty()) {
                        OutlinedButton(onClick = {
                            routineName = session.name
                            savingRoutine = true
                        }) {
                            Text("Save as routine")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = onDelete) { Text("Delete workout") }
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
            // The name takes the space that is left and wraps; Remove keeps
            // its own width. Without the weight they compete, and a long name
            // -- which every library name is -- squeezed "Remove" down to one
            // letter per line.
            Text(
                text = exercise.displayName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onRemove) { Text("Remove", maxLines = 1) }
        }

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
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
    var weight by remember { mutableStateOf(previousSet?.weightLb?.trimZero() ?: "") }
    // The set before is the best guess there is; the focus only has to answer
    // for the first one, where 5 and 10 are different training decisions.
    var reps by remember {
        mutableStateOf(previousSet?.reps?.toString() ?: focus.defaultReps?.toString() ?: "")
    }
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

/**
 * A ready-made split, before it is yours.
 *
 * Deliberately not a [RoutineCard]: there is no Start and no Delete, because
 * neither means anything yet. Adding copies it into your routines, where the
 * real card takes over — so there is exactly one place that starts a workout
 * and one place that deletes one.
 */
@Composable
private fun StarterSplitCard(
    routine: Routine,
    onAdd: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), border = dclCardBorder()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = routine.name, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${routine.exercises.size} exercises - " +
                    "${routine.exercises.sumOf { it.targetSets }} sets",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                // Names only, no equipment, clipped to two lines. The full
                // preview is unreadable here: these are library names, and
                // "Barbell Bench Press - Medium Grip (Barbell)" five times
                // over is a paragraph, not a summary. The detail is one tap
                // away once it is their routine.
                text = routine.exercises.joinToString(", ") { it.name },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onAdd) { Text("Add to my routines") }
        }
    }
}
