package com.dugcanlift.macrocalc

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.dugcanlift.macrocalc.data.HealthConnectManager
import com.dugcanlift.macrocalc.data.HeartRateAccessOffer
import com.dugcanlift.macrocalc.data.HeartRateChoices
import com.dugcanlift.macrocalc.data.HeartRateDevices
import com.dugcanlift.macrocalc.data.HeartRateSetting
import com.dugcanlift.macrocalc.data.HeartRateSettingsCopy
import com.dugcanlift.macrocalc.data.SettingsStore
import com.dugcanlift.macrocalc.ui.theme.dclAccentText
import com.dugcanlift.macrocalc.ui.theme.dclCardBorder
import com.dugcanlift.macrocalc.ui.theme.dclTextButtonColors

/**
 * Home's heart rate settings: where a run's heart rate comes from, beside [TrainingSettingsCard]
 * (this build has no settings screen, so its settings are cards on Home).
 *
 * Tracked and shown, never targeted: nothing here sets a zone or a limit. A Bluetooth device is
 * added by the person, from the scan list ([AddHeartRateDeviceSheet]), and never connected to
 * otherwise. Opening Home touches no radio: the scan, and the Bluetooth permission prompt, start
 * only when the person taps "Add a Bluetooth heart rate device".
 */
@Composable
fun HeartRateSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { SettingsStore.get(context) }
    var setting by remember { mutableStateOf(settings.heartRateSetting) }
    var devices by remember { mutableStateOf(settings.heartRateDevices) }
    var adding by rememberSaveable { mutableStateOf(false) }

    // People who connected Health Connect for steps before heart rate existed were never asked for
    // the two newer reads, and nothing else asks for a permission added later. Never asked on its
    // own: only this button opens Health Connect's sheet, after the footer below says what it is for.
    val healthConnectAvailable = remember { HealthConnectManager.isAvailable(context) }
    var granted by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) { granted = HealthConnectManager.grantedPermissions(context) }
    val grantLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { result -> granted = result }

    // The watch row waits for the watch link (LIFT Link v5): no watch is offered as a source yet.
    val rows = HeartRateChoices.rows(devices, watchName = null)

    Card(modifier = modifier.fillMaxWidth(), border = dclCardBorder()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = HeartRateSettingsCopy.SECTION_TITLE, style = MaterialTheme.typography.titleMedium, color = dclAccentText())
            Spacer(modifier = Modifier.height(8.dp))
            rows.forEach { row ->
                val chosen = setting == row.setting
                val device = (row.setting as? HeartRateSetting.Device)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = chosen, role = Role.RadioButton) {
                            setting = row.setting
                            settings.heartRateSetting = row.setting
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = chosen, onClick = null, modifier = Modifier.padding(end = 12.dp))
                    Text(text = row.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (device != null) {
                        TextButton(
                            onClick = {
                                val next = HeartRateDevices.settingAfterRemoving(setting, device.id)
                                devices = devices.filter { it.id != device.id }
                                settings.heartRateDevices = devices
                                setting = next
                                settings.heartRateSetting = next
                            },
                            colors = dclTextButtonColors(),
                            modifier = Modifier.semantics { contentDescription = "Remove ${row.title}" }
                        ) { Text("Remove", maxLines = 1) }
                    }
                }
            }
            TextButton(onClick = { adding = true }, colors = dclTextButtonColors()) {
                Text(HeartRateSettingsCopy.ADD_BUTTON)
            }
            Text(
                text = HeartRateSettingsCopy.FOOTER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (HeartRateAccessOffer.shouldOffer(healthConnectAvailable, granted, HealthConnectManager.heartRatePermission)) {
                TextButton(
                    onClick = { grantLauncher.launch(HealthConnectManager.heartRatePermissions) },
                    colors = dclTextButtonColors()
                ) { Text(HeartRateSettingsCopy.HEALTH_CONNECT_BUTTON) }
            }
        }
    }

    if (adding) {
        AddHeartRateDeviceSheet(
            alreadyAdded = devices.map { it.id }.toSet(),
            onAdd = { device ->
                devices = HeartRateDevices.adding(device, devices)
                settings.heartRateDevices = devices
                adding = false
            },
            onDismiss = { adding = false }
        )
    }
}
