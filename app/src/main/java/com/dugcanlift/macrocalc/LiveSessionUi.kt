package com.dugcanlift.macrocalc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dugcanlift.macrocalc.data.BarKind
import com.dugcanlift.macrocalc.data.LiveSessionScreen
import com.dugcanlift.macrocalc.data.PlateMath
import com.dugcanlift.macrocalc.data.PlateSetup
import com.dugcanlift.macrocalc.data.RestCountdown
import com.dugcanlift.macrocalc.data.RestTimer
import com.dugcanlift.macrocalc.data.SettingsStore
import com.dugcanlift.macrocalc.ui.adaptive.AdaptiveLayout
import com.dugcanlift.macrocalc.ui.theme.dclCardBorder
import com.dugcanlift.macrocalc.ui.theme.dclTextButtonColors
import kotlinx.coroutines.delay

/**
 * What a live session does to the whole app, attached once at the root so it holds on every tab:
 * someone checking Food between sets is still training.
 *
 * - **The screen stays on** ([LiveSessionScreen]), through the window's keep-screen-on flag, which
 *   needs no permission. Past eight hours it lets go, and it waits for that moment: a screen held
 *   awake changes nothing else that would make it ask again.
 * - **The phone taps when a rest is over** with LIFT on screen, whichever tab is showing -- the
 *   banner's "Rest over" on Train, LIFT iPhone's `.sensoryFeedback`.
 */
@Composable
fun LiveSessionEffects() {
    val context = LocalContext.current
    val store = remember { LiveSessionStore.get(context) }
    val live by store.live.collectAsState()
    val keepsScreenOn by store.keepsScreenOn.collectAsState()
    val rest by store.timer.current.collectAsState()
    val view = LocalView.current

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val starts = listOfNotNull(live?.startedAtMs)
    val holds = LiveSessionScreen.holdsScreenOn(starts, keepsScreenOn, now)
    DisposableEffect(holds) {
        view.keepScreenOn = holds
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(live?.startedAtMs) {
        now = System.currentTimeMillis()
        val cutoff = LiveSessionScreen.nextCutoff(starts, now) ?: return@LaunchedEffect
        delay(cutoff - now)
        now = System.currentTimeMillis()
    }

    val haptics = LocalHapticFeedback.current
    LaunchedEffect(rest?.endsAtMs) {
        val current = rest ?: return@LaunchedEffect
        val wait = current.endsAtMs - System.currentTimeMillis()
        if (wait < 0) return@LaunchedEffect
        delay(wait)
        // Off screen, the alert says it instead (RestNotifications).
        if (AppVisibility.visible) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}

/**
 * The rest at the foot of Train while one is running: the time left, fifteen seconds either way,
 * and Skip. Once it is over it says so. A port of LIFT iPhone's `RestBanner`, word for word.
 *
 * A tick that starts no rest -- inside a superset round, or before a drop set -- shows what comes
 * next here instead ("Next: Seated Cable Rows", "Drop set next"), until the next tick or Done.
 */
@Composable
fun RestBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val timer = remember { LiveSessionStore.get(context).timer }
    val rest by timer.current.collectAsState()
    val cue by timer.cue.collectAsState()

    val current = rest
    val currentCue = cue
    if (current != null) {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(current.endsAtMs) {
            while (true) {
                now = System.currentTimeMillis()
                // "Rest over" goes by itself a while after, as it does coming back from the background.
                if (now - current.endsAtMs > RestTimer.KEEPS_OVER_FOR_MS) {
                    timer.restore(now)
                    break
                }
                delay(250)
            }
        }
        RestCard(modifier) {
            val over = current.isOver(now)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            ) {
                Text(
                    text = if (over) "Rest over" else "Rest",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (over) {
                    Text(
                        text = current.exerciseName?.let { "Back to $it" } ?: "Next set",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = RestCountdown.clock(current.remaining(now)),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (!over) {
                BannerButton("−15", "Fifteen seconds less") { timer.adjust(-RestTimer.STEP_SECONDS) }
                BannerButton("+15", "Fifteen seconds more") { timer.adjust(RestTimer.STEP_SECONDS) }
            }
            BannerButton(if (over) "Done" else "Skip", if (over) "Done" else "Skip rest") { timer.skip() }
        }
    } else if (currentCue != null) {
        RestCard(modifier) {
            Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(
                    text = "No rest",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = currentCue.text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BannerButton("Done", "Done") { timer.skip() }
        }
    }
}

@Composable
private fun RestCard(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Card(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .widthIn(max = AdaptiveLayout.READABLE_DP.dp)
            .fillMaxWidth(),
        // Raised over the page it sits on, whose cards are the same color: a dark card has no border
        // of its own, so this one gets an outline there.
        border = dclCardBorder() ?: BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = content
        )
    }
}

@Composable
private fun BannerButton(title: String, label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = dclTextButtonColors(),
        modifier = Modifier.semantics { contentDescription = label }
    ) { Text(title, fontWeight = FontWeight.SemiBold) }
}

/**
 * "Per side: 45 · 25" under a set's weight, for a lift on a bar. Reads the rack in Home → Training
 * settings. Nothing at all for a lift that is not on a bar, or a weight that is not a weight.
 */
@Composable
fun PlateLine(weightLb: Double?, bar: BarKind?, setup: PlateSetup, modifier: Modifier = Modifier) {
    if (bar == null || weightLb == null) return
    val line = PlateMath.load(weightLb, bar, setup).line() ?: return
    Text(
        text = line,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { contentDescription = line.replace(" · ", ", ") }
    )
}

/** The rack, read once per screen and again whenever Training settings changes it. */
@Composable
fun rememberPlateSetup(): PlateSetup {
    val context = LocalContext.current
    return remember { SettingsStore.get(context).plateSetup }
}
