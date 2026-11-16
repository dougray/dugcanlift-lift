package com.dugcanlift.macrocalc

import android.content.Context
import com.dugcanlift.macrocalc.data.HealthConnectManager
import com.dugcanlift.macrocalc.data.HeartRateHistory
import com.dugcanlift.macrocalc.data.OutdoorActivity
import com.dugcanlift.macrocalc.data.OutdoorActivityMath
import com.dugcanlift.macrocalc.data.OutdoorActivityRepository
import com.dugcanlift.macrocalc.data.RunHeartRateFinish
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ends the recording and saves it, from anywhere: the recording screen's Finish and Train's live
 * card's Finish both come here, so a run can be finished with no recording screen on the display.
 *
 * **Saves exactly once.** A second or concurrent Finish (a double tap, the card and the screen)
 * finds one already waiting and returns null; a caller ignores that null silently, because the
 * first call saves the run. Health Connect is asked at most once, by that first call.
 *
 * **The run ends at the tap, the save happens after Health Connect answers.** The route, end time
 * and heart rate window are taken when Finish is chosen. Health Connect is asked (only when no
 * strap spoke and the setting is not Off) with a timeout, so a hung Health Connect holds Finish
 * for at most [RunHeartRateFinish.QUERY_TIMEOUT_MS] and then the run saves with no figures; the
 * review asks again later. While it waits the run is still recording, so Cancel still works: a
 * Discard in that window stops the run and this call saves nothing (it returns null). A screen
 * leaving mid-wait cancels only the wait, and the run goes on recording.
 *
 * Only then is the run taken from the tracker and saved under [NonCancellable], once. If that
 * save throws, the run is put back to recording with its route and start time, and a
 * [RunNotSavedException] says whether that worked ([RunNotSavedException.putBack]): it cannot when
 * GPS is off, location permission was revoked, the foreground service is refused, or another run
 * began while the save ran. A run that *was* saved is never put back, even if the caller was
 * cancelled during the save (a second Finish would save a duplicate). (Before, a save that threw
 * lost a run the tracker had already stopped.)
 *
 * A put-back run starts a fresh heart rate pick ([RunHeartRateController.onRunStarted]), so its
 * next Finish keeps the Avg/Max of only the readings after it was put back (under the strap's
 * name when the strap is found again) or, with none, asks Health Connect for the whole run.
 *
 * Callers on a screen use [launchFinish], which runs this in the tracker's own scope: leaving the
 * screen during the Health Connect wait does not cancel the person's Finish.
 */
object RecordingFinisher {

    suspend fun finish(
        context: Context,
        tracker: LocationTracker,
        repository: OutdoorActivityRepository,
        history: HeartRateHistory = HealthConnectManager.History(context),
        nowEpochMs: Long = System.currentTimeMillis(),
        queryTimeoutMs: Long = RunHeartRateFinish.QUERY_TIMEOUT_MS,
        saveRun: suspend (OutdoorActivity) -> Unit = { repository.save(it) },
    ): OutdoorActivity? {
        val snapshot = tracker.snapshotRecording() ?: return null
        val figures = tracker.heartRate.beginFinish(snapshot.startedAtEpochMs) ?: return null
        try {
            // The window is the run's own, ended at the tap.
            val record = RunHeartRateFinish.record(
                readings = figures.readings,
                sourceName = figures.sourceName,
                setting = figures.setting,
                history = history,
                startEpochMs = snapshot.startedAtEpochMs,
                endEpochMs = nowEpochMs,
                timeoutMs = queryTimeoutMs,
            )
            // Cancelled while Health Connect answered: the person's later word stands.
            // Only this run: if it was discarded and another began meanwhile, that one is not ours to stop.
            val taken = tracker.takeRecording(snapshot.startedAtEpochMs) ?: return null
            val finished = RunHeartRateFinish.apply(
                record,
                OutdoorActivity(
                    activityType = snapshot.activityType,
                    startedAtEpochMs = snapshot.startedAtEpochMs,
                    endedAtEpochMs = nowEpochMs,
                    distanceMeters = OutdoorActivityMath.totalDistanceMeters(snapshot.routePoints),
                    elevationGainMeters = OutdoorActivityMath.elevationGainMeters(snapshot.routePoints),
                    routePoints = snapshot.routePoints,
                ),
            )
            var saved = false
            try {
                // The run is already stopped, so a screen leaving mid-save must not drop it.
                withContext(NonCancellable) { saveRun(finished); saved = true }
            } catch (e: Exception) {
                // Saved, then cancelled on the way out: the run is on disk, so never put it back.
                if (saved) throw e
                // Released first, so the put-back run's own heart rate pick is not refused.
                tracker.heartRate.endFinish(snapshot.startedAtEpochMs)
                val putBack = runCatching { tracker.resume(taken) }.getOrDefault(false)
                throw RunNotSavedException(putBack, e)
            }
            return finished
        } finally {
            tracker.heartRate.endFinish(snapshot.startedAtEpochMs)
        }
    }

    /**
     * Finish in the tracker's scope, so it completes even if the screen that tapped it is gone.
     * [onSaved] gets the run on the main thread (a screen still present opens the review; one that
     * left just has the saved run appear in Last route, which reads the repository). [onFailed]
     * gets the words to say, never for a cancellation and never when no save was tried.
     */
    fun launchFinish(
        context: Context,
        tracker: LocationTracker,
        repository: OutdoorActivityRepository,
        onSaved: (OutdoorActivity) -> Unit,
        onFailed: (String) -> Unit,
        scope: CoroutineScope = tracker.finishScope,
        history: HeartRateHistory = HealthConnectManager.History(context),
    ): Job = scope.launch {
        try {
            finish(context, tracker, repository, history)?.let(onSaved)
        } catch (e: Exception) {
            RunFinishCopy.messageFor(e)?.let(onFailed)
        }
    }
}

/**
 * What decides which of Train's faces a run has. Pure, so the rules are tested rather than
 * left in a composable's state.
 */
object RecordingScreenState {

    /** The full recording screen takes over only while a run records AND the person wants it. */
    fun showScreen(trackerRecording: Boolean, screenWanted: Boolean): Boolean =
        trackerRecording && screenWanted

    /** The screen before Start: a type was chosen and nothing records yet. */
    fun showStartScreen(trackerRecording: Boolean, chosenType: Boolean): Boolean =
        !trackerRecording && chosenType

    /** Whenever a run records, Train's card shows it, so it can never be stranded unseen. */
    fun showLiveCard(trackerRecording: Boolean): Boolean = trackerRecording

    /** Run, Walk and Hike are one run at a time. */
    fun runButtonsEnabled(trackerRecording: Boolean): Boolean = !trackerRecording
}

/** A Finish whose save failed. [putBack]: the run is recording again with its route; false means it is lost. */
class RunNotSavedException(val putBack: Boolean, cause: Throwable) : Exception("The run was not saved", cause)
