package com.dugcanlift.macrocalc

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.dugcanlift.macrocalc.data.LifterProfile

@Composable
fun MacroCalculatorScreen(
    modifier: Modifier = Modifier,
    onSaveGoal: (MacroResult) -> Unit = {},
    /**
     * The inputs behind the goal, so a coach reading a 2,400 kcal target knows
     * whose it is, and so the weight typed here becomes a dated reading rather
     * than a number that vanishes into the maths.
     */
    onSaveProfile: (LifterProfile, Double) -> Unit = { _, _ -> }
) {
    var sex by remember { mutableStateOf(Sex.MALE) }
    var age by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var heightFt by remember { mutableStateOf("") }
    var heightIn by remember { mutableStateOf("") }
    var activity by remember { mutableStateOf(Activity.MODERATE) }
    var goal by remember { mutableStateOf(Goal.MAINTAIN) }

    val result = remember(sex, age, weight, heightFt, heightIn, activity, goal) {
        val a = age.toIntOrNull()
        val w = weight.toDoubleOrNull()
        val ft = heightFt.toDoubleOrNull()
        val inch = heightIn.toDoubleOrNull() ?: 0.0
        val h = if (ft != null) ft * 12 + inch else null
        if (a != null && a in 13..100 && w != null && w > 0 && h != null && h > 0) {
            calculateMacros(sex, a, w, h, activity, goal)
        } else {
            null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Macro Calculator",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        ChipRow(
            options = Sex.entries,
            selected = sex,
            label = { it.label },
            onSelect = { sex = it }
        )

        Spacer(modifier = Modifier.height(16.dp))

        NumberField(value = age, onValueChange = { age = it }, label = "Age")

        Spacer(modifier = Modifier.height(12.dp))

        NumberField(value = weight, onValueChange = { weight = it }, label = "Weight (lb)")

        Spacer(modifier = Modifier.height(12.dp))

        NumberField(value = heightFt, onValueChange = { heightFt = it }, label = "Height (ft)")

        Spacer(modifier = Modifier.height(12.dp))

        NumberField(value = heightIn, onValueChange = { heightIn = it }, label = "Height (in)")

        Spacer(modifier = Modifier.height(20.dp))

        Text(text = "Activity", style = MaterialTheme.typography.labelLarge)

        Spacer(modifier = Modifier.height(8.dp))

        ChipRow(
            options = Activity.entries,
            selected = activity,
            label = { it.label },
            onSelect = { activity = it }
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(text = "Goal", style = MaterialTheme.typography.labelLarge)

        Spacer(modifier = Modifier.height(8.dp))

        ChipRow(
            options = Goal.entries,
            selected = goal,
            label = { it.label },
            onSelect = { goal = it }
        )

        Spacer(modifier = Modifier.height(28.dp))

        if (result != null) {
            ResultCard(result)
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    onSaveGoal(result)
                    val enteredAge = age.toIntOrNull()
                    val enteredWeight = weight.toDoubleOrNull()
                    val feet = heightFt.toDoubleOrNull()
                    if (enteredAge != null && enteredWeight != null && feet != null) {
                        val inches = feet * 12 + (heightIn.toDoubleOrNull() ?: 0.0)
                        onSaveProfile(
                            LifterProfile(
                                sex = sex.name.lowercase(),
                                age = enteredAge,
                                heightIn = inches
                            ),
                            enteredWeight
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save as my goal") }
        } else {
            Text(
                text = "Enter age, weight, and height to see your numbers.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(40.dp))

        Image(
            painter = painterResource(id = R.drawable.dcl_logo),
            contentDescription = "DUGCANLIFT",
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            contentScale = ContentScale.Fit
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}