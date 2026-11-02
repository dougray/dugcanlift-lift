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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import com.dugcanlift.macrocalc.data.BarKind
import com.dugcanlift.macrocalc.data.MyEquipment
import com.dugcanlift.macrocalc.data.PlateMath
import com.dugcanlift.macrocalc.data.PlateSetup
import com.dugcanlift.macrocalc.data.RestBetweenSets
import com.dugcanlift.macrocalc.data.RestCountdown
import com.dugcanlift.macrocalc.data.normalizeDecimalInput
import com.dugcanlift.macrocalc.ui.theme.dclTextFieldColors
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
    val live = remember { LiveSessionStore.get(context) }
    var restSeconds by remember { mutableStateOf(settings.restBetweenSetsStored ?: RestBetweenSets.DEFAULT_SECONDS) }
    var keepsScreenOn by remember { mutableStateOf(settings.keepsScreenOn) }
    var plates by remember { mutableStateOf(settings.plateSetup) }
    var showingPlates by rememberSaveable { mutableStateOf(false) }

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

            // The live session: LIFT iPhone's Settings › Training rows, in its order.
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Rest between sets", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        restSeconds = (restSeconds - RestBetweenSets.STEP).coerceIn(RestBetweenSets.RANGE)
                        settings.restBetweenSetsStored = restSeconds
                    },
                    enabled = restSeconds > RestBetweenSets.RANGE.first,
                    colors = dclTextButtonColors(),
                    modifier = Modifier.semantics { contentDescription = "Fifteen seconds less" }
                ) { Text("−") }
                Text(text = RestCountdown.clock(restSeconds.toDouble()), style = MaterialTheme.typography.bodyLarge)
                TextButton(
                    onClick = {
                        restSeconds = (restSeconds + RestBetweenSets.STEP).coerceIn(RestBetweenSets.RANGE)
                        settings.restBetweenSetsStored = restSeconds
                    },
                    enabled = restSeconds < RestBetweenSets.RANGE.last,
                    colors = dclTextButtonColors(),
                    modifier = Modifier.semantics { contentDescription = "Fifteen seconds more" }
                ) { Text("+") }
            }
            SwitchRow(label = "Keep the screen on", checked = keepsScreenOn) {
                keepsScreenOn = it
                live.setKeepsScreenOn(it)
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Plates and bars", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { showingPlates = !showingPlates }, colors = dclTextButtonColors()) {
                    Text(if (showingPlates) "Done" else "Choose", maxLines = 1)
                }
            }
            if (showingPlates) {
                PlateSettings(plates) { updated ->
                    plates = updated
                    settings.plateSetup = updated
                }
            }
            Text(
                text = LIVE_SESSION_SETTINGS_TEXT,
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

/**
 * LIFT iPhone's Settings › Training footer, word for word but for its Apple Watch sentence: this
 * build's watch rests for its own default, not this setting.
 */
internal const val LIVE_SESSION_SETTINGS_TEXT =
    "In a live session, tick a set done to start your rest. If you allow notifications, LIFT tells " +
        "you when it's over, even with your phone locked. While a live session is running, the screen " +
        "doesn't lock. Plates and bars work out what to load for a set on a bar."

/**
 * Plates and bars: what each bar weighs and which plates are on the rack, in pounds. LIFT iPhone's
 * `PlateSettingsView`, word for word.
 */
@Composable
private fun PlateSettings(setup: PlateSetup, onChange: (PlateSetup) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = "Bars", style = MaterialTheme.typography.labelLarge)
        BarKind.entries.forEach { bar ->
            // Typed into local text, so a half-typed "4" is not stored as a 4 lb bar and re-shown.
            var text by remember(bar) { mutableStateOf(PlateMath.text(setup.barWeight(bar))) }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = bar.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        text = normalizeDecimalInput(input)
                        text.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { onChange(setup.withBar(bar, it)) }
                    },
                    singleLine = true,
                    suffix = { Text("lb") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = dclTextFieldColors(),
                    modifier = Modifier.width(120.dp).semantics { contentDescription = "${bar.displayName} weight, lb" }
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        Text(
            text = "What the bar weighs on its own. Smith machine bars vary; check yours.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "Plates", style = MaterialTheme.typography.labelLarge)
        setup.plates.forEach { plate ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "${PlateMath.text(plate.weight)} lb", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (plate.pairs == 0) "None" else "${plate.pairs} pair${if (plate.pairs == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(
                    onClick = { onChange(setup.withPairs(plate.pairs - 1, plate.weight)) },
                    enabled = plate.pairs > 0,
                    colors = dclTextButtonColors(),
                    modifier = Modifier.semantics { contentDescription = "One pair fewer of ${PlateMath.text(plate.weight)} lb" }
                ) { Text("−") }
                TextButton(
                    onClick = { onChange(setup.withPairs(plate.pairs + 1, plate.weight)) },
                    enabled = plate.pairs < PlateSetup.MOST_PAIRS,
                    colors = dclTextButtonColors(),
                    modifier = Modifier.semantics { contentDescription = "One pair more of ${PlateMath.text(plate.weight)} lb" }
                ) { Text("+") }
            }
        }
        Text(
            text = "Pairs, because a plate goes on each end. When your plates can't make a set's weight exactly, LIFT shows the closest load under it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = { onChange(PlateSetup.standard()) }, colors = dclTextButtonColors()) {
            Text("Reset to a standard gym")
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
