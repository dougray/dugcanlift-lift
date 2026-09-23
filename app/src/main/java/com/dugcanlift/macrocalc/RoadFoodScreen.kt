package com.dugcanlift.macrocalc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dugcanlift.kit.NutrientDetails
import com.dugcanlift.macrocalc.data.FoodRepository
import com.dugcanlift.macrocalc.data.Meal
import com.dugcanlift.macrocalc.data.NutrientDetailsText
import com.dugcanlift.macrocalc.data.Remaining
import com.dugcanlift.macrocalc.data.RoadFood
import com.dugcanlift.macrocalc.data.RoadFoodChain
import com.dugcanlift.macrocalc.data.RoadFoodData
import com.dugcanlift.macrocalc.data.RoadFoodItem
import com.dugcanlift.macrocalc.data.RoadFoodStore
import com.dugcanlift.macrocalc.data.RoadPicks
import com.dugcanlift.macrocalc.data.SettingsStore
import com.dugcanlift.macrocalc.data.forDate
import com.dugcanlift.macrocalc.data.mealForHour
import com.dugcanlift.macrocalc.data.remainingFor
import com.dugcanlift.macrocalc.data.todayKey
import com.dugcanlift.macrocalc.ui.adaptive.AdaptiveLayout
import com.dugcanlift.macrocalc.ui.adaptive.GridRow
import com.dugcanlift.macrocalc.ui.adaptive.MeasuredPane
import com.dugcanlift.macrocalc.ui.adaptive.rowMajor
import com.dugcanlift.macrocalc.ui.theme.dclCardBorder
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Calendar
import java.util.Locale

/**
 * Road Food, reached from Food: pick a chain (or the gas station), and see what
 * there fits what is left of today, ranked, with one tap to log it.
 *
 * The rules that decide what is shown are [RoadFood] (a port of LIFT web's
 * `road-food.js`), and "what is left" is [remainingFor], the function Home and
 * Food read their "kcal left" from. The data is a bundled asset
 * ([RoadFoodStore]), so this works with no signal. No location of any kind:
 * the person picks the chain.
 *
 * [onClose] is told whether anything was logged, so Food can show today.
 */
@Composable
fun RoadFoodScreen(goal: MacroResult?, onClose: (loggedAny: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = remember { FoodRepository.get(context) }
    val settings = remember { SettingsStore.get(context) }
    val scope = rememberCoroutineScope()

    var data by remember { mutableStateOf<RoadFoodData?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        repo.load()
        data = RoadFoodStore.load(context)
        if (data == null) loadError = RoadFoodStore.lastError ?: "unknown error"
    }

    // "picker", "chain:<id>" or "snacks:<category>" (blank for all), saveable so a
    // recreation keeps the place open.
    var view by rememberSaveable { mutableStateOf("picker") }
    var mealName by rememberSaveable {
        mutableStateOf(mealForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)).name)
    }
    var note by rememberSaveable { mutableStateOf("") }
    var loggedAny by rememberSaveable { mutableStateOf(false) }
    var recent by remember { mutableStateOf(settings.roadFoodRecent) }
    // What a coach marked and sent in a plan link, or null when none has. Held
    // here so clearing them re-draws the whole screen, not just one card.
    var picks by remember { mutableStateOf(settings.roadPicks) }

    val allEntries by repo.entries.collectAsState()
    val today = todayKey()
    val remaining = remainingFor(goal, allEntries.forDate(today))

    fun backToPicker() {
        view = "picker"
        note = ""
    }
    BackHandler { if (view == "picker") onClose(loggedAny) else backToPicker() }

    MeasuredPane(modifier = modifier.fillMaxSize()) { paneWidth ->
        val contentWidth = AdaptiveLayout.contentWidth(paneWidth)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AdaptiveLayout.sideGutter(paneWidth).dp, vertical = 8.dp)
        ) {
            TextButton(onClick = { if (view == "picker") onClose(loggedAny) else backToPicker() }) {
                Text(if (view == "picker") "Back to Food" else "All chains")
            }

            val loaded = data
            if (loaded == null) {
                Card(modifier = Modifier.fillMaxWidth(), border = dclCardBorder()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (loadError != null) {
                            Text("Road Food could not load its list.", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "($loadError)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                "Loading the menus...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                return@Column
            }

            val chain = view.removePrefix("chain:").takeIf { view.startsWith("chain:") }
                ?.let { id -> loaded.chains.firstOrNull { it.id == id } }
            val snackCategory = view.removePrefix("snacks:").takeIf { view.startsWith("snacks:") }

            if (chain == null && snackCategory == null) {
                Picker(
                    data = loaded,
                    recentIds = recent,
                    picks = picks,
                    onClearPicks = {
                        picks = null
                        settings.roadPicks = null
                    },
                    contentWidth = contentWidth,
                    onChain = { id ->
                        recent = RoadFood.remember(recent, id)
                        settings.roadFoodRecent = recent
                        view = "chain:$id"
                        note = ""
                    },
                    onSnacks = {
                        view = "snacks:"
                        note = ""
                    }
                )
            } else {
                Place(
                    data = loaded,
                    chain = chain,
                    snackCategory = snackCategory,
                    remaining = remaining,
                    today = today,
                    meal = Meal.entries.firstOrNull { it.name == mealName } ?: Meal.SNACK,
                    note = note,
                    picks = picks,
                    twoPane = AdaptiveLayout.roadFoodIsTwoPane(contentWidth),
                    onCategory = { view = "snacks:$it" },
                    onMeal = { mealName = it.name },
                    onLog = { item ->
                        val meal = Meal.entries.firstOrNull { it.name == mealName } ?: Meal.SNACK
                        val entry = RoadFood.entryFor(item, chain?.name, meal, date = today)
                        scope.launch { repo.add(entry) }
                        loggedAny = true
                        val after = remainingFor(goal, allEntries.forDate(today) + entry)
                        note = "Logged ${item.name} to ${meal.label}." + when {
                            after == null -> ""
                            after.calories >= 0 -> " ${after.calories} kcal left today."
                            else -> " ${-after.calories} kcal over today."
                        }
                    }
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

private val checkedFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun checkedLabel(day: String?): String? = RoadFood.parseDay(day)?.format(checkedFormat.withLocale(Locale.getDefault()))

private fun titleCase(text: String): String =
    text.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.getDefault()) } }

@Composable
private fun Picker(
    data: RoadFoodData,
    recentIds: List<String>,
    picks: RoadPicks?,
    onClearPicks: () -> Unit,
    contentWidth: Float,
    onChain: (String) -> Unit,
    onSnacks: () -> Unit
) {
    val columns = AdaptiveLayout.cardColumns(contentWidth)
    val ordered = RoadFood.orderChains(data.chains, recentIds)
    val pickIds = picks?.ids.orEmpty()

    Text("Road Food", style = MaterialTheme.typography.headlineSmall)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        "Pick where you are stopping. The list is ranked against what is left of today, " +
            "and it is in the app, so it works with no signal.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(16.dp))

    // What a coach marked, when a plan brought any. Only what this copy of the
    // file still has is counted -- an id it does not know is skipped -- so the
    // card can be empty while picks are stored, and then it is not drawn.
    val everything = data.chains.flatMap { it.items } + data.snacks
    val pickedTotal = RoadFood.pickCount(everything, pickIds)
    if (picks != null && pickedTotal > 0) {
        val places = data.chains.count { RoadFood.pickCount(it.items, pickIds) > 0 } +
            (if (RoadFood.pickCount(data.snacks, pickIds) > 0) 1 else 0)
        Card(modifier = Modifier.fillMaxWidth(), border = dclCardBorder()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(picks.label("picks"), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "$pickedTotal ${if (pickedTotal == 1) "item" else "items"} at " +
                        "$places ${if (places == 1) "place" else "places"}, at the top of those " +
                        "lists. The ranking underneath them is unchanged.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Retracting is this phone's job: a plan with no picks in it is
                // silent about them, not a retraction (see RoadPicks).
                OutlinedButton(onClick = onClearPicks, modifier = Modifier.fillMaxWidth()) {
                    Text("Clear these picks")
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }

    @Composable
    fun cards(tiles: List<@Composable (Modifier) -> Unit>) {
        if (columns == 1) {
            tiles.forEach { tile ->
                tile(Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(12.dp))
            }
        } else {
            rowMajor(tiles, columns).forEach { row ->
                GridRow(cells = row, columns = columns) { tile -> tile(Modifier.fillMaxSize()) }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    if (data.snacks.isNotEmpty()) {
        val categories = data.snackCategories
        cards(listOf { m ->
            val gasPicked = RoadFood.pickCount(data.snacks, pickIds)
            PlaceCard(
                title = "Gas station",
                subtitle = (if (categories.isEmpty()) "Snacks" else titleCase(categories.joinToString(", "))) +
                    (if (gasPicked > 0) " \u00b7 $gasPicked picked for you" else ""),
                modifier = m,
                onClick = onSnacks
            )
        })
    }

    fun chainTiles(chains: List<RoadFoodChain>): List<@Composable (Modifier) -> Unit> = chains.map { c ->
        { m: Modifier ->
            val count = c.items.size
            val picked = RoadFood.pickCount(c.items, pickIds)
            PlaceCard(
                title = c.name,
                subtitle = "$count ${if (count == 1) "item" else "items"}" +
                    (if (picked > 0) " · $picked picked for you" else "") +
                    (checkedLabel(c.checkedOn)?.let { " · checked $it" } ?: ""),
                modifier = m,
                onClick = { onChain(c.id) }
            )
        }
    }

    if (ordered.recent.isNotEmpty()) {
        SectionHeading("Recent")
        cards(chainTiles(ordered.recent))
    }
    if (ordered.rest.isNotEmpty()) {
        SectionHeading(if (ordered.recent.isNotEmpty()) "All chains" else "Chains")
        cards(chainTiles(ordered.rest))
    }
    Text(
        "Numbers come from each chain's own published nutrition, checked by hand, and each place " +
            "shows the date they were checked. LIFT never asks where you are.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SectionHeading(text: String) {
    Spacer(modifier = Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun PlaceCard(title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier = modifier, onClick = onClick, border = dclCardBorder()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Place(
    data: RoadFoodData,
    chain: RoadFoodChain?,
    snackCategory: String?,
    remaining: Remaining?,
    today: String,
    meal: Meal,
    note: String,
    picks: RoadPicks?,
    twoPane: Boolean,
    onCategory: (String) -> Unit,
    onMeal: (Meal) -> Unit,
    onLog: (RoadFoodItem) -> Unit
) {
    val isSnacks = chain == null
    val items = if (chain != null) chain.items
    else data.snacks.filter { snackCategory.isNullOrEmpty() || it.category == snackCategory }

    Text(chain?.name ?: "Gas station", style = MaterialTheme.typography.headlineSmall)

    // When the numbers were checked, in plain view. A gas-station view mixes
    // products, so it shows the oldest date among what is on screen.
    val checkedOn = if (chain != null) chain.checkedOn
    else items.mapNotNull { it.checkedOn }.filter { RoadFood.parseDay(it) != null }.minOrNull()
    val uriHandler = LocalUriHandler.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            checkedLabel(checkedOn)?.let { "Checked on $it" } ?: "No check date on file for these numbers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val source = chain?.source
        if (source != null && source.startsWith("https://")) {
            TextButton(onClick = { uriHandler.openUri(source) }) { Text("Source") }
        }
    }
    if (RoadFood.isStale(checkedOn, today) == true) {
        Text(
            "These numbers are more than six months old. Menus change, so check them against " +
                "the board before you count on them.",
            style = MaterialTheme.typography.bodyMedium
        )
    }

    if (isSnacks) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            (listOf("") + data.snackCategories).forEach { c ->
                FilterChip(
                    selected = (snackCategory ?: "") == c,
                    onClick = { onCategory(c) },
                    label = { Text(if (c.isEmpty()) "All" else titleCase(c)) }
                )
            }
        }
    }

    // Ranked first, then the coach's picks floated to the top of each group.
    // Nothing about the ranking changes: the same items fit, in the same order
    // among themselves, and the same ones are left out.
    val ranked = RoadFood.withPicks(RoadFood.rank(items, remaining?.calories), picks?.ids.orEmpty())
    Spacer(modifier = Modifier.height(12.dp))
    Card(modifier = Modifier.fillMaxWidth(), border = dclCardBorder()) {
        Column(modifier = Modifier.padding(16.dp)) {
            val headline = RoadFood.fitHeadline(remaining)
            if (headline == null) {
                Text("No goal set", style = MaterialTheme.typography.titleMedium)
                Text(
                    "So this is ranked by protein per 100 kcal alone, with nothing left out. Set a goal " +
                        "on Home and it will rank against what is left of your day.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(headline, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Most protein per 100 kcal first; lower sodium breaks a tie. Anything more than 10% " +
                        "over what is left is not shown.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (ranked.fits.isEmpty() && ranked.over.isEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        if (remaining != null && remaining.calories <= 0)
                            "Today's calories are used, so nothing here fits what is left."
                        else "Nothing here fits what is left today.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            if (picks != null && ranked.count > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${picks.label("picks")} are first, marked. Nothing else is moved, and " +
                        "nothing that fits is hidden.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (note.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            note,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }

    val rules = if (chain != null) RoadFood.rulesFor(data.rules, chain.kind) else RoadFood.snackRules(data.rules)
    // "Doug's pick", or nothing at all on an item nobody picked.
    val pickLabel: (RoadFoodItem) -> String? =
        { item -> picks?.takeIf { item in ranked }?.label("pick") }
    val list: @Composable () -> Unit = {
        Spacer(modifier = Modifier.height(12.dp))
        Text("Log to", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            Meal.entries.forEach { m ->
                FilterChip(selected = m == meal, onClick = { onMeal(m) }, label = { Text(m.label) })
            }
        }
        if (ranked.fits.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            ItemList(ranked.fits, pickLabel, onLog)
        }
        if (ranked.over.isNotEmpty()) {
            SectionHeading("A little over")
            Text(
                "Within 10% of what is left.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            // A pick that is over stays over: the pick is about the food, and
            // what is left of the day is the lifter's own arithmetic.
            ItemList(ranked.over, pickLabel, onLog)
        }
    }
    val ordering: @Composable () -> Unit = {
        if (rules.isNotEmpty()) {
            SectionHeading("Ordering")
            Card(modifier = Modifier.fillMaxWidth(), border = dclCardBorder()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    rules.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }

    if (!twoPane) {
        list()
        ordering()
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(AdaptiveLayout.PANE_GAP_DP.dp)) {
            Column(modifier = Modifier.weight(1f)) { list() }
            Column(modifier = Modifier.weight(1f)) { ordering() }
        }
    }
}

@Composable
private fun ItemList(
    items: List<RoadFoodItem>,
    pickLabel: (RoadFoodItem) -> String?,
    onLog: (RoadFoodItem) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), border = dclCardBorder()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            items.forEachIndexed { i, item ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ItemRow(item, pickLabel(item), onLog)
            }
        }
    }
}

@Composable
private fun ItemRow(item: RoadFoodItem, pickLabel: String?, onLog: (RoadFoodItem) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge)
            // A coach's pick, said in words under the name and above the
            // numbers, where web puts it. Weight, not colour: this labels what
            // a coach marked, it does not grade the food.
            if (pickLabel != null) {
                Text(
                    pickLabel,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(RoadFood.macroLine(item), style = MaterialTheme.typography.bodyMedium, color = muted)
            listOfNotNull(RoadFood.densityText(item), item.serving).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = muted)
            }
            // Shown, never targeted: plain text, no colour, no threshold.
            NutrientDetailsText.line(NutrientDetails(item.saturatedFatG, item.sugarG, item.sodiumMg))?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = muted)
            }
            item.modification?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
            item.barcode?.let { Text("Barcode $it", style = MaterialTheme.typography.bodySmall, color = muted) }
        }
        // Nothing to log without a calorie figure; every entry in LIFT has one.
        OutlinedButton(
            onClick = { onLog(item) },
            enabled = RoadFood.canLog(item),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = "Log ${item.name}" }
        ) {
            Text("Log it")
        }
    }
}
