package com.dugcanlift.macrocalc

import androidx.activity.compose.rememberLauncherForActivityResult
import com.dugcanlift.macrocalc.ui.theme.dclCardBorder
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dugcanlift.macrocalc.data.FoodEntry
import com.dugcanlift.macrocalc.data.FoodSearch
import com.dugcanlift.macrocalc.data.FoodSearchResult
import com.dugcanlift.macrocalc.data.Nutriments
import com.dugcanlift.macrocalc.data.NutrientDetailsText
import com.dugcanlift.macrocalc.data.SettingsStore
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

/**
 * Search panel backed by Open Food Facts. Tapping a result (whether it came
 * from a text search or a barcode scan — both land in the same [results]
 * list, so both go through the same flow here) opens an amount-entry dialog
 * asking for a gram or ounce amount, computed from the per-100g figures.
 * Confirming hands a fully-formed, gram-based [FoodEntry] straight to
 * [onConfirm] — the amount and nutrition are already fully determined by
 * then, so there's no separate confirm-through-a-form step for this path.
 */
@Composable
fun FoodSearchPanel(
    date: String,
    onConfirm: (FoodEntry) -> Unit,
    onCancel: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<FoodSearchResult>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var hasSearched by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<FoodSearchResult?>(null) }

    // Shared by both the text search and the barcode scanner.
    fun consume(outcome: FoodSearch.Outcome, emptyMessage: String) {
        when (outcome) {
            is FoodSearch.Outcome.Success -> {
                results = outcome.results
                if (outcome.results.isEmpty()) message = emptyMessage
            }
            is FoodSearch.Outcome.Failure -> {
                results = emptyList()
                message = outcome.message
            }
        }
        searching = false
        hasSearched = true
    }

    // ZXing asks for the camera permission itself when the scanner opens, so
    // there's no separate permission dance here.
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { scan ->
        val code = scan.contents
        if (code != null) {
            searching = true
            message = null
            scope.launch {
                consume(
                    FoodSearch.lookupBarcode(code),
                    "No product found for that barcode."
                )
            }
        }
    }

    fun runSearch() {
        if (query.isBlank() || searching) return
        searching = true
        message = null
        scope.launch {
            consume(FoodSearch.searchByName(query), "Nothing found for that.")
        }
    }

    Card(modifier = Modifier.fillMaxWidth(), border = dclCardBorder()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Search food", style = MaterialTheme.typography.titleMedium)

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Searches Open Food Facts. Only your search term or barcode is sent.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            NameField(
                value = query,
                onValueChange = { query = it },
                label = "Food or brand"
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { runSearch() }, enabled = query.isNotBlank() && !searching) {
                    Text(if (searching) "Searching..." else "Search")
                }
                OutlinedButton(
                    onClick = {
                        scanLauncher.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.PRODUCT_CODE_TYPES)
                                .setPrompt("Point the camera at a barcode")
                                .setBeepEnabled(false)
                                .setOrientationLocked(true)
                        )
                    },
                    enabled = !searching
                ) {
                    Text("Scan")
                }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }

            message?.let {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (results.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                results.forEach { result ->
                    SearchResultRow(result = result, onClick = { picked = result })
                }
            } else if (hasSearched && message == null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Nothing found for that.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    picked?.let { result ->
        AmountEntryDialog(
            result = result,
            date = date,
            onConfirm = { entry ->
                onConfirm(entry)
                picked = null
            },
            onDismiss = { picked = null }
        )
    }
}

/**
 * Asks for the amount actually eaten, in whichever unit the person prefers
 * (read from [SettingsStore], mirroring LIFT iOS), then converts to grams and
 * computes nutrition from the result's per-100g figures.
 */
@Composable
private fun AmountEntryDialog(
    result: FoodSearchResult,
    date: String,
    onConfirm: (FoodEntry) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val settings = remember { SettingsStore.get(context) }

    // Read once per dialog open; the real Serving Size preference lives in
    // Settings (DashboardScreen) now, so this just follows it rather than
    // offering its own switcher.
    val unit = settings.servingUnit
    var amountText by remember { mutableStateOf("") }

    val enteredAmount = amountText.toDoubleOrNull()
    val grams = enteredAmount?.let { unit.toGrams(it) }
    val nutrition: Nutriments? = grams?.let { result.nutrition(it) }
    val valid = grams != null && grams > 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(result.displayName) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "How much are you logging?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                NumberField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = "Amount (${unit.abbreviation})"
                )

                nutrition?.let {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "${it.calories} kcal - P ${it.proteinG} - F ${it.fatG} - " +
                            "C ${it.carbsG} - Fib ${it.fiberG}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    NutrientDetailsText.line(it.details)?.let { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val confirmedGrams = grams
                    val confirmedNutrition = nutrition
                    if (confirmedGrams != null && confirmedNutrition != null) {
                        // Same formatAmount used for the amount/macro line on
                        // TodayScreen, so the name suffix and that line always
                        // agree — see gram-based-serving-android Task "final
                        // fix wave".
                        val amountLabel = formatAmount(confirmedGrams, unit)
                        onConfirm(result.toFoodEntry(date, confirmedGrams, confirmedNutrition, amountLabel))
                    }
                },
                enabled = valid
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SearchResultRow(result: FoodSearchResult, onClick: () -> Unit) {
    val values = result.perServing ?: result.per100g
    val basis = when {
        result.perServing != null && result.servingSize.isNotEmpty() -> result.servingSize
        result.perServing != null -> "per serving"
        else -> "per 100 g"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Text(text = result.displayName, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "${values.calories} kcal, $basis - " +
                "P ${values.proteinG} - F ${values.fatG} - C ${values.carbsG}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
