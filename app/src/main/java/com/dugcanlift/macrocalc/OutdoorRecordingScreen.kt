package com.dugcanlift.macrocalc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dugcanlift.macrocalc.data.OutdoorActivity
import com.dugcanlift.macrocalc.data.OutdoorActivityMath
import com.dugcanlift.macrocalc.data.OutdoorActivityRepository
import com.dugcanlift.macrocalc.data.OutdoorActivityType
import com.dugcanlift.macrocalc.data.RoutePoint
import com.dugcanlift.macrocalc.data.formattedDistanceMiles
import com.dugcanlift.macrocalc.data.formattedDuration
import com.dugcanlift.macrocalc.data.formattedElevationGainFeet
import com.dugcanlift.macrocalc.data.formattedPace
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.max

/**
 * Live Run/Hike recording.
 *
 * Owns the permission ceremony carried forward from Task 4's review, then
 * drives [LocationTracker] and shows elapsed time, distance, pace and a live
 * route polyline drawn on a bare [Canvas] in the same style as
 * `LineChart.kt` (a `Path` built from projected points, no charting
 * library).
 *
 * Permission ceremony, in the order the OS forces:
 *  1. `ACCESS_FINE_LOCATION` (+`ACCESS_COARSE_LOCATION`, +`POST_NOTIFICATIONS`
 *     on API 33+ — all three fit in one dialog, so they're requested
 *     together). A user can grant only "Approximate location" here — that's
 *     COARSE, not FINE — so [hasFineLocationPermission] checks
 *     `ACCESS_FINE_LOCATION` specifically. Getting only COARSE does *not*
 *     unlock the Start button; `LocationTracker`'s own accuracy filter
 *     (Task 4) would silently reject every fix past 50m, and COARSE fixes on
 *     Android 12+ are obfuscated well past that, so recording would run and
 *     drain battery while producing an empty route.
 *  2. `ACCESS_BACKGROUND_LOCATION`, always a separate step: the OS drops a
 *     combined request with #1 entirely. Below Android 10 it isn't a real
 *     permission (foreground implies background) so it's treated as already
 *     granted. On Android 10 the normal runtime dialog still offers "Allow
 *     all the time" directly. On Android 11+ Google removed that option from
 *     the in-app dialog, so the only way to grant it is the app's own
 *     location settings page — there is no result callback for that, so
 *     [rememberLocationPermissionRefresher] re-checks on every `ON_RESUME`
 *     instead. Declining or skipping this step does not block Start —
 *     recording still works in a degraded foreground-only mode that stops if
 *     the phone locks — it only changes the message shown.
 */
@Composable
fun OutdoorRecordingScreen(
    activityType: OutdoorActivityType,
    modifier: Modifier = Modifier,
    onDiscard: () -> Unit,
    onFinished: (OutdoorActivity) -> Unit
) {
    val context = LocalContext.current
    val tracker = remember { LocationTracker.get(context) }
    val repository = remember { OutdoorActivityRepository.get(context) }
    val scope = rememberCoroutineScope()

    var selectedActivityType by remember { mutableStateOf(activityType) }
    // Source of truth for "is a recording live" lives on the tracker, not
    // here — see LocationTracker's doc comment. That's what lets a recording
    // survive this screen being recreated while GPS keeps running.
    val startedAtEpochMs by tracker.startedAtEpochMs.collectAsState()
    val trackerActivityType by tracker.activityType.collectAsState()
    val providerDisabled by tracker.providerDisabled.collectAsState()
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var statusNeedsSettings by remember { mutableStateOf(false) }
    var showBackgroundPrompt by remember { mutableStateOf(false) }
    var skippedBackgroundPrompt by remember { mutableStateOf(false) }

    var hasFineLocation by remember { mutableStateOf(hasFineLocationPermission(context)) }
    var hasBackgroundLocation by remember { mutableStateOf(hasBackgroundLocationPermission(context)) }

    fun refreshPermissions() {
        hasFineLocation = hasFineLocationPermission(context)
        hasBackgroundLocation = hasBackgroundLocationPermission(context)
        // The Settings-page grant flow (see requestBackgroundLocation) has no
        // result callback, so this is the only place that finds out the user
        // came back with background location now granted. Without clearing
        // the prompt here, a user who does exactly what was asked is stuck on
        // the same "Allow / Continue without it" card with no Start button.
        if (hasBackgroundLocation) showBackgroundPrompt = false
    }

    // Settings-page grants for background location have no result callback,
    // so pick the change up whenever this screen resumes.
    rememberLocationPermissionRefresher { refreshPermissions() }

    val isRecordingActive = startedAtEpochMs != null
    // While a recording is active, the tracker's own activity type (set at
    // the moment start() actually succeeded) is canonical; selectedActivityType
    // is only meaningful for the pre-start chip selection.
    val activeActivityType = trackerActivityType ?: selectedActivityType
    val routePoints by tracker.routePoints.collectAsState()

    fun startTracking() {
        showBackgroundPrompt = false
        val started = tracker.start(selectedActivityType)
        statusNeedsSettings = false
        statusMessage = if (started) {
            null
        } else {
            "Couldn't start recording — make sure Location/GPS is turned on for this device."
        }
    }

    val foregroundPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        refreshPermissions()
        val fineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        when {
            fineGranted && !hasBackgroundLocation && !skippedBackgroundPrompt -> {
                statusMessage = null
                statusNeedsSettings = false
                showBackgroundPrompt = true
            }
            fineGranted -> {
                statusMessage = null
                statusNeedsSettings = false
                startTracking()
            }
            coarseGranted -> {
                statusMessage = "Route recording needs precise location — go to Settings to allow it."
                statusNeedsSettings = true
            }
            else -> {
                // Fully denied — commonly means Android has latched "don't
                // ask again", so the in-app request dialog won't come back;
                // Settings is the only remaining way out, same as the
                // COARSE-only branch above.
                statusMessage = "Location permission is required to record a route."
                statusNeedsSettings = true
            }
        }
    }

    val backgroundPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        refreshPermissions()
        startTracking()
    }

    fun onStartClicked() {
        statusMessage = null
        when {
            !hasFineLocation -> requestForegroundLocation(context, foregroundPermissionLauncher)
            !hasBackgroundLocation && !skippedBackgroundPrompt -> showBackgroundPrompt = true
            else -> startTracking()
        }
    }

    LaunchedEffect(isRecordingActive) {
        while (isRecordingActive) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val liveActivity = if (isRecordingActive) {
        OutdoorActivity(
            activityType = activeActivityType,
            startedAtEpochMs = startedAtEpochMs!!,
            endedAtEpochMs = nowMs,
            distanceMeters = OutdoorActivityMath.totalDistanceMeters(routePoints),
            elevationGainMeters = OutdoorActivityMath.elevationGainMeters(routePoints),
            routePoints = routePoints
        )
    } else null

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(text = "Record a route", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        if (!isRecordingActive) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutdoorActivityType.entries.forEach { option ->
                    FilterChip(
                        selected = option == selectedActivityType,
                        onClick = { selectedActivityType = option },
                        label = { Text(option.displayName) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        RoutePolylineCanvas(routePoints = routePoints, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(16.dp))

        if (isRecordingActive && liveActivity != null) {
            OutdoorStatRow(label = "Time", value = liveActivity.formattedDuration())
            OutdoorStatRow(label = "Distance", value = liveActivity.formattedDistanceMiles())
            OutdoorStatRow(label = "Pace", value = liveActivity.formattedPace())
            OutdoorStatRow(label = "Elevation", value = liveActivity.formattedElevationGainFeet())
            if (!hasBackgroundLocation) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Background location isn't granted — recording will stop if you lock " +
                        "your phone or switch apps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (providerDisabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "GPS turned off — turn Location back on to keep recording this route.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        statusMessage?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = message, style = MaterialTheme.typography.bodyMedium)
                    if (statusNeedsSettings) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { openAppLocationSettings(context) }) {
                            Text("Open Settings")
                        }
                    }
                }
            }
        }

        if (showBackgroundPrompt) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    val label = backgroundPermissionSettingsLabel(context)
                    Text(
                        text = if (label != null) {
                            "To keep recording when your phone locks or you switch apps, " +
                                "allow \"$label\" for location in Settings."
                        } else {
                            "Allow background location so recording continues when your phone " +
                                "locks or you switch apps."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            requestBackgroundLocation(context, backgroundPermissionLauncher)
                        }) {
                            Text("Allow")
                        }
                        OutlinedButton(onClick = {
                            skippedBackgroundPrompt = true
                            startTracking()
                        }) {
                            Text("Continue without it")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        if (isRecordingActive) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        tracker.stop()
                        onDiscard()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        // Capture the tracker's own start time/type before
                        // stop() clears them.
                        val start = startedAtEpochMs
                        val finishedActivityType = activeActivityType
                        tracker.stop()
                        if (start != null) {
                            val finished = OutdoorActivity(
                                activityType = finishedActivityType,
                                startedAtEpochMs = start,
                                endedAtEpochMs = System.currentTimeMillis(),
                                distanceMeters = OutdoorActivityMath.totalDistanceMeters(routePoints),
                                elevationGainMeters = OutdoorActivityMath.elevationGainMeters(routePoints),
                                routePoints = routePoints
                            )
                            scope.launch {
                                repository.save(finished)
                                onFinished(finished)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Finish")
                }
            }
        } else if (!showBackgroundPrompt) {
            Button(onClick = { onStartClicked() }, modifier = Modifier.fillMaxWidth()) {
                Text("Start ${selectedActivityType.displayName}")
            }
        }
    }
}

/** Shared by [OutdoorRecordingScreen] (live) and `OutdoorReviewScreen` (finished route). */
@Composable
internal fun OutdoorStatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
    Spacer(modifier = Modifier.height(4.dp))
}

/**
 * A route's polyline on a bare [Canvas], in `LineChart.kt`'s style (a
 * [Path] built up point-by-point, colors pulled from [MaterialTheme] before
 * entering the draw scope). Points are projected with a simple equirectangular
 * projection — longitude scaled by cos(latitude) so east-west and
 * north-south distances stay proportional — which is a fine approximation at
 * the scale of a single run or hike and needs no external dependency.
 *
 * Shared by [OutdoorRecordingScreen] (live) and `OutdoorReviewScreen`
 * (finished route) rather than duplicated, since both draw the same route
 * data the same way.
 */
@Composable
internal fun RoutePolylineCanvas(
    routePoints: List<RoutePoint>,
    modifier: Modifier = Modifier
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val startColor = lineColor.copy(alpha = 0.5f)
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        if (routePoints.size < 2) {
            Text(
                text = "Waiting for GPS…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val offsets = projectRoutePoints(routePoints, size.minDimension)
                val path = Path()
                offsets.forEachIndexed { index, offset ->
                    if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
                }
                drawPath(
                    path = path,
                    color = lineColor,
                    style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawCircle(color = startColor, radius = 8f, center = offsets.first())
                drawCircle(color = lineColor, radius = 10f, center = offsets.last())
            }
        }
    }
}

/**
 * Projects [points] onto a square drawing area [drawableDimension] on a
 * side, preserving real-world proportions (never stretching a route to fill
 * a non-square aspect). A minimum span guards against a division blow-up
 * when every fix so far is nearly the same point (e.g. right after Start).
 */
internal fun projectRoutePoints(points: List<RoutePoint>, drawableDimension: Float): List<Offset> {
    val avgLatRadians = Math.toRadians(points.map { it.latitude }.average())
    val lonScale = cos(avgLatRadians)

    val xs = points.map { it.longitude * lonScale }
    val ys = points.map { it.latitude }

    val minX = xs.min()
    val maxX = xs.max()
    val minY = ys.min()
    val maxY = ys.max()

    val spanX = maxX - minX
    val spanY = maxY - minY
    val span = max(max(spanX, spanY), MIN_SPAN_DEGREES)

    val padding = drawableDimension * 0.1f
    val drawable = drawableDimension - (padding * 2f)

    return points.map { point ->
        val nx = ((point.longitude * lonScale - minX) + (span - spanX) / 2) / span
        val ny = ((point.latitude - minY) + (span - spanY) / 2) / span
        Offset(
            x = padding + (nx * drawable).toFloat(),
            // Screen Y grows downward; latitude grows northward, so flip.
            y = padding + ((1 - ny) * drawable).toFloat()
        )
    }
}

/** ~11m at the equator — keeps a nearly-stationary route from a wild zoom. */
private const val MIN_SPAN_DEGREES = 0.0001

/* ---------- permission plumbing ---------- */

private fun hasFineLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun hasBackgroundLocationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
}

/** Only available API 30+; null below that (no settings-redirect path exists pre-R). */
private fun backgroundPermissionSettingsLabel(context: Context): String? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.packageManager.backgroundPermissionOptionLabel?.toString()
    } else {
        null
    }

private fun requestForegroundLocation(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    val permissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    launcher.launch(permissions.toTypedArray())
}

/**
 * Below Android 10 there's nothing to request (foreground implies
 * background). On Android 10 exactly, the normal runtime dialog still offers
 * "Allow all the time", so [launcher] (a plain `RequestPermission` launcher)
 * works. On Android 11+ that option was removed from the in-app dialog
 * entirely, so the only way to actually grant it is the app's own location
 * settings page.
 */
private fun requestBackgroundLocation(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<String>
) {
    when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> Unit
        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
            launcher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else -> openAppLocationSettings(context)
    }
}

private fun openAppLocationSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    )
}

/**
 * Settings-page grants for `ACCESS_BACKGROUND_LOCATION` (Android 11+, see
 * this file's top doc comment) have no `ActivityResultLauncher` callback —
 * the user backs out of Settings straight back into this screen with no
 * signal beyond the activity resuming. Re-checking permission state on every
 * `ON_RESUME` is the standard pattern for that gap.
 */
@Composable
private fun rememberLocationPermissionRefresher(onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
