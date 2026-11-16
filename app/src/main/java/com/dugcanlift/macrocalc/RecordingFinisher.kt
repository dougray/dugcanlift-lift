package com.dugcanlift.macrocalc

import android.content.Context
import com.dugcanlift.macrocalc.data.HealthConnectManager
import com.dugcanlift.macrocalc.data.HeartRateHistory
import com.dugcanlift.macrocalc.data.OutdoorActivity
import com.dugcanlift.macrocalc.data.OutdoorActivityMath
import com.dugcanlift.macrocalc.data.OutdoorActivityRepository
import com.dugcanlift.macrocalc.data.RunHeartRateFinish
import kotlinx.coroutines.NonCancellable
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
 * save throws, the run is put back to recording with its route and start time, so nothing is lost
 * and a second Finish saves it once; the failure is rethrown so the caller can say so. (Before,
 * a save that threw lost a run the tracker had already stopped.) The heart rate session is not
 * resumed with it: a run put back has no live source, and Health Connect fills the summary.
 */
object RecordingFinisher {

    suspend fun finish(
        context: Context,
        tracker: LocationTracker,
        repository: OutdoorActivityRepository,
        history: HeartRateHistory = HealthConnectManager.History(context),
        nowEpochMs: Long = System.currentTimeMillis(),
        queryTimeoutMs: Long = RunHeartRateFinish.QUERY_TIMEOUT_MS,
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
            try {
                // The run is already stopped, so a screen leaving mid-save must not drop it.
                withContext(NonCancellable) { repository.save(finished) }
            } catch (e: Exception) {
                tracker.resume(taken)
                throw e
            }
            return finished
        } finally {
            tracker.heartRate.endFinish()
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
