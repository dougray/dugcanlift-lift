package com.dugcanlift.macrocalc.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.dugcanlift.macrocalc.MacroResult
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
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
        val json = buildPayload(store, settings, goal, sessions, entries, steps).toString()
        return COACH_URL + "#1z" + base64Url(deflateRaw(json))
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

    private fun buildPayload(
        store: CoachStore,
        settings: SettingsStore,
        goal: MacroResult?,
        sessions: List<WorkoutSession>,
        entries: List<FoodEntry>,
        steps: Map<String, Long>
    ): JSONObject {
        val span = store.weeks * 7
        val days = lastDays(span)
        val start = days.first()

        val exerciseDict = mutableListOf<String>()
        val foodDict = mutableListOf<String>()
        val weights = store.bodyweights()

        val dayArray = JSONArray()

        days.forEachIndexed { offset, key ->
            val day = JSONObject()
            var any = false

            val dayExercises = sessions.filter { it.date == key }.flatMap { it.exercises }
            if (dayExercises.isNotEmpty()) {
                any = true
                sessions.firstOrNull { it.date == key && it.name.isNotBlank() }
                    ?.let { day.put("n", it.name) }
                day.put("fo", settings.focus.name)

                val exercisesJson = JSONArray()
                dayExercises.forEach { exercise ->
                    val index = indexIn(exerciseDict, "${exercise.name.trim()}|${exercise.equipment.trim()}")
                    val setsJson = JSONArray()
                    exercise.sets.forEach { set -> setsJson.put(setTuple(set)) }
                    exercisesJson.put(JSONArray().put(index).put(setsJson))
                }
                day.put("w", exercisesJson)
            }

            val dayEntries = entries.filter { it.date == key }
            if (dayEntries.isNotEmpty()) {
                any = true
                if (store.itemisedFood) {
                    val foodJson = JSONArray()
                    dayEntries.forEach { entry ->
                        foodJson.put(
                            JSONArray()
                                .put(indexIn(foodDict, entry.name))
                                .put(entry.servings)
                                .put(entry.calories)
                                .put(entry.proteinG)
                                .put(entry.fatG)
                                .put(entry.carbsG)
                                .put(entry.fiberG)
                                .put(mealIndex(entry))
                        )
                    }
                    day.put("f", foodJson)
                } else {
                    val totals = dayEntries.totals()
                    day.put(
                        "ft",
                        JSONArray()
                            .put(totals.calories).put(totals.proteinG).put(totals.fatG)
                            .put(totals.carbsG).put(totals.fiberG)
                    )
                }
            }

            steps[key]?.let { day.put("st", it); any = true }
            weights[key]?.let { day.put("bw", it); any = true }

            if (any) {
                day.put("k", offset)
                dayArray.put(day)
            }
        }

        val client = JSONObject()
            .put("i", store.lifterId)
            .put("n", store.lifterName.ifBlank { "A LIFT user" })
            .put("u", "lb")
            .put("p", "and")
        store.profile?.let { profile ->
            if (profile.sex.isNotBlank()) client.put("s", profile.sex)
            if (profile.age > 0) client.put("a", profile.age)
            if (profile.heightIn > 0) client.put("h", profile.heightIn)
        }

        val payload = JSONObject()
            .put("v", 1)
            .put("c", client)
            .put("r", start)
            .put("t", todayKey())
            .put("z", System.currentTimeMillis() / 1000)
            .put("x", JSONArray(exerciseDict))
            .put("d", dayArray)

        goal?.let {
            payload.put(
                "g",
                JSONObject()
                    .put("c", it.calories).put("p", it.proteinG).put("f", it.fatG)
                    .put("cb", it.carbsG).put("fb", it.fiberG)
            )
        }
        if (foodDict.isNotEmpty()) payload.put("fd", JSONArray(foodDict))

        return payload
    }

    /**
     * [weight, reps, rpe, seconds, metres, flags] with trailing blanks dropped,
     * so an ordinary set costs eleven characters instead of sixty.
     */
    private fun setTuple(set: WorkoutSet): JSONArray {
        val values = mutableListOf<Any?>(
            set.weightLb, set.reps, set.rpe, set.durationSec, set.distanceMeters, 0
        )
        while (values.isNotEmpty() && (values.last() == null || values.last() == 0)) {
            values.removeAt(values.size - 1)
        }
        val array = JSONArray()
        values.forEach { if (it == null) array.put(JSONObject.NULL) else array.put(it) }
        return array
    }

    private fun indexIn(dict: MutableList<String>, value: String): Int {
        val at = dict.indexOf(value)
        if (at >= 0) return at
        dict.add(value)
        return dict.size - 1
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

    /**
     * Raw DEFLATE — no zlib wrapper. `nowrap = true` is what makes this the
     * same bytes as the browser's CompressionStream('deflate-raw') and iOS's
     * COMPRESSION_ZLIB, which is the whole reason one decoder can read all
     * three.
     */
    private fun deflateRaw(text: String): ByteArray {
        val input = text.toByteArray(Charsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        return try {
            deflater.setInput(input)
            deflater.finish()
            val buffer = ByteArray(8 * 1024)
            val out = java.io.ByteArrayOutputStream(input.size / 3)
            while (!deflater.finished()) {
                val written = deflater.deflate(buffer)
                out.write(buffer, 0, written)
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

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
