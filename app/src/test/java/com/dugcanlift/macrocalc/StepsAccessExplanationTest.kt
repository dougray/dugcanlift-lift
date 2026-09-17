package com.dugcanlift.macrocalc

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Steps card's disclosure, shown before LIFT asks Health Connect for
 * anything ([StepsAccessExplanation]). Health Connect's policy wants it to say
 * what is read and why; this pins that it still does.
 */
class StepsAccessExplanationTest {
    @Test
    fun `the explanation names steps, step history, why, the phone and Send to Coach`() {
        listOf("steps", "step history", "Health Connect", "against your goal", "stay on this phone", "coach")
            .forEach { assertTrue(it, STEPS_ACCESS_EXPLANATION.contains(it)) }
    }
}
