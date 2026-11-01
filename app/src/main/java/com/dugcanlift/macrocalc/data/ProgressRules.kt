package com.dugcanlift.macrocalc.data

import com.dugcanlift.kit.DayKey
import com.dugcanlift.kit.trimZeros
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/*
 * The rules behind Train's year and muscle cards and Home's body weight card. A port of LIFT
 * iPhone's `ProgressRules.swift`, function for function, and `ProgressRulesTest` is a port of its
 * `ProgressRulesTests`, case for case. Values with no view in them, so the tests can pin what each
 * one says. Counting, never grading: a quiet week is a count of zero, said plainly, and nothing is
 * colored as a failure.
 */

/**
 * A year of training days as weeks of seven, oldest first, ending with the week holding `today`.
 * A day's level is from its sets: 0 none, 1 a few (1-9), 2 a session (10-19), 3 a big one (20 or
 * more). Fixed steps rather than quartiles of the year, so the same day reads the same next month.
 *
 * The iPhone counts working sets, leaving warm-ups out. This build records no warm-up flag, so
 * every logged set is a working set here.
 */
object TrainingYear {
    data class Day(
        val key: String,
        val date: LocalDate,
        val sets: Int,
        /** After today: drawn as nothing at all, never as a rest day. */
        val isFuture: Boolean
    ) {
        val level: Int get() = if (isFuture) 0 else level(sets)
    }

    fun level(sets: Int): Int = when {
        sets < 1 -> 0
        sets < 10 -> 1
        sets < 20 -> 2
        else -> 3
    }

    /**
     * [setsByDay] is keyed by day key. [count] columns, each Monday-first or Sunday-first as the
     * reader's calendar is ([firstDayOfWeek]).
     */
    fun weeks(
        setsByDay: Map<String, Int>,
        today: LocalDate,
        count: Int = 53,
        firstDayOfWeek: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    ): List<List<Day>> {
        val thisWeek = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        val first = thisWeek.minusWeeks((count - 1).toLong())
        return (0 until count).map { week ->
            (0 until 7).map { offset ->
                val date = first.plusDays((week * 7 + offset).toLong())
                val key = date.toString()
                Day(key = key, date = date, sets = setsByDay[key] ?: 0, isFuture = date.isAfter(today))
            }
        }
    }

    /** Days with any set in the 365 days up to and including today. */
    fun trainingDays(setsByDay: Map<String, Int>, today: LocalDate): Int {
        val start = today.minusDays(364)
        return setsByDay.count { (key, sets) ->
            val date = DayKey.parse(key) ?: return@count false
            sets > 0 && !date.isBefore(start) && !date.isAfter(today)
        }
    }

    /** Sets per day key, every session on a day added together. */
    fun setsByDay(sessions: List<WorkoutSession>): Map<String, Int> =
        sessions.groupBy { it.date }.mapValues { (_, day) -> day.sumOf { it.setCount } }

    /** The card's line: "1 training day in the last 12 months", "N training days ...". */
    fun totalLine(days: Int): String =
        if (days == 1) "1 training day in the last 12 months" else "$days training days in the last 12 months"
}

/**
 * Sets per primary muscle over the calendar week holding `today`. A muscle comes from the exercise
 * as logged, and failing that from the exercise library by name and equipment, then by name
 * alone. An exercise nobody can place is counted as unplaced, said in words, rather than guessed
 * into a muscle.
 *
 * This build's logged exercise carries no muscle of its own, so [Logged.primaryMuscle] is null
 * for everything logged here and the library decides; the rule is kept whole so it reads as the
 * iPhone's does.
 */
object MuscleWeek {
    data class Logged(
        val name: String,
        val equipment: String?,
        val primaryMuscle: String?,
        val workingSets: Int
    )

    data class Trained(val muscle: String, val sets: Int)

    data class Result(
        /** Muscle and sets, most first, names breaking a tie. */
        val trained: List<Trained>,
        /** Every muscle the library knows that got no set this week. */
        val untrained: List<String>,
        val unplacedSets: Int
    )

    /** `"name|equipment"`, trimmed and lowercased: [LoggedExercise.matchKey]'s spelling. */
    fun key(name: String, equipment: String?): String =
        "${name.trim()}|${(equipment ?: "").trim()}".lowercase(Locale.US)

    /**
     * [lookup] maps [key] to a muscle, and `name|` (no equipment) as the fallback an exercise with
     * no equipment, or equipment the library spells differently, needs.
     */
    fun tally(logged: List<Logged>, lookup: Map<String, String>, allMuscles: List<String>): Result {
        val sets = mutableMapOf<String, Int>()
        var unplaced = 0
        for (entry in logged) {
            if (entry.workingSets <= 0) continue
            val muscle = entry.primaryMuscle?.takeIf { it.isNotEmpty() }
                ?: lookup[key(entry.name, entry.equipment)]
                ?: lookup[key(entry.name, null)]
            if (muscle != null) {
                val k = muscle.lowercase(Locale.US)
                sets[k] = (sets[k] ?: 0) + entry.workingSets
            } else {
                unplaced += entry.workingSets
            }
        }
        val trained = sets.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { Trained(it.key, it.value) }
        val untrained = (allMuscles.map { it.lowercase(Locale.US) }.toSet() - sets.keys).sorted()
        return Result(trained, untrained, unplaced)
    }

    /** The library as [tally]'s lookup: name and equipment, and the first muscle seen for a name. */
    fun lookup(library: List<LibraryExercise>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (record in library) {
            if (record.muscle.isEmpty()) continue
            map[key(record.name, record.storedEquipment)] = record.muscle
            map.putIfAbsent(key(record.name, null), record.muscle)
        }
        return map
    }

    /** Every muscle the lookup can name, sorted. */
    fun allMuscles(lookup: Map<String, String>): List<String> =
        lookup.values.map { it.lowercase(Locale.US) }.toSet().sorted()

    /** The first day of the calendar week holding [today], as the reader's calendar starts it. */
    fun weekStart(
        today: LocalDate,
        firstDayOfWeek: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    ): LocalDate = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))

    /** This week's exercises, from its first day to today, as [tally] reads them. */
    fun loggedThisWeek(sessions: List<WorkoutSession>, today: LocalDate, weekStart: LocalDate): List<Logged> {
        val from = weekStart.toString()
        val to = today.toString()
        return sessions.filter { it.date in from..to }.flatMap { it.exercises }.map {
            Logged(name = it.name, equipment = it.equipment, primaryMuscle = null, workingSets = it.sets.size)
        }
    }

    /** "Middle back" from "middle back": the iPhone's `capitalized`, first letter of each word. */
    fun display(muscle: String): String =
        muscle.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        }

    fun unplacedLine(sets: Int): String? = when {
        sets <= 0 -> null
        sets == 1 -> "1 set is from an exercise LIFT can't place on a muscle."
        else -> "$sets sets are from exercises LIFT can't place on a muscle."
    }
}

/**
 * The body weight card's line: the latest weigh-in, the change over the period on screen, and how
 * far from a goal, if one is set. Pounds, like every weight this build stores and shows -- the
 * iPhone converts to the reader's unit here, and this build has no unit to convert to.
 */
object BodyWeightTrend {
    data class Point(val date: LocalDate, val weightLb: Double)

    /** How far back the card and its chart reach. */
    const val DAYS = 90

    /**
     * The weigh-ins of the last [DAYS] days, oldest first. One a day already: the store is keyed by
     * day, so logging again replaces that day's ([CoachStore.recordBodyweight]), which is the
     * iPhone's "the last of that day". A non-positive or non-finite reading is not a weigh-in.
     */
    fun points(weights: Map<String, Double>, today: LocalDate): List<Point> {
        val since = today.minusDays(DAYS.toLong())
        return weights.mapNotNull { (key, lb) ->
            val date = DayKey.parse(key) ?: return@mapNotNull null
            if (!lb.isFinite() || lb <= 0 || date.isBefore(since) || date.isAfter(today)) null else Point(date, lb)
        }.sortedBy { it.date }
    }

    private fun tenth(value: Double): Double = (value * 10).roundToLong() / 10.0

    private fun show(lb: Double): String = tenth(lb).trimZeros()

    /** [day] says a date the reader's way ("Aug 1"); the screen passes the locale's own. */
    fun summary(points: List<Point>, goalLb: Double?, day: (LocalDate) -> String): String? {
        val latest = points.lastOrNull() ?: return null
        val parts = mutableListOf("${show(latest.weightLb)} lb")
        val first = points.first()
        if (points.size > 1) {
            val change = latest.weightLb - first.weightLb
            val rounded = tenth(abs(change))
            parts += if (rounded == 0.0) "no change since ${day(first.date)}"
            else "${if (change > 0) "up" else "down"} ${rounded.trimZeros()} since ${day(first.date)}"
        }
        if (goalLb != null && goalLb > 0) {
            val gap = tenth(abs(latest.weightLb - goalLb))
            parts += if (gap == 0.0) "at your goal" else "${gap.trimZeros()} from your goal of ${show(goalLb)}"
        }
        return parts.joinToString(" · ")
    }
}
