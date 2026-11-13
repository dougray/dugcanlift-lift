package com.dugcanlift.macrocalc

import com.dugcanlift.macrocalc.data.OutdoorActivity
import com.dugcanlift.macrocalc.data.OutdoorActivityMath
import com.dugcanlift.macrocalc.data.OutdoorActivityRepository

/**
 * Ends the recording and saves it, from anywhere: the recording screen's Finish and Train's live
 * card's Finish both come here, so a run can be finished with no recording screen on the display.
 *
 * Saves exactly once. The tracker hands the recording over and stops in one step
 * ([LocationTracker.takeRecording]), so a second call, a double tap, finds nothing recording and
 * returns null. A caller ignores that null silently: the first call already saved the run.
 */
object RecordingFinisher {

    suspend fun finish(
        tracker: LocationTracker,
        repository: OutdoorActivityRepository,
        nowEpochMs: Long = System.currentTimeMillis()
    ): OutdoorActivity? {
        val recording = tracker.takeRecording() ?: return null
        val finished = OutdoorActivity(
            activityType = recording.activityType,
            startedAtEpochMs = recording.startedAtEpochMs,
            endedAtEpochMs = nowEpochMs,
            distanceMeters = OutdoorActivityMath.totalDistanceMeters(recording.routePoints),
            elevationGainMeters = OutdoorActivityMath.elevationGainMeters(recording.routePoints),
            routePoints = recording.routePoints
        )
        repository.save(finished)
        return finished
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
