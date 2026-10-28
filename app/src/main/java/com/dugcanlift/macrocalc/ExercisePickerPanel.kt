package com.dugcanlift.macrocalc

import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dugcanlift.macrocalc.data.EQUIPMENT_FILTERS
import com.dugcanlift.macrocalc.data.ExerciseLibraryStore
import com.dugcanlift.macrocalc.data.LibraryExercise
import com.dugcanlift.macrocalc.data.LoggedExercise
import com.dugcanlift.macrocalc.data.MyEquipment
import com.dugcanlift.macrocalc.data.SettingsStore
import com.dugcanlift.macrocalc.data.searchExerciseLibraryOwned
import com.dugcanlift.macrocalc.data.titleCaseAscii
import com.dugcanlift.macrocalc.ui.theme.dclOutlinedButtonColors
import com.dugcanlift.macrocalc.ui.theme.dclTextButtonColors
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment

/**
 * Picking an exercise: search over the bundled 873, or type one that isn't
 * there.
 *
 * The typed path is not a fallback for when the library fails — it is the
 * first-class other half. The library is a convenience, not a gate on what you
 * are allowed to have done, so the name field stays live whether or not the
 * file loaded, and "Add as typed" is always one tap away.
 *
 * @param known exercises already logged, newest first — both the chips shown
 *   before anything is typed and the ranking applied to search results.
 */
@Composable
fun ExercisePickerPanel(
    known: List<LoggedExercise>,
    equipmentOptions: List<String>,
    onAdd: (name: String, equipment: String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current

    var library by remember { mutableStateOf<List<LibraryExercise>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    var query by remember { mutableStateOf("") }
    var equipment by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }

    // My equipment, read as the panel opens: what is switched off on Home is left out of the
    // results, and said so. "Show all" is for this search, without changing the setting.
    val notOwned = remember { SettingsStore.get(context).equipmentNotOwned }
    var showsEverything by remember { mutableStateOf(false) }
    val filtering = notOwned.isNotEmpty() && !showsEverything

    // First open pays for the parse; someone who only tracks food never does.
    LaunchedEffect(Unit) {
        library = ExerciseLibraryStore.load(context)
        loadError = ExerciseLibraryStore.lastError
        loading = false
    }

    val recentKeys = remember(known) { known.map { it.matchKey } }
    val search = remember(library, query, filter, recentKeys, filtering) {
        library?.let {
            searchExerciseLibraryOwned(it, query, filter, recentKeys, notOwned = if (filtering) notOwned else emptySet())
        }
    }
    val results = search?.shown.orEmpty()
    val leftOut = search?.leftOut ?: 0

    NameField(value = query, onValueChange = { query = it }, label = "Exercise")

    // Their own lifts, before they have typed anything to narrow by.
    if (query.isBlank() && known.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            known.forEach { item ->
                AssistChip(
                    onClick = { onAdd(item.name, item.equipment) },
                    label = { Text(item.displayName) }
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    when {
        loading -> Text(
            text = "Loading the exercise library...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        loadError != null -> Text(
            text = "Could not load the exercise library ($loadError). " +
                "You can still add an exercise by typing it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        else -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                FilterChip(
                    selected = filter.isEmpty(),
                    onClick = { filter = "" },
                    label = { Text("All") }
                )
                EQUIPMENT_FILTERS.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = if (filter == option) "" else option },
                        label = { Text(titleCaseAscii(option)) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Says the list is narrowed, and by how much, with the way out.
            if (notOwned.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = MyEquipment.statusText(filtering, leftOut),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showsEverything = !showsEverything }, colors = dclTextButtonColors()) {
                        Text(if (filtering) "Show all" else "Only mine", maxLines = 1)
                    }
                }
            }

            if (results.isEmpty() && leftOut > 0) {
                Text(text = "Nothing with your equipment", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = MyEquipment.leftOutText(leftOut),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (results.isEmpty()) {
                Text(
                    text = "Nothing matches that.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // A plain Column, not a lazy list: this panel already sits
                // inside a vertically scrolling parent, which gives a lazy
                // list an unbounded height to measure against. The browser's
                // own 40-row cap is what keeps that affordable.
                Column(modifier = Modifier.fillMaxWidth()) {
                    results.forEach { hit ->
                        ExerciseResultRow(
                            hit = hit,
                            logged = hit.matchKey in recentKeys,
                            onClick = { onAdd(hit.name, hit.storedEquipment) }
                        )
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(12.dp))

    NameField(
        value = equipment,
        onValueChange = { equipment = it },
        label = "Equipment (optional)"
    )

    Spacer(modifier = Modifier.height(8.dp))

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
    ) {
        equipmentOptions.forEach { option ->
            AssistChip(onClick = { equipment = option }, label = { Text(option) })
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = { onAdd(query.trim(), equipment.trim()) },
            enabled = query.isNotBlank()
        ) {
            Text("Add as typed")
        }
        OutlinedButton(onClick = onCancel, colors = dclOutlinedButtonColors()) { Text("Cancel") }
    }
}

@Composable
private fun ExerciseResultRow(
    hit: LibraryExercise,
    logged: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { if (logged) stateDescription = "Logged before" }
            .padding(vertical = 8.dp)
    ) {
        Text(
            // The clock face the iPhone app puts on a lift you have done
            // before, in the one glyph that needs no legend -- to the eye.
            // TalkBack read it as "stopwatch", so it hears the name and the
            // row's state, "Logged before", instead.
            text = if (logged) "${hit.name}  ⏱" else hit.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.clearAndSetSemantics { contentDescription = hit.name }
        )
        Text(
            text = hit.detailLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
