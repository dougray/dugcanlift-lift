package com.dugcanlift.macrocalc

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.dugcanlift.macrocalc.data.HealthConnectManager
import com.dugcanlift.macrocalc.data.OutdoorActivity
import com.dugcanlift.macrocalc.data.OutdoorActivityRepository
import com.dugcanlift.macrocalc.data.formattedDistanceMiles
import com.dugcanlift.macrocalc.data.formattedDuration
import com.dugcanlift.macrocalc.data.formattedPace
import kotlinx.coroutines.launch

/**
 * A finished Run/Hike: its stats, its full route on the same bare-Canvas
 * polyline technique [RoutePolylineCanvas] as the live recording screen, an
 * Export-to-Health-Connect action, and a Discard action.
 *
 * [activity] must already be finished ([OutdoorActivity.endedAtEpochMs] set)
 * — this screen never records, only reviews what
 * [OutdoorRecordingScreen] already saved via the repository.
 */
@Composable
fun OutdoorReviewScreen(
    activity: OutdoorActivity,
    modifier: Modifier = Modifier,
    onDiscard: () -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { OutdoorActivityRepository.get(context) }
    val scope = rememberCoroutineScope()

    var currentActivity by remember(activity.id) { mutableStateOf(activity) }
    var isExporting by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var hasWritePermission by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        hasWritePermission = HealthConnectManager.hasWritePermission(context)
    }

    fun exportNow() {
        scope.launch {
            isExporting = true
            exportError = null
            val recordId = HealthConnectManager.exportOutdoorActivity(context, currentActivity)
            isExporting = false
            if (recordId != null) {
                val updated = currentActivity.copy(healthConnectRecordId = recordId)
                repository.save(updated)
                currentActivity = updated
            } else {
                exportError = "Export to Health Connect failed. Make sure Health Connect is set up and try again."
            }
        }
    }

    val healthConnectPermissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        hasWritePermission = granted.containsAll(HealthConnectManager.writePermissions)
        if (hasWritePermission) {
            exportNow()
        } else {
            exportError = "Health Connect export needs permission to write exercise sessions and routes."
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(text = currentActivity.activityType.displayName, style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        RoutePolylineCanvas(routePoints = currentActivity.routePoints, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(16.dp))

        StatRow(label = "Time", value = currentActivity.formattedDuration())
        StatRow(label = "Distance", value = currentActivity.formattedDistanceMiles())
        StatRow(label = "Pace", value = currentActivity.formattedPace())
        StatRow(label = "Elevation", value = currentActivity.formattedElevationGainFeet())

        Spacer(modifier = Modifier.height(16.dp))

        if (currentActivity.healthConnectRecordId != null) {
            Text(
                text = "Exported to Health Connect",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Button(
                onClick = {
                    when {
                        !HealthConnectManager.isAvailable(context) ->
                            exportError = "Health Connect isn't available on this device."
                        !hasWritePermission ->
                            healthConnectPermissionLauncher.launch(HealthConnectManager.writePermissions)
                        else -> exportNow()
                    }
                },
                enabled = !isExporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isExporting) "Exporting…" else "Export to Health Connect")
            }
        }

        exportError?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                onClick = {
                    scope.launch {
                        repository.delete(currentActivity.id)
                        onDiscard()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Discard")
            }
            Button(onClick = onDone, modifier = Modifier.weight(1f)) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
    Spacer(modifier = Modifier.height(4.dp))
}
