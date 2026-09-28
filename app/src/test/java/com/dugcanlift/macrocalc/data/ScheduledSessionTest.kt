package com.dugcanlift.macrocalc.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A booking, and the file it lives in.
 *
 * `scheduled_sessions.json` is a plain JSON array this app writes and reads with no schema and no
 * version, so the two directions of compatibility are the whole contract and both are pinned here:
 * **a file an older build wrote loads unchanged**, and **a file this build writes loads in an older
 * build**. main's reader and encoder are frozen below, verbatim in what they read and write, the way
 * `PlanRoadPicksOldDecoderTest` freezes main's import.
 */
class ScheduledSessionTest {

    /* ---------------- main, frozen ---------------- */

    /** main's `scheduledSessionFromJson`, in what it reads: four keys, by name, and nothing else. */
    private fun oldReader(o: JSONObject): Map<String, String> = mapOf(
        "id" to o.optString("id", "missing"),
        "routineId" to o.optString("routineId", ""),
        "routineName" to o.optString("routineName", ""),
        "date" to o.optString("date", "")
    )

    /** main's `toJson`, unchanged. */
    private fun oldEncoder(session: ScheduledSession): JSONObject = JSONObject()
        .put("id", session.id)
        .put("routineId", session.routineId)
        .put("routineName", session.routineName)
        .put("date", session.date)

    /* ---------------- what it always did ---------------- */

    @Test
    fun `round trips through json`() {
        val session = ScheduledSession(routineId = "r1", routineName = "Lower A", date = "2026-09-10")
        val restored = scheduledSessionFromJson(session.toJson())
        assertEquals(session.routineId, restored.routineId)
        assertEquals(session.date, restored.date)
    }

    @Test
    fun `onDate filters correctly`() {
        val sessions = listOf(
            ScheduledSession(routineId = "r1", routineName = "Lower A", date = "2026-09-10"),
            ScheduledSession(routineId = "r2", routineName = "Upper A", date = "2026-09-11")
        )
        assertEquals(1, sessions.onDate("2026-09-10").size)
        assertEquals("Lower A", sessions.onDate("2026-09-10")[0].routineName)
    }

    /* ---------------- a file an older build wrote ---------------- */

    @Test
    fun `a booking an older build wrote loads unchanged and is signed by nobody`() {
        // Literal bytes, as they sit in an installed app's files dir: four keys and no more.
        val restored = scheduledSessionFromJson(JSONObject(
            """{"id":"s1","routineId":"r1","routineName":"Lower A","date":"2026-09-10"}"""
        ))
        assertEquals("s1", restored.id)
        assertEquals("r1", restored.routineId)
        assertEquals("Lower A", restored.routineName)
        assertEquals("2026-09-10", restored.date)
        // The two things this build can know about a booking and that one could not.
        assertNull("a plan accepted before this reads as From your coach", restored.fromCoach)
        assertNull("and answers whatever its day holds, pooled", restored.startedSessionId)
    }

    @Test
    fun `a booking with neither new fact writes byte for byte what main wrote`() {
        val session = ScheduledSession(routineId = "r1", routineName = "Lower A", date = "2026-09-10")
        assertEquals(oldEncoder(session).toString(), session.toJson().toString())
    }

    /* ---------------- a file this build wrote, in an older build ---------------- */

    @Test
    fun `an older build reads a file this one writes, every field it knows intact`() {
        val session = ScheduledSession(
            id = "s1", routineId = "r1", routineName = "Lower A", date = "2026-09-10",
            fromCoach = "Doug", startedSessionId = "w9"
        )
        val json = session.toJson()
        assertEquals(
            mapOf("id" to "s1", "routineId" to "r1", "routineName" to "Lower A", "date" to "2026-09-10"),
            oldReader(json)
        )
        // Additive only: the four old keys are untouched, and the two new ones are simply never
        // looked at by a build that has not heard of them.
        assertTrue(json.has("fromCoach"))
        assertTrue(json.has("startedSessionId"))
        assertEquals(6, json.length())
    }

    /* ---------------- the two new fields themselves ---------------- */

    @Test
    fun `both new fields round trip`() {
        val restored = scheduledSessionFromJson(ScheduledSession(
            routineId = "r1", routineName = "Lower A", date = "2026-09-10",
            fromCoach = "Doug", startedSessionId = "w9"
        ).toJson())
        assertEquals("Doug", restored.fromCoach)
        assertEquals("w9", restored.startedSessionId)
    }

    @Test
    fun `neither is written when there is nothing to write`() {
        val json = ScheduledSession(routineId = "r1", routineName = "Lower A", date = "2026-09-10")
            .toJson()
        assertFalse("omitted, never null", json.has("fromCoach"))
        assertFalse("omitted, never null", json.has("startedSessionId"))
    }

    @Test
    fun `a name that is not a string is not a name`() {
        // LIFT web stores `true` for "a coach, name unknown", and `optString` would read that as the
        // name "true" and sign a week with it. Nothing but this app writes this file, and a file
        // hand-edited or carried across still never names a coach "true".
        assertNull(scheduledSessionFromJson(JSONObject(
            """{"id":"s1","routineId":"r1","routineName":"Lower A","date":"2026-09-10","fromCoach":true}"""
        )).fromCoach)
        assertNull(scheduledSessionFromJson(JSONObject(
            """{"id":"s1","routineId":"r","routineName":"n","date":"d","fromCoach":"  "}"""
        )).fromCoach)
    }
}
