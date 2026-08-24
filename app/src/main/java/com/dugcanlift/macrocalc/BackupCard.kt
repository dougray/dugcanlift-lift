package com.dugcanlift.macrocalc

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dugcanlift.macrocalc.data.BackupStore
import com.dugcanlift.macrocalc.data.todayKey

/**
 * Save the log to a file, and read one back.
 *
 * This app has no account and nothing in the cloud — Android's own Auto Backup
 * is switched off in the manifest for exactly that reason — so a file the user
 * keeps is the only thing standing between them and a lost training history.
 * It is also the only way to move a log to a new phone.
 *
 * The file is the same format the web version of LIFT writes, so a backup taken
 * in a browser restores here and the other way round.
 */
@Composable
fun BackupCard() {
    val context = LocalContext.current

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val result = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(BackupStore.build(context).toByteArray())
            } ?: error("could not open that location")
        }
        Toast.makeText(
            context,
            if (result.isSuccess) "Backup saved." else "Could not save the backup.",
            Toast.LENGTH_LONG
        ).show()
    }

    val loadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()

        if (text == null) {
            Toast.makeText(context, "Could not read that file.", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }

        val outcome = BackupStore.restore(context, text)
        val message = when {
            !outcome.ok -> outcome.problem ?: "That file isn't a LIFT backup."
            outcome.added == 0 -> "Restored. This phone already had everything in that file."
            outcome.added == 1 -> "Restored. Added 1 entry this phone didn't have."
            else -> "Restored. Added ${outcome.added} entries this phone didn't have."
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    Text("Your data", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Everything you log stays on this phone. There is no account to " +
                    "log back into, so a reinstall or a new phone takes it all " +
                    "with it. A backup file is the only copy that survives.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { saveLauncher.launch("lift-${todayKey()}.json") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save a backup file") }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { loadLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Restore from a backup file") }

            Spacer(Modifier.height(12.dp))
            Text(
                "Restoring only adds what is missing, so an older file can never " +
                    "wipe out training you have logged since.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
