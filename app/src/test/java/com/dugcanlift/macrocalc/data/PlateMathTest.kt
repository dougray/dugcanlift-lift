package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which plates go on the bar. A port of LIFT iPhone's `PlateMathTests`, case for case. This build
 * is pounds only, so the iPhone's kilogram rack is built by hand where a case needs one, and its
 * "kept per unit" case is one rack kept and read back.
 */
class PlateMathTest {

    /* ---------- which bar ---------- */

    @Test fun `the bar comes from the name and the equipment`() {
        assertEquals(BarKind.BARBELL, BarKind.of("Barbell Squat", "barbell"))
        assertEquals(BarKind.BARBELL, BarKind.of("Bench Press", "Barbell"))
        assertEquals(BarKind.EZ_BAR, BarKind.of("EZ-Bar Curl", "e-z curl bar"))
        // The exercise data files some EZ-bar lifts under "barbell".
        assertEquals(BarKind.EZ_BAR, BarKind.of("Close-Grip EZ Bar Curl", "barbell"))
        // And every Smith machine lift under "machine", or even "barbell".
        assertEquals(BarKind.SMITH, BarKind.of("Smith Machine Squat", "machine"))
        assertEquals(BarKind.SMITH, BarKind.of("Smith Incline Shoulder Raise", "barbell"))
        assertEquals(BarKind.TRAP_BAR, BarKind.of("Trap Bar Deadlift", null))
        assertEquals(BarKind.TRAP_BAR, BarKind.of("Hex Bar Deadlift", "other"))
        assertEquals(BarKind.SMITH, BarKind.of("Back squat", "Smith machine"))
        // This build's own equipment list spells it "Smith Machine".
        assertEquals(BarKind.SMITH, BarKind.of("Squat", "Smith Machine"))
    }

    @Test fun `lifts without a bar get no plates`() {
        assertNull(BarKind.of("Dumbbell Bench Press", "dumbbell"))
        assertNull(BarKind.of("Leg Press", "machine"))
        assertNull(BarKind.of("Pull-up", "body only"))
        assertNull(BarKind.of("Bench Press", null))
        // One end in the floor: "per side" means nothing.
        assertNull(BarKind.of("Landmine 180's", "barbell"))
        assertNull(BarKind.of("T-Bar Row with Handle", "barbell"))
        assertNull(BarKind.of("Bent Over One-Arm Long Bar Row", "barbell"))
        assertNull(BarKind.of("Single-Arm Linear Jammer", "barbell"))
    }

    @Test fun `EZ bar spellings`() {
        assertEquals(BarKind.EZ_BAR, BarKind.of("EZBar Curl", "barbell"))
        assertEquals(BarKind.EZ_BAR, BarKind.of("Reverse EZ Curl", "barbell"))
        // "Blacksmith" is not a Smith machine.
        assertEquals(BarKind.BARBELL, BarKind.of("Blacksmith Squat", "barbell"))
    }

    /* ---------- loading ---------- */

    private val gym = PlateSetup.standard()

    private fun plates(perSide: List<Double>, total: Double, shortBy: Double) = PlateMath.Load.Plates(perSide, total, shortBy)

    /** The iPhone's standard kilogram rack, for the case that loads one. */
    private val gymKilos = PlateSetup(
        plates = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25).map { PlateSetup.Plate(it, 10) } + PlateSetup.Plate(0.5, 0),
        bars = mapOf()
    ).withBar(BarKind.BARBELL, 20.0)

    @Test fun `an exact load in pounds`() {
        assertEquals(plates(listOf(45.0, 45.0), 225.0, 0.0), PlateMath.load(225.0, BarKind.BARBELL, gym))
        assertEquals(plates(listOf(45.0, 25.0), 185.0, 0.0), PlateMath.load(185.0, BarKind.BARBELL, gym))
        assertEquals(plates(listOf(45.0, 2.5), 140.0, 0.0), PlateMath.load(140.0, BarKind.BARBELL, gym))
    }

    @Test fun `an exact load in kilograms`() {
        assertEquals(plates(listOf(25.0, 15.0, 1.25), 102.5, 0.0), PlateMath.load(102.5, BarKind.BARBELL, gymKilos))
    }

    /** Fewest plates, then heavier plates first: 70 a side is 45 + 25, and 35 + 35 only when there are no 25s. */
    @Test fun `fewest plates wins`() {
        assertEquals(2, PlateMath.load(185.0, BarKind.BARBELL, gym).perSide.size)
        val noTwentyFives = gym.withPairs(0, 25.0)
        assertEquals(plates(listOf(35.0, 35.0), 185.0, 0.0), PlateMath.load(185.0, BarKind.BARBELL, noTwentyFives))
    }

    private fun empty(): PlateSetup = gym.plates.fold(gym) { setup, plate -> setup.withPairs(0, plate.weight) }

    /** A home gym with one pair of 45s and one of 25s cannot make 225: the heaviest load under it, and how far short. */
    @Test fun `what you own limits the load`() {
        val home = empty().withPairs(1, 45.0).withPairs(1, 25.0)
        assertEquals(plates(listOf(45.0, 25.0), 185.0, 40.0), PlateMath.load(225.0, BarKind.BARBELL, home))
    }

    /** Greedy takes the 45 and then cannot make 15 out of 10s it does not own. */
    @Test fun `it is not greedy`() {
        val setup = empty().withPairs(1, 45.0).withPairs(2, 30.0)
        assertEquals(plates(listOf(30.0, 30.0), 165.0, 0.0), PlateMath.load(165.0, BarKind.BARBELL, setup))
    }

    @Test fun `a weight that needs half a plate is rounded down and says so`() {
        assertEquals(plates(listOf(45.0, 45.0), 225.0, 2.0), PlateMath.load(227.0, BarKind.BARBELL, gym))
    }

    /** Heavier than the bar by less than the smallest plate owned: the bar, and how far short. */
    @Test fun `less than the smallest plate over the bar`() {
        val load = PlateMath.load(47.5, BarKind.BARBELL, gym)
        assertEquals(plates(emptyList(), 45.0, 2.5), load)
        assertEquals("Your plates make 45 lb, 2.5 short. Just the bar", load.line())
    }

    @Test fun `an absurd weight is bounded by the rack`() {
        val load = PlateMath.load(100_000_000.0, BarKind.BARBELL, gym) as PlateMath.Load.Plates
        assertEquals(60, load.perSide.size)
        assertEquals(45.0 + 2 * 1225, load.total, 0.0)
        assertTrue(load.shortBy > 0)
    }

    @Test fun `the bar alone and less than the bar`() {
        assertEquals(PlateMath.Load.JustTheBar, PlateMath.load(45.0, BarKind.BARBELL, gym))
        assertEquals(PlateMath.Load.LighterThanTheBar, PlateMath.load(30.0, BarKind.BARBELL, gym))
        assertEquals(PlateMath.Load.Nothing, PlateMath.load(0.0, BarKind.BARBELL, gym))
    }

    @Test fun `each bar has its own weight`() {
        assertEquals(plates(listOf(25.0), 75.0, 0.0), PlateMath.load(75.0, BarKind.EZ_BAR, gym))
        val setup = gym.withBar(BarKind.TRAP_BAR, 60.0)
        assertEquals(plates(listOf(45.0), 150.0, 0.0), PlateMath.load(150.0, BarKind.TRAP_BAR, setup))
    }

    /** A weight a hair under from arithmetic (the iPhone's kilograms coming back) is not short by a hair. */
    @Test fun `a converted weight is not short by a hair`() {
        val total = 225.0 * 0.45359237 / 0.45359237 - 1e-9
        assertEquals(plates(listOf(45.0, 45.0), 225.0, 0.0), PlateMath.load(total, BarKind.BARBELL, gym))
    }

    /* ---------- words ---------- */

    @Test fun `the line`() {
        assertEquals("Per side: 45 · 25 · 2.5", plates(listOf(45.0, 25.0, 2.5), 190.0, 0.0).line())
        assertEquals("Your plates make 185 lb, 40 short. Per side: 45 · 25", plates(listOf(45.0, 25.0), 185.0, 40.0).line())
        assertEquals("Just the bar", PlateMath.Load.JustTheBar.line())
        assertEquals("Lighter than the bar", PlateMath.Load.LighterThanTheBar.line())
        assertNull(PlateMath.Load.Nothing.line())
    }

    /* ---------- setup ---------- */

    @Test fun `the standard rack`() {
        assertEquals(listOf(45.0, 35.0, 25.0, 10.0, 5.0, 2.5, 1.25), gym.plates.map { it.weight })
        assertEquals(45.0, gym.barWeight(BarKind.BARBELL), 0.0)
        // Fractional plates are not in most gyms; they are there to be added.
        assertEquals(0, gym.pairs(1.25))
    }

    @Test fun `a setup is kept and read back`() {
        assertEquals(gym, PlateSetup.fromJson(null))
        val mine = gym.withPairs(2, 45.0).withBar(BarKind.BARBELL, 35.0)
        assertEquals(mine, PlateSetup.fromJson(mine.toJson()))
    }

    @Test fun `an unreadable setup is the standard one`() {
        assertEquals(gym, PlateSetup.fromJson("nonsense"))
    }
}
