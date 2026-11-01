package com.dugcanlift.macrocalc

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dugcanlift.macrocalc.ui.theme.LocalDclDark
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import com.dugcanlift.macrocalc.ui.adaptive.AdaptiveLayout
import com.dugcanlift.macrocalc.ui.adaptive.AppPageInsets
import com.dugcanlift.macrocalc.ui.adaptive.LiftNavigationRail
import com.dugcanlift.macrocalc.ui.adaptive.LocalWindowWidth
import com.dugcanlift.macrocalc.ui.adaptive.ProvideWindowLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import com.dugcanlift.macrocalc.ui.theme.dclTextButtonColors
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.dugcanlift.macrocalc.data.CoachStore
import com.dugcanlift.macrocalc.data.RecipeLink
import com.dugcanlift.macrocalc.data.RecipeRepository
import com.dugcanlift.macrocalc.watchlink.WatchLinkTransport
import com.dugcanlift.macrocalc.data.GoalStore
import com.dugcanlift.kit.PlanDecodeResult
import com.dugcanlift.kit.PlanLinkCodec
import com.dugcanlift.macrocalc.ui.theme.DugCanLiftCalcTheme
import com.dugcanlift.macrocalc.ui.theme.dclAccentText

class MainActivity : ComponentActivity() {
    private val pendingPlan = mutableStateOf<PlanDecodeResult?>(null)

    /** Reads a plan link once, so a recreation never asks about it again. */
    private val planLinks = PlanLinkPrompt()

    /** A "Send to LIFT" recipe link, read once by the same rule ([RecipeLink]). */
    private val recipeLinks = RecipeLinkPrompt()
    private val pendingRecipe = mutableStateOf<RecipeLink.Opened?>(null)

    // A plain property (not a delegated `by`) so `onNewIntent` can update it
    // directly — `AppTabs` observes it via `State<Int?>` and re-selects the
    // tab on every change, not just on the initial composition. Without
    // this, a notification tap while the app is already running (warm
    // launch: `CLEAR_TOP`/`SINGLE_TOP` routes into `onNewIntent`, not
    // `onCreate`) would silently do nothing, since `getIntent()` still
    // returned the original launch intent and nothing re-read the extra.
    private val pendingOpenTab = mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        planLinks.onCreate(intent, savedInstanceState)?.let(::askAboutPlan)
        recipeLinks.onCreate(intent, savedInstanceState)?.let(::openRecipe)
        if (savedInstanceState == null) {
            // Lets the ongoing route-recording notification (LocationRecordingService)
            // bring the user back to the Train tab instead of leaving a live
            // recording with no way back into its UI — see M-22 in the
            // final-review fix wave. Once only, for the same reason: a recreation
            // must not pull the person back to Train from wherever they went.
            pendingOpenTab.value = extractOpenTab(intent)
        }
        setContent {
            DugCanLiftCalcTheme {
                // Status and navigation bar icons follow the appearance actually
                // drawn, not the phone's: with Light chosen on a dark-mode phone,
                // `enableEdgeToEdge()`'s default would leave light icons on a
                // parchment background, which is to say invisible.
                val dark = LocalDclDark.current
                LaunchedEffect(dark) {
                    val style = if (dark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    }
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                // One snackbar host for the whole app (undo after a delete, backup
                // results), and a scope that outlives any one tab -- see AppFeedback.
                val snackbarHostState = remember { SnackbarHostState() }
                val appScope = rememberCoroutineScope()
                val feedback = remember(snackbarHostState, appScope) { AppFeedback(snackbarHostState, appScope) }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    // The keyboard is part of the insets (AppPageInsets), so the page
                    // shrinks above it instead of drawing under it.
                    contentWindowInsets = AppPageInsets
                ) { innerPadding ->
                    CompositionLocalProvider(LocalAppFeedback provides feedback) {
                        ProvideWindowLayout(modifier = Modifier.fillMaxSize()) {
                            AppTabs(
                                // Consumed once applied, so nothing below pads by the same insets again.
                                modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
                                pendingPlan = pendingPlan,
                                onPlanDismissed = ::dismissPlan,
                                // Accepted: the dialog stays up to say so, but the link is answered.
                                onPlanImported = planLinks::answered,
                                pendingRecipe = pendingRecipe,
                                onRecipeDone = ::dismissRecipe,
                                openTab = pendingOpenTab
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Arms the pending Bluetooth connection to a paired watch, so the link comes up by itself when
     * LIFT is next open on the wrist. A no-op for anyone who has never paired, and for anyone who
     * has not granted Bluetooth: it checks and returns, it never asks.
     */
    override fun onStart() {
        super.onStart()
        WatchLinkTransport.get(this).reconnectIfPaired()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep getIntent()/this.intent current for any other code in this Activity that reads it —
        // without this it would keep returning the intent the Activity was originally launched with.
        setIntent(intent)
        planLinks.onNewIntent(intent)?.let(::askAboutPlan)
        recipeLinks.onNewIntent(intent)?.let(::openRecipe)
        pendingOpenTab.value = extractOpenTab(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        planLinks.save(outState)
        recipeLinks.save(outState)
    }

    /** Unwraps the link and reads its recipe. Nothing is saved here: the editor's Save does it. */
    private fun openRecipe(link: String) {
        pendingRecipe.value = RecipeLink.open(link)
    }

    /** Saved, cancelled, or the message read: a recreation now opens nothing. */
    private fun dismissRecipe() {
        recipeLinks.answered()
        pendingRecipe.value = null
    }

    private fun askAboutPlan(fragment: String) {
        val lifterId = CoachStore.get(this).lifterId
        pendingPlan.value = PlanLinkCodec.decode(fragment, lifterId)
    }

    /** The plan dialog closed: declined, dismissed, or Done after importing. */
    private fun dismissPlan() {
        planLinks.answered()
        pendingPlan.value = null
    }

    private fun extractOpenTab(intent: Intent?): Int? =
        intent?.getIntExtra(EXTRA_OPEN_TAB, -1)?.takeIf { it >= 0 }

    companion object {
        /** Int extra naming the tab index [AppTabs] should open on launch. */
        const val EXTRA_OPEN_TAB = "com.dugcanlift.macrocalc.EXTRA_OPEN_TAB"

        /** Index of the Train tab within [AppTabs]'s tab order (`titles` there), for [EXTRA_OPEN_TAB] callers. */
        const val TRAIN_TAB_INDEX = 3

        /** Index of the Cook tab, where a recipe link opens. */
        const val COOK_TAB_INDEX = 2
    }
}

@Composable
private fun AppTabs(
    modifier: Modifier = Modifier,
    pendingPlan: State<PlanDecodeResult?>,
    onPlanDismissed: () -> Unit,
    onPlanImported: () -> Unit,
    pendingRecipe: State<RecipeLink.Opened?>,
    onRecipeDone: () -> Unit,
    openTab: State<Int?> = remember { mutableStateOf(null) }
) {
    val context = LocalContext.current
    val goalStore = remember { GoalStore.get(context) }
    val coachStore = remember { CoachStore.get(context) }

    var goal by remember { mutableStateOf(goalStore.get()) }
    var selectedTab by rememberSaveable { mutableIntStateOf(openTab.value ?: 0) }

    // Re-select whenever a new tab request arrives (cold launch's initial
    // value, or a later `onNewIntent` while the app is already running) —
    // not just once at first composition.
    LaunchedEffect(openTab.value) {
        openTab.value?.let { selectedTab = it }
    }

    // The calculator is a set-it-once screen, so it lives behind the dashboard
    // rather than taking a permanent slot in the navigation.
    var showCalculator by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = showCalculator) { showCalculator = false }

    // Back from Food, Cook or Train goes Home, and only Back on Home leaves the app. Registered
    // before every screen's own handlers, so an open panel or a recording still closes first.
    BackHandler(enabled = !showCalculator && selectedTab != 0) { selectedTab = 0 }

    pendingPlan.value?.let { result ->
        PlanPreviewDialog(result = result, onDismiss = onPlanDismissed, onImported = onPlanImported)
    }

    // A recipe from the "Send to LIFT" bookmarklet opens Cook with the editor filled for review;
    // a link that cannot be read, or holds no recipe, says so and fills nothing.
    when (val opened = pendingRecipe.value) {
        is RecipeLink.Opened.Review -> {
            LaunchedEffect(opened) {
                showCalculator = false
                selectedTab = MainActivity.COOK_TAB_INDEX
            }
            val recipeRepo = remember { RecipeRepository.get(context) }
            RecipeEditorDialog(repo = recipeRepo, existing = null, draft = opened.draft, onDismiss = onRecipeDone)
        }
        RecipeLink.Opened.Unreadable, RecipeLink.Opened.NoRecipe -> RecipeLinkMessage(opened, onDismiss = onRecipeDone)
        null -> Unit
    }

    val titles = listOf("Home", "Food", "Cook", "Train")

    // Tabs along the top on a phone; a rail down the side from 600 dp (ui/adaptive/WindowLayout.kt).
    // One tree for both, with the rail and the tab row as the only conditional parts, so crossing
    // the width -- rotating, unfolding, resizing a split screen -- re-lays out the open screen
    // rather than rebuilding it, and nothing typed into it is lost. [modifier] carries the
    // Scaffold's inset padding; with a rail, the rail pads its own side and the page the others.
    val useRail = AdaptiveLayout.usesNavigationRail(LocalWindowWidth.current)
    Row(modifier = if (useRail) Modifier.fillMaxSize() else modifier.fillMaxSize()) {
        if (useRail) {
            LiftNavigationRail(
                labels = titles,
                selected = selectedTab,
                onSelect = { index ->
                    showCalculator = false
                    selectedTab = index
                }
            )
        }
        val page = Modifier.weight(1f).fillMaxHeight().then(
            if (useRail) {
                Modifier.windowInsetsPadding(
                    AppPageInsets.only(WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom)
                )
            } else {
                Modifier
            }
        )

        if (showCalculator) {
            MacroCalculatorScreen(
                modifier = page,
                onSaveGoal = { result ->
                    goalStore.save(result)
                    goal = result
                    showCalculator = false
                },
                onSaveProfile = { profile, weightLb ->
                    coachStore.profile = profile
                    coachStore.recordBodyweight(weightLb)
                }
            )
        } else {
            Column(modifier = page) {
                if (!useRail) {
                    // The labels are text: the readable rust (Material's default is
                    // `primary`, the fill, 3.1:1 on the dark surface). The indicator
                    // keeps the brand fill.
                    TabRow(selectedTabIndex = selectedTab, contentColor = dclAccentText()) {
                        titles.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = {
                                    Text(text = title, style = MaterialTheme.typography.labelLarge)
                                }
                            )
                        }
                    }
                }

                when (selectedTab) {
                    0 -> DashboardScreen(
                        goal = goal,
                        onOpenCalculator = { showCalculator = true },
                        onRestored = { goal = goalStore.get() }
                    )
                    1 -> TodayScreen(goal = goal)
                    2 -> CookScreen()
                    else -> WorkoutScreen()
                }
            }
        }
    }
}

/** The plain message for a recipe link that fills nothing ([RecipeLink.Opened]). */
@Composable
private fun RecipeLinkMessage(opened: RecipeLink.Opened, onDismiss: () -> Unit) {
    val body = recipeLinkMessage(opened) ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Couldn't read that recipe") },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onDismiss, colors = dclTextButtonColors()) { Text("OK") } }
    )
}

/**
 * The message for a link that fills nothing, or null for one that opens a recipe. The wording
 * every build shares, with {App} = LIFT: change it in every build or in none.
 */
internal fun recipeLinkMessage(opened: RecipeLink.Opened): String? = when (opened) {
    RecipeLink.Opened.Unreadable ->
        "That recipe link could not be read, so nothing was added. " +
            "Open the recipe page and tap Send to LIFT again."
    RecipeLink.Opened.NoRecipe ->
        "That page has no recipe LIFT can read. Copy the recipe text and use Paste a recipe instead."
    is RecipeLink.Opened.Review -> null
}
