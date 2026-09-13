package com.dugcanlift.macrocalc.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.dugcanlift.kit.ShareClient
import com.dugcanlift.kit.ShareDay
import com.dugcanlift.kit.ShareExercise
import com.dugcanlift.kit.ShareFood
import com.dugcanlift.kit.ShareGoal
import com.dugcanlift.kit.ShareLinkCodec
import com.dugcanlift.kit.SharePayload
import com.dugcanlift.kit.ShareSet
import com.dugcanlift.macrocalc.MacroResult
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Turns a stretch of this phone's log into one link and hands it to the email
 * app, already addressed and written.
 *
 * Nothing is uploaded. The whole log rides in the fragment of the URL — the
 * part after '#', which browsers never send to a server — so the numbers go
 * from this phone, through the mail provider, to the coach's browser, and the
 * site that serves the coach app never sees them.
 *
 * The format is specified in the coach app's SHARE-FORMAT.md and is shared
 * with the iOS and web versions of LIFT. All four have to agree byte for byte,
 * so this file stays boring on purpose.
 */
object CoachShare {

    const val COACH_URL = "https://www.dugcanlift.com/coach/"

    val WINDOW_CHOICES = listOf(4, 8, 12, 26)

    /** Past this, some mail apps wrap the link and quietly corrupt it. */
    private const val RISKY_LINK_CHARS = 16_000

    /* ---------- public API ---------- */

    fun buildLink(
        store: CoachStore,
        settings: SettingsStore,
        goal: MacroResult?,
        sessions: List<WorkoutSession>,
        entries: List<FoodEntry>,
        steps: Map<String, Long> = emptyMap()
    ): String {
        val payload = buildSharePayload(store, settings, goal, sessions, entries, steps)
        return COACH_URL + "#" + ShareLinkCodec.encodeFragment(payload)
    }

    fun linkIsRisky(link: String): Boolean = link.length > RISKY_LINK_CHARS

    /**
     * Opens the phone's email app with everything filled in. ACTION_SENDTO on
     * a mailto: URI rather than ACTION_SEND, because SENDTO offers only email
     * apps — a chooser full of messaging apps that will mangle a 4 KB link is
     * not a choice worth offering.
     */
    fun sendEmail(
        context: Context,
        store: CoachStore,
        settings: SettingsStore,
        goal: MacroResult?,
        sessions: List<WorkoutSession>,
        entries: List<FoodEntry>,
        steps: Map<String, Long> = emptyMap()
    ): Boolean {
        val link = buildLink(store, settings, goal, sessions, entries, steps)
        val name = store.lifterName.ifBlank { "your client" }
        val subject = "LIFT log from $name - ${shortDate(todayKey())}"
        val summary = weekSummary(goal, sessions, entries, store, steps)

        val plain = buildString {
            appendLine("Open the log:")
            appendLine(link)
            appendLine()
            appendLine(summary)
            appendLine()
            append("Covers the last ${store.weeks} weeks. Sent from LIFT.")
        }

        val html = buildString {
            append("<p><a href=\"").append(link).append("\" ")
            append("style=\"display:inline-block;padding:12px 22px;background:#c1442c;")
            append("color:#f7f1e8;text-decoration:none;border-radius:999px;")
            append("font-family:sans-serif;font-weight:600\">")
            append("Open ").append(escapeHtml(name.substringBefore(' '))).append("'s log</a></p>")
            append("<pre style=\"font-family:sans-serif;font-size:14px\">")
            append(escapeHtml(summary))
            append("</pre>")
            append("<p style=\"color:#777;font-size:12px\">Covers the last ")
            append(store.weeks).append(" weeks. Sent from LIFT.<br>")
            append("If the button does nothing, copy this link:<br>")
            append(escapeHtml(link)).append("</p>")
        }

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(store.email))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, plain)
            // Gmail and a few others render this and drop the plain text.
            // Everything else ignores it, which is why both are supplied.
            putExtra(Intent.EXTRA_HTML_TEXT, html)
        }

        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /* ---------- payload ---------- */

    /**
     * Maps this device's stores into the kit's typed [SharePayload]. One
     * [ShareDay] per day in the window, unfiltered — [ShareLinkCodec] applies
     * the day-emission rule (a day survives only when it has exercises,
     * itemized food, foodTotals, steps or bodyweight), so pre-filtering here
     * would shift every day's offset.
     */
    private fun buildSharePayload(
        store: CoachStore,
        settings: SettingsStore,
        goal: MacroResult?,
        sessions: List<WorkoutSession>,
        entries: List<FoodEntry>,
        steps: Map<String, Long>
    ): SharePayload {
        val span = store.weeks * 7
        val days = lastDays(span)
        val start = days.first()
        val weights = store.bodyweights()

        val shareDays = days.mapIndexed { offset, key ->
            val dayExercises = sessions.filter { it.date == key }.flatMap { it.exercises }
            val hasExercises = dayExercises.isNotEmpty()

            val exercises = if (hasExercises) {
                dayExercises.map { exercise ->
                    ShareExercise(
                        name = exercise.name.trim(),
                        equipment = exercise.equipment.trim(),
                        sets = exercise.sets.map { set ->
                            ShareSet(
                                weightLb = set.weightLb,
                                reps = set.reps,
                                rpe = set.rpe,
                                durationSec = set.durationSec?.toDouble(),
                                distanceMeters = set.distanceMeters,
                                isWarmup = false
                            )
                        }
                    )
                }
            } else emptyList()

            val sessionName = if (hasExercises) {
                sessions.firstOrNull { it.date == key && it.name.isNotBlank() }?.name
            } else null

            val dayEntries = entries.filter { it.date == key }

            val food = if (store.itemisedFood && dayEntries.isNotEmpty()) {
                dayEntries.map { entry ->
                    ShareFood(
                        name = entry.name,
                        servings = entry.servings,
                        calories = entry.calories.toDouble(),
                        proteinG = entry.proteinG.toDouble(),
                        fatG = entry.fatG.toDouble(),
                        carbsG = entry.carbsG.toDouble(),
                        fiberG = entry.fiberG.toDouble(),
                        meal = mealIndex(entry)
                    )
                }
            } else null

            val foodTotals = if (!store.itemisedFood && dayEntries.isNotEmpty()) {
                val totals = dayEntries.totals()
                listOf(
                    totals.calories.toDouble(), totals.proteinG.toDouble(), totals.fatG.toDouble(),
                    totals.carbsG.toDouble(), totals.fiberG.toDouble()
                )
            } else null

            ShareDay(
                dayOffset = offset,
                sessionName = sessionName,
                focus = if (hasExercises) settings.focus.name else null,
                bodyweightLb = weights[key],
                steps = steps[key],
                exercises = exercises,
                foodTotals = foodTotals,
                food = food
            )
        }

        val profile = store.profile
        val client = ShareClient(
            id = store.lifterId,
            name = store.lifterName.ifBlank { "A LIFT user" },
            sex = profile?.sex?.takeIf { it.isNotBlank() },
            age = profile?.age?.takeIf { it > 0 },
            heightIn = profile?.heightIn?.takeIf { it > 0 },
            unit = "lb",
            platform = "and"
        )

        val shareGoal = goal?.let {
            ShareGoal(calories = it.calories, proteinG = it.proteinG, fatG = it.fatG, carbsG = it.carbsG, fiberG = it.fiberG)
        }

        return SharePayload(
            client = client,
            goal = shareGoal,
            startDay = start,
            endDay = todayKey(),
            exportedAtEpochSeconds = System.currentTimeMillis() / 1000,
            days = shareDays
        )
    }

    private fun mealIndex(entry: FoodEntry): Int = when (entry.mealOrDefault) {
        Meal.BREAKFAST -> 0
        Meal.LUNCH -> 1
        Meal.DINNER -> 2
        Meal.SNACK -> 3
    }

    /* ---------- the part the coach reads without tapping ---------- */

    fun weekSummary(
        goal: MacroResult?,
        sessions: List<WorkoutSession>,
        entries: List<FoodEntry>,
        store: CoachStore,
        steps: Map<String, Long> = emptyMap()
    ): String {
        val week = lastDays(7)
        val weekSessions = sessions.filter { it.date in week && it.exercises.isNotEmpty() }
        val trainingDays = weekSessions.map { it.date }.distinct().size
        val sets = weekSessions.sumOf { it.setCount }
        val volume = weekSessions.sumOf { it.volumeLb }

        val lines = mutableListOf("Last 7 days")
        lines.add(
            "Training   $trainingDays session${if (trainingDays == 1) "" else "s"} - $sets sets" +
                if (volume > 0) " - ${formatNumber(volume.roundToInt())} lb" else ""
        )

        val loggedDays = week.filter { day -> entries.any { it.date == day } }
        if (loggedDays.isEmpty()) {
            lines.add("Fuel       nothing logged this week")
        } else {
            val totals = entries.filter { it.date in loggedDays }.totals()
            val kcal = totals.calories / loggedDays.size
            val protein = totals.proteinG / loggedDays.size
            lines.add(
                "Fuel       ${formatNumber(kcal)} kcal - $protein g protein" +
                    (goal?.let { "  (goal ${formatNumber(it.calories)} - ${it.proteinG})" } ?: "") +
                    "  over ${loggedDays.size} logged day${if (loggedDays.size == 1) "" else "s"}"
            )
        }

        val weekSteps = week.mapNotNull { steps[it] }
        if (weekSteps.isNotEmpty()) {
            lines.add("Steps      ${formatNumber((weekSteps.sum() / weekSteps.size).toInt())} a day" +
                "  over ${weekSteps.size} day${if (weekSteps.size == 1) "" else "s"}")
        }

        store.bodyweights().maxByOrNull { it.key }?.let { (key, lb) ->
            lines.add("Weight     $lb lb on ${shortDate(key)}")
        }

        return lines.joinToString("\n")
    }

    /* ---------- encoding ---------- */

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /* ---------- dates ---------- */

    private fun lastDays(count: Int): List<String> {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -(count - 1))
        return (0 until count).map {
            val key = format.format(calendar.time)
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            key
        }
    }

    private fun shortDate(key: String): String = try {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(key) ?: Date()
        SimpleDateFormat("MMM d", Locale.getDefault()).format(parsed)
    } catch (e: Exception) {
        key
    }

    private fun formatNumber(value: Int): String =
        String.format(Locale.getDefault(), "%,d", value)
}
