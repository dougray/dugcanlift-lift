package com.dugcanlift.macrocalc.data

import java.util.Locale

/**
 * A folder of routines is a rotation: Push, Pull, Legs, Push… The routine up next is the one after
 * the last one started from that folder, wrapping round, and the first of them before any has been
 * started.
 *
 * A port of LIFT iPhone's `RoutineRotation`, function for function, adapted to what this build
 * records.
 *
 * A folder's order is the order its routines were added in: [Routine.createdAt], and then the order
 * they sit in `routines.json`, which is the order they were saved. The iPhone breaks a tie in
 * `createdAt` by name because its store has no order of its own; this one does, and a tie is
 * common here -- a starter split's routines are all made in the same instant when the file is read,
 * and a coach's plan writes its routines in one go -- so a name tie-break would put Legs before
 * Push. The default folder (blank, shown as "My Routines") is not a rotation: it is where routines
 * go when nobody chose a folder, so it groups nothing on purpose.
 *
 * **Which routine a session came from.** The iPhone reads a `routine:<exercise id>` ref that its
 * start writes on every exercise. This build writes no such thing: [Routine.toSession] gives the
 * session the routine's **name** and fresh exercise ids, and nothing else. Two facts are kept, and
 * they are what is read:
 *
 * - [ScheduledSession.startedSessionId], when a coach's booking was started from Train's card: that
 *   session is that booking's routine, whatever it has been renamed since.
 * - Otherwise the session's name, matched against the routine's (trimmed, case ignored).
 *
 * So what cannot be told, said plainly: a session renamed after it was started counts as no
 * routine; a workout logged by hand and given a routine's name counts as that routine; a routine
 * renamed or deleted loses the sessions logged under its old name; and two routines of one folder
 * with the same name are both matched by one session, where the later in the rotation is where it
 * got to -- the iPhone's own rule for two of a folder's routines started on one day. A session with
 * no exercises in it is nothing trained and is not counted.
 *
 * Value inputs rather than repositories, so the rule is tested without files.
 */
object RoutineRotation {
    data class Member(
        val id: String,
        val createdAt: Long,
        val name: String
    )

    /**
     * One date's sessions, as far as rotations can read them: the routines they were started from
     * by a recorded booking ([routineIds]), and the [names][nameKey] of the rest.
     */
    data class Day(
        /** `yyyy-MM-dd`, which sorts as the dates do. */
        val date: String,
        val routineIds: Set<String> = emptySet(),
        val sessionNames: Set<String> = emptySet()
    )

    data class UpNext(
        val routineId: String,
        /** The routine the rotation last moved through, null before the first. */
        val after: String?,
        val lastDate: String?
    )

    /** How a session's name and a routine's are compared. */
    fun nameKey(name: String): String = name.trim().lowercase(Locale.ROOT)

    /** Stable: equal `createdAt` keeps the order given, which is the order the routines were saved. */
    fun ordered(members: List<Member>): List<Member> = members.sortedBy { it.createdAt }

    private fun startedOn(member: Member, day: Day): Boolean =
        member.id in day.routineIds || nameKey(member.name).let { it.isNotEmpty() && it in day.sessionNames }

    /** Null for a folder of one, or the default folder. */
    fun upNext(folder: String, members: List<Member>, days: List<Day>): UpNext? {
        if (folder.isBlank() || members.size < 2) return null
        val order = ordered(members)

        for (day in days.sortedByDescending { it.date }) {
            // Two of a folder's routines started on one day: the later one in the rotation is where
            // it got to.
            val index = order.indices.lastOrNull { startedOn(order[it], day) } ?: continue
            return UpNext(
                routineId = order[(index + 1) % order.size].id,
                after = order[index].id,
                lastDate = day.date
            )
        }
        return UpNext(routineId = order[0].id, after = null, lastDate = null)
    }

    data class Current(val folder: String, val upNext: UpNext)

    /**
     * Of every folder's up next, the one whose rotation was trained most recently: that is the
     * programme someone is actually running. Null when no folder's rotation has been started at all,
     * because the first routine of every folder is not a suggestion anybody asked for.
     */
    fun current(folders: List<Pair<String, List<Member>>>, days: List<Day>): Current? =
        folders
            .mapNotNull { (folder, members) -> upNext(folder, members, days)?.let { Current(folder, it) } }
            .filter { it.upNext.lastDate != null }
            .maxByOrNull { it.upNext.lastDate!! }

    /* ---------- from the stores ---------- */

    /** Routines by their stored folder, in the order they are saved. */
    fun folders(routines: List<Routine>): List<Pair<String, List<Member>>> =
        routines.groupBy { it.folder }.map { (folder, list) -> folder to list.map(::member) }

    fun member(routine: Routine): Member = Member(id = routine.id, createdAt = routine.createdAt, name = routine.name)

    /**
     * Every date's sessions as [Day]s. A session a booking recorded starting is that booking's
     * routine and nothing else; any other session is read by its name.
     */
    fun days(sessions: List<WorkoutSession>, bookings: List<ScheduledSession>): List<Day> {
        val startedBy = bookings.mapNotNull { b -> b.startedSessionId?.let { it to b.routineId } }.toMap()
        return sessions
            .filter { it.exercises.isNotEmpty() }
            .groupBy { it.date }
            .map { (date, list) ->
                val (recorded, named) = list.partition { it.id in startedBy }
                Day(
                    date = date,
                    routineIds = recorded.mapNotNull { startedBy[it.id] }.toSet(),
                    sessionNames = named.map { nameKey(it.name) }.filter { it.isNotEmpty() }.toSet()
                )
            }
    }

    /** Each rotation folder's next routine, by id -- what Routines marks "Up next". */
    fun upNextIds(routines: List<Routine>, days: List<Day>): Set<String> =
        folders(routines).mapNotNull { (folder, members) -> upNext(folder, members, days)?.routineId }.toSet()

    /**
     * One folder's routines as Routines lists them: a named folder in rotation order, so the list
     * reads as the rotation, and the default folder in the order it has always had.
     */
    fun listed(routines: List<Routine>): List<Routine> =
        if (routines.any { it.folder.isBlank() }) routines else routines.sortedBy { it.createdAt }

    const val FOOTER = "A folder is a rotation: Up next is the routine after the last one you started from it."

    /** Train's offer: "Up next: Pull · Push/Pull/Legs". */
    fun offer(routineName: String, folder: String): String = "Up next: $routineName · $folder"
}
