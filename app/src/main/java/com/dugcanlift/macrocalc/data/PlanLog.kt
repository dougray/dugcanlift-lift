package com.dugcanlift.macrocalc.data

import com.dugcanlift.kit.DayKey
import com.dugcanlift.kit.trimZeros
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * What you were asked to do, and what you did.
 *
 * A coach's plan and this log have always been two separate records on this device — the
 * [ScheduledSession]s and [Routine]s a plan link wrote are what was *sent*, the [WorkoutSession]s
 * are what *happened* — and they have been shown side by side nowhere. Train shows one day at a
 * time and **Next is disabled past today**, so a booked Wednesday is invisible on Thursday and a
 * booked Friday cannot be looked at anywhere on the phone. That is why this is a **week** rather
 * than a marker on the day screen: a marker would only restate what the day screen already shows,
 * and the days a lifter cannot reach are the ones they most need to see mid-week.
 *
 * A port of LIFT web's `lift/plan-log.js`, function for function, the way [SideBalance] is a port
 * of `lift/sides.js` and [RoadFood] of `lift/road-food.js`: **the rule changes there first and is
 * ported again**, never improved here, because three platforms describing one week differently is
 * worse than any of them describing it slightly better. Pure and free of Android so the unit tests
 * read the sentences it produces rather than an eye reading one render of them.
 *
 * **Nothing new travels.** No wire change, no new file, no new permission — `n` is a head key
 * PLAN-FORMAT has always carried and this build simply dropped on the floor. One thing is newly
 * *stored*: [ScheduledSession.fromCoach], the coach a booking came from, which is what lets this card
 * sign a week the way the browser signs it. It sits on the booking and never on a session, because a
 * booking is a file that stays on the device and a session is one that travels.
 *
 * **You are not being graded.** No score, no percentage, no streak, no colour on a day nothing was
 * logged against, and nothing carried from one week to the next. [lines] flattens every sentence
 * the card can produce so `PlanLogTest` can hold the whole screen to that rule as strings. The
 * house discipline — tracked and shown, never targeted — applies here with more force than
 * anywhere else in the app, because this is the screen most likely to drift into nagging.
 *
 * **The words are Coach's, where the fact is the same one.** `logged`, `not logged`, `not booked`,
 * `Asked` / `Logged`, the side counts and the count line are Coach web's and Coach Android's
 * wording unchanged, so a lifter and their coach describe one week the same way. Two of Coach's
 * are deliberately absent:
 *
 *  - `outside the log they sent` — Coach's fourth state, which exists because a client sends a
 *    window and a booked day can fall outside it. This log is right here; the state cannot arise.
 *  - Coach's footer, "whether it arrived, and whether they opened it, only they know", is a
 *    sentence about somebody else. [FOOTER] is that thought pointed the other way.
 *
 * And one state is new, because only the person living the week has it: **`to do`**. A booked day
 * that has not happened yet is not an absence, and calling it one would be the app inventing a
 * failure out of a Wednesday.
 *
 * The side counts are [PerSideLogging]'s own — the same `L 3/3 · R 2/3` the session header has
 * shown since per-side prescriptions shipped, called with the same arguments — so this card and
 * the session it describes cannot disagree about a limb.
 */
object PlanLog {

    /* ---------------- dates ----------------
     *
     * Monday. A coach writes weeks, so a rolling seven days would move a booked Tuesday out of
     * "this week" overnight and describe one plan two ways on two consecutive days. Fixed here as
     * a constant rather than read from a locale, so the week a lifter sees is the week their coach
     * wrote wherever either of them happens to be — LIFT web's `WEEK_STARTS_ON`. */
    private val WEEK_STARTS_ON = DayOfWeek.MONDAY

    private fun shiftKey(key: String, days: Int): String =
        runCatching { DayKey.adding(days, key) }.getOrDefault(key)

    /** The Monday and Sunday of the week containing [key]. */
    fun weekOf(key: String): Pair<String, String> {
        val date = DayKey.parse(key) ?: return key to key
        val back = (date.dayOfWeek.value - WEEK_STARTS_ON.value + 7) % 7
        val from = shiftKey(key, -back)
        return from to shiftKey(from, 6)
    }

    /**
     * "Oct", in the reader's own language — the locale is theirs, as web's
     * `toLocaleDateString(undefined, …)` and Coach Android's `PlanLog` both leave it. Written
     * day-then-month by hand rather than through a localised date pattern, because "12–18 Oct" has
     * to read as a range and a US pattern would put the month in the middle of it.
     */
    private fun monthName(key: String): String =
        DayKey.parse(key)?.month?.getDisplayName(TextStyle.SHORT, Locale.getDefault()).orEmpty()

    private fun weekdayName(key: String): String =
        DayKey.parse(key)?.dayOfWeek?.getDisplayName(TextStyle.SHORT, Locale.getDefault()).orEmpty()

    private fun dayOf(key: String): Int = DayKey.parse(key)?.dayOfMonth ?: 0

    /** "13 Oct". */
    fun dayMonth(key: String): String = "${dayOf(key)} ${monthName(key)}"

    /**
     * "Mon 13 Oct". A week can cross a month, and a bare "Mon 13" above a "Sun 5" says nothing
     * about which. Coach's label, unchanged.
     */
    fun dayLabel(key: String): String = "${weekdayName(key)} ${dayMonth(key)}"

    /** "12–18 Oct", "28 Sep–4 Oct", "12 Oct" for a single day. */
    fun rangeText(from: String, to: String): String {
        if (from == to) return dayMonth(from)
        val a = DayKey.parse(from)
        val b = DayKey.parse(to)
        if (a != null && b != null && a.year == b.year && a.month == b.month) {
            return "${dayOf(from)}–${dayMonth(to)}"
        }
        return "${dayMonth(from)}–${dayMonth(to)}"
    }

    private fun plural(n: Int, one: String, many: String): String = "$n ${if (n == 1) one else many}"

    /* ---------------- what was asked for ----------------
     *
     * One day's booking: the [ScheduledSession]s on a date, and the [Routine] each names.
     *
     * **The ask is read from the stored plan, never from the logged session.** This is the one
     * place the port had to look at Android's own storage rather than web's: [Routine.toSession]
     * copies a prescription's numbers into the session's sets and carries `prescribed` onto the
     * [LoggedExercise] *only when it says something about sides*, so the moment a lifter edits a
     * set the session no longer says what was asked for, and for a ramp it never did. The routine a
     * booking names still holds every set the coach wrote. Web reads its own `training` for the
     * same reason.
     *
     * A booking whose routine has since been deleted keeps its name — [ScheduledSession] snapshots
     * `routineName` — and asks for nothing, rather than vanishing from a week the coach did book. */

    /** One booked day: what it is called, who sent it, and the exercises the plan behind it asks for. */
    data class Booking(
        val date: String,
        val name: String,
        val exercises: List<RoutineExercise>,
        /** The coach who sent it, when the plan link named one. Read by [sentBy] and nothing else. */
        val fromCoach: String? = null
    )

    /**
     * The bookings a plan has written, each resolved against the routine it names.
     *
     * Pure, so `PlanLogTest` reaches it: which routine answers which booking is part of the rule,
     * not of a composable.
     */
    fun bookings(scheduled: List<ScheduledSession>, routines: List<Routine>): List<Booking> =
        scheduled.filter { it.date.isNotBlank() }.map { session ->
            Booking(
                date = session.date,
                name = session.routineName,
                exercises = routines.firstOrNull { it.id == session.routineId }?.exercises.orEmpty(),
                fromCoach = session.fromCoach
            )
        }

    /* ---------------- the two halves in one shape ---------------- */

    fun exerciseKey(name: String?, equipment: String?): String =
        (name.orEmpty().trim() + "|" + equipment.orEmpty().trim()).lowercase(Locale.US)

    private fun nameKey(name: String?): String = name.orEmpty().trim().lowercase(Locale.US)

    /** A lift the plan asks for, its sets pooled. */
    data class AskedLift(
        val key: String,
        val name: String,
        val equipment: String,
        val eachSide: Boolean,
        val sets: List<PrescribedSet>
    )

    /** A lift the log holds, its sets pooled. */
    data class LoggedLift(
        val key: String,
        val name: String,
        val equipment: String,
        val sets: List<WorkoutSet>
    )

    /**
     * Exercises pooled by `name|equipment`, first-seen order kept. The same lift asked for twice in
     * a day, or logged twice in a day, is one lift of more sets — Coach pools both sides the same
     * way, and has to, because a share link merges a day's sessions before Coach ever sees them.
     *
     * "Each side" is a property of the lift, not of one booking of it, so any booking saying it
     * says it for the pool.
     */
    private fun poolAsked(exercises: List<RoutineExercise>): List<AskedLift> {
        val order = LinkedHashMap<String, AskedLift>()
        exercises.forEach { ex ->
            val key = exerciseKey(ex.name, ex.equipment)
            val held = order[key]
            order[key] = AskedLift(
                key = key,
                name = held?.name ?: ex.name,
                equipment = held?.equipment ?: ex.equipment,
                eachSide = (held?.eachSide ?: false) || ex.eachSide,
                // askedSets, never the flattened targets alone: a ramp of 225/225/245 read off
                // targetWeightLb is three 225s, a plan the coach never wrote.
                sets = held?.sets.orEmpty() + ex.askedSets
            )
        }
        return order.values.toList()
    }

    /**
     * The exercises of some sessions, pooled.
     *
     * Web filters warmups out of both halves here. **This build has no warmup flag at all** —
     * [WorkoutSet] has no such field, the backup never wrote one and `CoachShare` sends flags 0, 2
     * or 4 — so every logged set is a working set and there is nothing to exclude. Should a warmup
     * ever be recorded on this platform, this is the function that has to drop it, on both halves,
     * masked and never compared.
     */
    fun loggedIn(sessions: List<WorkoutSession>): List<LoggedLift> {
        val order = LinkedHashMap<String, LoggedLift>()
        sessions.flatMap { it.exercises }.forEach { ex ->
            val key = exerciseKey(ex.name, ex.equipment)
            val held = order[key]
            order[key] = LoggedLift(
                key = key,
                name = held?.name ?: ex.name,
                equipment = held?.equipment ?: ex.equipment,
                sets = held?.sets.orEmpty() + ex.sets
            )
        }
        return order.values.toList()
    }

    /* ---------------- how a set reads ---------------- */

    private fun formatDuration(seconds: Int): String =
        if (seconds >= 60) "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        else "${seconds}s"

    /**
     * One set's numbers, asked or logged, or null when the set gave none.
     *
     * PLAN-FORMAT's set tuple and SHARE-FORMAT's are deliberately the same six fields "so nothing
     * has to be transposed to compare what was asked for against what was done", and one row
     * sitting above the other is what that was written for — so **both rows are printed by this**,
     * and never one by this and one by something that orders its fields differently. The session
     * card's own set rows read it too, for the same reason: a set has to look the same here and
     * there, or the card would be comparing two sentences rather than two sets.
     *
     * The field order is this app's existing one (`@8` before the clock), not web's, because the
     * logged rows under this card have printed it that way since long before the card existed.
     *
     * Weights are pounds, the only unit this build stores or shows. The side is deliberately not
     * here: [setGroups] groups sets by side, and a set carrying its own "L" inside a group already
     * labelled "L" would say it twice. The flat lists on the session and prescribed rows add it.
     *
     * Blank stays blank: five reps with no weight is "5 reps", never "0 x 5".
     */
    fun setNumbers(
        weightLb: Double?,
        reps: Int?,
        rpe: Double?,
        durationSec: Int?,
        distanceMeters: Double?
    ): String? {
        val parts = mutableListOf<String>()
        if (weightLb != null && reps != null) parts += "${weightLb.trimZeros()} x $reps"
        else {
            weightLb?.let { parts += "${it.trimZeros()} lb" }
            reps?.let { parts += "$it reps" }
        }
        rpe?.let { parts += "@${it.trimZeros()}" }
        durationSec?.let { parts += formatDuration(it) }
        distanceMeters?.let { parts += "${it.trimZeros()} m" }
        return parts.joinToString(" ").ifEmpty { null }
    }

    /** [setNumbers] for a prescribed set. A set the coach gave nothing for is "as written". */
    fun setText(set: PrescribedSet): String =
        setNumbers(set.weightLb, set.reps, set.rpe, set.durationSec, set.distanceMeters) ?: "as written"

    /** [setNumbers] for a logged set. */
    fun setText(set: WorkoutSet): String =
        setNumbers(set.weightLb, set.reps, set.rpe, set.durationSec, set.distanceMeters) ?: "as written"

    /** One group of sets on screen: a side and its sets, or an unlabelled group when nothing is sided. */
    data class SetGroup(val label: String, val text: String)

    /**
     * A row of sets: the groups, and the clause that applies to all of them.
     *
     * [suffix] is a field rather than glued onto the last group's text, because "each side" is a
     * clause on the ask and not a set of its own — a view that draws the groups and forgets the
     * clause prints a plan asking for half of what it asks for.
     */
    data class SetRow(val label: String, val groups: List<SetGroup>, val suffix: String) {
        val text: String get() = groupsText(groups) + suffix
    }

    private val SERIES = listOf(SetSide.LEFT, SetSide.RIGHT, null)

    private fun <T> groupsOf(sets: List<T>, side: (T) -> SetSide?, text: (T) -> String): List<SetGroup> {
        if (sets.none { side(it) != null }) {
            if (sets.isEmpty()) return emptyList()
            return listOf(SetGroup("", sets.joinToString(" · ", transform = text)))
        }
        return SERIES.mapNotNull { want ->
            val mine = sets.filter { side(it) == want }
            if (mine.isEmpty()) null
            else SetGroup(want?.short ?: "Both", mine.joinToString(" · ", transform = text))
        }
    }

    /**
     * Sets as one group per side — "L 40 x 8 · 40 x 8 · 40 x 5" beside "R 40 x 8 · 40 x 8" — or a
     * single unlabelled group when nothing is sided.
     *
     * Sets are listed, never paired one to one with the row above. If three of four sets came back,
     * nothing here can say which one was dropped, so nothing here says. Sets logged before per-side
     * logging are a real third group labelled "Both" beside the two limbs, as the session header's
     * own "· 2 both" counts them.
     */
    fun askedGroups(sets: List<PrescribedSet>): List<SetGroup> = groupsOf(sets, { it.side }, ::setText)

    fun loggedGroups(sets: List<WorkoutSet>): List<SetGroup> = groupsOf(sets, { it.side }, ::setText)

    private fun groupsText(groups: List<SetGroup>): String =
        groups.joinToString("   ") { (if (it.label.isEmpty()) "" else it.label + " ") + it.text }

    /* ---------------- one lift, asked against logged ---------------- */

    private fun title(name: String, equipment: String): String =
        if (equipment.isBlank()) name else "$name ($equipment)"

    private fun equipmentWord(equipment: String): String = equipment.ifBlank { "no equipment" }

    /** What one lift says, asked above logged. [state] is `logged`, `notLogged` or `toDo`. */
    data class ExerciseLines(
        val key: String,
        /** The lift on its own, without whatever the day says about it. */
        val lift: String,
        val title: String,
        val state: String,
        val substitution: String?,
        val sideLine: String?,
        val countLine: String?,
        val asked: SetRow?,
        val logged: SetRow?
    )

    /** A lift the log has and the plan does not: its name and how many sets it carried, counted against nothing. */
    data class AlsoLogged(val key: String, val title: String, val text: String)

    /**
     * The side counts the session header has shown since per-side prescriptions shipped:
     * "L 3/3 · R 2/3", logged over asked, over never capped, an each-side exercise's ask twice its
     * tuples. [PerSideLogging.targetsLabel] *is* that header, called with the same two arguments it
     * is called with there, so this card and the session it describes can never disagree about a
     * side. A prescription that says nothing about sides falls back to the plain per-side count the
     * header also falls back to, and to no line at all when nothing anywhere named a limb.
     *
     * Printed, not judged: nothing does arithmetic on the difference between the two numbers.
     */
    fun sideLine(asked: AskedLift, logged: LoggedLift?): String? {
        val loggedSets = logged?.sets ?: return null
        return PerSideLogging.targetsLabel(asked.sets, asked.eachSide, loggedSets)
            ?: PerSideLogging.sideCountLabel(loggedSets)
    }

    /**
     * What an each-side lift asks for on a day nothing has been logged against yet:
     * "Each side · L 4 · R 3", the sentence the prescribed card has printed under an each-side
     * exercise since per-side prescriptions shipped.
     *
     * Deliberately not [PerSideLogging.targetsLabel], which would read "L 0/3 · R 0/3" — true
     * during a session, and on a day still ahead a nought nobody has had the chance to earn.
     */
    fun askLine(asked: AskedLift): String? {
        if (!asked.eachSide) return null
        val t = PerSideLogging.prescribedTargets(asked.sets, true)
        return "Each side · L ${t.left} · R ${t.right}"
    }

    /**
     * One lift's two rows.
     *
     * [recite] is whether the prescription is printed under a lift nothing has been logged against.
     * True for a day still ahead — that is the work, and Train cannot be moved to a day that has
     * not happened, so this card is the only place it can be read — and false for a day in the
     * past, where reciting what was asked for under a day nothing was logged on turns a fact into a
     * list of what someone did not do. The past day is one tap away and its own prescribed card
     * still holds every set.
     */
    fun pairLines(
        asked: AskedLift,
        logged: LoggedLift?,
        substituted: Boolean,
        absentWord: String,
        recite: Boolean
    ): ExerciseLines {
        val side = sideLine(asked, logged)
        val askedSets = asked.sets
        val loggedSets = logged?.sets.orEmpty()
        val lift = title(asked.name, asked.equipment) + if (asked.eachSide) " · each side" else ""
        return ExerciseLines(
            key = asked.key,
            lift = lift,
            title = if (logged != null || absentWord.isEmpty()) lift else "$lift · $absentWord",
            state = if (logged != null) "logged" else if (recite) "toDo" else "notLogged",
            substitution = if (substituted && logged != null)
                "Asked ${equipmentWord(asked.equipment)} · logged ${equipmentWord(logged.equipment)}"
            else null,
            sideLine = side ?: if (recite) askLine(asked) else null,
            // How many were asked for and how many came back, when they differ and there is no
            // side line already saying it per side.
            countLine = if (logged != null && side == null && askedSets.size != loggedSets.size)
                "Asked ${plural(askedSets.size, "set", "sets")} · logged ${loggedSets.size}"
            else null,
            asked = if ((logged != null || recite) && askedSets.isNotEmpty())
                SetRow("Asked", askedGroups(askedSets), if (asked.eachSide) " each side" else "")
            else null,
            logged = if (logged != null) SetRow("Logged", loggedGroups(loggedSets), "") else null
        )
    }

    private data class Joined(val exercises: List<ExerciseLines>, val alsoLogged: List<AlsoLogged>)

    /**
     * The asked and the logged lifts of one day, joined.
     *
     * Two passes, in this order, so an exact match always wins:
     *  1. name and equipment — a cable pulldown and a machine pulldown are not the same lift, and a
     *     coach prescribing one of them meant it.
     *  2. name alone, over what is left on each side: the equipment substitution, paired and
     *     labelled.
     *
     * Never by position: skipping the second exercise would shift every pairing after it.
     */
    private fun joinExercises(asked: List<AskedLift>, logged: List<LoggedLift>): Joined {
        val remaining = logged.toMutableList()
        fun take(predicate: (LoggedLift) -> Boolean): LoggedLift? {
            val index = remaining.indexOfFirst(predicate)
            return if (index < 0) null else remaining.removeAt(index)
        }

        val pairs = asked.map { ex -> Triple(ex, take { it.key == ex.key }, false) }.toMutableList()
        for (index in pairs.indices) {
            val (ex, match, _) = pairs[index]
            if (match != null) continue
            val substitute = take { nameKey(it.name) == nameKey(ex.name) } ?: continue
            pairs[index] = Triple(ex, substitute, true)
        }

        return Joined(
            exercises = pairs.map { (ex, match, substituted) ->
                pairLines(ex, match, substituted, WORDS.getValue("notLogged"), recite = false)
            },
            // Sets are the claim everywhere else here, so a lift nobody asked for that holds none
            // is not "0 sets" on screen.
            alsoLogged = remaining.filter { it.sets.isNotEmpty() }.map(::alsoLogged)
        )
    }

    private fun alsoLogged(ex: LoggedLift): AlsoLogged {
        val name = title(ex.name, ex.equipment)
        return AlsoLogged(
            key = ex.key,
            title = name,
            text = "$name · ${plural(ex.sets.size, "set", "sets")}"
        )
    }

    /* ---------------- the card ---------------- */

    /**
     * Coach's footer is about somebody else — "whether it arrived, and whether they opened it, only
     * they know". This is the same thought pointed the other way, and it is the whole discipline of
     * the card in one sentence: it holds two records and knows nothing about the week that produced
     * them. A day nothing was logged against may have been a day you trained and did not log, a day
     * you were ill, or a day you were told to rest, and the only person who can tell those apart is
     * reading this.
     */
    const val FOOTER: String =
        "Your coach’s plan beside your own log. What else the week held, only you know."

    /**
     * Who sent the week, when no booking in it carries a name: web's own fallback sentence, and the
     * sentence this card printed always before a name was stored at all. A plan accepted before
     * [ScheduledSession.fromCoach] existed carries none, so the week it booked reads exactly as it
     * did then.
     */
    const val SENT_BY: String = "From your coach"

    /**
     * Who sent the week, for the one muted line above the head — "From Doug", or [SENT_BY] when no
     * plan in the week named anybody. Web's `sentBy`, ported: the names of the bookings **inside the
     * week on screen**, each once, in the order they are met, joined the way every other list on this
     * card is joined, because two coaches can book one week and the line is the only place either is
     * named.
     *
     * The name is a coach's own, spelled by a coach's own app, and is never treated as anything but
     * text: not parsed, not matched against anything, not a key, and printed in one place and no
     * other. [PlanImporter.coachName] trims it and collapses its whitespace on the way in; a name
     * long enough to need more room than the line has is the view's problem and `PlanWeekCard`
     * solves it there, because a sentence three platforms share cannot be shortened by one of them.
     */
    fun sentBy(result: Result, bookings: List<Booking>): String {
        val names = bookings.filter { it.date in result.from..result.to }
            .mapNotNull { it.fromCoach?.trim()?.ifBlank { null } }
            .distinct()
        return if (names.isEmpty()) SENT_BY else "From ${names.joinToString(" · ")}"
    }

    /** The four verdicts a day row can carry. `to do` is this side's own; the other three are Coach's. */
    val WORDS: Map<String, String> = mapOf(
        "logged" to "logged",
        "toDo" to "to do",
        "notLogged" to "not logged",
        "notBooked" to "not booked"
    )

    data class Counts(
        val booked: Int,
        val logged: Int,
        val notLogged: Int,
        val toDo: Int,
        val other: Int
    )

    /** One day of the week: what it was called, what it is, and what it holds when opened. */
    data class DayRow(
        val key: String,
        val state: String,
        val name: String,
        /** Whether Train can be moved to this day. It cannot be moved past today. */
        val openable: Boolean,
        val text: String,
        val exercises: List<ExerciseLines>,
        val alsoLogged: List<AlsoLogged>
    ) {
        val hasDetail: Boolean get() = exercises.isNotEmpty() || alsoLogged.isNotEmpty()
    }

    data class Result(
        val from: String,
        val to: String,
        val range: String,
        val counts: Counts,
        val head: String,
        val days: List<DayRow>,
        val footer: String = FOOTER
    )

    /**
     * The head of the week.
     *
     * `logged N` is printed only once something is behind you: a week booked entirely in the days
     * ahead would otherwise open with "logged 0", a nought nobody earned. A week that is over
     * prints it whatever it is, because by then it counts a finished thing and is the same sentence
     * a coach reads.
     */
    private fun headLine(range: String, counts: Counts): String {
        var head = "Booked ${plural(counts.booked, "day", "days")}, $range"
        if (counts.logged > 0 || counts.notLogged > 0) head += " · logged ${counts.logged}"
        if (counts.toDo > 0) head += " · ${counts.toDo} to do"
        if (counts.other > 0) head += " · ${plural(counts.other, "other day logged", "other days logged")}"
        return head
    }

    /**
     * One week of a coach's plan against this device's log, or **null** when there is nothing to
     * say.
     *
     * Null — not an empty card, not an explanation — whenever the week being looked at books no
     * training. A lifter who has never been sent a plan should not learn this card exists by being
     * told it has nothing for them, and a week of their own training held up against a plan nobody
     * wrote is the app inventing an expectation.
     *
     * **Days join on date, and nothing else.** Web has one thing this side does not:
     * `startedSessionId`, which says which session a booking was started as, and lets a day holding
     * two sessions compare the right one. Nothing on this platform records it — starting a booked
     * routine writes an ordinary [WorkoutSession] with a fresh id — so a booked day is compared
     * against everything logged on it, pooled, which is exactly web's own fallback when the session
     * a booking started was deleted. A session lifted the day after the one it was booked for is
     * still a booked day with nothing logged **and** a session of its own, adjacent on screen, with
     * nothing claimed about the two.
     */
    fun compare(
        bookings: List<Booking>,
        sessions: List<WorkoutSession>,
        today: String,
        anchor: String = today
    ): Result? {
        val (from, to) = weekOf(anchor)
        val booked = bookings.filter { it.date in from..to }
        if (booked.isEmpty()) return null

        val logged = sessions.filter { it.date in from..to }
        val byDate = booked.groupBy { it.date }
        val bookedDates = byDate.keys.sorted()

        var loggedDays = 0
        var notLoggedDays = 0
        var toDoDays = 0
        var otherDays = 0

        val rows = bookedDates.map { date ->
            val name = byDate.getValue(date).map { it.name }.filter { it.isNotBlank() }.joinToString(" · ")
            val asked = poolAsked(byDate.getValue(date).flatMap { it.exercises })
            val mine = loggedIn(logged.filter { it.date == date })

            val state = when {
                mine.isNotEmpty() -> { loggedDays += 1; "logged" }
                date >= today -> { toDoDays += 1; "toDo" }
                else -> { notLoggedDays += 1; "notLogged" }
            }

            val joined = if (state == "logged") joinExercises(asked, mine) else Joined(
                // A day still ahead prints what it asks for; a day in the past does not recite what
                // was not done. `recite` is the whole of that difference.
                exercises = asked.map { ex ->
                    pairLines(
                        asked = ex,
                        logged = null,
                        substituted = false,
                        absentWord = if (state == "toDo") "" else WORDS.getValue("notLogged"),
                        recite = state == "toDo"
                    )
                },
                alsoLogged = emptyList()
            )

            DayRow(
                key = date,
                state = state,
                name = name,
                // Past or today, so Train can be moved to it; a day still ahead cannot be shown
                // there, which is the reason its prescription is printed here instead.
                openable = date <= today,
                text = listOf(dayLabel(date), name, WORDS.getValue(state))
                    .filter { it.isNotBlank() }.joinToString(" · "),
                exercises = joined.exercises,
                alsoLogged = joined.alsoLogged
            )
        }.toMutableList()

        // A day in the week that was trained and that nothing was booked for. Shown beside the
        // bookings, saying nothing about cause: a session lifted the day after the one it was
        // booked for looks exactly like this, and so does a session added for its own sake.
        logged.filter { it.date !in byDate.keys }.forEach { session ->
            val all = loggedIn(listOf(session))
            if (all.isEmpty()) return@forEach
            val lifts = all.filter { it.sets.isNotEmpty() }
            val existing = rows.indexOfFirst { it.key == session.date && it.state == "notBooked" }
            if (existing >= 0) {
                val row = rows[existing]
                rows[existing] = row.copy(alsoLogged = row.alsoLogged + lifts.map(::alsoLogged))
                return@forEach
            }
            otherDays += 1
            rows += DayRow(
                key = session.date,
                state = "notBooked",
                name = session.name,
                openable = session.date <= today,
                text = listOf(dayLabel(session.date), session.name, WORDS.getValue("notBooked"))
                    .filter { it.isNotBlank() }.joinToString(" · "),
                exercises = emptyList(),
                alsoLogged = lifts.map(::alsoLogged)
            )
        }
        rows.sortBy { it.key }

        val counts = Counts(
            booked = bookedDates.size,
            logged = loggedDays,
            notLogged = notLoggedDays,
            toDo = toDoDays,
            other = otherDays
        )
        return Result(
            from = from,
            to = to,
            range = rangeText(from, to),
            counts = counts,
            head = headLine(rangeText(from, to), counts),
            days = rows
        )
    }

    /**
     * The Monday of the nearest week in [direction] (-1 back, +1 on) that books something, or null
     * when there is none.
     *
     * Paging moves between weeks a coach actually wrote, never one week at a time. A card that
     * vanished on the way to an empty week would take its own arrows with it and leave no way back,
     * which is the one thing worse than an empty frame; and an arrow with nothing behind it is
     * disabled rather than hidden, so the row does not change shape as it is used.
     */
    fun adjacentWeek(bookings: List<Booking>, fromMonday: String, direction: Int): String? {
        val weeks = bookings.map { weekOf(it.date).first }.distinct().sorted()
        return if (direction < 0) weeks.lastOrNull { it < fromMonday }
        else weeks.firstOrNull { it > fromMonday }
    }

    /**
     * Every sentence this card can produce, flattened, in the order it is read. The
     * line-discipline tests run over this rather than over a rendered screen, so a string that
     * grades a week fails a test the day it is written rather than the day someone reads it.
     */
    fun lines(result: Result?): List<String> {
        result ?: return emptyList()
        val out = mutableListOf(result.head)
        result.days.forEach { day ->
            out += day.text
            day.exercises.forEach { ex ->
                out += ex.title
                ex.sideLine?.let { out += it }
                ex.countLine?.let { out += it }
                ex.asked?.let { out += "${it.label} ${it.text}" }
                ex.logged?.let { out += "${it.label} ${it.text}" }
                // Under the pair, as it sits on screen: the substitution line says what the two
                // rows above it are, and reads as nonsense above them.
                ex.substitution?.let { out += it }
            }
            if (day.alsoLogged.isNotEmpty()) {
                out += "Also logged"
                day.alsoLogged.forEach { out += it.text }
            }
        }
        out += result.footer
        return out
    }
}
