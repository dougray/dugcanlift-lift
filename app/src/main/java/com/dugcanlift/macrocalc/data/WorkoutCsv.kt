package com.dugcanlift.macrocalc.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong

/**
 * Training history in and out as CSV: in from Strong, Hevy and FitNotes, out as LIFT's own
 * one-row-per-set file. A port of LIFT iPhone's `WorkoutCSV`, function for function, and
 * `WorkoutCsvTest` is a port of its `WorkoutCSVTests` against the same fixtures, copied unchanged.
 *
 * **None of the three apps publishes its export format.** Their help pages say only where the
 * export button is. Every header, delimiter and date shape here was read from real exports people
 * have published, and the fixtures are built from those shapes, not copied from a maker. So the
 * reader is strict about what it recognizes and says plainly when it does not: a file it cannot
 * place is refused, never half-read.
 *
 * **Weights become pounds on the way in**, like every weight this build stores (the iPhone stores
 * kilograms), rounded to the hundredth of a pound as [com.dugcanlift.macrocalc.watchlink.WatchPlanMapper]
 * rounds a converted weight. Hevy and FitNotes say their unit in the file. Strong's semicolon export
 * says "Weight (kg)". Strong's comma export says nothing at all, so the person importing it is
 * asked, because a guess is 2.2x wrong either way.
 */
object WorkoutCsv {

    const val KG_PER_LB = 0.45359237
    private const val METERS_PER_MILE = 1609.344

    enum class Unit { POUNDS, KILOGRAMS;
        fun toPounds(value: Double): Double = if (this == POUNDS) value else value / KG_PER_LB
    }

    data class ImportedSet(
        val weightLb: Double? = null,
        val reps: Int? = null,
        val rpe: Double? = null,
        val isWarmup: Boolean = false,
        val durationSec: Int? = null,
        val distanceMeters: Double? = null
    )

    data class ImportedExercise(
        val name: String,
        val equipment: String?,
        /** FitNotes' category. Read as the iPhone reads it; this build has nowhere to keep it. */
        val primaryMuscle: String?,
        val sets: List<ImportedSet>
    )

    data class ImportedWorkout(val date: LocalDateTime, val name: String, val exercises: List<ImportedExercise>)

    enum class Source(val label: String) { STRONG("Strong"), HEVY("Hevy"), FIT_NOTES("FitNotes") }

    class Failure(val reason: Reason) : Exception(reason.name) {
        enum class Reason { NOT_RECOGNIZED, EMPTY }
    }

    /**
     * What reading a file found, before anything is written. [needsUnit] is Strong's comma export,
     * whose numbers carry no unit: workouts cannot be built until the person says which.
     */
    class Reading(val source: Source, val needsUnit: Boolean, val parse: (Unit?) -> List<ImportedWorkout>)

    fun read(text: String): Reading {
        val rows = parse(text)
        val header = rows.firstOrNull()
        if (header == null || rows.size <= 1) throw Failure(Failure.Reason.EMPTY)
        val column = Columns(header.map { it.trim() })
        val body = rows.drop(1).filterNot { it.size == 1 && it[0].isEmpty() }

        if (column["Workout #"] != null && column["Set Order"] != null) {
            val kg = column["Weight (kg)"] != null
            return Reading(Source.STRONG, needsUnit = false) {
                strong(body, column, if (kg) Unit.KILOGRAMS else Unit.POUNDS, if (kg) "Weight (kg)" else "Weight (lbs)")
            }
        }
        if (column["Exercise Name"] != null && column["Set Order"] != null && column["Weight"] != null) {
            return Reading(Source.STRONG, needsUnit = true) { unit ->
                strong(body, column, unit ?: Unit.KILOGRAMS, "Weight")
            }
        }
        if (column["exercise_title"] != null && column["set_type"] != null) {
            return Reading(Source.HEVY, needsUnit = false) { hevy(body, column) }
        }
        if (column["Exercise"] != null && column["Category"] != null &&
            (column["Weight"] != null || column["Weight (kgs)"] != null || column["Weight (lbs)"] != null)
        ) {
            return Reading(Source.FIT_NOTES, needsUnit = false) { fitNotes(body, column) }
        }
        throw Failure(Failure.Reason.NOT_RECOGNIZED)
    }

    /* ---------- Strong ---------- */

    /**
     * One row per set, plus "Note" and "Rest Timer" rows that are not sets. `Set Order` is a number
     * for a working set, `W` a warm-up, `D` a drop set and `F` a set to failure; the last two are
     * working sets here.
     */
    private fun strong(rows: List<List<String>>, c: Columns, unit: Unit, weightColumn: String): List<ImportedWorkout> {
        val builder = Builder()
        for (row in rows) {
            val order = c.value(row, "Set Order")
            if (order == "Note" || order == "Rest Timer") continue
            val date = strongDate(c.value(row, "Date")) ?: continue
            val key = (if (c["Workout #"] != null) c.value(row, "Workout #") else "") + "|" + c.value(row, "Date") +
                "|" + c.value(row, "Workout Name")
            val (name, equipment) = splitEquipment(c.value(row, "Exercise Name"))
            val distanceColumn = if (c["Distance (meters)"] != null) "Distance (meters)" else "Distance"
            val set = ImportedSet(
                weightLb = positive(number(c.value(row, weightColumn)))?.let { pounds(unit.toPounds(it)) },
                reps = positive(number(c.value(row, "Reps")))?.toInt(),
                rpe = positive(number(c.value(row, "RPE"))),
                isWarmup = order == "W",
                durationSec = positive(number(c.value(row, "Seconds")))?.let { it.roundToLong().toInt() },
                distanceMeters = positive(number(c.value(row, distanceColumn)))
            )
            builder.add(set, key, date, c.value(row, "Workout Name"), name, equipment, muscle = null)
        }
        return builder.workouts
    }

    /* ---------- Hevy ---------- */

    private fun hevy(rows: List<List<String>>, c: Columns): List<ImportedWorkout> {
        val pounds = c["weight_lbs"] != null
        val miles = c["distance_miles"] != null
        val builder = Builder()
        for (row in rows) {
            val date = hevyDate(c.value(row, "start_time")) ?: continue
            val key = c.value(row, "title") + "|" + c.value(row, "start_time") + "|" + c.value(row, "end_time")
            val (name, equipment) = splitEquipment(c.value(row, "exercise_title"))
            val weight = positive(number(c.value(row, if (pounds) "weight_lbs" else "weight_kg")))
            val distance = positive(number(c.value(row, if (miles) "distance_miles" else "distance_km")))
            val set = ImportedSet(
                weightLb = weight?.let { pounds((if (pounds) Unit.POUNDS else Unit.KILOGRAMS).toPounds(it)) },
                reps = positive(number(c.value(row, "reps")))?.toInt(),
                rpe = positive(number(c.value(row, "rpe"))),
                isWarmup = c.value(row, "set_type").lowercase(Locale.US) == "warmup",
                durationSec = positive(number(c.value(row, "duration_seconds")))?.let { it.roundToLong().toInt() },
                distanceMeters = distance?.let { it * if (miles) METERS_PER_MILE else 1000.0 }
            )
            builder.add(set, key, date, c.value(row, "title"), name, equipment, muscle = null)
        }
        return builder.workouts
    }

    /* ---------- FitNotes ---------- */

    /** One workout a day, which is how FitNotes keeps them. Weight's unit is on every row, and one file can mix the two. */
    private fun fitNotes(rows: List<List<String>>, c: Columns): List<ImportedWorkout> {
        val builder = Builder()
        for (row in rows) {
            val day = c.value(row, "Date")
            val date = fitNotesDate(day) ?: continue
            val (weight, unit) = when {
                c["Weight Unit"] != null -> number(c.value(row, "Weight")) to
                    if (c.value(row, "Weight Unit").lowercase(Locale.US).startsWith("lb")) Unit.POUNDS else Unit.KILOGRAMS
                c["Weight (lbs)"] != null -> number(c.value(row, "Weight (lbs)")) to Unit.POUNDS
                else -> number(c.value(row, "Weight (kgs)")) to Unit.KILOGRAMS
            }
            val distance = positive(number(c.value(row, "Distance")))?.let {
                it * when (c.value(row, "Distance Unit").lowercase(Locale.US)) {
                    "km" -> 1000.0
                    "mi" -> METERS_PER_MILE
                    "ft" -> 0.3048
                    else -> 1.0
                }
            }
            val set = ImportedSet(
                weightLb = positive(weight)?.let { pounds(unit.toPounds(it)) },
                reps = positive(number(c.value(row, "Reps")))?.toInt(),
                durationSec = clockSeconds(c.value(row, "Time")),
                distanceMeters = distance
            )
            // FitNotes' category is a muscle group, except "Cardio", which is not.
            val category = c.value(row, "Category").lowercase(Locale.US)
            builder.add(set, day, date, "", c.value(row, "Exercise"), null,
                muscle = if (category.isEmpty() || category == "cardio") null else category)
        }
        return builder.workouts
    }

    /* ---------- pieces ---------- */

    /** Groups rows into workouts and exercises in the order they first appear. */
    private class Builder {
        private val order = mutableListOf<String>()
        private val byKey = mutableMapOf<String, ImportedWorkout>()

        fun add(set: ImportedSet, key: String, date: LocalDateTime, workoutName: String,
                exercise: String, equipment: String?, muscle: String?) {
            if (exercise.isEmpty()) return
            val workout = byKey.getOrPut(key) { order += key; ImportedWorkout(date, workoutName, emptyList()) }
            val last = workout.exercises.lastOrNull()
            val exercises = if (last != null && last.name == exercise && last.equipment == equipment) {
                workout.exercises.dropLast(1) + last.copy(sets = last.sets + set)
            } else {
                workout.exercises + ImportedExercise(exercise, equipment, muscle, listOf(set))
            }
            byKey[key] = workout.copy(exercises = exercises)
        }

        val workouts: List<ImportedWorkout> get() = order.mapNotNull { byKey[it] }.sortedBy { it.date }
    }

    private class Columns(names: List<String>) {
        private val index = mutableMapOf<String, Int>().apply {
            names.forEachIndexed { i, name -> putIfAbsent(name, i) }
        }
        operator fun get(name: String): Int? = index[name]
        fun value(row: List<String>, name: String): String {
            val i = index[name] ?: return ""
            return if (i < row.size) row[i].trim() else ""
        }
    }

    /**
     * "Bench Press (Barbell)" to ("Bench Press", "Barbell"), the shape Strong and Hevy both name an
     * exercise in. A name with no trailing bracket is left whole.
     */
    fun splitEquipment(text: String): Pair<String, String?> {
        val trimmed = text.trim()
        val open = trimmed.lastIndexOf('(')
        if (!trimmed.endsWith(")") || open <= 0) return trimmed to null
        val name = trimmed.substring(0, open).trim()
        val equipment = trimmed.substring(open + 1, trimmed.length - 1).trim()
        return if (name.isEmpty() || equipment.isEmpty()) trimmed to null else name to equipment
    }

    /**
     * Digits and a point only: every export seen writes a point whatever the phone's language, and
     * a comma read as a decimal would turn 1,000 m into one.
     */
    fun number(text: String): Double? {
        val t = text.trim()
        if (t.isEmpty() || !t.all { it.isDigit() || it == '.' }) return null
        return t.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    }

    /**
     * Zero is a blank here, where the iPhone stores a zero: this build's set fields are optional, and
     * "0.0" in a weight column is a bodyweight lift or an unused column, never a 0 lb load.
     */
    private fun positive(value: Double?): Double? = value?.takeIf { it > 0 }

    private fun pounds(value: Double): Double = (value * 100).roundToLong() / 100.0

    /** "0:01:30" or "1:30" to seconds. */
    fun clockSeconds(text: String): Int? {
        val parts = text.split(":").map { it.toIntOrNull() }
        if (text.isEmpty() || parts.any { it == null }) return null
        val seconds = parts.filterNotNull().fold(0) { acc, n -> acc * 60 + n }
        return seconds.takeIf { it > 0 }
    }

    private fun formatter(pattern: String) = DateTimeFormatter.ofPattern(pattern, Locale.US)
    private val strongFormat = formatter("yyyy-MM-dd HH:mm:ss")
    private val hevyFormats = listOf(formatter("d MMM yyyy, HH:mm"), formatter("MMM d, yyyy, h:mm a"))

    /**
     * Times have no zone in any of the three files, so they are read as the phone's own local time,
     * which is what each app wrote them in.
     */
    fun strongDate(text: String): LocalDateTime? = runCatching { LocalDateTime.parse(text, strongFormat) }.getOrNull()
    fun hevyDate(text: String): LocalDateTime? =
        hevyFormats.firstNotNullOfOrNull { runCatching { LocalDateTime.parse(text, it) }.getOrNull() }
    fun fitNotesDate(text: String): LocalDateTime? =
        runCatching { LocalDate.parse(text).atTime(12, 0) }.getOrNull()

    /**
     * RFC 4180, with the delimiter taken from the header (Strong's Android export uses `;`), quotes
     * doubled to escape, CR LF or LF, and a BOM dropped.
     */
    fun parse(text: String): List<List<String>> {
        val source = text.removePrefix("\uFEFF")
        val firstLine = source.substringBefore('\n')
        val delimiter = if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else ','
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < source.length) {
            val ch = source[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < source.length && source[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                } else field.append(ch)
            } else when (ch) {
                '"' -> quoted = true
                delimiter -> { row.add(field.toString()); field.clear() }
                '\n' -> { row.add(field.toString()); field.clear(); rows.add(row); row = mutableListOf() }
                '\r' -> Unit
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }

    /* ---------- LIFT's own export ---------- */

    val EXPORT_HEADER = listOf("Date", "Workout", "Exercise", "Equipment", "Set", "Warmup", "Side",
        "Weight (kg)", "Reps", "RPE", "Seconds", "Distance (m)")

    data class ExportRow(
        val dayKey: String,
        val workout: String,
        val exercise: String,
        val equipment: String?,
        val set: Int,
        val isWarmup: Boolean,
        val side: String?,
        val weightKg: Double?,
        val reps: Int?,
        val rpe: Double?,
        val seconds: Int?,
        val distanceMeters: Double?
    )

    /**
     * One row per set, kilograms always -- the iPhone's file, so one spreadsheet reads the same
     * whichever phone wrote it -- and blanks blank. A point for decimals whatever the phone's
     * language, so the file reads the same everywhere.
     */
    fun export(rows: List<ExportRow>): String {
        fun cell(s: String) =
            if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        fun num(d: Double?): String {
            if (d == null || !d.isFinite()) return ""
            if (d == Math.rint(d)) return d.toLong().toString()
            // Two places, then the trailing zeros and a bare point: 60.001 is "60", never "60.".
            return String.format(Locale.US, "%.2f", d).trimEnd('0').trimEnd('.')
        }
        val lines = mutableListOf(EXPORT_HEADER.joinToString(","))
        for (r in rows) {
            lines += listOf(r.dayKey, cell(r.workout), cell(r.exercise), cell(r.equipment ?: ""), r.set.toString(),
                if (r.isWarmup) "yes" else "", r.side ?: "", num(r.weightKg), r.reps?.toString() ?: "",
                num(r.rpe), r.seconds?.toString() ?: "", num(r.distanceMeters)).joinToString(",")
        }
        return lines.joinToString("\n") + "\n"
    }

    /** Every logged set, oldest day first. This build records no warm-up flag, so Warmup is always blank. */
    fun exportRows(sessions: List<WorkoutSession>): List<ExportRow> =
        sessions.sortedWith(compareBy<WorkoutSession> { it.date }.thenBy { it.startedAt }).flatMap { session ->
            session.exercises.flatMap { exercise ->
                exercise.sets.mapIndexed { index, set ->
                    ExportRow(
                        dayKey = session.date, workout = session.name, exercise = exercise.name,
                        equipment = exercise.equipment.ifBlank { null }, set = index + 1, isWarmup = false,
                        side = set.side?.wire, weightKg = set.weightLb?.let { it * KG_PER_LB }, reps = set.reps,
                        rpe = set.rpe, seconds = set.durationSec, distanceMeters = set.distanceMeters
                    )
                }
            }
        }

    /* ---------- into the log ---------- */

    data class Outcome(
        val daysAdded: Int = 0,
        val setsAdded: Int = 0,
        /** Days LIFT already had training on, left exactly as they were. */
        val daysSkipped: Int = 0,
        /** Warm-up sets the file marked, which this build has no flag for and so leaves out. */
        val warmupsLeftOut: Int = 0
    )

    data class Plan(val sessions: List<WorkoutSession>, val outcome: Outcome)

    /**
     * The sessions to write for [workouts]. **A day LIFT already has training on is left alone**,
     * said in the outcome, and never merged into: there is no telling an imported bench session
     * from the same one logged by hand, and a doubled day is worse than a missing one. That also
     * makes importing the same file twice a no-op.
     *
     * Two workouts on one day in the file (Hevy allows it) stay two sessions here, because a day on
     * this build can hold several, where the iPhone has one day and folds them into it.
     *
     * Warm-ups are left out and counted: this build has no warm-up flag, and a warm-up stored as a
     * working set would count in volume, the year and the muscles.
     */
    fun plan(workouts: List<ImportedWorkout>, existing: List<WorkoutSession>, zone: ZoneId = ZoneId.systemDefault()): Plan {
        val trained = existing.filter { it.exercises.isNotEmpty() }.map { it.date }.toSet()
        var outcome = Outcome()
        val sessions = mutableListOf<WorkoutSession>()
        val byDay = workouts.groupBy { it.date.toLocalDate().toString() }
        for (key in byDay.keys.sorted()) {
            val day = byDay.getValue(key).sortedBy { it.date }
            if (day.none { w -> w.exercises.any { it.sets.isNotEmpty() } }) continue
            if (key in trained) { outcome = outcome.copy(daysSkipped = outcome.daysSkipped + 1); continue }
            var added = false
            for (workout in day) {
                val exercises = workout.exercises.mapNotNull { imported ->
                    val sets = imported.sets.filterNot { it.isWarmup }.map {
                        WorkoutSet(weightLb = it.weightLb, reps = it.reps, rpe = it.rpe,
                            durationSec = it.durationSec, distanceMeters = it.distanceMeters)
                    }.filterNot { it.isEmpty }
                    outcome = outcome.copy(warmupsLeftOut = outcome.warmupsLeftOut + imported.sets.count { it.isWarmup })
                    if (sets.isEmpty()) null
                    else LoggedExercise(name = imported.name, equipment = imported.equipment ?: "", sets = sets)
                }
                if (exercises.isEmpty()) continue
                sessions += WorkoutSession(
                    id = UUID.randomUUID().toString(), date = key, name = workout.name, exercises = exercises,
                    startedAt = workout.date.atZone(zone).toInstant().toEpochMilli()
                )
                outcome = outcome.copy(setsAdded = outcome.setsAdded + exercises.sumOf { it.sets.size })
                added = true
            }
            if (added) outcome = outcome.copy(daysAdded = outcome.daysAdded + 1)
        }
        return Plan(sessions, outcome)
    }

    /** "Added 3 days (40 sets). 1 day you already had training on was left as it was." */
    fun summary(outcome: Outcome): String {
        val parts = mutableListOf(
            (if (outcome.daysAdded == 1) "Added 1 day" else "Added ${outcome.daysAdded} days") +
                (if (outcome.setsAdded == 1) " (1 set)." else " (${outcome.setsAdded} sets).")
        )
        if (outcome.daysSkipped > 0) {
            parts += if (outcome.daysSkipped == 1) "1 day you already had training on was left as it was."
            else "${outcome.daysSkipped} days you already had training on were left as they were."
        }
        warmupLine(outcome.warmupsLeftOut)?.let { parts += it }
        return parts.joinToString(" ")
    }

    /** Said before Import ([done] false) and after it, so leaving warm-ups out is never a surprise. */
    fun warmupLine(count: Int, done: Boolean = true): String? = when {
        count <= 0 -> null
        count == 1 -> "1 warm-up set ${if (done) "was" else "will be"} left out: LIFT for Android doesn't mark warm-ups."
        else -> "$count warm-up sets ${if (done) "were" else "will be"} left out: LIFT for Android doesn't mark warm-ups."
    }

    /** "2 workouts, 11 sets, Mar 2, 2026 to Mar 4, 2026." [day] says a date the reader's way. */
    fun describe(workouts: List<ImportedWorkout>, day: (LocalDate) -> String): String {
        val first = workouts.firstOrNull()?.date ?: return "No sets found in this file."
        val last = workouts.last().date
        val sets = workouts.sumOf { w -> w.exercises.sumOf { e -> e.sets.count { !it.isWarmup } } }
        val span = day(first.toLocalDate()) + " to " + day(last.toLocalDate())
        return "${if (workouts.size == 1) "1 workout" else "${workouts.size} workouts"}, " +
            "${if (sets == 1) "1 set" else "$sets sets"}, $span."
    }

    fun warmups(workouts: List<ImportedWorkout>): Int =
        workouts.sumOf { w -> w.exercises.sumOf { e -> e.sets.count { it.isWarmup } } }
}
