package com.dugcanlift.macrocalc

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The app's one feedback channel: the snackbar host on MainActivity's root
 * Scaffold, and a scope that lives as long as that Scaffold does.
 *
 * The scope matters as much as the host. A screen's own `rememberCoroutineScope`
 * is cancelled when its tab is left, so an Undo tapped after switching tabs
 * would run in a dead scope and silently do nothing; [showUndo] runs it here
 * instead. [scope] is also there for a save that must outlive the screen that
 * asked for it (the workout name's last keystrokes when the tab is switched).
 */
class AppFeedback(val hostState: SnackbarHostState, val scope: CoroutineScope) {

    /** A short result line ("Backup saved."), replacing whatever is showing. */
    fun show(message: String, long: Boolean = false) {
        scope.launch {
            hostState.currentSnackbarData?.dismiss()
            hostState.showSnackbar(
                message = message,
                withDismissAction = long,
                duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short
            )
        }
    }

    /**
     * Something was just removed; offer it back. [onUndo] runs only if Undo is
     * tapped while the snackbar is up, and runs in the app scope.
     */
    fun showUndo(message: String, onUndo: suspend () -> Unit) {
        scope.launch {
            hostState.currentSnackbarData?.dismiss()
            val result = hostState.showSnackbar(
                message = message,
                actionLabel = "Undo",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) onUndo()
        }
    }
}

/** Provided once, by MainActivity, around everything the root Scaffold draws. */
val LocalAppFeedback = staticCompositionLocalOf<AppFeedback> {
    error("LocalAppFeedback is provided by MainActivity's root Scaffold")
}
