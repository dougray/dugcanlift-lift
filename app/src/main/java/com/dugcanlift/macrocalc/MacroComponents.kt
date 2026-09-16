package com.dugcanlift.macrocalc

import androidx.compose.foundation.horizontalScroll
import com.dugcanlift.macrocalc.ui.theme.dclCardBorder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.dugcanlift.macrocalc.data.NutrientDetailsText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter { it.isDigit() || it == '.' }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) }
            )
        }
    }
}

@Composable
fun ResultCard(result: MacroResult) {
    Card(modifier = Modifier.fillMaxWidth(), border = dclCardBorder()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "${result.calories} kcal / day",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(12.dp))
            MacroRow("Protein", result.proteinG)
            MacroRow("Fat", result.fatG)
            MacroRow("Carbs", result.carbsG)
            MacroRow("Fiber", result.fiberG)
        }
    }
}

@Composable
fun MacroRow(name: String, grams: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = name, style = MaterialTheme.typography.bodyLarge)
        Text(text = "$grams g", style = MaterialTheme.typography.bodyLarge)
    }
}@Composable
fun NameField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}
/**
 * A day's saturated fat, sugar and sodium as plain rows: label, total, and how
 * many of the day's foods the total covers when that is not all of them. No
 * bars -- there is no goal to fill one against. Draws nothing when no food
 * recorded any of the three.
 */
@Composable
fun NutrientDetailRows(rows: List<NutrientDetailsText.Row>) {
    rows.forEach { row ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = row.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = row.value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The saturated fat, sugar and sodium fields, behind a "More nutrients" toggle
 * so the food form does not grow for everyone who only counts macros. [label]
 * builds each field's label from its name and unit, so the form can say what
 * basis the number is on the same way its macro fields do.
 */
@Composable
fun MoreNutrientsFields(
    expanded: Boolean,
    onToggle: () -> Unit,
    label: (name: String, unit: String) -> String,
    saturatedFat: String,
    onSaturatedFat: (String) -> Unit,
    sugar: String,
    onSugar: (String) -> Unit,
    sodium: String,
    onSodium: (String) -> Unit
) {
    Spacer(modifier = Modifier.height(4.dp))
    TextButton(onClick = onToggle) {
        Text(if (expanded) "Fewer nutrients" else "More nutrients")
    }
    if (expanded) {
        Text(
            text = "Saturated fat, sugar and sodium. Optional \u2014 leave blank if the label doesn't say.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        NumberField(value = saturatedFat, onValueChange = onSaturatedFat, label = label("Saturated fat", "g"))
        Spacer(modifier = Modifier.height(12.dp))
        NumberField(value = sugar, onValueChange = onSugar, label = label("Sugar", "g"))
        Spacer(modifier = Modifier.height(12.dp))
        NumberField(value = sodium, onValueChange = onSodium, label = label("Sodium", "mg"))
    }
}
