package com.dugcanlift.macrocalc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.dugcanlift.macrocalc.data.CookSampleData
import com.dugcanlift.macrocalc.data.IngredientParser
import com.dugcanlift.macrocalc.data.Meal
import com.dugcanlift.macrocalc.data.PlannedMeal
import com.dugcanlift.macrocalc.data.Recipe
import com.dugcanlift.macrocalc.data.RecipeIngredient
import com.dugcanlift.macrocalc.data.RecipeNutrition
import com.dugcanlift.macrocalc.data.RecipeRepository
import com.dugcanlift.macrocalc.data.ShoppingList
import com.dugcanlift.macrocalc.data.FoodRepository
import com.dugcanlift.macrocalc.data.planBetween
import com.dugcanlift.macrocalc.data.shoppingAmountLabel
import com.dugcanlift.macrocalc.data.todayKey
import com.dugcanlift.macrocalc.data.trimZeros
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

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        ChipRow(
            options = CookSection.entries,
            selected = section,
            label = { it.label },
            onSelect = { section = it }
        )

        Spacer(modifier = Modifier.height(16.dp))

        when (section) {
            CookSection.RECIPES -> RecipesSection(repo)
            CookSection.PLAN -> PlanSection(repo)
            CookSection.SHOPPING -> ShoppingSection(repo)
        }
    }
}

/* ---------- recipes ---------- */

@Composable
private fun RecipesSection(repo: RecipeRepository) {
    val scope = rememberCoroutineScope()
    val recipes by repo.recipes.collectAsState()
    var editing by remember { mutableStateOf<Recipe?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
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
            recipes.sortedBy { it.name.lowercase() }.forEach { recipe ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    onClick = { editing = recipe }
                ) {
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
        RecipeEditorDialog(repo = repo, existing = recipe) { editing = null }
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
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var servings by remember { mutableStateOf((existing?.servings ?: 1.0).trimZeros()) }
    var ingredientText by remember {
        mutableStateOf(existing?.ingredients.orEmpty().joinToString("\n") { it.rawText })
    }
    var stepText by remember { mutableStateOf(existing?.steps.orEmpty().joinToString("\n")) }
    var calories by remember { mutableStateOf(existing?.nutritionPerServing?.calories?.trimZeros() ?: "") }
    var protein by remember { mutableStateOf(existing?.nutritionPerServing?.proteinG?.trimZeros() ?: "") }
    var carbs by remember { mutableStateOf(existing?.nutritionPerServing?.carbsG?.trimZeros() ?: "") }
    var fat by remember { mutableStateOf(existing?.nutritionPerServing?.fatG?.trimZeros() ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New recipe" else "Edit recipe") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
                NumberField(value = calories, onValueChange = { calories = it }, label = "kcal")
                Spacer(modifier = Modifier.height(8.dp))
                NumberField(value = protein, onValueChange = { protein = it }, label = "Protein g")
                Spacer(modifier = Modifier.height(8.dp))
                NumberField(value = carbs, onValueChange = { carbs = it }, label = "Carbs g")
                Spacer(modifier = Modifier.height(8.dp))
                NumberField(value = fat, onValueChange = { fat = it }, label = "Fat g")

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
                        ingredientText = ingredientText,
                        stepText = stepText,
                        calories = calories, protein = protein, carbs = carbs, fat = fat
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
    ingredientText: String,
    stepText: String,
    calories: String,
    protein: String,
    carbs: String,
    fat: String
): Recipe {
    val ingredients: List<RecipeIngredient> = ingredientText
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { IngredientParser.parse(it) }

    val steps = stepText.lines().map { it.trim() }.filter { it.isNotEmpty() }

    // Null unless something was actually typed — an untouched form must not
    // write zeros, which would later log as a zero-calorie meal.
    val typed = listOf(calories, protein, carbs, fat).map { it.trim().toDoubleOrNull() }
    val nutrition = if (typed.all { it == null }) null else RecipeNutrition(
        calories = typed[0] ?: 0.0,
        proteinG = typed[1] ?: 0.0,
        carbsG = typed[2] ?: 0.0,
        fatG = typed[3] ?: 0.0
    )

    val base = existing ?: Recipe(name = name)
    return base.copy(
        name = name,
        servings = if (servings > 0) servings else 1.0,
        ingredients = ingredients,
        steps = steps,
        nutritionPerServing = nutrition
    )
}

/* ---------- plan ---------- */

@Composable
private fun PlanSection(repo: RecipeRepository) {
    val context = LocalContext.current
    val foodRepo = remember { FoodRepository.get(context) }
    val scope = rememberCoroutineScope()

    val recipes by repo.recipes.collectAsState()
    val plan by repo.plan.collectAsState()

    var picking by remember { mutableStateOf<Pair<String, Meal>?>(null) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (recipes.isEmpty()) {
            Text(
                text = "Add a recipe first — the plan is built from them.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        weekDays().forEach { day ->
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(dayLabel(day), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    Meal.entries.forEach { meal ->
                        val forSlot = plan.filter { it.date == day && it.mealOrDefault == meal }

                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(
                                text = meal.label,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(84.dp)
                            )

                            if (forSlot.isEmpty()) {
                                TextButton(
                                    onClick = { picking = day to meal },
                                    enabled = recipes.isNotEmpty()
                                ) { Text("Add") }
                            } else {
                                Column(modifier = Modifier.weight(1f)) {
                                    forSlot.forEach { planned ->
                                        PlannedRow(
                                            planned = planned,
                                            onLog = {
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
                                            onRemove = { scope.launch { repo.unplan(planned.id) } }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    picking?.let { (day, meal) ->
        RecipePickerDialog(
            recipes = recipes,
            onDismiss = { picking = null },
            onPick = { recipe, servings ->
                scope.launch { repo.plan(recipe, day, meal, servings) }
                picking = null
            }
        )
    }
}

@Composable
private fun PlannedRow(planned: PlannedMeal, onLog: () -> Unit, onRemove: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(planned.recipeName, style = MaterialTheme.typography.bodyMedium)

        planned.snapshotNutrition?.let { nutrition ->
            Text(
                text = "${(nutrition.calories * planned.servings).trimZeros()} kcal",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                planned.isLogged ->
                    Text("Logged", style = MaterialTheme.typography.bodySmall)
                planned.snapshotNutrition != null ->
                    TextButton(onClick = onLog) { Text("Log it") }
            }
            TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}

@Composable
private fun RecipePickerDialog(
    recipes: List<Recipe>,
    onDismiss: () -> Unit,
    onPick: (Recipe, Double) -> Unit
) {
    var servings by remember { mutableStateOf("1") }

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
                        onClick = { onPick(recipe, multiplier) },
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
}

/* ---------- shopping ---------- */

@Composable
private fun ShoppingSection(repo: RecipeRepository) {
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

        lines.forEach { line ->
            val isChecked = line.key in checked
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = { scope.launch { repo.setChecked(line.key, it) } }
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

        if (checked.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = { scope.launch { repo.clearChecked() } }) {
                Text("Clear ticks")
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
