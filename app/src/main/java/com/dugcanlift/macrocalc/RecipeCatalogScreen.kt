package com.dugcanlift.macrocalc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dugcanlift.kit.IngredientParser
import com.dugcanlift.macrocalc.data.CatalogRecipe
import com.dugcanlift.macrocalc.data.RecipeCatalog
import com.dugcanlift.macrocalc.data.RecipeCatalogStore
import com.dugcanlift.macrocalc.data.RecipeRepository
import com.dugcanlift.macrocalc.ui.adaptive.AdaptiveLayout
import com.dugcanlift.macrocalc.ui.adaptive.MeasuredPane
import com.dugcanlift.macrocalc.ui.theme.dclAccentText
import com.dugcanlift.macrocalc.ui.theme.dclCardBorder
import com.dugcanlift.macrocalc.ui.theme.dclTextButtonColors
import com.dugcanlift.macrocalc.ui.theme.dclTextFieldColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Browse the bundled recipe catalogue and copy one into your own library.
 *
 * A port of LIFT iPhone's `RecipeCatalogView`: the same list, detail, wording
 * and rules. 622 recipes ship as `assets/recipes.json`, so this needs no typing
 * and no connection -- nothing here makes a request.
 *
 * Nothing is in the library until **Add** is tapped. A catalogue recipe is
 * reference data, like a Road Food item, and browsing it must not quietly fill
 * someone's recipe list. The rules that decide its numbers are
 * [CatalogRecipe.nutrition] and [CatalogRecipe.makeRecipe], with no view in
 * them; nothing is re-costed.
 */
@Composable
fun RecipeCatalogScreen(repo: RecipeRepository, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val feedback = LocalAppFeedback.current

    var catalogue by remember { mutableStateOf<List<CatalogRecipe>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val loaded = RecipeCatalogStore.load(context)
        catalogue = loaded
        loadFailed = loaded == null
    }

    var query by rememberSaveable { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<CatalogRecipe>>(emptyList()) }
    // By id and saveable, so an open recipe outlives the activity being recreated.
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    // Only consulted for a recipe whose source stated no yield. A published
    // per-serving figure is never rescaled by it.
    var servings by rememberSaveable { mutableDoubleStateOf(RecipeCatalog.DEFAULT_SERVINGS) }
    var showingCredits by rememberSaveable { mutableStateOf(false) }

    // Debounced, with a two-character floor, as the iPhone's search is: a
    // keystroke is not a query.
    LaunchedEffect(query, catalogue) {
        val all = catalogue ?: return@LaunchedEffect
        val trimmed = query.trim()
        if (trimmed.isNotEmpty() && trimmed.length < RecipeCatalog.MIN_QUERY) return@LaunchedEffect
        if (trimmed.isNotEmpty()) delay(200)
        entries = withContext(Dispatchers.Default) {
            if (trimmed.length >= RecipeCatalog.MIN_QUERY) RecipeCatalog.search(all, trimmed)
            else RecipeCatalog.browse(all)
        }
    }
    val credits = remember(catalogue) { catalogue?.let(RecipeCatalog::attributions).orEmpty() }

    val selected = selectedId?.let { id -> catalogue?.firstOrNull { it.id == id } }

    BackHandler { if (selectedId != null) selectedId = null else onClose() }

    fun open(recipe: CatalogRecipe) {
        // Seeded from the source when it stated one, so the stepper starts
        // somewhere sensible rather than at a default the reader must undo.
        servings = recipe.servings ?: RecipeCatalog.DEFAULT_SERVINGS
        selectedId = recipe.id
    }

    fun add(recipe: CatalogRecipe) {
        val copy = recipe.makeRecipe(recipe.servings ?: servings)
        // The app's scope, not this screen's: the screen closes in the same
        // tap, and a save in a cancelled scope silently never happens.
        feedback.scope.launch { repo.addRecipe(copy) }
        feedback.show("Added ${recipe.name} to your recipes.")
        onClose()
    }

    MeasuredPane(modifier = modifier.fillMaxSize()) { paneWidth ->
        val gutter = AdaptiveLayout.sideGutter(paneWidth, AdaptiveLayout.READABLE_DP).dp
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = gutter)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { if (selected == null) onClose() else selectedId = null },
                    colors = dclTextButtonColors()
                ) { Text(if (selected == null) "Done" else "Back") }
                Text(
                    text = if (selected == null) "Recipe catalog" else "Add recipe",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                if (selected != null) {
                    Button(onClick = { add(selected) }) { Text("Add") }
                }
            }

            if (selected != null) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RecipeDetail(
                        recipe = selected,
                        servings = servings,
                        onServings = { servings = it }
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search 600+ recipes") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = dclTextFieldColors()
                )
                Spacer(modifier = Modifier.height(12.dp))
                CatalogueList(
                    entries = entries,
                    loadFailed = loadFailed,
                    searching = query.trim().length >= RecipeCatalog.MIN_QUERY,
                    loaded = catalogue != null,
                    credits = credits,
                    showingCredits = showingCredits,
                    onToggleCredits = { showingCredits = !showingCredits },
                    onOpen = ::open
                )
            }
        }
    }
}

/* ---------- list ---------- */

@Composable
private fun CatalogueList(
    entries: List<CatalogRecipe>,
    loadFailed: Boolean,
    searching: Boolean,
    loaded: Boolean,
    credits: List<String>,
    showingCredits: Boolean,
    onToggleCredits: () -> Unit,
    onOpen: (CatalogRecipe) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when {
            loadFailed -> item { CatalogCard { Detail("The recipe catalog could not be opened.") } }
            entries.isEmpty() -> item {
                CatalogCard { Detail(if (searching && loaded) "Nothing matches that." else "Loading…") }
            }
            else -> items(entries, key = { it.id }) { recipe -> CatalogRow(recipe) { onOpen(recipe) } }
        }

        // The credit is the CC BY-SA obligation, not decoration, so it sits on
        // the screen that shows the data rather than buried in a settings page.
        if (credits.isNotEmpty()) {
            item {
                TextButton(onClick = onToggleCredits, colors = dclTextButtonColors()) {
                    Text(
                        if (showingCredits) "Hide credits" else "Credits and licenses",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (showingCredits) {
                item {
                    CatalogCard {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            credits.forEach { Detail(it) }
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun CatalogRow(recipe: CatalogRecipe, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick, border = dclCardBorder()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = recipe.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = dclAccentText(),
                    modifier = Modifier.weight(1f)
                )
                recipe.servings?.let {
                    Spacer(modifier = Modifier.width(8.dp))
                    Detail(servingsLabel(it), fill = false)
                }
            }
            Detail(
                when {
                    recipe.caloriesPerServing != null ->
                        "${RecipeCatalog.wholeNumber(recipe.caloriesPerServing)} kcal per serving"
                    // Said here rather than only in the detail, so a browsing eye
                    // never reads a whole-dish number as a portion.
                    recipe.needsServings -> "Macros for the whole dish"
                    else -> "No macros"
                }
            )
            Detail("${recipe.ingredients.size} ingredients")
        }
    }
}

/* ---------- detail ---------- */

@Composable
private fun RecipeDetail(recipe: CatalogRecipe, servings: Double, onServings: (Double) -> Unit) {
    CatalogCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(recipe.name, style = MaterialTheme.typography.titleMedium, color = dclAccentText())
            recipe.summary?.let { Detail(it) }

            // Every recipe whose source stated no yield, macros or none: the copy
            // stores a serving count either way, and it must be one the person chose.
            if (recipe.statesNoYield) {
                ServingsStepper(value = servings, onChange = onServings)
                Detail(
                    if (recipe.needsServings) {
                        "This book states no yield, so the macros below are for the whole dish. " +
                            "Set how many it serves and they divide."
                    } else {
                        "This book states no yield. Set how many it serves."
                    }
                )
            } else {
                recipe.servings?.let { Detail(servingsLabel(it)) }
            }

            val prep = recipe.prepMinutes
            val cook = recipe.cookMinutes
            if (prep != null && cook != null) Detail("$prep min prep · $cook min cook")
        }
    }

    CatalogCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Ingredients (${recipe.ingredients.size})")
            recipe.ingredients.forEach { line ->
                val parsed = IngredientParser.parse(line)
                Row(verticalAlignment = Alignment.Top) {
                    Text(parsed.displayText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (parsed.qty == null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Detail("as written", fill = false)
                    }
                }
            }
        }
    }

    if (recipe.steps.isNotEmpty()) {
        CatalogCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Method (${recipe.steps.size})")
                recipe.steps.forEachIndexed { index, step ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            "${index + 1}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = dclAccentText(),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    CatalogCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Macros")
            val facts = recipe.nutrition(servings)
            if (facts != null) {
                Text(
                    "${RecipeCatalog.wholeNumber(facts.calories)} kcal · P ${RecipeCatalog.wholeNumber(facts.proteinG)} · " +
                        "C ${RecipeCatalog.wholeNumber(facts.carbsG)} · F ${RecipeCatalog.wholeNumber(facts.fatG)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Detail(
                    if (recipe.needsServings) "Per serving at ${servingsLabel(servings)}."
                    else "Per serving, as published."
                )
            } else {
                Detail("None.")
            }
            recipe.nutritionNote?.let { Detail(it) }
        }
    }

    CatalogCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("Source")
            Detail(recipe.attribution)
            Detail(recipe.license)
        }
    }
}

/** The iPhone's `Stepper(in: 1...48, step: 1)`, in Road Food's −/+ style. */
@Composable
private fun ServingsStepper(value: Double, onChange: (Double) -> Unit) {
    val canLower = value > RecipeCatalog.MIN_SERVINGS
    val canRaise = value < RecipeCatalog.MAX_SERVINGS
    val disabled = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(servingsLabel(value), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(
            onClick = { onChange((value - 1).coerceAtLeast(RecipeCatalog.MIN_SERVINGS)) },
            enabled = canLower,
            modifier = Modifier.size(44.dp).semantics { contentDescription = "One fewer serving" }
        ) {
            Text("−", style = MaterialTheme.typography.titleLarge, color = if (canLower) dclAccentText() else disabled)
        }
        IconButton(
            onClick = { onChange((value + 1).coerceAtMost(RecipeCatalog.MAX_SERVINGS)) },
            enabled = canRaise,
            modifier = Modifier.size(44.dp).semantics { contentDescription = "One more serving" }
        ) {
            Text("+", style = MaterialTheme.typography.titleLarge, color = if (canRaise) dclAccentText() else disabled)
        }
    }
}

/* ---------- chrome ---------- */

@Composable
private fun CatalogCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), border = dclCardBorder()) {
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) { content() }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = dclAccentText())
}

@Composable
private fun Detail(text: String, fill: Boolean = true) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = if (fill) Modifier.fillMaxWidth() else Modifier
    )
}
