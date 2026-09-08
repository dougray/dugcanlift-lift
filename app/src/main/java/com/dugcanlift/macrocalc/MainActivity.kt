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
import androidx.compose.runtime.MutableState
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
import com.dugcanlift.macrocalc.data.PlanDecodeResult
import com.dugcanlift.macrocalc.data.PlanLinkCodec
import com.dugcanlift.macrocalc.ui.theme.DugCanLiftCalcTheme

class MainActivity : ComponentActivity() {
    private val pendingPlan = mutableStateOf<PlanDecodeResult?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handlePlanIntent(intent)
        setContent {
            DugCanLiftCalcTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppTabs(modifier = Modifier.padding(innerPadding), pendingPlan = pendingPlan)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep getIntent()/this.intent current for any other code in this Activity that reads it —
        // without this it would keep returning the intent the Activity was originally launched with.
        setIntent(intent)
        handlePlanIntent(intent)
    }

    private fun handlePlanIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        val fragment = uri.fragment ?: return
        val lifterId = CoachStore.get(this).lifterId
        pendingPlan.value = PlanLinkCodec.decode(fragment, lifterId)
    }
}

@Composable
private fun AppTabs(modifier: Modifier = Modifier, pendingPlan: MutableState<PlanDecodeResult?>) {
    val context = LocalContext.current
    val goalStore = remember { GoalStore.get(context) }
    val coachStore = remember { CoachStore.get(context) }

    var goal by remember { mutableStateOf(goalStore.get()) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // The calculator is a set-it-once screen, so it lives behind the dashboard
    // rather than taking a permanent slot in the navigation.
    var showCalculator by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = showCalculator) { showCalculator = false }

    pendingPlan.value?.let { result ->
        PlanPreviewDialog(result = result, onDismiss = { pendingPlan.value = null })
    }

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
