package com.dugcanlift.macrocalc

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dugcanlift.macrocalc.data.WorkoutCsv
import com.dugcanlift.macrocalc.data.WorkoutRepository
import com.dugcanlift.macrocalc.data.todayKey
import com.dugcanlift.macrocalc.ui.theme.dclOutlinedButtonColors
import com.dugcanlift.macrocalc.ui.theme.dclTextButtonColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Your data: training history in from another app, and out as a spreadsheet. A port of LIFT
 * iPhone's `WorkoutCSVSection`, word for word. Both are files the person chooses through the
 * system's document picker (the Storage Access Framework), so no storage permission is asked for,
 * and nothing is sent anywhere.
 */
@Composable
internal fun WorkoutCsvButtons() {
    val context = LocalContext.current
    val feedback = LocalAppFeedback.current
    val scope = rememberCoroutineScope()
    val workouts = remember { WorkoutRepository.get(context) }
    var reading by remember { mutableStateOf<WorkoutCsv.Reading?>(null) }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { decodeText(it.readBytes()) } }.getOrNull()
            }
            if (text == null) {
                feedback.show("Could not read that file.", long = true)
                return@launch
            }
            reading = try {
                WorkoutCsv.read(text)
            } catch (e: WorkoutCsv.Failure) {
                feedback.show("That doesn't look like an export from Strong, Hevy or FitNotes.", long = true)
                null
            }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    workouts.load()
                    val text = WorkoutCsv.export(WorkoutCsv.exportRows(workouts.sessions.value))
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                        ?: error("could not open that location")
                }.isSuccess
            }
            feedback.show(if (ok) "Spreadsheet saved." else "Could not write the file.", long = !ok)
        }
    }

    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { openLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) },
        modifier = Modifier.fillMaxWidth(),
        colors = dclOutlinedButtonColors()
    ) { Text("Import from Strong, Hevy or FitNotes") }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { saveLauncher.launch("lift-training-${todayKey()}.csv") },
        modifier = Modifier.fillMaxWidth(),
        colors = dclOutlinedButtonColors()
    ) { Text("Export training as a spreadsheet (CSV)") }

    reading?.let { pending ->
        WorkoutCsvImportDialog(
            reading = pending,
            onImport = { imported ->
                reading = null
                scope.launch {
                    workouts.load()
                    val outcome = workouts.importCsv(imported)
                    feedback.show(WorkoutCsv.summary(outcome), long = true)
                }
            },
            onDismiss = { reading = null }
        )
    }
}

/** UTF-8, and ISO 8859-1 for a file that is not, as the iPhone falls back to Latin-1. */
internal fun decodeText(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
} catch (e: CharacterCodingException) {
    String(bytes, Charsets.ISO_8859_1)
}

private fun csvDay(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/**
 * What the file holds, the one question when the file can't answer it, and Import. The iPhone's
 * sheet as a dialog, its words kept.
 */
@Composable
private fun WorkoutCsvImportDialog(
    reading: WorkoutCsv.Reading,
    onImport: (List<WorkoutCsv.ImportedWorkout>) -> Unit,
    onDismiss: () -> Unit
) {
    var unit by remember { mutableStateOf<WorkoutCsv.Unit?>(null) }
    val workouts = remember(unit) { if (reading.needsUnit && unit == null) null else reading.parse(unit) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import training") },
        text = {
            Column {
                Text("From ${reading.source.label}", style = MaterialTheme.typography.titleSmall)
                workouts?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(WorkoutCsv.describe(it, ::csvDay), style = MaterialTheme.typography.bodyMedium)
                    WorkoutCsv.warmupLine(WorkoutCsv.warmups(it), done = false)?.let { line ->
                        Spacer(Modifier.height(4.dp))
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (reading.needsUnit) {
                    Spacer(Modifier.height(12.dp))
                    Text("Weights in this file are in", style = MaterialTheme.typography.bodyMedium)
                    Column(Modifier.selectableGroup()) {
                        listOf(WorkoutCsv.Unit.POUNDS to "Pounds (lb)", WorkoutCsv.Unit.KILOGRAMS to "Kilograms (kg)")
                            .forEach { (choice, label) ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                        .selectable(selected = unit == choice, onClick = { unit = choice }, role = Role.RadioButton)
                                ) {
                                    RadioButton(selected = unit == choice, onClick = null)
                                    Text(label, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                    }
                    Text(
                        "This Strong file doesn't say. It is whichever unit Strong was showing when you exported it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Days you already have training on in LIFT are left exactly as they are, so importing the " +
                        "same file twice adds nothing the second time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { workouts?.let(onImport) },
                enabled = !workouts.isNullOrEmpty(),
                colors = dclTextButtonColors()
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss, colors = dclTextButtonColors()) { Text("Cancel") } }
    )
}
