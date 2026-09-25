package com.dugcanlift.macrocalc

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dugcanlift.macrocalc.data.RoutineRepository
import com.dugcanlift.macrocalc.data.ScheduledSessionRepository
import com.dugcanlift.macrocalc.data.onDate
import com.dugcanlift.macrocalc.data.todayKey
import com.dugcanlift.macrocalc.ui.theme.dclCardBorder
import com.dugcanlift.macrocalc.watchlink.WatchLinkTransport
import com.dugcanlift.macrocalc.watchlink.WatchLinkTransport.Status
import kotlinx.coroutines.launch

/**
 * "Pair with my watch", and afterwards the link in a line.
 *
 * Says what crosses the link before anything does, the way the Coach card says what an email
 * includes before Send: the day's workout goes out, finished sessions come back, and nothing else
 * leaves either device. There is no server in the middle — it is Bluetooth between two things the
 * lifter owns.
 *
 * The six-digit code is the point of the pairing screen. The watch shows the same number, computed
 * on its own from the handshake, and a lifter with two watches on the bench can see which one they
 * are about to adopt.
 */
@Composable
fun WatchCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val link = remember { WatchLinkTransport.get(context) }
    val routineRepo = remember { RoutineRepository.get(context) }
    val scheduleRepo = remember { ScheduledSessionRepository.get(context) }
    val scope = rememberCoroutineScope()

    val status by link.status.collectAsState()
    val found by link.found.collectAsState()
    val lastEvent by link.lastEvent.collectAsState()
    val routines by routineRepo.routines.collectAsState()
    val scheduled by scheduleRepo.sessions.collectAsState()
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        routineRepo.load()
        scheduleRepo.load()
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) link.startScan()
        else note = "LIFT needs Bluetooth permission to find your watch. Nothing else is asked for."
    }
    val today = todayKey()
    val todaysRoutine = scheduled.onDate(today).firstNotNullOfOrNull { s -> routines.firstOrNull { it.id == s.routineId } }

    Card(modifier = modifier.fillMaxWidth(), border = dclCardBorder()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Watch", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))

            when (val current = status) {
                Status.Idle -> {
                    Explainer()
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { note = null; permissions.launch(WatchLinkTransport.runtimePermissions) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Pair with my watch") }
                }

                Status.Scanning -> {
                    Body("On your watch, open LIFT and choose Phone, then Pair with phone.")
                    Spacer(modifier = Modifier.height(8.dp))
                    if (found.isEmpty()) Body("Looking…")
                    found.forEach { watch ->
                        TextButton(onClick = { link.pair(watch) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${watch.name}  ·  ${signal(watch.rssi)}")
                        }
                    }
                    TextButton(onClick = { link.stopScan() }) { Text("Stop looking") }
                }

                is Status.Connecting -> Body("Connecting to ${current.name}…")

                is Status.Bonding -> Body(
                    "Accept the Bluetooth pairing request on your phone and on ${current.name}. " +
                        "The link is encrypted, and nothing can be read from it until both have accepted."
                )

                is Status.Confirming -> {
                    Text(
                        text = current.code.chunked(3).joinToString(" "),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (!current.confirmedHere) {
                        Body("Does ${current.name} show this number? If it doesn't, you are pairing a different watch.")
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { link.confirm(true) }) { Text("Yes, it matches") }
                            OutlinedButton(onClick = { link.confirm(false) }) { Text("No") }
                        }
                    } else {
                        Body("Now accept on the watch.")
                    }
                }

                is Status.Waiting -> {
                    Body("Paired with ${current.name}. It connects by itself whenever LIFT is open on the watch.")
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { link.forget() }) { Text("Forget this watch") }
                }

                is Status.Linked -> {
                    Body("Connected to ${current.name}.")
                    Spacer(modifier = Modifier.height(12.dp))
                    if (todaysRoutine != null) {
                        Button(
                            onClick = { link.pushToday { ok -> note = if (ok) null else "Could not send. Is LIFT open on the watch?" } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Send today's workout: ${todaysRoutine.name}") }
                    } else {
                        Body("Nothing is scheduled today. Send a routine instead:")
                    }
                    routines.filter { it.id != todaysRoutine?.id }.take(MAX_ROUTINES).forEach { routine ->
                        TextButton(onClick = {
                            scope.launch {
                                val ok = link.pushRoutine(routine.id, day = null)
                                note = if (ok) null else "Could not send. Is LIFT open on the watch?"
                            }
                        }) { Text("Send ${routine.name}") }
                    }
                    TextButton(onClick = { link.forget() }) { Text("Forget this watch") }
                }

                is Status.Unavailable -> {
                    Body(current.reason)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { permissions.launch(WatchLinkTransport.runtimePermissions) }) { Text("Try again") }
                }

                is Status.Failed -> {
                    Body(current.reason)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { permissions.launch(WatchLinkTransport.runtimePermissions) }) { Text("Try again") }
                }
            }

            (note ?: lastEvent)?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Explainer() {
    Body(
        "LIFT on a Wear OS watch can take the day's workout to your wrist: the exercises, sets, target " +
            "weights and your last numbers for each. Sessions you finish on the watch come back into " +
            "your log. It is Bluetooth between your phone and your watch — nothing goes anywhere else, " +
            "and the watch still works on its own when your phone isn't there."
    )
}

@Composable
private fun Body(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Words, not dBm. Close enough to tell two watches on one bench apart. */
private fun signal(rssi: Int): String = when {
    rssi >= -60 -> "right here"
    rssi >= -75 -> "nearby"
    else -> "further away"
}

/** A card is not a routine library; the Train tab is. */
private const val MAX_ROUTINES = 6
