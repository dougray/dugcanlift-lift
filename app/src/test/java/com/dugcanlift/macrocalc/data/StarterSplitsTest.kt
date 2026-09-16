package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Run against the **shipped assets**, both of them, because the thing most
 * worth proving about a starter split is that it agrees with the library.
 */
class StarterSplitsTest {

    private val splits: List<Routine> by lazy {
        parseStarterSplits(File("src/main/assets/$STARTER_SPLITS_ASSET").readText())
    }

    private val library: List<LibraryExercise> by lazy {
        parseExerciseLibrary(File("src/main/assets/$EXERCISE_LIBRARY_ASSET").readText())
    }

    // MARK: - The names are the library's names

    @Test fun `every exercise in every split exists in the library`() {
        // This is the reason splits waited on the library. A name that does
        // not match what the app searches generates history that lines up with
        // nothing: no previous-set lookup, no trend line, no coach agreement.
        val known = library.map { it.name }.toSet()
        val strangers = splits.flatMap { routine ->
            routine.exercises.filterNot { it.name in known }.map { "${routine.name}: ${it.name}" }
        }
        assertTrue("not in exercises.json — $strangers", strangers.isEmpty())
    }

    @Test fun `every equipment string is what the library would have stored`() {
        // "Barbell", not "barbell". If these disagree, a set logged from a
        // split and the same lift picked from the picker are two lifts.
        val byName = library.associateBy { it.name }
        val wrong = splits.flatMap { routine ->
            routine.exercises.mapNotNull { exercise ->
                val expected = byName[exercise.name]?.storedEquipment ?: return@mapNotNull null
                if (exercise.equipment == expected) null
                else "${exercise.name}: '${exercise.equipment}' should be '$expected'"
            }
        }
        assertTrue(wrong.toString(), wrong.isEmpty())
    }

    @Test fun `a split's exercises match the library's own identity key`() {
        val squat = splits.first { it.name == "Legs" }.exercises.first()
        val fromLibrary = library.first { it.name == squat.name }
        assertEquals(
            "a logged set and a prescribed one have to be the same lift",
            LoggedExercise(name = squat.name, equipment = squat.equipment).matchKey,
            fromLibrary.matchKey
        )
    }

    // MARK: - What shipped

    @Test fun `the ten splits are there, in five folders`() {
        assertEquals(10, splits.size)
        assertEquals(
            listOf("Push/Pull/Legs", "Upper/Lower", "Full Body", "Mobility & Recovery", "Running"),
            splits.map { it.folder }.distinct()
        )
        assertEquals(
            listOf("Push", "Pull", "Legs", "Upper", "Lower", "Full Body",
                   "Mobility", "Active Rest", "Short Run", "Long Run"),
            splits.map { it.name }
        )
    }

    @Test fun `mobility, recovery and running are timed, never repped`() {
        // A stretch held for 45 s and a 20 minute run have no rep count. A
        // rep target here would open a set asking how many reps of a run.
        val timed = splits.filter { it.folder == "Mobility & Recovery" || it.folder == "Running" }
        assertEquals(4, timed.size)
        timed.flatMap { r -> r.exercises.map { r.name to it } }.forEach { (name, exercise) ->
            assertNull("$name/${exercise.name} has reps", exercise.targetReps)
            assertTrue("$name/${exercise.name} has no time", (exercise.targetDurationSec ?: 0) > 0)
        }
    }

    @Test fun `the long run is longer than the short one`() {
        fun runSeconds(name: String) = splits.first { it.name == name }.exercises
            .filter { it.name == "Trail Running/Walking" }.sumOf { it.targetDurationSec ?: 0 }
        assertTrue(runSeconds("Long Run") > runSeconds("Short Run"))
    }

    @Test fun `every split holds real work`() {
        splits.forEach { routine ->
            assertTrue("${routine.name} has no exercises", routine.exercises.isNotEmpty())
            routine.exercises.forEach {
                assertTrue("${routine.name}/${it.name} has no sets", it.targetSets > 0)
                assertTrue(
                    "${routine.name}/${it.name} prescribes neither reps nor time",
                    it.targetReps != null || it.targetDurationSec != null
                )
            }
        }
    }

    @Test fun `push pull legs does not deadlift twice in a rotation`() {
        // Doug's call on 2026-09-14: Barbell Deadlift on Pull and a Romanian
        // Deadlift on Legs in the same week is too much posterior chain for
        // whoever is most likely to tap a canned split. If a later edit puts a
        // second one back, this should be a decision rather than an accident.
        val ppl = splits.filter { it.folder == "Push/Pull/Legs" }
        val deadlifts = ppl.flatMap { r -> r.exercises.filter { "deadlift" in it.name.lowercase() } }
        assertEquals("deadlifts across PPL: ${deadlifts.map { it.name }}", 1, deadlifts.size)
    }

    @Test fun `the timed exercise is timed and not repped`() {
        val plank = splits.first { it.name == "Full Body" }.exercises.last()
        assertEquals("Plank", plank.name)
        assertEquals(45, plank.targetDurationSec)
        assertNull("a plank has no rep count", plank.targetReps)
    }

    // MARK: - Adding one

    @Test fun `each read mints fresh ids so a starter is never the copy`() {
        val first = parseStarterSplits(File("src/main/assets/$STARTER_SPLITS_ASSET").readText())
        val second = parseStarterSplits(File("src/main/assets/$STARTER_SPLITS_ASSET").readText())
        assertNotEquals(first[0].id, second[0].id)
        assertNotEquals(first[0].exercises[0].id, second[0].exercises[0].id)
    }

    @Test fun `a starter already added is recognised by folder and name`() {
        val starter = splits.first()
        assertTrue(listOf(starter).alreadyHas(starter))
        assertTrue("case should not matter",
                   listOf(starter.copy(name = starter.name.uppercase())).alreadyHas(starter))
        assertFalse(emptyList<Routine>().alreadyHas(starter))
    }

    @Test fun `a renamed copy gets the starter offered again`() {
        // The alternative is a starter that disappears because of an edit made
        // to something else, which is worse than an occasional duplicate.
        val starter = splits.first()
        assertFalse(listOf(starter.copy(name = "Monday")).alreadyHas(starter))
    }

    @Test fun `two splits sharing a name in different folders are different routines`() {
        val lower = splits.first { it.name == "Lower" }
        assertFalse(listOf(lower.copy(folder = "Something else")).alreadyHas(lower))
    }

    // MARK: - Malformed input

    @Test fun `one bad entry costs that split, not the list`() {
        val json = """
            {"routines":[
              {"folder":"F","name":"Good","exercises":[{"name":"Squat","sets":3,"reps":5}]},
              {"folder":"F","name":"","exercises":[{"name":"Squat","sets":3,"reps":5}]},
              {"folder":"F","name":"Empty","exercises":[]},
              {"folder":"F","name":"Nameless exercise","exercises":[{"name":"","sets":3}]}
            ]}
        """.trimIndent()
        val parsed = parseStarterSplits(json)
        assertEquals("only the good one survives", 1, parsed.size)
        assertEquals("Good", parsed[0].name)
    }

    @Test fun `a missing sets count falls back rather than shipping zero sets`() {
        val parsed = parseStarterSplits(
            """{"routines":[{"name":"X","exercises":[{"name":"Squat"}]}]}"""
        )
        assertEquals(3, parsed[0].exercises[0].targetSets)
        assertNull(parsed[0].exercises[0].targetReps)
    }

    @Test fun `an empty document is empty, not a crash`() {
        assertTrue(parseStarterSplits("""{"v":1}""").isEmpty())
    }
}
