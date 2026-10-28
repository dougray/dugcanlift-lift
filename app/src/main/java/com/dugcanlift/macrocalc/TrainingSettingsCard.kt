package com.dugcanlift.macrocalc

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dugcanlift.macrocalc.data.MyEquipment
import com.dugcanlift.macrocalc.data.ProgressionRule
import com.dugcanlift.macrocalc.data.SettingsStore
import com.dugcanlift.macrocalc.ui.theme.dclAccentText
import com.dugcanlift.macrocalc.ui.theme.dclCardBorder
import com.dugcanlift.macrocalc.ui.theme.dclTextButtonColors

/**
 * Home's training settings: "Suggest the next weight" ([ProgressionRule]) and My equipment
 * ([MyEquipment]), a switch per kind of equipment in the bundled exercise data -- LIFT iPhone's
 * Settings → Training, as a card beside Serving Size and Appearance, which is where this build keeps
 * its settings. The equipment list opens from its own button, so twelve switches are not a standing
 * part of Home.
 */
@Composable
fun TrainingSettingsCard() {
    val context = LocalContext.current
    val settings = remember { SettingsStore.get(context) }
    var suggests by remember { mutableStateOf(settings.suggestsNextWeight) }
    var notOwned by remember { mutableStateOf(settings.equipmentNotOwned) }
    var showingEquipment by rememberSaveable { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth(), border = dclCardBorder()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Training settings", style = MaterialTheme.typography.titleMedium, color = dclAccentText())
            Spacer(modifier = Modifier.height(8.dp))
            SwitchRow(label = "Suggest the next weight", checked = suggests) {
                suggests = it
                settings.suggestsNextWeight = it
            }
            Text(
                text = ProgressionRule.SETTINGS_TEXT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "My equipment", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (notOwned.isEmpty()) "All of it"
                        else "${MyEquipment.kinds.count { it.id !in notOwned }} of ${MyEquipment.kinds.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { showingEquipment = !showingEquipment }, colors = dclTextButtonColors()) {
                    Text(if (showingEquipment) "Done" else "Choose", maxLines = 1)
                }
            }
            Text(
                text = "My equipment narrows exercise search to what you have.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (showingEquipment) {
                Spacer(modifier = Modifier.height(8.dp))
                MyEquipment.kinds.forEach { kind ->
                    SwitchRow(label = kind.displayName, checked = kind.id !in notOwned) { owned ->
                        notOwned = if (owned) notOwned - kind.id else notOwned + kind.id
                        settings.equipmentNotOwned = notOwned
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = MyEquipment.SETTINGS_FOOTER,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (notOwned.isNotEmpty()) {
                    TextButton(onClick = {
                        notOwned = emptySet()
                        settings.equipmentNotOwned = notOwned
                    }, colors = dclTextButtonColors()) { Text("I have all of it") }
                }
            }
        }
    }
}

/** A label and its switch, the whole row the touch target. */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        // Null: the row is the control, so the switch does not take a second, separate tap target.
        Switch(checked = checked, onCheckedChange = null)
    }
}
