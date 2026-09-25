package com.dugcanlift.macrocalc.data

/**
 * Whether a coach's prescription has to be kept set by set.
 *
 * [RoutineExercise] has always carried one set's worth of targets and a count,
 * which can say "3 x 8 @ 60 lb" and nothing else. PLAN-FORMAT lists a
 * prescription's sets individually for exactly that reason: "Sets are listed
 * individually rather than as '3 x 5'. Coaches ramp, and a count-and-tuple
 * shape cannot say 225/225/245 without special cases." Nor can it say "five
 * reps, you pick the weight, then 225 for five", or "plus one more on the
 * left" -- and reducing the sets to the most common one does not fail loudly,
 * it silently prescribes something the coach did not write.
 *
 * So the targets stay -- a routine saved from a workout, a starter split and
 * every routine an older build wrote is nothing but targets, and a prescription
 * whose sets are all alike is exactly what they say -- and [PrescribedSet]s are
 * kept beside them whenever the targets would lose something. A plan the
 * targets can say in full is stored and serialised byte for byte as before.
 *
 * Pure and free of Android, like [PerSideLogging] and [SideBalance]: this
 * decides which numbers a lifter is asked to do, and a rule that decides that
 * cannot live in a composable's state where no test can reach it.
 */
object Prescription {

    /**
     * True when [RoutineExercise]'s targets would reproduce [sets] exactly:
     * every set the same as every other, none naming a side, and the exercise
     * not each side.
     *
     * An empty prescription flattens too. An exercise a coach sent with no sets
     * at all has always started as the one blank row `targetSets` floors at,
     * which is something to log against; no rows would be nothing.
     */
    fun flattensLosslessly(sets: List<PrescribedSet>, eachSide: Boolean): Boolean =
        !eachSide && sets.all { it == sets.firstOrNull() } && sets.firstOrNull()?.side == null

    /** The opposite of [flattensLosslessly]: these sets have to be carried one by one. */
    fun needsSetBySet(sets: List<PrescribedSet>, eachSide: Boolean): Boolean =
        !flattensLosslessly(sets, eachSide)
}
