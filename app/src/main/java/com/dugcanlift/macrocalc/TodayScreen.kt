package com.dugcanlift.macrocalc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.dugcanlift.macrocalc.ui.theme.dclCardBorder
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.dugcanlift.macrocalc.ui.adaptive.AdaptiveLayout
import com.dugcanlift.macrocalc.ui.adaptive.rememberMovablePart
import com.dugcanlift.macrocalc.ui.adaptive.MeasuredPane
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dugcanlift.macrocalc.data.DayTotals
import com.dugcanlift.macrocalc.data.FoodEntry
import com.dugcanlift.macrocalc.data.FoodEntryEdit
import com.dugcanlift.macrocalc.data.FoodRepository
import com.dugcanlift.macrocalc.data.Meal
import com.dugcanlift.macrocalc.data.NutrientDetailsText
import com.dugcanlift.kit.NutrientDetails
import com.dugcanlift.macrocalc.data.mealForHour
import com.dugcanlift.macrocalc.data.forDate
import com.dugcanlift.macrocalc.data.ServingUnit
import com.dugcanlift.macrocalc.data.SettingsStore
import com.dugcanlift.macrocalc.data.todayKey
import com.dugcanlift.macrocalc.data.totals
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import com.dugcanlift.macrocalc.data.scaleFrom100g
import com.dugcanlift.macrocalc.data.Per100g

@Composable
fun TodayScreen(
    goal: MacroResult?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repo = remember { FoodRepository.get(context) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { repo.load() }

    val allEntries by repo.entries.collectAsState()

    var selectedDate by rememberSaveable { mutableStateOf(todayKey()) }
    val entries = allEntries.forDate(selectedDate)
    val eaten = entries.totals()
    val detailRows = NutrientDetailsText.dayRows(entries)

    // Most people eat the same handful of things. Anything logged before can be
    // re-logged in one tap, which removes most of the manual entry pain.
    val recent = remember(allEntries) {
        allEntries
            .sortedByDescending { it.loggedAt }
            .distinctBy { it.name.trim().lowercase(Locale.US) }
            .take(10)
    }

    // Saveable, and the entry being edited by id, so an open form outlives the activity being
    // recreated (a density or theme change; rotation and resizing do not recreate it).
    var panel by rememberSaveable { mutableStateOf(Panel.NONE) }
    var prefill by remember { mutableStateOf<FoodEntry?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = editingId?.let { id -> allEntries.firstOrNull { it.id == id } }

    // Each part once, placed by width: one column exactly as on the phone, or the day's totals and
    // the add/edit forms beside the meal list once two phone-width panes fit (ui/adaptive).
    val summary: @Composable () -> Unit = rememberMovablePart {
        if (goal == null) {
            Text(
                text = "Set a goal on the Calculator tab and it'll show up here.",
                style = MaterialTheme.typography.bodyMedium
            )
            // Tracked without a goal, so they have no reason to wait for one.
            if (detailRows.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth(), border = dclCardBorder()) {
                    Column(modifier = Modifier.padding(16.dp)) { NutrientDetailRows(detailRows) }
                }
            }
        } else {
            SummaryCard(goal = goal, eaten = eaten, detailRows = detailRows)
    }
    }

    val panelArea: @Composable () -> Unit = rememberMovablePart {
        val editingEntry = editing
        if (panel == Panel.EDIT && editingEntry != null) {
            EditFoodForm(
                entry = editingEntry,
                onSave = { updated ->
                    scope.launch { repo.update(updated) }
                    panel = Panel.NONE
                    editingId = null
                },
                onCancel = {
                    panel = Panel.NONE
                    editingId = null
                }
            )
        } else if (panel == Panel.FORM) {
            AddFoodForm(
                onAdd = { entry ->
                    scope.launch { repo.add(entry) }
                    panel = Panel.NONE
                    prefill = null
                },
                onCancel = {
                    panel = Panel.NONE
                    prefill = null
                },
                date = selectedDate,
                initial = prefill
            )
        } else if (panel == Panel.SEARCH) {
            // Gram-based entries are already fully determined (amount and
            // macros both) by the time the amount-entry dialog confirms, so
            // this skips AddFoodForm entirely rather than routing through it
            // pre-filled — there's nothing left for that form to add.
            FoodSearchPanel(
                date = selectedDate,
                onConfirm = { entry ->
                    scope.launch { repo.add(entry) }
                    panel = Panel.NONE
                },
                onCancel = { panel = Panel.NONE }
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        prefill = null
                        panel = Panel.FORM
                    },
                    modifier = Modifier.fillMaxWidth(0.5f)
                ) {
                    Text("Add food")
                }
                OutlinedButton(
                    onClick = { panel = Panel.SEARCH },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Search")
                }
            }

            if (recent.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))

                Text(text = "Recent", style = MaterialTheme.typography.labelLarge)

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    recent.forEach { item ->
                        AssistChip(
                            onClick = {
                                scope.launch {
                                    repo.add(
                                        item.copy(
                                            id = java.util.UUID.randomUUID().toString(),
                                            date = selectedDate,
                                            loggedAt = System.currentTimeMillis()
                                        )
                                    )
                                }
                            },
                            label = { Text(item.name) }
                        )
                    }
                }
            }
    }
    }

    val mealList: @Composable () -> Unit = rememberMovablePart {
        if (entries.isEmpty()) {
            Text(
                text = "Nothing logged on this day.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            // Grouped by meal, in the order you'd eat them rather than the
            // order they happened to be entered.
            Meal.entries.forEach { meal ->
                val forMeal = entries.filter { it.mealOrDefault == meal }
                if (forMeal.isEmpty()) return@forEach

                val mealCalories = forMeal.sumOf { it.totalCalories }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = meal.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "$mealCalories kcal",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))

                forMeal.sortedBy { it.loggedAt }.forEach { entry ->
                    EntryRow(
                        entry = entry,
                        onEdit = {
                            editingId = entry.id
                            panel = Panel.EDIT
                        },
                        onDelete = {
                            if (editingId == entry.id) {
                                editingId = null
                                panel = Panel.NONE
                            }
                            scope.launch { repo.delete(entry.id) }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
    }
    }

    MeasuredPane(modifier = modifier.fillMaxSize()) { paneWidth ->
        val twoPane = AdaptiveLayout.foodIsTwoPane(AdaptiveLayout.contentWidth(paneWidth))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AdaptiveLayout.sideGutter(paneWidth).dp, vertical = 16.dp)
        ) {
            DateNavigator(
                date = selectedDate,
                onPrevious = { selectedDate = shiftDate(selectedDate, -1) },
                onNext = { selectedDate = shiftDate(selectedDate, 1) }
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (!twoPane) {
                summary()
                Spacer(modifier = Modifier.height(20.dp))
                panelArea()
                Spacer(modifier = Modifier.height(24.dp))
                mealList()
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(AdaptiveLayout.PANE_GAP_DP.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        summary()
                        Spacer(modifier = Modifier.height(20.dp))
                        panelArea()
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        mealList()
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DateNavigator(
    date: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val isToday = date == todayKey()

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TextButton(onClick = onPrevious) { Text("Previous") }

        Text(
            text = dateLabel(date),
            style = MaterialTheme.typography.headlineSmall
        )

        TextButton(onClick = onNext, enabled = !isToday) { Text("Next") }
    }
}

@Composable
private fun SummaryCard(goal: MacroResult, eaten: DayTotals, detailRows: List<NutrientDetailsText.Row>) {
    Card(modifier = Modifier.fillMaxWidth(), border = dclCardBorder()) {
        Column(modifier = Modifier.padding(16.dp)) {
            val remaining = goal.calories - eaten.calories
            Text(
                text = if (remaining >= 0) "$remaining kcal left" else "${-remaining} kcal over",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "${eaten.calories} of ${goal.calories}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            MacroProgress("Protein", eaten.proteinG, goal.proteinG)
            MacroProgress("Fat", eaten.fatG, goal.fatG)
            MacroProgress("Carbs", eaten.carbsG, goal.carbsG)
            MacroProgress("Fiber", eaten.fiberG, goal.fiberG)

            if (detailRows.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                NutrientDetailRows(detailRows)
            }
        }
    }
}

@Composable
private fun MacroProgress(name: String, eaten: Int, goal: Int) {
    val fraction = if (goal <= 0) 0f else (eaten.toFloat() / goal).coerceIn(0f, 1f)
    val over = goal > 0 && eaten > goal
    val barColor =
        if (over) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary

    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "$eaten / $goal g",
                style = MaterialTheme.typography.bodyLarge,
                color = if (over) barColor else MaterialTheme.colorScheme.onSurface
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

@Composable
private fun EntryRow(entry: FoodEntry, onEdit: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsStore.get(context) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // weight(1f) lets the text take the space that's left instead of a
        // fixed fraction, which was squeezing the button into one letter
        // per line.
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClickLabel = "Edit ${entry.name}", onClick = onEdit)
        ) {
            val amountGrams = entry.amountGrams
            Text(
                text = if (amountGrams != null || entry.servings == 1.0) entry.name
                else "${entry.name} x${formatServings(entry.servings)}",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = if (amountGrams != null) {
                    "${formatAmount(amountGrams, settings.servingUnit)} - " +
                        "${entry.totalCalories} kcal - " +
                        "P ${entry.totalProteinG} - F ${entry.totalFatG} - " +
                        "C ${entry.totalCarbsG} - Fib ${entry.totalFiberG}"
                } else {
                    "${entry.totalCalories} kcal - " +
                        "P ${entry.totalProteinG} - F ${entry.totalFatG} - " +
                        "C ${entry.totalCarbsG} - Fib ${entry.totalFiberG}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            NutrientDetailsText.entryLine(entry)?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TextButton(
            onClick = onDelete,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(text = "x", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun AddFoodForm(
    onAdd: (FoodEntry) -> Unit,
    onCancel: () -> Unit,
    date: String,
    initial: FoodEntry? = null
) {
    // Keyed on the prefill so picking a different search result refills the
    // fields rather than keeping the previous one's numbers.
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore.get(context) }
    var unit by rememberSaveable { mutableStateOf(settingsStore.servingUnit) }

    var name by rememberSaveable(initial) { mutableStateOf(initial?.name ?: "") }

    // Food is logged by weight. Servings is gone as something you type; the
    // amount is a real weight in the person's own unit, and grams are what
    // gets stored. Entries logged before this keep their old multiplier.
    var amount by rememberSaveable(initial, unit) {
        mutableStateOf(trimAmount(unit.fromGrams(100.0)))
    }
    var calories by rememberSaveable(initial) { mutableStateOf(initial?.calories?.toString() ?: "") }
    var protein by rememberSaveable(initial) { mutableStateOf(initial?.proteinG?.toString() ?: "") }
    var fat by rememberSaveable(initial) { mutableStateOf(initial?.fatG?.toString() ?: "") }
    var carbs by rememberSaveable(initial) { mutableStateOf(initial?.carbsG?.toString() ?: "") }
    var fiber by rememberSaveable(initial) { mutableStateOf(initial?.fiberG?.toString() ?: "") }
    // Per 100 g, like the macros above. Blank is not recorded, never zero.
    var saturatedFat by rememberSaveable(initial) { mutableStateOf("") }
    var sugar by rememberSaveable(initial) { mutableStateOf("") }
    var sodium by rememberSaveable(initial) { mutableStateOf("") }
    var moreNutrients by rememberSaveable(initial) { mutableStateOf(false) }
    val per100Details = NutrientDetails(
        NutrientDetailsText.parse(saturatedFat), NutrientDetailsText.parse(sugar), NutrientDetailsText.parse(sodium)
    )

    // Defaults to whatever meal it currently is, so most of the time nobody
    // has to touch this.
    var meal by rememberSaveable(initial) {
        mutableStateOf(
            mealForHour(
                java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            )
        )
    }

    val amountGrams = amount.toDoubleOrNull()?.let { unit.toGrams(it) }
    val valid = name.isNotBlank() && calories.toIntOrNull() != null &&
        amountGrams != null && amountGrams > 0.0

    Card(modifier = Modifier.fillMaxWidth(), border = dclCardBorder()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Add food", style = MaterialTheme.typography.titleMedium)

            Spacer(modifier = Modifier.height(12.dp))

            NameField(value = name, onValueChange = { name = it }, label = "Name")

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Meal.entries.forEach { option ->
                    FilterChip(
                        selected = option == meal,
                        onClick = { meal = option },
                        label = { Text(option.label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Log by",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ServingUnit.entries.forEach { option ->
                    FilterChip(
                        selected = option == unit,
                        onClick = {
                            // Keep the amount meaning the same weight, so
                            // switching to ounces mid-entry does not silently
                            // re-scale what is about to be logged.
                            val grams = amount.toDoubleOrNull()?.let { unit.toGrams(it) }
                            unit = option
                            settingsStore.servingUnit = option
                            if (grams != null) amount = trimAmount(option.fromGrams(grams))
                        },
                        label = { Text(option.label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Macros as they read per 100 g, then the amount you actually ate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            NumberField(value = amount, onValueChange = { amount = it },
                        label = "Amount (${unit.abbreviation})")

            Spacer(modifier = Modifier.height(12.dp))

            NumberField(value = calories, onValueChange = { calories = it },
                        label = "Calories (per 100 g)")

            Spacer(modifier = Modifier.height(12.dp))

            NumberField(value = protein, onValueChange = { protein = it }, label = "Protein (g/100g)")

            Spacer(modifier = Modifier.height(12.dp))

            NumberField(value = fat, onValueChange = { fat = it }, label = "Fat (g/100g)")

            Spacer(modifier = Modifier.height(12.dp))

            NumberField(value = carbs, onValueChange = { carbs = it }, label = "Carbs (g/100g)")

            Spacer(modifier = Modifier.height(12.dp))

            NumberField(value = fiber, onValueChange = { fiber = it }, label = "Fiber (g/100g)")

            MoreNutrientsFields(
                expanded = moreNutrients,
                onToggle = { moreNutrients = !moreNutrients },
                label = { name, unit -> "$name ($unit/100g)" },
                saturatedFat = saturatedFat, onSaturatedFat = { saturatedFat = it },
                sugar = sugar, onSugar = { sugar = it },
                sodium = sodium, onSodium = { sodium = it }
            )

            // What it will actually count as, before it is committed. The
            // iPhone's food search and both watches have always shown this
            // while you set the amount; this form made you save first and
            // find out afterwards. It also makes a typo in the per-100 g
            // numbers obvious at entry rather than in the day's total.
            val preview = amountGrams?.let { g ->
                calories.toIntOrNull()?.let { kcal ->
                    scaleFrom100g(
                        Per100g(
                            calories = kcal,
                            proteinG = protein.toIntOrNull() ?: 0,
                            fatG = fat.toIntOrNull() ?: 0,
                            carbsG = carbs.toIntOrNull() ?: 0,
                            fiberG = fiber.toIntOrNull() ?: 0,
                            details = per100Details,
                        ),
                        g
                    )
                }
            }
            if (preview != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "${formatAmount(amountGrams, unit)} = ${preview.calories} kcal - " +
                        "P ${preview.proteinG} - F ${preview.fatG} - " +
                        "C ${preview.carbsG} - Fib ${preview.fiberG}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                NutrientDetailsText.line(preview.details)?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        // amountGrams is authoritative, servings stays 1,
                        // and the macros are already the totals for this
                        // amount -- FoodEntry's own contract, and the one the
                        // browser and iPhone builds write too.
                        val scaled = scaleFrom100g(
                            Per100g(
                                calories = calories.toIntOrNull() ?: 0,
                                proteinG = protein.toIntOrNull() ?: 0,
                                fatG = fat.toIntOrNull() ?: 0,
                                carbsG = carbs.toIntOrNull() ?: 0,
                                fiberG = fiber.toIntOrNull() ?: 0,
                                details = per100Details,
                            ),
                            amountGrams ?: return@Button
                        ) ?: return@Button

                        onAdd(
                            FoodEntry(
                                name = name.trim(),
                                servings = 1.0,
                                amountGrams = amountGrams,
                                calories = scaled.calories,
                                proteinG = scaled.proteinG,
                                fatG = scaled.fatG,
                                carbsG = scaled.carbsG,
                                fiberG = scaled.fiberG,
                                date = date,
                                meal = meal.name,
                                saturatedFatG = scaled.details.saturatedFatG,
                                sugarG = scaled.details.sugarG,
                                sodiumMg = scaled.details.sodiumMg
                            )
                        )
                    },
                    enabled = valid
                ) {
                    Text("Save")
                }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

/**
 * Changes an entry already in the log. Shaped like the entry was logged -- by
 * weight with the totals for that weight, or by servings with per-serving
 * macros -- because those numbers mean different things; the rules live in
 * [FoodEntryEdit], where they are tested.
 */
@Composable
private fun EditFoodForm(
    entry: FoodEntry,
    onSave: (FoodEntry) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore.get(context) }
    var form by remember(entry) { mutableStateOf(FoodEntryEdit.from(entry, settingsStore.servingUnit)) }
    val saved = form.applyTo(entry)
    val per = if (form.byWeight) "for this amount" else "per serving"
    // Open already when the entry recorded any, so they are never hidden from
    // someone who is here to change them.
    var moreNutrients by remember(entry) { mutableStateOf(!entry.details.isEmpty) }

    Card(modifier = Modifier.fillMaxWidth(), border = dclCardBorder()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Edit food", style = MaterialTheme.typography.titleMedium)

            Spacer(modifier = Modifier.height(12.dp))

            NameField(value = form.name, onValueChange = { form = form.withName(it) }, label = "Name")

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Meal.entries.forEach { option ->
                    FilterChip(
                        selected = option == form.meal,
                        onClick = { form = form.withMeal(option) },
                        label = { Text(option.label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (form.byWeight) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ServingUnit.entries.forEach { option ->
                        FilterChip(
                            selected = option == form.unit,
                            onClick = {
                                form = form.withUnit(option)
                                settingsStore.servingUnit = option
                            },
                            label = { Text(option.label) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Macros are the totals for the amount. Change the amount and they follow it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                NumberField(value = form.amount, onValueChange = { form = form.withAmount(it) },
                            label = "Amount (${form.unit.abbreviation})")
            } else {
                Text(
                    text = "Logged by servings. Macros are per serving.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                NumberField(value = form.amount, onValueChange = { form = form.withAmount(it) },
                            label = "Servings")
            }

            listOf(
                Triple(FoodEntryEdit.Field.CALORIES, form.macros.calories, "Calories (kcal, $per)"),
                Triple(FoodEntryEdit.Field.PROTEIN, form.macros.proteinG, "Protein (g, $per)"),
                Triple(FoodEntryEdit.Field.FAT, form.macros.fatG, "Fat (g, $per)"),
                Triple(FoodEntryEdit.Field.CARBS, form.macros.carbsG, "Carbs (g, $per)"),
                Triple(FoodEntryEdit.Field.FIBER, form.macros.fiberG, "Fiber (g, $per)"),
            ).forEach { (field, value, label) ->
                Spacer(modifier = Modifier.height(12.dp))
                NumberField(value = value, onValueChange = { form = form.withMacro(field, it) }, label = label)
            }

            MoreNutrientsFields(
                expanded = moreNutrients,
                onToggle = { moreNutrients = !moreNutrients },
                label = { name, unit -> "$name ($unit, $per)" },
                saturatedFat = form.macros.saturatedFatG,
                onSaturatedFat = { form = form.withMacro(FoodEntryEdit.Field.SATURATED_FAT, it) },
                sugar = form.macros.sugarG,
                onSugar = { form = form.withMacro(FoodEntryEdit.Field.SUGAR, it) },
                sodium = form.macros.sodiumMg,
                onSodium = { form = form.withMacro(FoodEntryEdit.Field.SODIUM, it) }
            )

            if (saved != null) {
                Spacer(modifier = Modifier.height(12.dp))
                val amountText = saved.amountGrams?.let { formatAmount(it, form.unit) }
                    ?: "x${formatServings(saved.servings)}"
                Text(
                    text = "$amountText = ${saved.totalCalories} kcal - " +
                        "P ${saved.totalProteinG} - F ${saved.totalFatG} - " +
                        "C ${saved.totalCarbsG} - Fib ${saved.totalFiberG}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                NutrientDetailsText.entryLine(saved)?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { saved?.let(onSave) }, enabled = saved != null) {
                    Text("Save")
                }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

private enum class Panel { NONE, FORM, SEARCH, EDIT }

/* ---------- date helpers ---------- */

private fun formatter() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

private fun shiftDate(key: String, days: Int): String {
    val fmt = formatter()
    val calendar = Calendar.getInstance()
    calendar.time = try {
        fmt.parse(key) ?: Date()
    } catch (e: Exception) {
        Date()
    }
    calendar.add(Calendar.DAY_OF_YEAR, days)
    return fmt.format(calendar.time)
}

private fun dateLabel(key: String): String {
    val today = todayKey()
    return when (key) {
        today -> "Today"
        shiftDate(today, -1) -> "Yesterday"
        else -> try {
            val parsed = formatter().parse(key)
            if (parsed != null) SimpleDateFormat("EEE, MMM d", Locale.US).format(parsed) else key
        } catch (e: Exception) {
            key
        }
    }
}

private fun formatServings(value: Double): String =
    if (value == value.roundToInt().toDouble()) value.roundToInt().toString()
    else value.toString()

/**
 * Formats a gram amount in the given [unit] for display, e.g. "140 g" or
 * "4.9 oz" — converted from the canonical gram value stored on the entry,
 * not whatever unit was active when it was logged. Mirrors LIFT iOS's
 * `FoodEntryDisplay.amountText(for:preferredUnit:)`.
 */
/** An amount for a text field: one decimal at most, never a trailing ".0". */
internal fun trimAmount(value: Double): String {
    val rounded = (value * 10.0).roundToInt() / 10.0
    return if (rounded == rounded.roundToInt().toDouble()) rounded.roundToInt().toString()
    else rounded.toString()
}

internal fun formatAmount(amountGrams: Double, unit: ServingUnit): String {
    val converted = unit.fromGrams(amountGrams)
    val rounded = (converted * 10.0).roundToInt() / 10.0
    val text = if (rounded == rounded.roundToInt().toDouble()) rounded.roundToInt().toString()
        else rounded.toString()
    return "$text ${unit.abbreviation}"
}
