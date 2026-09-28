package com.dugcanlift.macrocalc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dugcanlift.macrocalc.data.PlanLog
import com.dugcanlift.macrocalc.ui.theme.dclCardBorder

/**
 * What you were asked to do, and what you did — a coach's week beside the log that answers it.
 *
 * On Train, under the coach's own card for the day and above the routines. It is a **week** because
 * Train is one day at a time and cannot be moved past today: a booked Wednesday is invisible on
 * Thursday, and a booked Friday can be read nowhere else on the phone. A marker on the day screen
 * would only restate what the day screen already shows.
 *
 * Every sentence here is [PlanLog]'s, which the unit tests read as strings; this draws what it
 * returns and decides nothing of its own. **Nobody is graded**: every day row is the same weight and
 * the same colour whichever state it is in, there is no score, no percentage, no streak
 * and nothing carried from one week to the next.
 *
 * A day can also carry the **meals a coach booked** for it, under the training. Those are stated and
 * nothing more: no verdict per slot, no macros, and nothing at all about what was eaten -- see
 * [PlanLog]'s own head for why at length. A day nothing was logged against may have been a
 * day you trained and did not log, a day you were ill, or a day you were told to rest, and the
 * footer says in words that only the reader knows which.
 *
 * The one line that names anybody is the signature above the head, [PlanLog.sentBy]'s: a coach who
 * sent a plan with their name in it is named once, and nowhere else on the card does a name appear.
 *
 * Absent entirely when no plan books a day in the week on screen — the caller draws nothing at all
 * rather than an empty frame explaining itself.
 */
/* One block of the card, drawn as it always was and **said as one sentence**.
 *
 * Compose gives every `Text` its own accessibility node, so the six lines of a lift arrive as six
 * stops with the word that named the numbers two swipes behind them.
 * `clearAndSetSemantics` rather than `semantics(mergeDescendants = true)`: it leaves the subtree
 * with exactly one source of text, so there is no question of whether the drawn lines are still
 * announced beneath the sentence. Only ever used on a block of plain `Text`s -- it would clear an
 * action too. The sentence is [PlanLog]'s; this only decides that the block is one thing.
 */
private fun Modifier.saidAs(sentence: String): Modifier =
    clearAndSetSemantics { contentDescription = sentence }

/** A glyph drawn as an affordance is not a word. `▸` announced is junk; the state it stands for
 *  is on the row itself, as `stateDescription`. */
private fun Modifier.drawnOnly(): Modifier = clearAndSetSemantics { }

@Composable
fun PlanWeekCard(
    week: PlanLog.Result,
    /** [PlanLog.sentBy]'s line — "From Doug", or "From your coach" when no plan in the week named one. */
    sentBy: String,
    openDay: String?,
    /** The Monday of the nearest booked week either side, or null when the arrow has nothing behind it. */
    previousWeek: String?,
    nextWeek: String?,
    onStepWeek: (String) -> Unit,
    onOpenDay: (PlanLog.DayRow) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    Card(modifier = modifier, border = dclCardBorder()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // A coach's name is free text from somebody else's app, and this is the only line on the
            // card that carries one. Two lines and then an ellipsis: the sentence is [PlanLog]'s and
            // three platforms share it, so it is not shortened there -- but a name long enough to
            // push the week's own head off the screen is a layout problem, and a layout is where it
            // is answered.
            Text(
                text = sentBy,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // The arrows move between weeks a coach actually booked, never one week at a time: a
            // card that vanished on the way to an empty week would take its own arrows with it and
            // leave no way back. An arrow with nothing behind it is disabled rather than hidden, so
            // the row does not change shape as it is used.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // An arrow's name is its glyph unless it is given one, and `‹` is a quotation
                // mark. `enabled` carries "disabled" on its own; what it cannot carry is which
                // arrow this is.
                TextButton(onClick = { previousWeek?.let(onStepWeek) }, enabled = previousWeek != null) {
                    Text("‹", modifier = Modifier.saidAs("Previous booked week"))
                }
                Text(
                    text = week.head,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).saidAs(week.spokenHead)
                )
                TextButton(onClick = { nextWeek?.let(onStepWeek) }, enabled = nextWeek != null) {
                    Text("›", modifier = Modifier.saidAs("Next booked week"))
                }
            }

            week.days.forEach { day ->
                PlanWeekDay(day = day, open = day.key == openDay, onOpen = { onOpenDay(day) })
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = week.footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * One day of the week, and what it holds when it is the open one.
 *
 * Tapping it opens it — and moves Train to that day where Train can show it, which is where the
 * sets, the notes and the prescribed card for that day already live. A day still ahead cannot be
 * shown there, which is exactly why its prescription is printed here instead.
 */
@Composable
private fun PlanWeekDay(day: PlanLog.DayRow, open: Boolean, onOpen: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The row is one thing to a reader: its three clauses as one sentence, the
                // open/closed state it drew with a glyph said as a state rather than a character,
                // and -- where tapping it moves Train as well as opening it -- that too.
                //
                // **The two `Text`s carry no semantics of their own** (`drawnOnly`), so this
                // subtree has exactly one source of text however Compose splits it into nodes: a
                // `clickable` emits a node of its own around the row, and leaving the drawn text
                // in place beneath it left a reader hearing the `·` line after the sentence.
                .clickable(
                    onClickLabel = if (day.openable) "Open this day on Train" else "Open this day"
                ) { onOpen() }
                .semantics(mergeDescendants = true) {
                    contentDescription = day.spoken
                    if (day.hasDetail) {
                        stateDescription = if (open) "Expanded" else "Collapsed"
                    }
                },
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // The same weight and the same colour in all four states.
            Text(
                text = day.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.drawnOnly()
            )
            if (day.hasDetail) {
                Text(
                    text = if (open) "▾" else "▸",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.drawnOnly()
                )
            }
        }
        if (!open) return@Column
        day.exercises.forEach { PlanWeekExercise(it) }
        if (day.alsoLogged.isNotEmpty()) {
            Text(
                text = "Also logged",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )
            day.alsoLogged.forEach {
                Text(
                    text = it.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.saidAs(it.spoken)
                )
            }
        }
        // What a coach booked for this day to eat, under the training it sits beside. Stated and
        // nothing more -- no verdict per slot, no macros, and nothing at all about what was logged,
        // however much this device knows. PlanLog says at length why.
        if (day.meals.isNotEmpty()) {
            Text(
                text = "Meals",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )
            // The slot and the dish are MealRow's own two columns, so Breakfast and Dinner line up
            // with each other and this composes no sentence of its own. Wider than a set row's
            // label column, which holds "Asked" and not "Breakfast".
            day.meals.forEach { meal ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        text = meal.slotLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(88.dp)
                    )
                    Text(text = meal.detail, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun PlanWeekExercise(exercise: PlanLog.ExerciseLines) {
    Column(modifier = Modifier.padding(vertical = 4.dp).saidAs(exercise.spoken)) {
        Text(
            text = exercise.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        exercise.sideLine?.let { Muted(it) }
        exercise.countLine?.let { Muted(it) }
        exercise.asked?.let { PlanWeekSetRow(it) }
        exercise.logged?.let { PlanWeekSetRow(it) }
        // Under the pair, where it says what the two rows above it are.
        exercise.substitution?.let { Muted(it) }
    }
}

/**
 * One row of sets: "Asked   L 40 x 8 · 40 x 8   R 40 x 8".
 *
 * Both the groups and the suffix, always. "each side" is a clause on the ask rather than a set of
 * its own, and a row that drew the groups and dropped it would print a plan asking for half of what
 * it asks for — which is why [PlanLog.SetRow.text] puts them together and this never assembles them
 * itself.
 */
@Composable
private fun PlanWeekSetRow(row: PlanLog.SetRow) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "${row.label} ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = row.text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Muted(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
