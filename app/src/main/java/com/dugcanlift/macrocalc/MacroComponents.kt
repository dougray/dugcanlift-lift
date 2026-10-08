package com.dugcanlift.macrocalc

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.dugcanlift.macrocalc.ui.theme.dclAccentText
import com.dugcanlift.macrocalc.ui.theme.dclCardBorder
import com.dugcanlift.macrocalc.ui.theme.dclTextButtonColors
import com.dugcanlift.macrocalc.ui.theme.dclTextFieldColors
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
import com.dugcanlift.macrocalc.data.normalizeDecimalInput
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
        onValueChange = { input -> onValueChange(normalizeDecimalInput(input)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
        colors = dclTextFieldColors()
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
    label: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        colors = dclTextFieldColors()
    )
}
/**
 * One row of "eaten against a goal": a label, `eaten / goal unit`, and a thin bar.
 * Shared by Home's and Food's totals, which used to carry a copy each. The bar is
 * drawn by hand to keep its 4 dp look, so it states its own semantics: one
 * merged node TalkBack reads as the label, the numbers and a progress value.
 */
@Composable
fun GoalProgressRow(name: String, eaten: Int, goal: Int, unit: String = "g") {
    val fraction = if (goal <= 0) 0f else (eaten.toFloat() / goal).coerceIn(0f, 1f)
    val over = goal > 0 && eaten > goal
    val barColor =
        if (over) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary

    Column(
        modifier = Modifier
            .padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                if (over) stateDescription = "Over goal"
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "$eaten / $goal $unit",
                style = MaterialTheme.typography.bodyLarge,
                // The bar keeps the brand rust; the number is text, so it takes
                // the accent that reads at 4.5:1 on a dark card.
                color = if (over) dclAccentText() else MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor)
            )
        }
    }
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
    TextButton(onClick = onToggle, colors = dclTextButtonColors()) {
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
