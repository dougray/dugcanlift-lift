package com.dugcanlift.macrocalc

import androidx.compose.foundation.layout.Arrangement
import com.dugcanlift.macrocalc.ui.theme.dclCardBorder
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import com.dugcanlift.macrocalc.data.ShoppingListLine
import com.dugcanlift.macrocalc.ui.adaptive.AdaptiveLayout
import com.dugcanlift.macrocalc.ui.adaptive.GridRow
import com.dugcanlift.macrocalc.ui.adaptive.MeasuredPane
import com.dugcanlift.macrocalc.ui.adaptive.columnMajor
import com.dugcanlift.macrocalc.ui.adaptive.rowMajor
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.dugcanlift.kit.CaptionRecipe
import com.dugcanlift.kit.IngredientParser
import com.dugcanlift.kit.RecipeIngredient
import com.dugcanlift.kit.Split
import com.dugcanlift.kit.trimZeros
import com.dugcanlift.macrocalc.data.CookSampleData
import com.dugcanlift.macrocalc.data.Meal
import com.dugcanlift.macrocalc.data.NutrientDetailsText
import com.dugcanlift.macrocalc.data.recipeNutritionFromText
import com.dugcanlift.macrocalc.data.PlannedMeal
import com.dugcanlift.macrocalc.data.Recipe
import com.dugcanlift.macrocalc.data.RecipeRepository
import com.dugcanlift.macrocalc.data.ShoppingList
import com.dugcanlift.macrocalc.data.FoodRepository
import com.dugcanlift.macrocalc.data.SettingsStore
import com.dugcanlift.macrocalc.data.planBetween
import com.dugcanlift.macrocalc.data.shoppingAmountLabel
import com.dugcanlift.macrocalc.data.todayKey
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * COOK — recipes, the week's plan, and the shopping list that falls out of it.
 *
 * The client half. The trainer half is the COOK page in Coach, which authors
 * the same [PlannedMeal] shapes and sends them here as a link.
 *
 * Three sections rather than three tabs: they are one workflow — pick recipes,
 * place them on days, shop for what that adds up to — and splitting them across
 * the top bar would suggest they are separate places.
 *
 * Mirrors `CookView` in the iOS build.
 */

private enum class CookSection(val label: String) {
    RECIPES("Recipes"),
    PLAN("Plan"),
    SHOPPING("Shopping")
}

@Composable
fun CookScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = remember { RecipeRepository.get(context) }

    LaunchedEffect(Unit) { repo.load() }

    var section by rememberSaveable { mutableStateOf(CookSection.RECIPES) }

    // Each section decides its own columns from the width it is actually given (ui/adaptive).
    MeasuredPane(modifier = modifier.fillMaxSize()) { paneWidth ->
        val contentWidth = AdaptiveLayout.contentWidth(paneWidth)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AdaptiveLayout.sideGutter(paneWidth).dp, vertical = 16.dp)
        ) {
            ChipRow(
                options = CookSection.entries,
                selected = section,
                label = { it.label },
                onSelect = { section = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

            when (section) {
                CookSection.RECIPES -> RecipesSection(repo, contentWidth)
                CookSection.PLAN -> PlanSection(repo, contentWidth)
                CookSection.SHOPPING -> ShoppingSection(repo, contentWidth)
            }
        }
    }
}

/* ---------- recipes ---------- */

@Composable
private fun RecipesSection(repo: RecipeRepository, contentWidth: Float) {
    val scope = rememberCoroutineScope()
    val recipes by repo.recipes.collectAsState()
    // By id and saveable, so an open editor outlives the activity being recreated.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = editingId?.let { id -> recipes.firstOrNull { it.id == id } }
    var creating by rememberSaveable { mutableStateOf(false) }
    val columns = AdaptiveLayout.cardColumns(contentWidth)

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Button(
            onClick = { creating = true },
            modifier = if (columns == 1) Modifier.fillMaxWidth()
            else Modifier.widthIn(max = AdaptiveLayout.MAX_WIDE_BUTTON_DP.dp).fillMaxWidth()
        ) {
            Text("New recipe")
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (recipes.isEmpty()) {
            Text(
                text = "No recipes yet. Add one you already cook — the plan and " +
                    "the shopping list build themselves from here.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            val sorted = recipes.sortedBy { it.name.lowercase() }
            if (columns == 1) {
                sorted.forEach { recipe ->
                    RecipeCard(
                        recipe = recipe,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        onClick = { editingId = recipe.id }
                    )
                }
            } else {
                rowMajor(sorted, columns).forEach { row ->
                    GridRow(cells = row, columns = columns) { recipe ->
                        RecipeCard(recipe = recipe, modifier = Modifier.fillMaxSize(), onClick = { editingId = recipe.id })
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }

        // Below the list on purpose: it must not appear in the top of a
        // screenshot. Debuggable builds only, so it is never in a release —
        // checked from the manifest flag rather than BuildConfig, which this
        // module does not generate.
        if (isDebuggable(LocalContext.current)) {
            Spacer(modifier = Modifier.height(24.dp))
            TextButton(onClick = { scope.launch { CookSampleData.load(repo) } }) {
                Text("Load sample recipes", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (creating) {
        RecipeEditorDialog(repo = repo, existing = null) { creating = false }
    }
    editing?.let { recipe ->
        RecipeEditorDialog(repo = repo, existing = recipe) { editingId = null }
    }
}

@Composable
private fun RecipeCard(recipe: Recipe, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier = modifier, onClick = onClick, border = dclCardBorder()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = recipe.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = servingsLabel(recipe.servings),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            val nutrition = recipe.nutritionPerServing
            Text(
                // Deliberately not "0 kcal". An unknown that renders
                // as zero becomes a zero-calorie dinner in a day total.
                text = if (nutrition == null) "Macros not set" else
                    "${nutrition.calories.trimZeros()} kcal  " +
                        "P ${nutrition.proteinG.trimZeros()}  " +
                        "C ${nutrition.carbsG.trimZeros()}  " +
                        "F ${nutrition.fatG.trimZeros()}",
                style = MaterialTheme.typography.bodySmall
            )
            NutrientDetailsText.line(nutrition?.details)?.let { line ->
                Text(text = "$line per serving", style = MaterialTheme.typography.bodySmall)
            }

            if (recipe.ingredients.isNotEmpty()) {
                Text(
                    text = "${recipe.ingredients.size} ingredient" +
                        if (recipe.ingredients.size == 1) "" else "s",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * Ingredients are typed as free text — "2 tbsp olive oil" — and parsed on save.
 * The raw line is always kept: it is what the person checks the parse against,
 * and what the shopping list falls back to when the parse fails.
 */
@Composable
private fun RecipeEditorDialog(
    repo: RecipeRepository,
    existing: Recipe?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Read once per dialog open, same convention as Task 3/4's amount
    // entry — the real Serving Size preference lives in Settings.
    val servingUnit = remember { SettingsStore.get(context).servingUnit }

    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var servings by rememberSaveable { mutableStateOf((existing?.servings ?: 1.0).trimZeros()) }
    var totalWeightText by rememberSaveable {
        mutableStateOf(
            existing?.totalWeightGrams
                ?.let { servingUnit.fromGrams(it).trimZeros() }
                ?: ""
        )
    }
    var ingredientText by rememberSaveable {
        mutableStateOf(existing?.ingredients.orEmpty().joinToString("\n") { it.rawText })
    }
    var stepText by rememberSaveable { mutableStateOf(existing?.steps.orEmpty().joinToString("\n")) }
    // Paste-a-recipe, offered only on a new recipe: pasting over a recipe that
    // already exists would replace work rather than start from it.
    var pasting by rememberSaveable { mutableStateOf(false) }
    var pasteText by rememberSaveable { mutableStateOf("") }
    var splitAdvice by rememberSaveable { mutableStateOf<String?>(null) }

    var calories by rememberSaveable { mutableStateOf(existing?.nutritionPerServing?.calories?.trimZeros() ?: "") }
    var protein by rememberSaveable { mutableStateOf(existing?.nutritionPerServing?.proteinG?.trimZeros() ?: "") }
    var carbs by rememberSaveable { mutableStateOf(existing?.nutritionPerServing?.carbsG?.trimZeros() ?: "") }
    var fat by rememberSaveable { mutableStateOf(existing?.nutritionPerServing?.fatG?.trimZeros() ?: "") }
    var fiber by rememberSaveable { mutableStateOf(existing?.nutritionPerServing?.fiberG?.trimZeros() ?: "") }
    var saturatedFat by rememberSaveable { mutableStateOf(NutrientDetailsText.field(existing?.nutritionPerServing?.saturatedFatG)) }
    var sugar by rememberSaveable { mutableStateOf(NutrientDetailsText.field(existing?.nutritionPerServing?.sugarG)) }
    var sodium by rememberSaveable { mutableStateOf(NutrientDetailsText.field(existing?.nutritionPerServing?.sodiumMg)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New recipe" else "Edit recipe") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Paste a recipe written out as text -- a video caption, an
                // email, a card off the fridge. `CaptionRecipe` only PROPOSES
                // a split; it fills the fields below and they are checked
                // before anything is saved. That is what makes it safe: a
                // wrong split costs an edit, never a number, because
                // `IngredientParser` still reads the quantities on save and
                // still refuses to weigh a volume.
                if (existing == null) {
                    if (!pasting) {
                        TextButton(onClick = { pasting = true }) { Text("Paste a recipe") }
                    } else {
                        OutlinedTextField(
                            value = pasteText,
                            onValueChange = { pasteText = it },
                            label = { Text("Paste the recipe's text") },
                            placeholder = { Text("Ingredients:\n- 2 eggs\n\nMethod:\n1. Whisk") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row {
                            TextButton(
                                enabled = pasteText.isNotBlank(),
                                onClick = {
                                    val parsed = CaptionRecipe.parse(pasteText)
                                    if (parsed.isEmpty) {
                                        splitAdvice = "Nothing in that reads as a recipe. " +
                                            "Paste the ingredients and steps as text."
                                    } else {
                                        name = parsed.name.orEmpty()
                                        ingredientText = parsed.ingredientLines.joinToString("\n")
                                        stepText = parsed.steps.joinToString("\n")
                                        // Only ever from an explicit "serves 4".
                                        // A guessed yield silently divides every
                                        // macro by a number nobody chose.
                                        parsed.servings?.let { servings = it.trimZeros() }
                                        splitAdvice = when (parsed.split) {
                                            Split.LABELLED ->
                                                "Split on the headings in the text \u2014 check it read them right."
                                            Split.INFERRED ->
                                                "The text labelled one section and this worked out the rest, " +
                                                    "so check the division."
                                            Split.UNSORTED ->
                                                "The text had no headings, so everything landed in Ingredients " +
                                                    "\u2014 cut any method steps out and paste them into Method."
                                        } + if (parsed.servings == null) {
                                            " It didn't say how many this serves; set it below."
                                        } else ""
                                        pasting = false
                                        pasteText = ""
                                    }
                                }
                            ) { Text("Read it") }
                            TextButton(onClick = { pasting = false; pasteText = "" }) { Text("Cancel") }
                        }
                    }
                    splitAdvice?.let {
                        Text(text = it, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))
                NumberField(value = servings, onValueChange = { servings = it }, label = "Servings")

                Spacer(modifier = Modifier.height(8.dp))
                NumberField(
                    value = totalWeightText,
                    onValueChange = { totalWeightText = it },
                    label = "Total weight (${servingUnit.abbreviation})"
                )
                Text(
                    text = "Weight of the whole finished dish. Leave blank if " +
                        "unknown — gram-based logging needs this plus macros " +
                        "per serving.",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = ingredientText,
                    onValueChange = { ingredientText = it },
                    label = { Text("Ingredients, one per line") },
                    placeholder = { Text("2 tbsp olive oil") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = stepText,
                    onValueChange = { stepText = it },
                    label = { Text("Method, one step per line") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text("Macros per serving", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = "Leave blank if you don't know them. Blank stays " +
                        "unknown — it will not log as zero.",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(modifier = Modifier.height(8.dp))
                NumberField(value = calories, onValueChange = { calories = it }, label = "Calories (kcal)")
                Spacer(modifier = Modifier.height(8.dp))
                NumberField(value = protein, onValueChange = { protein = it }, label = "Protein (g)")
                Spacer(modifier = Modifier.height(8.dp))
                NumberField(value = carbs, onValueChange = { carbs = it }, label = "Carbs (g)")
                Spacer(modifier = Modifier.height(8.dp))
                NumberField(value = fat, onValueChange = { fat = it }, label = "Fat (g)")
                Spacer(modifier = Modifier.height(8.dp))
                NumberField(value = fiber, onValueChange = { fiber = it }, label = "Fibre (g)")
                Spacer(modifier = Modifier.height(8.dp))
                NumberField(value = saturatedFat, onValueChange = { saturatedFat = it }, label = "Saturated fat (g)")
                Spacer(modifier = Modifier.height(8.dp))
                NumberField(value = sugar, onValueChange = { sugar = it }, label = "Sugar (g)")
                Spacer(modifier = Modifier.height(8.dp))
                NumberField(value = sodium, onValueChange = { sodium = it }, label = "Sodium (mg)")
                val macrosBlank = listOf(calories, protein, carbs, fat, fiber).all { it.isBlank() }
                val detailsTyped = listOf(saturatedFat, sugar, sodium).any { it.isNotBlank() }
                if (macrosBlank && detailsTyped) {
                    Text(
                        text = "Saturated fat, sugar and sodium are saved with the macros " +
                            "above \u2014 add at least one macro to keep them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (existing != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch { repo.deleteRecipe(existing.id) }
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete recipe")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val recipe = buildRecipe(
                        existing = existing,
                        name = name.trim(),
                        servings = servings.toDoubleOrNull() ?: 1.0,
                        totalWeightGrams = totalWeightText.trim().toDoubleOrNull()
                            ?.let { servingUnit.toGrams(it) },
                        ingredientText = ingredientText,
                        stepText = stepText,
                        calories = calories, protein = protein, carbs = carbs, fat = fat,
                        fiber = fiber, saturatedFat = saturatedFat, sugar = sugar, sodium = sodium
                    )
                    scope.launch {
                        if (existing == null) repo.addRecipe(recipe) else repo.updateRecipe(recipe)
                    }
                    onDismiss()
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun buildRecipe(
    existing: Recipe?,
    name: String,
    servings: Double,
    totalWeightGrams: Double?,
    ingredientText: String,
    stepText: String,
    calories: String,
    protein: String,
    carbs: String,
    fat: String,
    fiber: String,
    saturatedFat: String,
    sugar: String,
    sodium: String
): Recipe {
    val ingredients: List<RecipeIngredient> = ingredientText
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { IngredientParser.parse(it) }

    val steps = stepText.lines().map { it.trim() }.filter { it.isNotEmpty() }

    // Every field the figure holds is passed back in. Fibre was once absent
    // here, so every save rebuilt the figure without it and silently zeroed
    // whatever the recipe had -- and saturated fat, sugar and sodium would go
    // the same way if they were left out.
    val nutrition = recipeNutritionFromText(
        calories, protein, carbs, fat, fiber, saturatedFat, sugar, sodium,
        // Likewise `estimated`: rebuilding the figure reset the flag, so an
        // imported recipe stopped admitting it was an estimate the first time
        // anyone opened it. Editing a number does not make it a measurement.
        estimated = existing?.nutritionPerServing?.estimated ?: false
    )

    val base = existing ?: Recipe(name = name)
    return base.copy(
        name = name,
        servings = if (servings > 0) servings else 1.0,
        totalWeightGrams = totalWeightGrams,
        ingredients = ingredients,
        steps = steps,
        nutritionPerServing = nutrition
    )
}

/* ---------- plan ---------- */

@Composable
private fun PlanSection(repo: RecipeRepository, contentWidth: Float) {
    val context = LocalContext.current
    val foodRepo = remember { FoodRepository.get(context) }
    val scope = rememberCoroutineScope()

    val recipes by repo.recipes.collectAsState()
    val plan by repo.plan.collectAsState()

    var picking by rememberSaveable { mutableStateOf<Pair<String, Meal>?>(null) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (recipes.isEmpty()) {
            Text(
                text = "Add a recipe first — the plan is built from them.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        val columns = AdaptiveLayout.planDayColumns(contentWidth)
        val dayCard: @Composable (String, Modifier) -> Unit = { day, cardModifier ->
            PlanDayCard(
                day = day,
                // A week's seven columns are too narrow for the meal label beside its recipes.
                stacked = columns > 2,
                plan = plan,
                canAdd = recipes.isNotEmpty(),
                modifier = cardModifier,
                onAdd = { meal -> picking = day to meal },
                onLog = { planned ->
                    scope.launch {
                        val entry = planned.toFoodEntry() ?: return@launch
                        // Write the entry first, then
                        // record it: that id is the only
                        // thing stopping a second tap
                        // logging the same dinner twice.
                        foodRepo.add(entry)
                        repo.markLogged(planned.id, entry.id)
                    }
                },
                onRemove = { planned -> scope.launch { repo.unplan(planned.id) } }
            )
        }
        if (columns == 1) {
            weekDays().forEach { day ->
                dayCard(day, Modifier.fillMaxWidth().padding(bottom = 12.dp))
            }
        } else {
            rowMajor(weekDays(), columns).forEach { row ->
                GridRow(cells = row, columns = columns, gap = if (columns > 2) 8f else AdaptiveLayout.GRID_GAP_DP) { day ->
                    dayCard(day, Modifier.fillMaxSize())
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    picking?.let { (day, meal) ->
        RecipePickerDialog(
            recipes = recipes,
            onDismiss = { picking = null },
            onPick = { recipe, servings, amountGrams ->
                scope.launch { repo.plan(recipe, day, meal, servings, amountGrams) }
                picking = null
            }
        )
    }
}

/**
 * One day of the plan. [stacked] puts each meal's label above its recipes instead of beside them,
 * for the week's seven narrow columns; the phone and two-column layouts keep the label beside.
 */
@Composable
private fun PlanDayCard(
    day: String,
    stacked: Boolean,
    plan: List<PlannedMeal>,
    canAdd: Boolean,
    modifier: Modifier,
    onAdd: (Meal) -> Unit,
    onLog: (PlannedMeal) -> Unit,
    onRemove: (PlannedMeal) -> Unit
) {
    Card(modifier = modifier, border = dclCardBorder()) {
        Column(modifier = Modifier.padding(if (stacked) 12.dp else 16.dp)) {
            Text(dayLabel(day), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Meal.entries.forEach { meal ->
                val forSlot = plan.filter { it.date == day && it.mealOrDefault == meal }

                val slot: @Composable (Modifier) -> Unit = { slotModifier ->
                    if (forSlot.isEmpty()) {
                        TextButton(
                            onClick = { onAdd(meal) },
                            enabled = canAdd
                        ) { Text("Add") }
                    } else {
                        Column(modifier = slotModifier) {
                            forSlot.forEach { planned ->
                                PlannedRow(
                                    planned = planned,
                                    onLog = { onLog(planned) },
                                    onRemove = { onRemove(planned) },
                                    narrow = stacked
                                )
                            }
                        }
                    }
                }

                if (stacked) {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(text = meal.label, style = MaterialTheme.typography.bodySmall)
                        slot(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            text = meal.label,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(84.dp)
                        )
                        slot(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlannedRow(
    planned: PlannedMeal,
    onLog: () -> Unit,
    onRemove: () -> Unit,
    /** In a week's narrow column: the two actions stack rather than breaking "Remove" in two. */
    narrow: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(planned.recipeName, style = MaterialTheme.typography.bodyMedium)

        // Gram-based plans pin servings to 1.0 (see RecipeRepository.plan()),
        // so nutrition.calories * planned.servings would show the whole
        // recipe's calories instead of the portion actually planned —
        // prefer the gram-scaled preview when both fields are present.
        val previewCalories = planned.snapshotNutritionPerGram?.let { perGram ->
            planned.amountGrams?.let { grams -> perGram.calories * grams }
        } ?: planned.snapshotNutrition?.let { it.calories * planned.servings }

        previewCalories?.let { calories ->
            Text(
                text = "${calories.trimZeros()} kcal",
                style = MaterialTheme.typography.bodySmall
            )
        }

        val actions: @Composable () -> Unit = {
            when {
                planned.isLogged ->
                    Text("Logged", style = MaterialTheme.typography.bodySmall)
                planned.snapshotNutrition != null ->
                    TextButton(onClick = onLog) { Text("Log it", maxLines = 1) }
            }
            TextButton(onClick = onRemove) { Text("Remove", maxLines = 1) }
        }
        if (narrow) {
            // One above the other: side by side they need more than a week's column has.
            Column { actions() }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    }
}

@Composable
private fun RecipePickerDialog(
    recipes: List<Recipe>,
    onDismiss: () -> Unit,
    // recipe, servings, amountGrams — amountGrams is non-null only for the
    // gram-based flow (recipe.nutritionPerGram != null), which pins servings
    // at its default and lets amountGrams carry the actual quantity.
    onPick: (Recipe, Double, Double?) -> Unit
) {
    var servings by rememberSaveable { mutableStateOf("1") }
    // Set when a gram-capable recipe row is tapped, to show the amount-entry
    // step before calling onPick. A recipe without nutritionPerGram never
    // sets this — it goes straight to onPick with today's servings behavior.
    var amountRecipe by remember { mutableStateOf<Recipe?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a recipe") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                NumberField(value = servings, onValueChange = { servings = it }, label = "Servings")
                Spacer(modifier = Modifier.height(12.dp))

                recipes.sortedBy { it.name.lowercase() }.forEach { recipe ->
                    val multiplier = servings.toDoubleOrNull() ?: 1.0
                    TextButton(
                        onClick = {
                            // Gate on nutritionPerGram, not totalWeightGrams — a
                            // recipe can have a weight set with no macros ever
                            // entered, in which case nutritionPerGram is
                            // correctly null and gram-based logging cannot run.
                            if (recipe.nutritionPerGram != null) {
                                amountRecipe = recipe
                            } else {
                                onPick(recipe, multiplier, null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(recipe.name, modifier = Modifier.weight(1f))
                            recipe.nutritionPerServing?.let {
                                Text("${(it.calories * multiplier).trimZeros()} kcal")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    amountRecipe?.let { recipe ->
        RecipeAmountEntryDialog(
            recipe = recipe,
            onConfirm = { grams ->
                onPick(recipe, 1.0, grams)
                amountRecipe = null
            },
            onDismiss = { amountRecipe = null }
        )
    }
}

/**
 * Second step of picking a recipe whose nutritionPerGram is known: how much
 * of the finished dish, in whichever unit the person prefers. Mirrors
 * FoodSearchPanel's AmountEntryDialog for FoodSearchResult.
 */
@Composable
private fun RecipeAmountEntryDialog(
    recipe: Recipe,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val unit = remember { SettingsStore.get(context).servingUnit }
    var amountText by rememberSaveable { mutableStateOf("") }

    val enteredAmount = amountText.toDoubleOrNull()
    val grams = enteredAmount?.let { unit.toGrams(it) }
    val perGram = recipe.nutritionPerGram
    val nutrition = if (grams != null && perGram != null) perGram.scaled(grams) else null
    val valid = grams != null && grams > 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(recipe.name) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "How much are you planning?",
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
                        text = "${it.calories.trimZeros()} kcal  " +
                            "P ${it.proteinG.trimZeros()}  " +
                            "C ${it.carbsG.trimZeros()}  " +
                            "F ${it.fatG.trimZeros()}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { grams?.let { onConfirm(it) } },
                enabled = valid
            ) { Text("Confirm") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/* ---------- shopping ---------- */

@Composable
private fun ShoppingSection(repo: RecipeRepository, contentWidth: Float) {
    val scope = rememberCoroutineScope()
    val recipes by repo.recipes.collectAsState()
    val plan by repo.plan.collectAsState()
    val checked by repo.checked.collectAsState()

    val days = weekDays()
    // Only what is still ahead. A list that keeps yesterday's shopping on it
    // stops being a list you trust.
    val upcoming = plan.planBetween(days.first(), days.last())
    val lines = ShoppingList.build(upcoming, recipes.associateBy { it.id })

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (lines.isEmpty()) {
            Text(
                text = "Nothing planned for the next week, so there is nothing to buy yet.",
                style = MaterialTheme.typography.bodyMedium
            )
            return@Column
        }

        val columns = AdaptiveLayout.shoppingColumns(contentWidth)
        if (columns == 1) {
            lines.forEach { line ->
                ShoppingLineCard(
                    line = line,
                    isChecked = line.key in checked,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    onCheckedChange = { scope.launch { repo.setChecked(line.key, it) } }
                )
            }
        } else {
            // Read down each column, in the list's own order. Two columns of short lines drift
            // apart across a whole tablet, so they stay within reading distance.
            Column(modifier = Modifier.widthIn(max = AdaptiveLayout.READABLE_DP.dp * 1.3f)) {
                columnMajor(lines, columns).forEach { row ->
                    GridRow(cells = row, columns = columns, gap = 8f) { line ->
                        ShoppingLineCard(
                            line = line,
                            isChecked = line.key in checked,
                            modifier = Modifier.fillMaxSize(),
                            onCheckedChange = { scope.launch { repo.setChecked(line.key, it) } }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        if (checked.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = { scope.launch { repo.clearChecked() } }) {
                Text("Clear ticks")
            }
        }
    }
}

@Composable
private fun ShoppingLineCard(
    line: ShoppingListLine,
    isChecked: Boolean,
    modifier: Modifier,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(modifier = modifier, border = dclCardBorder()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = onCheckedChange
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = line.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration =
                        if (isChecked) TextDecoration.LineThrough else TextDecoration.None
                )
                if (line.amounts.isNotEmpty()) {
                    Text(
                        text = line.amounts.shoppingAmountLabel(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                // Ingredients that never parsed, verbatim, so nothing
                // silently drops off the list you shop from.
                line.unparsed.forEach { raw ->
                    Text(raw, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/* ---------- days ---------- */

/** Today plus six. A plan is a week you are shopping for, not a calendar. */
private fun weekDays(): List<String> {
    val calendar = Calendar.getInstance()
    return (0 until 7).map { offset ->
        val day = calendar.clone() as Calendar
        day.add(Calendar.DAY_OF_YEAR, offset)
        DAY_KEY_FORMAT.format(day.time)
    }
}

private fun dayLabel(key: String): String {
    if (key == todayKey()) return "Today"
    val date = DAY_KEY_FORMAT.parse(key) ?: return key
    val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
    if (key == DAY_KEY_FORMAT.format(tomorrow.time)) return "Tomorrow"
    return WEEKDAY_FORMAT.format(date)
}

private fun isDebuggable(context: android.content.Context): Boolean =
    (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

private fun servingsLabel(value: Double): String =
    if (value == 1.0) "1 serving" else "${value.trimZeros()} servings"

private val DAY_KEY_FORMAT: SimpleDateFormat
    get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

private val WEEKDAY_FORMAT: SimpleDateFormat
    get() = SimpleDateFormat("EEEE", Locale.US)
