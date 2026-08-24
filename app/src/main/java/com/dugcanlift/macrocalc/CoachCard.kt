package com.dugcanlift.macrocalc

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.dugcanlift.macrocalc.data.CoachShare
import com.dugcanlift.macrocalc.data.CoachStore
import com.dugcanlift.macrocalc.data.FoodEntry
import com.dugcanlift.macrocalc.data.HealthConnectManager
import com.dugcanlift.macrocalc.data.SettingsStore
import com.dugcanlift.macrocalc.data.WorkoutSession

/**
 * "Send to Coach" — one tap opens the email app with the whole thing written.
 *
 * The final Send is the mail app's, not this app's. Sending mail silently
 * would mean holding the person's mail credentials or standing up a server,
 * and neither belongs in a tracker that otherwise keeps everything on the
 * phone. Nothing has to be typed or attached, which is the part that matters.
 */
@Composable
fun CoachCard(
    goal: MacroResult?,
    sessions: List<WorkoutSession>,
    entries: List<FoodEntry>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val store = remember { CoachStore.get(context) }
    val settings = remember { SettingsStore.get(context) }

    var editing by remember { mutableStateOf(!store.isConfigured) }
    var email by remember { mutableStateOf(store.email) }
    var name by remember { mutableStateOf(store.lifterName) }
    var weeks by remember { mutableStateOf(store.weeks) }
    var itemised by remember { mutableStateOf(store.itemisedFood) }
    var sizeNote by remember { mutableStateOf("") }

    // Step history is read from Health Connect rather than stored here, so it
    // is whatever Health Connect believes right now — including steps logged
    // by a watch this app never sees. Empty when the permission was declined,
    // which costs the step lines and nothing else.
    var steps by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }

    LaunchedEffect(weeks, editing) {
        if (editing) return@LaunchedEffect
        steps = HealthConnectManager.dailyStepCounts(context, weeks * 7)
    }

    // Recomputed rather than guessed: the person deserves to know how long the
    // email is before they send one their coach's mail app might mangle.
    LaunchedEffect(weeks, itemised, sessions, entries, steps, editing) {
        if (editing) return@LaunchedEffect
        val link = CoachShare.buildLink(store, settings, goal, sessions, entries, steps)
        val kb = link.length / 1024.0
        sizeNote = String.format("About %.1f KB of email.", kb) +
            if (CoachShare.linkIsRisky(link))
                " That is long enough that some mail apps will break it - send a shorter window."
            else ""
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Coach",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (editing) {
                Text(
                    text = "Working with a coach? Put their email in once and you can send " +
                        "them your training and eating with one tap.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Your name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Coach's email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        val trimmed = email.trim()
                        if (!trimmed.contains("@") || !trimmed.contains(".")) {
                            Toast.makeText(
                                context, "That doesn't look like an email address.", Toast.LENGTH_SHORT
                            ).show()
                            return@Button
                        }
                        store.email = trimmed
                        store.lifterName = name.trim()
                        editing = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save")
                }
            } else {
                Text(
                    text = "Goes to ${store.email}. Your email app opens with it all " +
                        "written - you just hit send.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text(text = "How much to send", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoachShare.WINDOW_CHOICES.forEach { choice ->
                        FilterChip(
                            selected = weeks == choice,
                            onClick = { weeks = choice; store.weeks = choice },
                            label = { Text(if (choice == 26) "6 mo" else "${choice}w") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !itemised,
                        onClick = { itemised = false; store.itemisedFood = false },
                        label = { Text("Daily totals") }
                    )
                    FilterChip(
                        selected = itemised,
                        onClick = { itemised = true; store.itemisedFood = true },
                        label = { Text("Every food logged") }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        val opened = CoachShare.sendEmail(
                            context, store, settings, goal, sessions, entries, steps
                        )
                        if (!opened) {
                            Toast.makeText(
                                context,
                                "No email app is set up on this phone.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Send to Coach")
                }

                if (sizeNote.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = sizeNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                TextButton(onClick = { editing = true }) { Text("Change these details") }
            }
        }
    }
}
