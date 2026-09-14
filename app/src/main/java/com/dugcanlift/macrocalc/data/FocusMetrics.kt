package com.dugcanlift.macrocalc.data

import com.dugcanlift.kit.trimZeros
import kotlin.math.roundToInt

/**
 * The numbers a [TrainingFocus] puts in front of you.
 *
 * Kept pure and free of Compose so it can be tested directly, the same way
 * `OutdoorActivityFormatting` is. The web app carries the identical rules in
 * `lift/app.js`; if one changes, the other changes with it, because a coach
 * reading a log should see the same summary the athlete did.
 */

/** Every set in a session, flattened. */
fun WorkoutSession.allSets(): List<WorkoutSet> = exercises.flatMap { it.sets }

fun WorkoutSession.totalSeconds(): Int = allSets().sumOf { it.durationSec ?: 0 }

fun WorkoutSession.totalMetres(): Double = allSets().sumOf { it.distanceMeters ?: 0.0 }

/**
 * The heaviest set that also has reps — "315 x 3" is the number a strength
 * session is remembered by, and a weight with no reps is not a set.
 */
fun WorkoutSession.topSet(): WorkoutSet? = allSets()
    .filter { it.weightLb != null && (it.reps ?: 0) > 0 }
    .maxByOrNull { it.weightLb ?: 0.0 }

/** mm:ss, or h:mm:ss once it runs past an hour. */
fun clockLabel(seconds: Int): String {
    val total = if (seconds < 0) 0 else seconds
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Metres are the stored unit; km reads better past a kilometre. */
fun distanceLabel(metres: Double): String =
    if (metres >= 1000) "%.2f km".format(metres / 1000) else "${metres.roundToInt()} m"

/**
 * The one line under a workout's name.
 *
 * Volume is the wrong answer for four of the six focuses: a Hyrox session's
 * number is metres and minutes, and a top single is the point of a
 * powerlifting day.
 *
 * Every branch falls back to the plain set count when the session holds
 * nothing of its kind, so an endurance focus over a weights-only session
 * reads "2 sets" rather than "2 sets - 0 m".
 */
fun focusSummary(session: WorkoutSession, focus: TrainingFocus): String {
    val sets = session.setCount
    val count = "$sets ${if (sets == 1) "set" else "sets"}"

    return when (focus.summary) {
        FocusSummary.TOP_SET -> session.topSet()
            ?.let { "$count - top ${it.weightLb?.trimZeros()} x ${it.reps}" } ?: count

        FocusSummary.WORK -> session.totalSeconds()
            .takeIf { it > 0 }
            ?.let { "$count - ${clockLabel(it)} working" } ?: count

        FocusSummary.DISTANCE -> {
            val parts = mutableListOf(count)
            session.totalMetres().takeIf { it > 0 }?.let { parts += distanceLabel(it) }
            session.totalSeconds().takeIf { it > 0 }?.let { parts += clockLabel(it) }
            parts.joinToString(" - ")
        }

        FocusSummary.VOLUME -> session.volumeLb
            .takeIf { it > 0 }
            ?.let { "$count - ${it.roundToInt()} lb volume" } ?: count
    }
}

/** Total of a numeric set field across one exercise. */
fun LoggedExercise.totalSeconds(): Int = sets.sumOf { it.durationSec ?: 0 }
fun LoggedExercise.totalMetres(): Double = sets.sumOf { it.distanceMeters ?: 0.0 }
fun LoggedExercise.totalReps(): Int = sets.sumOf { it.reps ?: 0 }
