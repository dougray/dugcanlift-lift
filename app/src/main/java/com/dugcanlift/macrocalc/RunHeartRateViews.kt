package com.dugcanlift.macrocalc

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
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
import com.dugcanlift.macrocalc.data.HealthConnectManager
import com.dugcanlift.macrocalc.data.LiveRunExtras
import com.dugcanlift.macrocalc.data.OutdoorActivity
import com.dugcanlift.macrocalc.data.RunHeartRateWords
import com.dugcanlift.macrocalc.data.VO2MaxLookup

// What a run says about its heart rate and VO2 max. Tracked and shown, never targeted: plain
// text, no color, no zone, no advice, and nothing at all when there is nothing to say.

/**
 * The VO2 max line, asked once per appearance (per [key]) and held, never from a body that
 * redraws each second. [onOrBeforeEpochMs] is [VO2MaxLookup.LATEST] while a run records and the
 * run's end for a finished one. The write into state happens only if this effect is still alive:
 * a cancelled lookup (the screen left, the key changed) writes nothing.
 */
@Composable
internal fun rememberVO2MaxLine(key: Any?, onOrBeforeEpochMs: Long?): String? {
    val context = LocalContext.current
    var line by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key, onOrBeforeEpochMs) {
        val ask = onOrBeforeEpochMs ?: return@LaunchedEffect
        // Cancellation throws out of the lookup, so a stale answer never reaches `line`.
        line = VO2MaxLookup.line(ask, HealthConnectManager.History(context))
    }
    return line
}

/**
 * The live card's slot under Time/Distance/Pace. Takes the state and draws it; the *caller* reads
 * [RunHeartRateController.liveHeartRate] with the current tick inside its own once-a-second
 * composable, so the number goes to a dash at ten seconds with nothing else changing.
 */
@Composable
internal fun LiveRunExtrasBlock(extras: LiveRunExtras) {
    if (extras.isEmpty) return
    Column {
        Spacer(modifier = Modifier.height(8.dp))
        extras.heartRate?.let { line ->
            Text(line.number, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(line.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        extras.vo2MaxLine?.let { vo2 ->
            Text(vo2, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A finished run's `Avg 141 · Max 168 bpm`, its source, and its VO2 max line; nothing when none. */
@Composable
internal fun FinishedRunHeartRate(activity: OutdoorActivity, vo2MaxLine: String?) {
    val line = RunHeartRateWords.line(activity)
    if (line == null && vo2MaxLine == null) return
    Column {
        Spacer(modifier = Modifier.height(8.dp))
        line?.let {
            Text(it.summary, style = MaterialTheme.typography.titleMedium)
            it.source?.let { source ->
                Text(source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        vo2MaxLine?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Words a failed Finish shows, chosen by what is true. */
internal object RunFinishCopy {
    const val SAVE_FAILED = "Couldn't save this run. It is still recording: tap Finish to try again."
    const val RUN_LOST = "Couldn't save this run, and it can't be put back. Start a new one."

    /**
     * What to say about a failed Finish, or null for nothing: a cancellation is no failure and no
     * save was tried. The "still recording" words only when the run really is back.
     */
    fun messageFor(e: Throwable): String? = when {
        e is kotlinx.coroutines.CancellationException -> null
        e is RunNotSavedException && !e.putBack -> RUN_LOST
        else -> SAVE_FAILED
    }
}
