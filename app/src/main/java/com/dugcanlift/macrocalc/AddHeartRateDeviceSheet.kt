package com.dugcanlift.macrocalc

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dugcanlift.macrocalc.data.AddedHeartRateDevice
import com.dugcanlift.macrocalc.data.HeartRateRadioState
import com.dugcanlift.macrocalc.data.HeartRateSettingsCopy
import com.dugcanlift.macrocalc.ui.theme.dclTextButtonColors

/**
 * The scan list: a Bluetooth heart rate device is added by the person, from here, and never
 * connected to otherwise. Opening it is what asks for the Bluetooth permission and starts the scan;
 * closing it stops the scan (a run's own look for an added device, if one is going, keeps its own).
 *
 * When Bluetooth is not allowed, switched off, or missing, the list says so in words rather than
 * "Looking…" for ever ([HeartRateRadioState]).
 */
@Composable
fun AddHeartRateDeviceSheet(
    alreadyAdded: Set<String>,
    onAdd: (AddedHeartRateDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val central = remember { HeartRateCentral.get(context) }
    val radio by central.radio.collectAsState()
    val found by central.found.collectAsState()
    val missing = HeartRateCentral.runtimePermissions.any {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
    // While the system's own permission dialog is up the list has nothing to say about it. Both
    // survive a rotation, so turning the phone neither asks again nor forgets it is asking.
    var asking by rememberSaveable { mutableStateOf(missing) }
    var asked by rememberSaveable { mutableStateOf(false) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asking = false
        central.startScan()
    }

    // The permission is asked once per opening of the sheet, not once per rotation.
    LaunchedEffect(Unit) {
        if (missing && !asked) {
            asked = true
            permissions.launch(HeartRateCentral.runtimePermissions)
        }
    }
    // The scan follows the sheet: a rotation closes and reopens it, which the central coalesces
    // into the one scan. Coming back from the system's Settings (a permission or Bluetooth just
    // turned on) re-reads the radio and starts again.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !asking) central.startScan()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            central.stopScan()
        }
    }

    val state = if (asking) HeartRateRadioState.Unknown else radio
    val offered = found.filter { it.address !in alreadyAdded }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(HeartRateSettingsCopy.SHEET_TITLE) },
        text = {
            // Scrolls: a crowded gym can offer more rows than the dialog has room for.
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (offered.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.isLooking) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        Text(
                            text = state.emptyListMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                offered.forEach { device ->
                    TextButton(
                        onClick = { onAdd(AddedHeartRateDevice(device.address, device.name)) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = dclTextButtonColors()
                    ) { Text(device.name) }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = HeartRateSettingsCopy.SHEET_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, colors = dclTextButtonColors()) { Text("Cancel") }
        }
    )
}
