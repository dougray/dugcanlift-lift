package com.dugcanlift.macrocalc

import androidx.activity.compose.BackHandler
import com.dugcanlift.macrocalc.ui.theme.dclCardBorder
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import com.dugcanlift.macrocalc.ui.adaptive.AdaptiveLayout
import com.dugcanlift.macrocalc.ui.adaptive.rememberMovablePart
import com.dugcanlift.macrocalc.ui.adaptive.GridRow
import com.dugcanlift.macrocalc.ui.adaptive.MeasuredPane
import com.dugcanlift.macrocalc.ui.adaptive.rowMajor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dugcanlift.macrocalc.data.COMMON_EQUIPMENT
import com.dugcanlift.macrocalc.data.focusSummary
import com.dugcanlift.macrocalc.data.LoggedExercise
import com.dugcanlift.macrocalc.data.OutdoorActivity
import com.dugcanlift.macrocalc.data.OutdoorActivityRepository
import com.dugcanlift.macrocalc.data.OutdoorActivityType
import com.dugcanlift.macrocalc.data.OutdoorRecords
import com.dugcanlift.macrocalc.data.Routine
import com.dugcanlift.macrocalc.data.StarterSplitStore
import com.dugcanlift.macrocalc.data.alreadyHas
import com.dugcanlift.macrocalc.data.RoutineRepository
import com.dugcanlift.macrocalc.data.ScheduledSessionRepository
import com.dugcanlift.macrocalc.data.PerSideLogging
import com.dugcanlift.macrocalc.data.PrescribedSet
import com.dugcanlift.macrocalc.data.SetSide
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

    // Starter routines off a bundled file, for someone who has not written any.
    var starters by remember { mutableStateOf<List<Routine>>(emptyList()) }
    LaunchedEffect(Unit) { starters = StarterSplitStore.load(context) }
    val scheduledSessions by scheduledSessionRepo.sessions.collectAsState()
    val outdoorActivities by outdoorRepo.activities.collectAsState()

    var selectedDate by rememberSaveable { mutableStateOf(todayKey()) }
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

    // Each part once, placed by width: one column exactly as on the phone, or the day's lifting
    // beside Outdoor once two phone-width panes fit, with routines as a card grid below both.
    val focusBlock: @Composable () -> Unit = rememberMovablePart {
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
    }

    val scheduledBlock: @Composable () -> Unit = rememberMovablePart {
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
    }

    val outdoorBlock: @Composable () -> Unit = rememberMovablePart {
        Text(text = "Outdoor", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutdoorActivityType.entries.forEach { type ->
                Button(
                    onClick = { recordingActivityType = type },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(type.displayName)
                }
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
    }

    val highlightsBlock: @Composable (sideBySide: Boolean) -> Unit = { sideBySide ->
        OutdoorHighlights(
            activities = outdoorActivities,
            onOpen = { reviewingActivityId = it.id },
            sideBySide = sideBySide
        )
    }

    val routinesBlock: @Composable (cardColumns: Int) -> Unit = { cardColumns ->
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
                val routineCard: @Composable (Routine, Modifier) -> Unit = { routine, cardModifier ->
                    RoutineCard(
                        routine = routine,
                        modifier = cardModifier,
                        onStart = {
                            scope.launch { workouts.save(routine.toSession(selectedDate)) }
                        },
                        onDelete = { scope.launch { routineRepo.delete(routine.id) } }
                    )
                }
                if (cardColumns == 1) {
                    list.forEach { routine ->
                        routineCard(routine, Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                } else {
                    rowMajor(list, cardColumns).forEach { row ->
                        GridRow(cells = row, columns = cardColumns) { routineCard(it, Modifier.fillMaxSize()) }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
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
                val starterCard: @Composable (Routine, Modifier) -> Unit = { starter, cardModifier ->
                    StarterSplitCard(
                        routine = starter,
                        modifier = cardModifier,
                        // Copied, not referenced: from here on it is an
                        // ordinary routine of theirs, editable and deletable,
                        // and nothing about it stays special.
                        onAdd = { scope.launch { routineRepo.save(starter) } }
                    )
                }
                if (cardColumns == 1) {
                    list.forEach { starter ->
                        starterCard(starter, Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                } else {
                    rowMajor(list, cardColumns).forEach { row ->
                        GridRow(cells = row, columns = cardColumns) { starterCard(it, Modifier.fillMaxSize()) }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    val sessionsBlock: @Composable () -> Unit = rememberMovablePart {
        daysSessions.forEach { session ->
            SessionCard(
                session = session,
                focus = focus,
                known = known,
                equipmentOptions = equipmentOptions,
                lastFor = { name, equipment -> sessions.lastPerformed(name, equipment) },
                logsPerSide = { matchKey -> settings.logsPerSide(matchKey) },
                onLogsPerSideChange = { matchKey, value -> settings.setLogsPerSide(matchKey, value) },
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
    }

    MeasuredPane(modifier = modifier.fillMaxSize()) { paneWidth ->
        val contentWidth = AdaptiveLayout.contentWidth(paneWidth)
        val twoPane = AdaptiveLayout.trainIsTwoPane(contentWidth)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AdaptiveLayout.sideGutter(paneWidth).dp, vertical = 16.dp)
        ) {
            WorkoutDateNavigator(
                date = selectedDate,
                onPrevious = { selectedDate = shiftWorkoutDate(selectedDate, -1) },
                onNext = { selectedDate = shiftWorkoutDate(selectedDate, 1) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (!twoPane) {
                focusBlock()
                Spacer(modifier = Modifier.height(24.dp))
                scheduledBlock()
                outdoorBlock()
                Spacer(modifier = Modifier.height(16.dp))
                highlightsBlock(false)
                Spacer(modifier = Modifier.height(24.dp))
                routinesBlock(1)
                sessionsBlock()
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(AdaptiveLayout.PANE_GAP_DP.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        focusBlock()
                        Spacer(modifier = Modifier.height(24.dp))
                        scheduledBlock()
                        sessionsBlock()
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        outdoorBlock()
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                highlightsBlock(true)
                Spacer(modifier = Modifier.height(24.dp))
                routinesBlock(AdaptiveLayout.cardColumns(contentWidth))
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun RoutineCard(
    routine: Routine,
    onStart: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    Card(modifier = modifier, border = dclCardBorder()) {
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

/**
 * The space under Outdoor: the newest route and the best distance, time and
 * pace for each kind of activity. All-time rather than the selected day's, so
 * it is still there on a rest day. The rules are [OutdoorRecords].
 *
 * The route is a line on a plain canvas, not a street map — this app has no
 * maps dependency, and the recording and review screens draw it the same way.
 */
@Composable
private fun OutdoorHighlights(
    activities: List<OutdoorActivity>,
    onOpen: (OutdoorActivity) -> Unit,
    /**
     * Last route beside Personal bests, across the width under Train's two panes, with the map
     * drawn larger than a phone's. False is the phone's stack.
     */
    sideBySide: Boolean = false
) {
    val last = remember(activities) { OutdoorRecords.lastRoute(activities) }
    val bests = remember(activities) { OutdoorRecords.bests(activities) }

    val bestsCard: @Composable (Modifier) -> Unit = { cardModifier ->
        Card(modifier = cardModifier, border = dclCardBorder()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Personal bests", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                if (bests.isEmpty()) {
                    Text(
                        "Your last route and your best distance, time and pace show up here after your first run, walk or hike.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                bests.forEachIndexed { index, best ->
                    if (index > 0) Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        "${best.type.displayName} · ${best.count} " + if (best.count == 1) "activity" else "activities",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutdoorStat("Farthest", best.longestDistanceMeters?.let(OutdoorRecords::distanceText) ?: "—")
                        OutdoorStat("Longest", best.longestDurationMs?.let(OutdoorRecords::durationText) ?: "—")
                        OutdoorStat("Fastest pace", best.fastestPaceSecondsPerMeter?.let(OutdoorRecords::paceText) ?: "—")
                    }
                }
            }
        }
    }

    val lastCard: @Composable (OutdoorActivity, Modifier) -> Unit = { last, cardModifier ->
        Card(
            modifier = cardModifier.clickable { onOpen(last) },
            border = dclCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Last route", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(12.dp))
                RoutePolylineCanvas(
                    routePoints = last.routePoints,
                    modifier = Modifier.fillMaxWidth(),
                    aspectRatio = if (sideBySide) 1.6f else 2f
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(last.activityType.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(outdoorActivityDateLabel(last.startedAtEpochMs),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutdoorStat("Distance", OutdoorRecords.distanceText(last.distanceMeters))
                    OutdoorStat("Time", last.durationMs?.let(OutdoorRecords::durationText) ?: "—")
                    OutdoorStat("Pace",
                        last.averagePaceSecondsPerMeter
                            ?.takeIf { last.distanceMeters >= OutdoorRecords.MINIMUM_PACE_DISTANCE_METERS }
                            ?.let(OutdoorRecords::paceText) ?: "—")
                }
            }
        }
    }

    if (last != null && sideBySide) {
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(AdaptiveLayout.PANE_GAP_DP.dp)
        ) {
            lastCard(last, Modifier.weight(1f).fillMaxHeight())
            bestsCard(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        if (last != null) {
            lastCard(last, Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(16.dp))
        }
        bestsCard(Modifier.fillMaxWidth())
    }
}

@Composable
private fun RowScope.OutdoorStat(label: String, value: String) {
    Column(modifier = Modifier.weight(1f)) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
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
    logsPerSide: (String) -> Boolean,
    onLogsPerSideChange: (String, Boolean) -> Unit,
    onChange: (WorkoutSession) -> Unit,
    onDelete: () -> Unit,
    onSaveAsRoutine: (String, String) -> Unit
) {
    var addingExercise by rememberSaveable(session.id) { mutableStateOf(false) }

    var savingRoutine by rememberSaveable(session.id) { mutableStateOf(false) }
    var routineName by rememberSaveable(session.id) { mutableStateOf("") }
    var routineFolder by rememberSaveable(session.id) { mutableStateOf("") }

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
                    logsPerSide = logsPerSide,
                    onLogsPerSideChange = onLogsPerSideChange,
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
    logsPerSide: (String) -> Boolean,
    onLogsPerSideChange: (String, Boolean) -> Unit,
    onChange: (LoggedExercise) -> Unit,
    onRemove: () -> Unit
) {
    var addingSet by rememberSaveable(exercise.id) { mutableStateOf(false) }

    // Seeded from the stored preference, which falls back to the name's own
    // guess only while nobody has answered. Toggling writes the answer through,
    // so it is remembered for this lift everywhere, not for this card.
    var perSide by rememberSaveable(exercise.matchKey) {
        mutableStateOf(logsPerSide(exercise.matchKey))
    }

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

        // Offered on every exercise, ticked to start only when the name looks
        // unilateral. With it off the rest of this block is exactly what it was
        // before sides existed.
        // What the coach asked for, when it said anything about sides: the
        // sided sets are not laid out as rows (nothing is logged until it is
        // done), so this is where their numbers are. "Each side · L 4 · R 3"
        // for the seven-set case, whose extra left set is listed with its L.
        exercise.prescribed?.takeIf { exercise.eachSide || it.any { set -> set.side != null } }?.let { asked ->
            val targets = PerSideLogging.prescribedTargets(asked, exercise.eachSide)
            Text(
                text = (if (exercise.eachSide) "Each side · L ${targets.left} · R ${targets.right}\n" else "") +
                    "Coach: " + asked.joinToString(", ") { formatPrescribed(it) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Against the coach's prescription when it has sides in it -- "L 1/3 ·
        // R 0/3", and "L 4/3" when over, never capped. On its own line: it is
        // longer than the plain count and would squeeze the chip beside it.
        PerSideLogging.targetsLabel(exercise)?.let { targets ->
            Text(
                text = targets,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The preference itself, not whether L / R happens to be offered: a
            // named set from the coach offers them without switching this on.
            FilterChip(
                selected = perSide,
                onClick = {
                    perSide = !perSide
                    onLogsPerSideChange(exercise.matchKey, perSide)
                },
                label = { Text("Left and right separately", maxLines = 1) }
            )
            if (perSide && exercise.prescribed == null) {
                PerSideLogging.sideCountLabel(exercise)?.let { counts ->
                    Spacer(modifier = Modifier.width(12.dp))
                    // The whole point of the line: a side one set behind is visible
                    // without counting rows.
                    Text(
                        text = counts,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
            // A named set from the coach on a lift not logged per side offers L
            // and R, with Both, until it is logged.
            val pending = PerSideLogging.pendingNamedSide(exercise, perSide)
            // The side this set starts on: the one the next unfilled prescribed
            // set names, otherwise whichever has fewer today, so the control
            // alternates by itself and a pair costs one extra tap.
            val startingSide = if (perSide || pending) PerSideLogging.startingSide(exercise) else null
            SetForm(
                focus = focus,
                // "Same as last": this side's own last set if it has one,
                // otherwise the set just logged — which on the second side of a
                // pair is the first side's, the numbers most people are about
                // to match.
                previousSet = exercise.sets(startingSide).lastOrNull()
                    ?: exercise.sets.lastOrNull()
                    ?: previous?.sets?.lastOrNull { it.side == startingSide }
                    ?: previous?.sets?.lastOrNull(),
                // A coach's prescription for a side comes first: the next set it
                // asks for on that side is what the lifter is about to do.
                prescribedFor = { side ->
                    exercise.prescribed?.let { PerSideLogging.prescribedSetFor(it, exercise.eachSide, exercise.sets, side) }
                },
                startingSide = startingSide,
                offerBoth = pending,
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
    /** The coach's next prescribed set on a side, to prefill from before [previousSet]; null for none. */
    prescribedFor: (SetSide?) -> PrescribedSet?,
    /**
     * Null means this exercise is not logged per side, and no L/R control is
     * drawn at all -- unless [offerBoth], where null is Both.
     */
    startingSide: SetSide?,
    /** L and R with Both beside them: a coach's named set on a lift not logged per side. */
    offerBoth: Boolean,
    onAdd: (WorkoutSet) -> Unit,
    onCancel: () -> Unit
) {
    // Held as the enum's name rather than the enum: what rememberSaveable can
    // put in a Bundle is what survives a rotation mid-set.
    var sideName by rememberSaveable { mutableStateOf(startingSide?.name) }
    val side = sideName?.let { name -> SetSide.entries.firstOrNull { it.name == name } }
    // The coach's set, when there is one for this side, is the whole guess: a
    // weight the coach left blank ("you pick") stays blank rather than being
    // filled from the last set.
    val asked = prescribedFor(startingSide)
    var weight by rememberSaveable {
        mutableStateOf((if (asked != null) asked.weightLb else previousSet?.weightLb)?.trimZero() ?: "")
    }
    // The set before is the best guess there is; the focus only has to answer
    // for the first one, where 5 and 10 are different training decisions.
    var reps by rememberSaveable {
        mutableStateOf(
            if (asked != null) asked.reps?.toString() ?: ""
            else previousSet?.reps?.toString() ?: focus.defaultReps?.toString() ?: ""
        )
    }
    var rpe by rememberSaveable { mutableStateOf(asked?.rpe?.trimZero() ?: "") }
    var time by rememberSaveable { mutableStateOf(asked?.durationSec?.let(::durationInput) ?: "") }
    var distance by rememberSaveable {
        mutableStateOf((if (asked != null) asked.distanceMeters else previousSet?.distanceMeters)?.trimZero() ?: "")
    }
    // Until a number is typed, switching sides refills the form from the
    // coach's next set on the new side, when there is one. After, what was
    // typed stays: the prescription suggests, the lifter decides.
    var typed by rememberSaveable { mutableStateOf(false) }
    fun chooseSide(option: SetSide?) {
        sideName = option?.name
        val next = prescribedFor(option) ?: return
        if (typed) return
        weight = next.weightLb?.trimZero() ?: ""
        reps = next.reps?.toString() ?: ""
        rpe = next.rpe?.trimZero() ?: ""
        time = next.durationSec?.let(::durationInput) ?: ""
        distance = next.distanceMeters?.trimZero() ?: ""
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        if (startingSide != null || offerBoth) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (offerBoth) {
                    FilterChip(
                        selected = side == null,
                        onClick = { chooseSide(null) },
                        label = { Text("Both") }
                    )
                }
                SetSide.entries.forEach { option ->
                    FilterChip(
                        selected = side == option,
                        onClick = { chooseSide(option) },
                        label = { Text(option.label) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (focus.showWeight) {
            NumberField(value = weight, onValueChange = { weight = it; typed = true }, label = "Weight (lb)")
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (focus.showReps) {
            NumberField(value = reps, onValueChange = { reps = it; typed = true }, label = "Reps")
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (focus.showRpe) {
            NumberField(value = rpe, onValueChange = { rpe = it; typed = true }, label = "RPE")
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (focus.showTime) {
            NameField(value = time, onValueChange = { time = it; typed = true }, label = "Time (mm:ss or seconds)")
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (focus.showDistance) {
            NumberField(value = distance, onValueChange = { distance = it; typed = true }, label = "Distance (m)")
            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                val set = WorkoutSet(
                    weightLb = weight.toDoubleOrNull(),
                    reps = reps.toIntOrNull(),
                    rpe = rpe.toDoubleOrNull(),
                    durationSec = parseDuration(time),
                    distanceMeters = distance.toDoubleOrNull(),
                    side = side
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
    // "185 x 5 L". A both-sided set says nothing, because saying "both" on
    // every bench press set would be noise on every screen.
    set.side?.let { parts += it.short }
    return if (parts.isEmpty()) "-" else parts.joinToString(" ")
}

/** A coach's set: "40 x 8", "40 x 8 L", "600 s"-style as [formatSet] writes a logged one. */
private fun formatPrescribed(set: PrescribedSet): String = formatSet(
    WorkoutSet(
        weightLb = set.weightLb,
        reps = set.reps,
        rpe = set.rpe,
        durationSec = set.durationSec,
        distanceMeters = set.distanceMeters,
        side = set.side
    )
).let { if (it == "-") "as written" else it }

private fun formatDuration(seconds: Int): String =
    if (seconds >= 60) "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    else "${seconds}s"

/** A duration as the Time field reads it back: "10:00", or plain seconds under a minute. */
private fun durationInput(seconds: Int): String =
    if (seconds >= 60) formatDuration(seconds) else seconds.toString()

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
    onAdd: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    Card(modifier = modifier, border = dclCardBorder()) {
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
