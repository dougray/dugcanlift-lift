package com.dugcanlift.macrocalc.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutinesTest {

    @Test
    fun `conditioning target fields round-trip through json`() {
        val exercise = RoutineExercise(
            name = "Row erg",
            targetSets = 1,
            targetDurationSec = 600,
            targetDistanceMeters = 1600.0
        )
        val restored = routineExerciseFromJson(exercise.toJson())
        assertEquals(600, restored.targetDurationSec)
        assertEquals(1600.0, restored.targetDistanceMeters)
        assertNull(restored.targetReps)
    }

    @Test
    fun `old routine json with no conditioning fields still loads`() {
        val legacy = JSONObject()
            .put("id", "abc")
            .put("name", "Bench")
            .put("equipment", "Barbell")
            .put("targetSets", 3)
            .put("targetReps", 8)
            .put("note", "")
        val restored = routineExerciseFromJson(legacy)
        assertEquals(8, restored.targetReps)
        assertNull(restored.targetRpe)
        assertNull(restored.targetDurationSec)
    }
}
