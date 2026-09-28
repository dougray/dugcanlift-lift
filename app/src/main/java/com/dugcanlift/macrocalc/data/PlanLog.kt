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
 * *stored*, and both on the booking rather than on a session, because a booking is a file that stays
 * on the device and a session is one that travels: [ScheduledSession.fromCoach], the coach a booking
 * came from, which is what lets this card sign a week the way the browser signs it, and
 * [ScheduledSession.startedSessionId], which says which session starting a booking wrote.
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
 * **Meals are stated, never answered.** A coach can book meals as well as sessions (PLAN-FORMAT's
 * `m`), and accepting a plan files them as [PlannedMeal]s beside the ones placed in Cook. This card
 * lists the ones a coach booked -- `Dinner · Beef Chilli · 2 servings` -- and says **nothing
 * whatever about what you ate**. Coach's card does the other half as well: it names the foods the
 * client stamped with that slot, above a count of the day's foods so `Nothing logged at lunch`
 * cannot read as `they ate nothing`, under a note saying it cannot know whether the dish was the one
 * it booked. None of that half is worth anything here. Your food log is on the Food screen, dated,
 * and reading it back to you in the third person tells you nothing you did not already know -- and
 * you are the one person who does not need telling whether they ate their dinner. So there is no
 * `Nothing logged at lunch`, no food count above the rows, no macros beside a booked dish, no figure
 * for meals eaten, and no meal footer: Coach's note exists to disclaim a join Coach cannot make, and
 * this card makes no claim to disclaim.
 *
 * What is left is the one thing no other screen gives you: **the food booked for a day you cannot
 * reach.** Cook's plan shows seven days from today and Train shows one, so a dish booked for next
 * Thursday is legible nowhere until you arrive at it, and a week of it that a coach sent reached no
 * screen at all. `to do` carries that, as it does for a session, and **a plan of meals with no
 * training is now a card** where before there was none.
 *
 * [PlannedMeal.loggedFoodEntryId] is deliberately not read. This device really does know a planned
 * meal was logged -- the lifter tapped "Log it" and the entry's id was stored against it -- so
 * unlike Coach there would be no guessing in saying so. It is still not printed. The only thing it
 * could add is a tick on some meal rows and a blank on the rest, which is a score with the numbers
 * filed off, and the screen that can act on the answer (Cook's plan, with `Log it` beside the dish)
 * already shows it where it is useful.
 *
 * Only a coach's meals, never the lifter's own -- [PlannedMeal.fromCoach] is what tells them apart.
 * A week a coach booked no meals in reads exactly as it did before any of this: the count, the
 * clause and the rows appear only where there is a booked meal to carry them, which `PlanLogTest`
 * pins line for line against the card as it shipped.
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

    /* ---------------- how a line reads aloud ----------------
     *
     * Every line on this card is written with " · " between its clauses, which is a comma that
     * takes no vertical space. Aloud it is not a comma: TalkBack either names the character or
     * passes over it, and either way "Mon 28 Sep · Lower A · logged" arrives as three unrelated
     * fragments. Worse here than anywhere: `PlanWeekSetRow` is a `Row` of two `Text`s, so "Asked"
     * and its numbers are two separate accessibility nodes and a reader meets the word two swipes
     * before what it named.
     *
     * So every line that reaches a screen also carries a spoken form, composed here from the same
     * parts the written one is composed from -- never by a regex over the finished string, which
     * would have to guess whether the `x` in a name you typed is a multiplication sign. " · "
     * becomes a comma, " x " becomes "by", `L`/`R` become "left"/"right", `3/3` becomes "3 of 3",
     * "Mon 28 Sep" becomes "Monday 28 September". **Nothing else**: no word is added that the card
     * does not draw. You are not being graded here, and a day nothing was logged against is as
     * flat aloud as it is on screen.
     *
     * Here rather than in `PlanWeekCard`, for the reason everything else on this card is here: a
     * sentence you read is a rule, and a rule in a composable cannot be tested.
     */

    /** " · " is the only thing this may touch -- for the lines this object no longer has the
     *  parts of by the time a screen asks. */
    fun plainly(text: String?): String = text.orEmpty().replace(" · ", ", ")

    private fun said(parts: List<String?>): String =
        parts.filter { !it.isNullOrBlank() }.joinToString(", ")

    private fun longMonth(key: String): String =
        DayKey.parse(key)?.month?.getDisplayName(TextStyle.FULL, Locale.getDefault()).orEmpty()

    private fun longWeekday(key: String): String =
        DayKey.parse(key)?.dayOfWeek?.getDisplayName(TextStyle.FULL, Locale.getDefault()).orEmpty()

    /** "Monday 28 September" -- [dayLabel] in the words a person says. The locale is the
     *  reader's, as everywhere else here. */
    fun spokenDayLabel(key: String): String = "${longWeekday(key)} ${dayOf(key)} ${longMonth(key)}"

    /** "28 September to 4 October" -- the en dash in [rangeText] is a range sighted and a dash
     *  aloud. */
    fun spokenRange(from: String, to: String): String {
        if (from == to) return "${dayOf(from)} ${longMonth(from)}"
        val a = DayKey.parse(from)
        val b = DayKey.parse(to)
        if (a != null && b != null && a.year == b.year && a.month == b.month) {
            return "${dayOf(from)} to ${dayOf(to)} ${longMonth(to)}"
        }
        return "${dayOf(from)} ${longMonth(from)} to ${dayOf(to)} ${longMonth(to)}"
    }

    /** "L" and "R" are a column heading, not a word. */
    private fun sideWord(label: String): String = when (label) {
        "L" -> "left"
        "R" -> "right"
        else -> label.lowercase()
    }

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
        val fromCoach: String? = null,
        /**
         * The session starting this booking wrote, when it was started on this device. Used only to
         * pick a session out of a day that holds two — never to claim a session logged on one day
         * answers a booking on another.
         */
        val startedSessionId: String? = null
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
                fromCoach = session.fromCoach,
                startedSessionId = session.startedSessionId
            )
        }

    /* ---------------- the meals a coach booked ----------------
     *
     * A [PlannedMeal] is a coach's or the lifter's own, in one list, the coach's marked
     * [PlannedMeal.fromCoach] exactly as a prescribed session is. The slot is stored as this app
     * writes it and read into the four words PLAN-FORMAT's `m.s` indexes in the same order, so a
     * booked slot is named here the way Coach names it. */

    /** The four slots a coach can book, in the order `m.s` indexes them -- the order a day is eaten
     *  in, not the order the coach happened to book them. */
    val MEAL_SLOTS: List<Meal> = listOf(Meal.BREAKFAST, Meal.LUNCH, Meal.DINNER, Meal.SNACK)

    /**
     * One booked meal: the slot, the dish and how much of it, which are the three things a coach
     * wrote and so the three things that can be said without reservation. Macros are not here, and
     * neither is anything about the food log -- see the head of this file.
     *
     * [title] is the whole line and [slotLabel] / [detail] are its two columns, so the view can
     * align the slots without composing a second sentence of its own that could drift from the one
     * the tests read.
     */
    data class MealRow(
        val date: String,
        /** The slot's place in [MEAL_SLOTS], or null for one this build cannot read. */
        val slot: Int?,
        val slotLabel: String,
        val name: String,
        val servings: Double,
        val detail: String,
        val title: String
    )

    /** "1 serving", "2 servings", "0.5 servings" -- Cook's own label, so a booked dish reads here the
     *  way it reads there. */
    private fun servingsLabel(servings: Double): String =
        if (servings == 1.0) "1 serving" else "${servings.cookDisplay()} servings"

    private fun mealRow(date: String, slot: Int?, name: String, servings: Double): MealRow {
        val label = slot?.let { MEAL_SLOTS[it].label }.orEmpty()
        val detail = "$name · ${servingsLabel(servings)}"
        return MealRow(
            date = date,
            slot = slot,
            slotLabel = label,
            name = name,
            servings = servings,
            detail = detail,
            title = listOf(label, detail).filter { it.isNotBlank() }.joinToString(" · ")
        )
    }

    /**
     * The meals a coach booked, in the window given, breakfast to snack within each day.
     *
     * **Only a coach's.** A meal the lifter placed in Cook is theirs to move, and holding it up on a
     * card headed "your coach's plan" would make an expectation out of their own note-taking -- the
     * same reason a week nobody booked is no card at all.
     *
     * Two dishes at one dinner are two dishes and both are shown, in the order they were booked;
     * meals do not pool, as sessions on a date do, because a coach who booked both wants both eaten.
     * A slot this build cannot read sorts last rather than being dropped: a dish a coach booked is a
     * dish a coach booked, and hiding it would hide the plan. [from] and [to] are optional -- the
     * arrows need every week a coach booked a meal in, not one week of them.
     */
    fun bookedMeals(plan: List<PlannedMeal>, from: String? = null, to: String? = null): List<MealRow> =
        plan.filter { meal ->
            meal.fromCoach && meal.date.isNotBlank() &&
                (from == null || meal.date >= from) && (to == null || meal.date <= to)
        }.map { meal ->
            val slot = MEAL_SLOTS.indexOfFirst { it.name == meal.meal.trim().uppercase(Locale.US) }
            val name = meal.recipeName.trim().ifBlank { "Recipe" }
            // A coach's own number, however odd. Anything that is not a real amount is one serving
            // rather than a dish of none.
            val servings = if (meal.servings.isFinite() && meal.servings > 0) meal.servings else 1.0
            mealRow(meal.date, if (slot < 0) null else slot, name, servings)
        // sortedWith is stable, so two dishes at one dinner keep the order the coach booked them in.
        }.sortedWith(compareBy({ it.date }, { it.slot ?: MEAL_SLOTS.size }))

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

    /**
     * [setNumbers], said. The ` x ` is the only difference that matters: it is the letter, and
     * "225 by 5" is what a lifter says out loud anyway. Built from the fields, not from
     * [setNumbers]'s output -- a transform over the finished string would have to decide what to
     * do with an ` x ` in a name somebody typed.
     */
    fun spokenNumbers(
        weightLb: Double?,
        reps: Int?,
        rpe: Double?,
        durationSec: Int?,
        distanceMeters: Double?
    ): String? {
        val parts = mutableListOf<String>()
        if (weightLb != null && reps != null) parts += "${weightLb.trimZeros()} by $reps"
        else {
            weightLb?.let { parts += "${it.trimZeros()} lb" }
            reps?.let { parts += "$it reps" }
        }
        rpe?.let { parts += "@${it.trimZeros()}" }
        durationSec?.let { parts += formatDuration(it) }
        distanceMeters?.let { parts += "${it.trimZeros()} m" }
        return parts.joinToString(" ").ifEmpty { null }
    }

    fun spokenSetText(set: PrescribedSet): String =
        spokenNumbers(set.weightLb, set.reps, set.rpe, set.durationSec, set.distanceMeters) ?: "as written"

    fun spokenSetText(set: WorkoutSet): String =
        spokenNumbers(set.weightLb, set.reps, set.rpe, set.durationSec, set.distanceMeters) ?: "as written"

    /** One group of sets on screen: a side and its sets, or an unlabelled group when nothing is sided. */
    data class SetGroup(val label: String, val text: String, val spoken: String = text)

    /**
     * A row of sets: the groups, and the clause that applies to all of them.
     *
     * [suffix] is a field rather than glued onto the last group's text, because "each side" is a
     * clause on the ask and not a set of its own — a view that draws the groups and forgets the
     * clause prints a plan asking for half of what it asks for.
     */
    data class SetRow(val label: String, val groups: List<SetGroup>, val suffix: String) {
        val text: String get() = groupsText(groups) + suffix
        /** The label **and** the groups, in one string: on Android they are two `Text`s in a
         *  `Row` and so two accessibility nodes, and "Asked" two swipes before its numbers is not
         *  a comparison. */
        val spoken: String get() = "$label " + groupsSpoken(groups) + suffix
    }

    private val SERIES = listOf(SetSide.LEFT, SetSide.RIGHT, null)

    private fun <T> groupsOf(
        sets: List<T>,
        side: (T) -> SetSide?,
        text: (T) -> String,
        spoken: (T) -> String
    ): List<SetGroup> {
        if (sets.none { side(it) != null }) {
            if (sets.isEmpty()) return emptyList()
            return listOf(SetGroup("", sets.joinToString(" · ", transform = text),
                sets.joinToString(", ", transform = spoken)))
        }
        return SERIES.mapNotNull { want ->
            val mine = sets.filter { side(it) == want }
            if (mine.isEmpty()) null
            else SetGroup(want?.short ?: "Both", mine.joinToString(" · ", transform = text),
                mine.joinToString(", ", transform = spoken))
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
    fun askedGroups(sets: List<PrescribedSet>): List<SetGroup> =
        groupsOf(sets, { it.side }, ::setText, ::spokenSetText)

    fun loggedGroups(sets: List<WorkoutSet>): List<SetGroup> =
        groupsOf(sets, { it.side }, ::setText, ::spokenSetText)

    private fun groupsText(groups: List<SetGroup>): String =
        groups.joinToString("   ") { (if (it.label.isEmpty()) "" else it.label + " ") + it.text }

    /** The groups said, one limb after the other. A semicolon between them, because the sets
     *  inside a group are already separated by commas and "right" has to land as a new column. */
    private fun groupsSpoken(groups: List<SetGroup>): String =
        groups.joinToString("; ") { (if (it.label.isEmpty()) "" else sideWord(it.label) + " ") + it.spoken }

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
        /** [sideLine] said: "L 3/3 · R 2/3" is a letter, a slash and the name of a character read
         *  out, on the one line of the card that says what one side of your body did. */
        val spokenSideLine: String?,
        val countLine: String?,
        val asked: SetRow?,
        val logged: SetRow?
    ) {
        /**
         * The whole block as one announcement.
         *
         * **The two rows are a comparison, and read apart they are two lists of numbers with
         * nothing between them.** One node carrying the lift and both rows is what makes the
         * relationship audible.
         */
        val spoken: String get() = listOfNotNull(
            plainly(title), spokenSideLine, plainly(countLine).ifBlank { null },
            asked?.spoken, logged?.spoken, plainly(substitution).ifBlank { null }
        ).joinToString(". ")
    }

    /** A lift the log has and the plan does not: its name and how many sets it carried, counted against nothing. */
    data class AlsoLogged(val key: String, val title: String, val text: String) {
        val spoken: String get() = plainly(text)
    }

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

    /** [sideLine] said -- [PerSideLogging]'s own spoken forms, called with the same arguments in
     *  the same order, so the card and the session header it echoes can no more disagree about a
     *  side aloud than they can on screen. */
    fun spokenSideLine(asked: AskedLift, logged: LoggedLift?): String? {
        val loggedSets = logged?.sets ?: return null
        return PerSideLogging.targetsSpoken(asked.sets, asked.eachSide, loggedSets)
            ?: PerSideLogging.sideCountSpoken(loggedSets)
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

    /** [askLine], said. */
    fun spokenAskLine(asked: AskedLift): String? {
        if (!asked.eachSide) return null
        val t = PerSideLogging.prescribedTargets(asked.sets, true)
        return "Each side, left ${t.left}, right ${t.right}"
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
            spokenSideLine = spokenSideLine(asked, logged)
                ?: if (recite) spokenAskLine(asked) else null,
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
    fun sentBy(result: Result, bookings: List<Booking>, plan: List<PlannedMeal> = emptyList()): String {
        val fromBookings = bookings.filter { it.date in result.from..result.to }
            .mapNotNull { it.fromCoach?.trim()?.ifBlank { null } }
        // A week that booked only meals is signed by whoever sent it, like any other. A meal placed
        // on this device is not from a coach at all, so the same test that keeps it off the card
        // keeps its owner out of the name.
        val fromMeals = plan.filter { it.fromCoach && it.date in result.from..result.to }
            .mapNotNull { it.coachName?.trim()?.ifBlank { null } }
        val names = (fromBookings + fromMeals).distinct()
        return if (names.isEmpty()) SENT_BY else "From ${names.joinToString(" · ")}"
    }

    /**
     * The four verdicts a day row can carry. `to do` is this side's own; the other three are Coach's.
     *
     * A day in the past booked only for food is the fifth state and carries **no word at all**, so
     * it is not in here: there is no `not logged` for a meal, and no figure standing in for one.
     */
    val WORDS: Map<String, String> = mapOf(
        "logged" to "logged",
        "toDo" to "to do",
        "notLogged" to "not logged",
        "notBooked" to "not booked"
    )

    /**
     * The days this week booked and the days it holds, and nothing else.
     *
     * [training] and [meals] count what a coach wrote -- days that book a session, and dishes
     * booked. Neither carries a figure for what came back: [logged] is that figure for training, and
     * **there is none for a meal**.
     */
    data class Counts(
        val booked: Int,
        val training: Int,
        val meals: Int,
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
        /** [text] said: three clauses separated by " · " are three fragments to a screen reader,
         *  and a day row is one thing you read. */
        val spoken: String,
        val exercises: List<ExerciseLines>,
        val alsoLogged: List<AlsoLogged>,
        /** What a coach booked for this day to eat, and **nothing about what was eaten**. Empty on
         *  every day nobody booked a meal for. */
        val meals: List<MealRow> = emptyList()
    ) {
        val hasDetail: Boolean
            get() = exercises.isNotEmpty() || alsoLogged.isNotEmpty() || meals.isNotEmpty()
    }

    data class Result(
        val from: String,
        val to: String,
        val range: String,
        val counts: Counts,
        val head: String,
        /** [head] with the range said as a range: "28 Sep–4 Oct" is a dash and two abbreviations
         *  aloud. */
        val spokenHead: String,
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
        // What a coach booked, which is a count of their own writing. There is no figure beside it
        // for meals eaten, in this line or anywhere else.
        if (counts.meals > 0) head += " · ${plural(counts.meals, "meal booked", "meals booked")}"
        if (counts.logged > 0 || counts.notLogged > 0) {
            // `logged 1` under `Booked 5 days` would read as one day of five when three of them
            // booked no session at all, so once meals are in the line the figure says what it
            // counts. Coach's sentence, for the same reason.
            head += if (counts.meals > 0) {
                " · ${plural(counts.training, "training day", "training days")}, ${counts.logged} logged"
            } else {
                " · logged ${counts.logged}"
            }
        }
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
     * **Days join on date, and on [Booking.startedSessionId] where there is one**: starting a booked
     * routine records which session it became, so a day carrying two sessions compares the right one
     * and the other falls to "Also logged". That link only ever picks a session out of a day. It
     * never claims a session logged on one day answers a booking on another: a session lifted the day
     * after the one it was booked for is still a booked day with nothing logged **and** a session of
     * its own, adjacent on screen, with nothing claimed about the two.
     *
     * **Pooling is the fallback and stays the fallback.** A booking with no id — every booking
     * written before the link was recorded, and every session logged without pressing Start — is
     * compared against everything logged on its day, and so is a booking whose session has since
     * been deleted. That is web's own fallback, in web's own order.
     */
    fun compare(
        bookings: List<Booking>,
        sessions: List<WorkoutSession>,
        today: String,
        anchor: String = today,
        /** This device's planned meals, a coach's and the lifter's own in one list. Which of the two
         *  each is is [bookedMeals]'s decision and not a caller's -- a filter in a composable could
         *  not be tested, and this one decides whether somebody's own note-taking is held up to them
         *  as an expectation. */
        plan: List<PlannedMeal> = emptyList()
    ): Result? {
        val (from, to) = weekOf(anchor)
        val booked = bookings.filter { it.date in from..to }
        // A plan is a plan whichever half of it arrived: `k` and `m` are independent
        // (PLAN-FORMAT), and a send carrying only meals books days. Before this the card was absent
        // for one, so a coach who sent a week of food reached no screen that said so past Cook's
        // seven days from today.
        val meals = bookedMeals(plan, from, to)
        if (booked.isEmpty() && meals.isEmpty()) return null

        val logged = sessions.filter { it.date in from..to }
        val byDate = booked.groupBy { it.date }
        val mealsByDate = meals.groupBy { it.date }
        // Every date this week books anything at all. A day may book a session with no meals, meals
        // with no session, or both, and all three are one row -- this card opens one date at a time
        // and tapping a row moves Train to a date, so a second row on the same day would open two
        // details and go nowhere new.
        val bookedDates = (byDate.keys + mealsByDate.keys).sorted()

        var trainingDays = 0
        var mealsBooked = 0
        var loggedDays = 0
        var notLoggedDays = 0
        var toDoDays = 0
        var otherDays = 0

        val rows = bookedDates.map { date ->
            val bookedHere = byDate[date].orEmpty()
            // Whether this day books a session at all. A day that books only meals gets no training
            // verdict: `not logged` against a day nobody was asked to train would be the app
            // inventing a booking to hold against you.
            val booksTraining = bookedHere.isNotEmpty()
            val dayMeals = mealsByDate[date].orEmpty()
            if (booksTraining) trainingDays += 1
            mealsBooked += dayMeals.size
            val bookedName = bookedHere.map { it.name }.filter { it.isNotBlank() }.joinToString(" · ")
            val asked = poolAsked(bookedHere.flatMap { it.exercises })
            val onDay = logged.filter { it.date == date }
            // The session this booking was started as, when it still exists: a day with a booked
            // session and an extra one of the lifter's own compares the right half. An id naming a
            // session that has been deleted resolves to nothing and falls back to the day, which is
            // what a booking nobody pressed Start on does anyway. Two bookings on one day, each
            // started, leave the later of them holding the comparison -- web's own behaviour, ported
            // rather than tidied, because the alternative is this build describing such a day
            // differently from the browser.
            var startedSession: WorkoutSession? = null
            bookedHere.forEach { booking ->
                val startedId = booking.startedSessionId ?: return@forEach
                onDay.forEach { session -> if (session.id == startedId) startedSession = session }
            }
            val started = startedSession
            val mine = if (!booksTraining) emptyList() else
                loggedIn(if (started != null) listOf(started) else onDay)
            // A session on the same day that was not the one booked is logged work, counted against
            // nothing, exactly like a lift nobody asked for -- and on a day booked only for food,
            // everything logged on it is that.
            val extra = when {
                !booksTraining -> loggedIn(onDay).filter { it.sets.isNotEmpty() }
                started == null -> emptyList()
                else -> loggedIn(onDay.filter { it.id != started.id }).filter { it.sets.isNotEmpty() }
            }

            val state = when {
                booksTraining && mine.isNotEmpty() -> { loggedDays += 1; "logged" }
                booksTraining && date >= today -> { toDoDays += 1; "toDo" }
                booksTraining -> { notLoggedDays += 1; "notLogged" }
                // A day booked for food that was trained anyway. The training was not booked, which
                // is the same fact -- and Coach's same word -- as a day the plan says nothing about,
                // and it is said on the row itself so a week read with every day shut still says
                // which days you trained.
                extra.isNotEmpty() -> { otherDays += 1; "notBooked" }
                // A day still ahead is a plan, whether it books a session, a dinner or both. `to do`
                // is a fact about the calendar and needs no log to be true, which is why it is the
                // one verdict a meals-only day can carry.
                date >= today -> { toDoDays += 1; "toDo" }
                // A day in the past that booked food and nothing else. No word at all: there is no
                // `not logged` for a meal, and no figure for one either.
                else -> "meals"
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

            // A booking names itself. Only a day that books no session at all takes its name from
            // what was logged on it, exactly as a `not booked` day of its own does -- a booked
            // session with a blank name keeps its blank.
            val name = if (bookedName.isNotBlank() || booksTraining) bookedName
            else onDay.firstOrNull { loggedIn(listOf(it)).isNotEmpty() }?.name.orEmpty()
            val word = WORDS[state].orEmpty()
            val mealsClause =
                if (dayMeals.isEmpty()) "" else plural(dayMeals.size, "meal booked", "meals booked")
            // The training word hugs the session it judges; the meal clause follows it. A day that
            // booked no session has no session for it to hug, so what is left -- `to do`, or nothing
            // -- goes last instead. Coach's own rule, and its own sentence: a week described to a
            // coach reads the same way.
            val head = if (name.isBlank()) listOf(dayLabel(date), mealsClause, word)
            else listOf(dayLabel(date), name, word, mealsClause)
            // The same clauses in the same order, with the date said in words. Built beside [head]
            // rather than from it, so a clause can never be in one and not the other.
            val spokenDayHead = if (name.isBlank()) listOf(spokenDayLabel(date), mealsClause, word)
            else listOf(spokenDayLabel(date), name, word, mealsClause)

            DayRow(
                key = date,
                state = state,
                name = name,
                // Past or today, so Train can be moved to it; a day still ahead cannot be shown
                // there, which is the reason its prescription is printed here instead.
                openable = date <= today,
                text = head.filter { it.isNotBlank() }.joinToString(" · "),
                // One sentence rather than three fragments. `word`, never `WORDS.getValue(state)`:
                // a day in the past booked only for food is the fifth state and is deliberately
                // not in `WORDS`, so looking it up there would throw rather than say nothing.
                spoken = said(spokenDayHead),
                exercises = joined.exercises,
                alsoLogged = joined.alsoLogged + extra.map(::alsoLogged),
                // What a coach booked for this day to eat, and nothing about what was eaten.
                meals = dayMeals
            )
        }.toMutableList()

        // A day in the week that was trained and that nothing was booked for. Shown beside the
        // bookings, saying nothing about cause: a session lifted the day after the one it was
        // booked for looks exactly like this, and so does a session added for its own sake.
        // Every date this send booked, meals included: a day booked for food and trained anyway is
        // already a row above, with `not booked` on it.
        logged.filter { it.date !in bookedDates }.forEach { session ->
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
                spoken = said(listOf(spokenDayLabel(session.date), session.name,
                    WORDS.getValue("notBooked"))),
                exercises = emptyList(),
                alsoLogged = lifts.map(::alsoLogged)
            )
        }
        rows.sortBy { it.key }

        val counts = Counts(
            booked = bookedDates.size,
            training = trainingDays,
            meals = mealsBooked,
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
            spokenHead = plainly(headLine(spokenRange(from, to), counts)),
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
    fun adjacentWeek(
        bookings: List<Booking>,
        fromMonday: String,
        direction: Int,
        /** Meals book weeks too. Without this a week a coach sent food for would be reachable only
         *  by standing in it, and the arrows would skip over a card that exists. A meal placed on
         *  this device books no week: the arrow would land on a card that is not there. */
        plan: List<PlannedMeal> = emptyList()
    ): String? {
        val weeks = (bookings.map { it.date } + bookedMeals(plan).map { it.date })
            .map { weekOf(it).first }.distinct().sorted()
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
            // Meals last, under the training they sit beside. One line each, and nothing under them:
            // there is no second row about the food log.
            if (day.meals.isNotEmpty()) {
                out += "Meals"
                day.meals.forEach { out += it.title }
            }
        }
        out += result.footer
        return out
    }

    /**
     * Every sentence a screen reader can be handed, in the order it is read -- [lines], said.
     *
     * Its own list rather than a widening of [lines], which the line-discipline tests already walk
     * against the strings they pin. This exists so they walk the announced sentences too: a label
     * is a sentence you read, and nothing here grades you in either form.
     */
    fun spokenLines(result: Result?): List<String> {
        result ?: return emptyList()
        val out = mutableListOf(result.spokenHead)
        result.days.forEach { day ->
            out += day.spoken
            day.exercises.forEach { out += it.spoken }
            if (day.alsoLogged.isNotEmpty()) {
                out += "Also logged"
                day.alsoLogged.forEach { out += it.spoken }
            }
            // Meals last, exactly where [lines] puts them. A meal row is composed by the time a
            // screen asks for it, so [plainly] is what says it -- the case that helper exists for.
            if (day.meals.isNotEmpty()) {
                out += "Meals"
                day.meals.forEach { out += plainly(it.title) }
            }
        }
        out += result.footer
        return out
    }
}
