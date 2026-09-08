package com.dugcanlift.macrocalc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dugcanlift.macrocalc.data.*
import kotlinx.coroutines.launch

@Composable
fun PlanPreviewDialog(result: PlanDecodeResult, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importResult by remember { mutableStateOf<PlanImportResult?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titleFor(result, importResult)) },
        text = { Text(bodyFor(result, importResult)) },
        confirmButton = {
            when (result) {
                is PlanDecodeResult.Success -> if (importResult == null) {
                    TextButton(onClick = {
                        scope.launch { importResult = PlanImporter.accept(result.payload, context) }
                    }) { Text("Accept") }
                } else {
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                else -> TextButton(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = {
            if (result is PlanDecodeResult.Success && importResult == null) {
                TextButton(onClick = onDismiss) { Text("Decline") }
            }
        }
    )
}

private fun titleFor(result: PlanDecodeResult, importResult: PlanImportResult?): String = when {
    importResult is PlanImportResult.Imported -> "Imported"
    importResult is PlanImportResult.AlreadyImported -> "Already imported"
    result is PlanDecodeResult.Success -> "Plan from ${result.payload.coachName}"
    result is PlanDecodeResult.NotAddressedToYou -> "Not your plan"
    result is PlanDecodeResult.UnsupportedVersion -> "Update required"
    else -> "This plan link couldn't be read"
}

private fun bodyFor(result: PlanDecodeResult, importResult: PlanImportResult?): String = when {
    importResult is PlanImportResult.Imported ->
        "Added ${importResult.recipeCount} recipes, ${importResult.mealCount} meals, ${importResult.routineCount} routines."
    importResult is PlanImportResult.AlreadyImported -> "You already imported this plan."
    result is PlanDecodeResult.Success -> PlanImporter.summarize(result.payload)
    result is PlanDecodeResult.NotAddressedToYou -> "This plan link isn't addressed to you."
    result is PlanDecodeResult.UnsupportedVersion -> "This plan needs a newer version of the app."
    else -> "The link may be corrupted or incomplete."
}
