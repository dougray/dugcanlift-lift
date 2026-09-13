package com.dugcanlift.macrocalc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dugcanlift.macrocalc.data.*
import com.dugcanlift.kit.PlanDecodeResult
import kotlinx.coroutines.launch

@Composable
fun PlanPreviewDialog(result: PlanDecodeResult, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importResult by remember { mutableStateOf<PlanImportResult?>(null) }
    var importing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titleFor(result, importResult)) },
        text = { Text(bodyFor(result, importResult)) },
        confirmButton = {
            when (result) {
                is PlanDecodeResult.Success -> if (importResult == null) {
                    TextButton(
                        enabled = !importing,
                        onClick = {
                            importing = true
                            scope.launch {
                                try {
                                    importResult = PlanImporter.accept(result.payload, context)
                                } finally {
                                    importing = false
                                }
                            }
                        }
                    ) { Text("Accept") }
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
    importResult is PlanImportResult.Imported -> {
        val parts = mutableListOf<String>()
        if (importResult.recipeCount > 0) parts += pluralize(importResult.recipeCount, "recipe")
        if (importResult.mealCount > 0) parts += pluralize(importResult.mealCount, "meal")
        if (importResult.routineCount > 0) parts += pluralize(importResult.routineCount, "routine")
        if (importResult.sessionCount > 0) parts += pluralize(importResult.sessionCount, "session")
        if (parts.isEmpty()) "Nothing new was added." else "Added ${parts.joinToString(", ")}."
    }
    importResult is PlanImportResult.AlreadyImported -> "You already imported this plan."
    result is PlanDecodeResult.Success -> PlanImporter.summarize(result.payload)
    result is PlanDecodeResult.NotAddressedToYou -> "This plan link isn't addressed to you."
    result is PlanDecodeResult.UnsupportedVersion -> "This plan needs a newer version of the app."
    else -> "The link may be corrupted or incomplete."
}

private fun pluralize(count: Int, noun: String): String = "$count $noun${if (count == 1) "" else "s"}"
