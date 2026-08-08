package com.dugcanlift.macrocalc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dugcanlift.macrocalc.data.FoodSearch
import com.dugcanlift.macrocalc.data.FoodSearchResult
import kotlinx.coroutines.launch

/**
 * Search panel backed by Open Food Facts. Picking a result hands a prefilled
 * entry back to the caller, which opens the normal add form so the person can
 * set servings before anything is logged.
 */
@Composable
fun FoodSearchPanel(
    onPick: (FoodSearchResult) -> Unit,
    onCancel: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<FoodSearchResult>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var hasSearched by remember { mutableStateOf(false) }

    fun runSearch() {
        if (query.isBlank() || searching) return
        searching = true
        message = null
        scope.launch {
            when (val outcome = FoodSearch.searchByName(query)) {
                is FoodSearch.Outcome.Success -> {
                    results = outcome.results
                    if (outcome.results.isEmpty()) message = "Nothing found for that."
                }
                is FoodSearch.Outcome.Failure -> {
                    results = emptyList()
                    message = outcome.message
                }
            }
            searching = false
            hasSearched = true
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Search food", style = MaterialTheme.typography.titleMedium)

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Looks up Open Food Facts. Only your search term is sent.",
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
                    SearchResultRow(result = result, onClick = { onPick(result) })
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
