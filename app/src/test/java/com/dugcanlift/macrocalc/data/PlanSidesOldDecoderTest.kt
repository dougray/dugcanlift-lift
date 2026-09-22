package com.dugcanlift.macrocalc.data

import com.dugcanlift.kit.PlanDecodeResult
import com.dugcanlift.kit.PlanLinkCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Old decoder degradation: what a LIFT Android build that has never heard of
 * PLAN-FORMAT's `b` or a sixth set position does with a plan carrying both.
 *
 * `fixtures/web-plan-per-side.txt` is a link Coach web's own encoder wrote
 * (dugcanlift-coach `coach/fixtures/`, the same bytes). Never regenerate it
 * from Kotlin: its value is that a different implementation wrote it.
 *
 * First run on 2026-09-22 against main itself (commit 292e62d on this branch:
 * kit 1.5.0's `PlanLinkCodec`, main's `PlanImporter` and `Routine.toSession`,
 * unmodified) before anything here changed, and it passed: the plan is
 * accepted, `b` and the sixth position are ignored, and every exercise
 * arrives as ordinary two-sided sets with the right count. The code that read
 * it is now the per-side decoder, so the old path is frozen below, verbatim
 * in what it reads: kit 1.5.0's `parseSet` (positions 0-4, nothing else) and
 * main's `toRoutineExercise` and `toSession`. LIFT 1.11 on a phone is exactly
 * this, and it is what the promise "old builds degrade correctly" is about.
 */
class PlanSidesOldDecoderTest {

    private fun fixtureJson(): JSONObject {
        val link = javaClass.classLoader!!.getResource("fixtures/web-plan-per-side.txt")!!.readText().trim()
        val decoded = PlanLinkCodec.decode(link.substringAfter('#'), expectedLifterId = "a1b2c3d4")
        return JSONObject((decoded as PlanDecodeResult.Success).payload.rawJson)
    }

    /** kit 1.5.0 `PlanLinkCodec.parseSet`: five positions, lenient, nothing past them read. */
    private fun oldParseSet(array: JSONArray): List<Any?> {
        fun d(i: Int): Double? {
            if (i >= array.length() || array.isNull(i)) return null
            val v = array.optDouble(i)
            return if (v.isNaN()) null else v
        }
        fun n(i: Int): Int? = d(i)?.toInt()
        return listOf(d(0), n(1), d(2), n(3), d(4))
    }

    /** main's `PlanImporter.toRoutineExercise` then `Routine.toSession`: the most common value of each field, times the count. */
    private fun oldLoggedSets(sets: List<List<Any?>>): List<List<Any?>> {
        fun mostCommon(i: Int): Any? =
            sets.map { it[i] }.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val one = (0..4).map(::mostCommon)
        return List(sets.size.coerceAtLeast(1)) { one }
    }

    @Test
    fun `the per-side fixture reads as two-sided sets, weights and reps intact`() {
        val json = fixtureJson()
        assertEquals("no version bump, so an old build does not refuse the link", 1, json.getInt("v"))
        val exercises = json.getJSONArray("w").getJSONObject(0).getJSONArray("e")
        val logged = (0 until exercises.length()).associate { i ->
            val e = exercises.getJSONObject(i)
            val s = e.getJSONArray("s")
            e.getString("n") to oldLoggedSets((0 until s.length()).map { oldParseSet(s.getJSONArray(it)) })
        }
        assertEquals(List(3) { listOf(225.0, 5, 8.0, null, null) }, logged["Back Squat"])
        assertEquals(List(3) { listOf(30.0, 8, null, null, null) }, logged["Single-Arm Dumbbell Row"])
        assertEquals(List(4) { listOf(40.0, 8, null, null, null) }, logged["Bulgarian Split Squat"])
        // The old build collapses a ramp to its most common set, so the 40 x 10 third set reads
        // as a third 60 x 8 -- every plan, sided or not. That was this app's own rule too until
        // `plan-set-fidelity`; it is now only what a build that predates `prescribed` does, and
        // the count is still right. PlanSetFidelityTest is what this app does with the same link.
        assertEquals(List(3) { listOf(60.0, 8, null, null, null) }, logged["Dumbbell Bench Press"])
        assertEquals(listOf(listOf<Any?>(null, null, null, 600, 1600.0)), logged["Suitcase Carry"])
    }
}
