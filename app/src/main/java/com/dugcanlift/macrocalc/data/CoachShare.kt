package com.dugcanlift.macrocalc.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.dugcanlift.kit.OutdoorShare
import com.dugcanlift.kit.OutdoorShareActivity
import com.dugcanlift.kit.ShareClient
import com.dugcanlift.kit.ShareDay
import com.dugcanlift.kit.ShareExercise
import com.dugcanlift.kit.ShareFood
import com.dugcanlift.kit.ShareGoal
import com.dugcanlift.kit.ShareLinkCodec
import com.dugcanlift.kit.SharePayload
import com.dugcanlift.kit.ShareSet
import com.dugcanlift.kit.ShareSide
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
        steps: Map<String, Long> = emptyMap(),
        outdoor: List<OutdoorActivity> = emptyList()
    ): String {
        val payload = buildSharePayload(store, settings, goal, sessions, entries, steps, outdoor)
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
        steps: Map<String, Long> = emptyMap(),
        outdoor: List<OutdoorActivity> = emptyList()
    ): Boolean {
        val link = buildLink(store, settings, goal, sessions, entries, steps, outdoor)
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
     * itemized food, foodTotals, outdoor activity, steps or bodyweight), so
     * pre-filtering here would shift every day's offset.
     *
     * Saturated fat, sugar and sodium follow SHARE-FORMAT's section of that
     * name: `fx` on any day with food that recorded one, `fe` beside an
     * itemised `f`. Both are built by the kit, as the screen's totals are.
     *
     * Outdoor follows SHARE-FORMAT "Outdoor": each day's finished runs, walks
     * and hikes as `o`, all-time bests as `ob`, and the newest route as `lr`
     * only when [CoachStore.sendLastRoute] is on. The numbers and the polyline
     * come from the kit's [OutdoorShare], because every sender has to produce
     * the same string. [nowMs] is only ever overridden by tests, so a fixture
     * recorded on a fixed date stays inside the window.
     */
    internal fun buildSharePayload(
        store: CoachStore,
        settings: SettingsStore,
        goal: MacroResult?,
        sessions: List<WorkoutSession>,
        entries: List<FoodEntry>,
        steps: Map<String, Long>,
        outdoor: List<OutdoorActivity> = emptyList(),
        nowMs: Long = System.currentTimeMillis()
    ): SharePayload {
        val span = store.weeks * 7
        val days = lastDays(span, nowMs)
        // Enforced here, not only in the card: whatever a caller passes, steps
        // from Health Connect leave the phone only with the person's opt-in.
        val sentSteps = stepsToSend(store, steps)
        val start = days.first()
        val weights = store.bodyweights()
        // Unfinished recordings go in too; OutdoorShare skips them itself.
        val outdoorShare = outdoor.map { it.toShareActivity() }
        val outdoorByDay = outdoor.indices.groupBy { dateKey(outdoor[it].startedAtEpochMs) }

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
                                isWarmup = false,
                                // SHARE-FORMAT: flags bits 1-2, beside bit 0's
                                // warmup flag. Null stays null -- a both-sided
                                // set sends 0 flags, which the codec trims away
                                // entirely, so a log with no per-limb sets is
                                // the link this app has always written.
                                side = when (set.side) {
                                    SetSide.LEFT -> ShareSide.LEFT
                                    SetSide.RIGHT -> ShareSide.RIGHT
                                    null -> null
                                }
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
                        meal = mealIndex(entry),
                        // `fe`: per serving like the macros, null when none recorded.
                        details = entry.details.takeIf { !it.isEmpty }
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

            val dayOutdoor = outdoorByDay[key]
                ?.let { indices -> OutdoorShare.day(indices.map { outdoorShare[it] }) }
                ?.takeIf { it.isNotEmpty() }

            ShareDay(
                dayOffset = offset,
                sessionName = sessionName,
                focus = if (hasExercises) settings.focus.name else null,
                bodyweightLb = weights[key],
                steps = sentSteps[key],
                exercises = exercises,
                foodTotals = foodTotals,
                food = food,
                outdoor = dayOutdoor,
                // `fx`, itemised or not: totals over only the foods that
                // recorded each, with the counts. Null -- no key -- when no
                // food that day recorded any of the three.
                nutrientTotals = dayEntries.nutrientTotals()
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
            endDay = dateKey(nowMs),
            exportedAtEpochSeconds = nowMs / 1000,
            days = shareDays,
            // All-time, not the window: a best set three months ago is still the best.
            outdoorBests = OutdoorShare.bests(outdoorShare),
            lastRoute = if (store.sendLastRoute) OutdoorShare.lastRoute(outdoorShare) else null
        )
    }

    private fun OutdoorActivity.toShareActivity() = OutdoorShareActivity(
        type = when (activityType) {
            OutdoorActivityType.RUN -> 0
            OutdoorActivityType.WALK -> 1
            OutdoorActivityType.HIKE -> 2
        },
        startedAtEpochMs = startedAtEpochMs,
        endedAtEpochMs = endedAtEpochMs,
        distanceMeters = distanceMeters,
        climbMeters = elevationGainMeters,
        route = routePoints.map { it.latitude to it.longitude }
    )

    private fun mealIndex(entry: FoodEntry): Int = when (entry.mealOrDefault) {
        Meal.BREAKFAST -> 0
        Meal.LUNCH -> 1
        Meal.DINNER -> 2
        Meal.SNACK -> 3
    }

    private fun stepsToSend(store: CoachStore, steps: Map<String, Long>): Map<String, Long> =
        if (store.sendSteps) steps else emptyMap()

    /* ---------- what the person is told before they send ---------- */

    /**
     * One sentence naming what the email carries, shown above Send to Coach so
     * the person knows before they tap it. It follows [buildSharePayload] item
     * for item and reads the same choices, so it cannot promise less than goes:
     * a profile field, bodyweight or goal is named only when there is one to
     * send, and steps only when [CoachStore.sendSteps] is on and Health Connect
     * can supply them ([stepsAvailable]).
     *
     * The lifter id is left out on purpose: it is a random tag that tells the
     * coach app two links came from the same person, and says nothing about them.
     */
    fun includedSummary(store: CoachStore, goal: MacroResult?, stepsAvailable: Boolean): String {
        val items = mutableListOf<String>()
        if (store.lifterName.isNotBlank()) items += "your name"
        val profile = store.profile
        val profileParts = listOfNotNull(
            "sex".takeIf { !profile?.sex.isNullOrBlank() },
            "age".takeIf { (profile?.age ?: 0) > 0 },
            "height".takeIf { (profile?.heightIn ?: 0.0) > 0 }
        )
        if (profileParts.isNotEmpty()) items += joinWithAnd(profileParts)
        if (store.bodyweights().isNotEmpty()) items += "bodyweight"
        if (goal != null) items += "your calorie and macro goal"
        items += "every workout set"
        items += if (store.itemisedFood) "every food you logged" else "daily food totals"
        items += "your runs, walks and hikes"
        if (store.sendLastRoute) items += "the trimmed map of your last route"
        if (store.sendSteps && stepsAvailable) items += "daily steps from Health Connect"
        // Semicolons between items, because items such as "sex, age and height"
        // carry their own commas.
        val list = if (items.size <= 2) joinWithAnd(items)
            else items.dropLast(1).joinToString("; ") + "; and " + items.last()
        return "The email includes $list."
    }

    private fun joinWithAnd(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
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

        val sentSteps = stepsToSend(store, steps)
        val weekSteps = week.mapNotNull { sentSteps[it] }
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

    private fun lastDays(count: Int, nowMs: Long = System.currentTimeMillis()): List<String> {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val calendar = Calendar.getInstance().apply { timeInMillis = nowMs }
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
