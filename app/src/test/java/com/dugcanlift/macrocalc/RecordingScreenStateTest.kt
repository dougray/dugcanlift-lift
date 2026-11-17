package com.dugcanlift.macrocalc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingScreenStateTest {

    @Test
    fun `recording and wanted shows the screen`() {
        assertTrue(RecordingScreenState.showScreen(trackerRecording = true, screenWanted = true))
    }

    @Test
    fun `recording but not wanted is Train with the card`() {
        assertFalse(RecordingScreenState.showScreen(trackerRecording = true, screenWanted = false))
        assertTrue(RecordingScreenState.showLiveCard(trackerRecording = true))
    }

    @Test
    fun `not recording is Train and no card`() {
        assertFalse(RecordingScreenState.showScreen(trackerRecording = false, screenWanted = true))
        assertFalse(RecordingScreenState.showScreen(trackerRecording = false, screenWanted = false))
        assertFalse(RecordingScreenState.showLiveCard(trackerRecording = false))
    }

    @Test
    fun `the card is there whenever a run records, wanted screen or not`() {
        // A run must never be stranded with neither the screen nor the card.
        listOf(true, false).forEach { wanted ->
            val screen = RecordingScreenState.showScreen(trackerRecording = true, screenWanted = wanted)
            val card = RecordingScreenState.showLiveCard(trackerRecording = true)
            assertTrue(screen || card)
        }
    }

    @Test
    fun `the start screen shows while a type is chosen and nothing records yet`() {
        assertTrue(RecordingScreenState.showStartScreen(trackerRecording = false, chosenType = true))
        assertFalse(RecordingScreenState.showStartScreen(trackerRecording = false, chosenType = false))
        assertFalse(RecordingScreenState.showStartScreen(trackerRecording = true, chosenType = true))
    }

    @Test
    fun `anotherRunCannotStartWhileOneRecords`() {
        assertFalse(RecordingScreenState.runButtonsEnabled(trackerRecording = true))
        assertTrue(RecordingScreenState.runButtonsEnabled(trackerRecording = false))
    }
}
