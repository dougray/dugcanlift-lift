package com.dugcanlift.macrocalc

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.dugcanlift.macrocalc.data.GoalStore
import com.dugcanlift.macrocalc.ui.theme.DugCanLiftCalcTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DugCanLiftCalcTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppTabs(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun AppTabs(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val goalStore = remember { GoalStore.get(context) }

    // The goal lives here so saving it on the Calculator tab immediately
    // updates the Today tab, without either screen reaching into the other.
    var goal by remember { mutableStateOf(goalStore.get()) }
    var selectedTab by remember { mutableIntStateOf(0) }

    val titles = listOf("Calculator", "Today")

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
            0 -> MacroCalculatorScreen(
                onSaveGoal = { result ->
                    goalStore.save(result)
                    goal = result
                    selectedTab = 1
                }
            )
            else -> TodayScreen(goal = goal)
        }
    }
}
