package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduledSessionTest {

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
}
