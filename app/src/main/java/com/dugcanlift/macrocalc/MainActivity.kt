package com.dugcanlift.macrocalc

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.dugcanlift.macrocalc.data.GoalStore
import com.dugcanlift.macrocalc.ui.theme.DugCanLiftCalcTheme

class MainActivity : ComponentActivity() {
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
        // Lets the ongoing route-recording notification (LocationRecordingService)
        // bring the user back to the Train tab instead of leaving a live
        // recording with no way back into its UI — see M-22 in the
        // final-review fix wave.
        pendingOpenTab.value = extractOpenTab(intent)
        setContent {
            DugCanLiftCalcTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppTabs(modifier = Modifier.padding(innerPadding), openTab = pendingOpenTab)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOpenTab.value = extractOpenTab(intent)
    }

    private fun extractOpenTab(intent: Intent?): Int? =
        intent?.getIntExtra(EXTRA_OPEN_TAB, -1)?.takeIf { it >= 0 }

    companion object {
        /** Int extra naming the tab index [AppTabs] should open on launch. */
        const val EXTRA_OPEN_TAB = "com.dugcanlift.macrocalc.EXTRA_OPEN_TAB"

        /** Index of the Train tab within [AppTabs]'s tab order (`titles` there), for [EXTRA_OPEN_TAB] callers. */
        const val TRAIN_TAB_INDEX = 3
    }
}

@Composable
private fun AppTabs(modifier: Modifier = Modifier, openTab: State<Int?> = remember { mutableStateOf(null) }) {
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

    if (showCalculator) {
        MacroCalculatorScreen(
            modifier = modifier,
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
        return
    }

    val titles = listOf("Home", "Food", "Cook", "Train")

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
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

        when (selectedTab) {
            0 -> DashboardScreen(
                goal = goal,
                onOpenCalculator = { showCalculator = true }
            )
            1 -> TodayScreen(goal = goal)
            2 -> CookScreen()
            else -> WorkoutScreen()
        }
    }
}
