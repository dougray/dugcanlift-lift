package com.dugcanlift.macrocalc.data

/**
 * What ticking a set done starts, once the session knows its supersets, drop sets and rest-pause
 * sets (PLAN-FORMAT "Set types", the LIFT superproject's `2026-10-28-set-types-design.md` "The rest
 * timer").
 *
 * A port of LIFT iPhone's `SessionRest`, rule for rule, and [SessionRestTest] is a port of its
 * `SessionRestTests`, case for case. Pure and free of Android, like [SetTypes]: a rule in a
 * composable cannot be tested, and this one decides whether somebody rests. [RestTimer] keeps doing
 * exactly what it does; this only says which of its doors to use.
 *
 * **The order** is the prescribed one, round by round: an ungrouped exercise is its sets in order;
 * a group is one set of each member, in order, then the next round, and a member that has run out
 * of sets is skipped. Three things change from "ticking a set starts the rest from Settings":
 *
 * - **Inside a superset round**, while any other set of the same round is not ticked, no rest: the
 *   banner says "Next: Seated Cable Rows". The round's last tick starts the full rest.
 * - **The next set to do is a drop set**: no rest, "Drop set next".
 * - **The next set to do is rest-pause**: a 15-second rest.
 *
 * "The next set to do" is the first set after the ticked one in that order that is not ticked. With
 * the rest turned off nothing starts and nothing is said.
 */
object SessionRest {

    /** Rest-pause is a fixed pause, not a setting (the design's own call). */
    const val REST_PAUSE_SECONDS = 15

    data class Exercise(
        /** What the banner names: "Next: Seated Cable Rows". */
        val name: String,
        /** What a rest is before: "Back to Seated Cable Rows (Cable)". */
        val restName: String = name,
        /** The logged group number, null when not grouped. Adjacency decides ([SetTypes.runs]). */
        val group: Int? = null,
        val sets: List<SetState>
    )

    data class SetState(
        val id: String = java.util.UUID.randomUUID().toString(),
        val done: Boolean = false,
        /** As trained: an exercise's first set is never a drop or rest-pause, which the caller has already applied ([SetTypes.kindAt]). */
        val kind: SetKind? = null
    )

    sealed interface After {
        /** A rest of this many seconds, before the named exercise. */
        data class Rest(val seconds: Int, val before: String?) : After
        /** No rest, and what the banner says instead. */
        data class NoRest(val cue: String) : After
        /** Nothing at all: the rest is turned off. */
        data object Nothing : After
    }

    /** One place in the order. */
    data class Step(
        val exercise: Int,
        val set: Int,
        /** The first exercise of the block -- a group, or an exercise alone. */
        val block: Int,
        val round: Int,
        val grouped: Boolean
    )

    /** A logged session, as this rule reads it: each exercise's group, and each set's tick and kind as trained. */
    fun exercises(session: WorkoutSession, done: Set<String>): List<Exercise> =
        session.exercises.map { exercise ->
            Exercise(
                name = exercise.name,
                restName = exercise.displayName,
                group = exercise.group,
                sets = exercise.sets.mapIndexed { index, set ->
                    SetState(id = set.id, done = set.id in done, kind = SetTypes.kindAt(exercise.sets, index) { it.kind })
                }
            )
        }

    /** Every set of the session in the order it is trained. */
    fun order(exercises: List<Exercise>): List<Step> {
        val groups = SetTypes.runs(exercises.map { it.group })
        val steps = mutableListOf<Step>()
        var i = 0
        while (i < exercises.size) {
            val group = groups[i]
            if (group == null) {
                for (set in exercises[i].sets.indices) {
                    steps += Step(exercise = i, set = set, block = i, round = set, grouped = false)
                }
                i += 1
                continue
            }
            var end = i
            while (end < exercises.size && groups[end] == group) end += 1
            val rounds = (i until end).maxOfOrNull { exercises[it].sets.size } ?: 0
            for (round in 0 until rounds) {
                for (member in i until end) {
                    if (round < exercises[member].sets.size) {
                        steps += Step(exercise = member, set = round, block = i, round = round, grouped = true)
                    }
                }
            }
            i = end
        }
        return steps
    }

    /** What ticking [setId] done starts. [restSeconds] is Settings' "Rest between sets", null when it is off. */
    fun after(setId: String, exercises: List<Exercise>, restSeconds: Int?): After {
        if (restSeconds == null || restSeconds <= 0) return After.Nothing
        val steps = order(exercises)
        fun set(step: Step): SetState = exercises[step.exercise].sets[step.set]
        fun waiting(step: Step): Boolean = !set(step).done && set(step).id != setId

        val position = steps.indexOfFirst { set(it).id == setId }
        if (position < 0) return After.Rest(restSeconds, before = null)
        val ticked = steps[position]

        // The round is not over while another of its sets is still to do.
        if (ticked.grouped) {
            steps.firstOrNull { it.block == ticked.block && it.round == ticked.round && waiting(it) }?.let { other ->
                return After.NoRest("Next: ${exercises[other.exercise].name}")
            }
        }

        val next = steps.drop(position + 1).firstOrNull(::waiting)
            ?: return After.Rest(restSeconds, before = exercises[ticked.exercise].restName)
        return when (set(next).kind) {
            SetKind.DROP -> After.NoRest("Drop set next")
            SetKind.REST_PAUSE -> After.Rest(REST_PAUSE_SECONDS, before = exercises[next.exercise].restName)
            null -> {
                // Back to whatever comes next inside a group; otherwise the exercise just ticked,
                // which is what the rest always said.
                val before = if (next.block == ticked.block) next.exercise else ticked.exercise
                After.Rest(restSeconds, before = exercises[before].restName)
            }
        }
    }
}
