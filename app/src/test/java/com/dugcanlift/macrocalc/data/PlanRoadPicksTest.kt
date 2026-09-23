package com.dugcanlift.macrocalc.data

import androidx.test.core.app.ApplicationProvider
import com.dugcanlift.kit.PlanDecodeResult
import com.dugcanlift.kit.PlanLinkCodec
import com.dugcanlift.kit.PlanPayload
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What this build does with a plan's road picks: the same link
 * [PlanRoadPicksOldDecoderTest] runs through main's frozen path, taken in for
 * real.
 *
 * `fixtures/web-plan-road-picks.txt` is Coach web's own encoder's output, the
 * same bytes as `dugcanlift-coach/coach/fixtures/`. Never regenerate it here:
 * its value is that a different implementation wrote it.
 */
@RunWith(RobolectricTestRunner::class)
class PlanRoadPicksTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** See [PlanImporterTest.resetSingletons]: Robolectric reuses these across methods. */
    @Before
    fun resetSingletons() {
        listOf(
            RecipeRepository::class.java,
            RoutineRepository::class.java,
            ScheduledSessionRepository::class.java,
            ImportedPlanStore::class.java,
            SettingsStore::class.java
        ).forEach { clazz ->
            val field = clazz.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        }
        SettingsStore.get(context).roadPicks = null
    }

    private fun fixture(): PlanPayload {
        val link = javaClass.classLoader!!.getResource("fixtures/web-plan-road-picks.txt")!!.readText().trim()
        val decoded = PlanLinkCodec.decode(link.substringAfter('#'), expectedLifterId = "a1b2c3d4")
        return (decoded as PlanDecodeResult.Success).payload
    }

    private fun bundledData(): RoadFoodData =
        parseRoadFood(java.io.File("src/main/assets/road-food.json").readText())

    private val fixtureIds = listOf(
        "wendys-large-chili",
        "wendys-grilled-chicken-ranch-wrap",
        "chickfila-8-ct-grilled-nuggets",
        "chickfila-grilled-filet",
        "snack-jack-links-original-beef-jerky",
        "wendys-item-withdrawn-2019"
    )

    // MARK: - The stored list

    @Test fun `picks are trimmed strings, unique, in the order they arrived`() {
        assertEquals(listOf("b", "a", "c"), RoadPicks.normalise(listOf(" b ", "a", "b", "", "a", null, "c")))
        assertEquals(emptyList<String>(), RoadPicks.normalise(emptyList()))
    }

    @Test fun `a plan with no rf says nothing about picks rather than retracting them`() {
        assertNull(RoadPicks.fromPlan("""{"v":1,"t":"plan","l":"a1b2c3d4","n":"Doug"}"""))
        assertNull(RoadPicks.fromPlan("""{"n":"Doug","rf":[]}"""))
        assertNull("junk reads as none, never as a refused plan", RoadPicks.fromPlan("""{"rf":"a"}"""))
        assertNull(RoadPicks.fromPlan("""{"rf":{"a":1}}"""))
        assertNull(RoadPicks.fromPlan("not json"))
        assertNull(RoadPicks.fromPlan("""{"rf":["","  "]}"""))
    }

    @Test fun `an unnamed coach is not a missing pick`() {
        val picks = RoadPicks.fromPlan("""{"rf":["a","a",3,"  b "]}""")!!
        assertEquals(listOf("a", "b"), picks.ids)
        assertEquals("", picks.from)
        assertEquals("Your coach’s picks", picks.label("picks"))
        assertEquals("Doug’s pick", RoadPicks(listOf("a"), from = "Doug").label("pick"))
    }

    @Test fun `stored picks round-trip through their own json`() {
        val picks = RoadPicks(ids = fixtureIds, from = "Doug", at = 1_700_000_000_000L)
        assertEquals(picks, RoadPicks.fromJson(picks.toJson()))
        assertNull(RoadPicks.fromJson(null))
        assertNull(RoadPicks.fromJson(""))
        assertNull(RoadPicks.fromJson("{}"))
    }

    // MARK: - Through the importer

    @Test fun `the fixture's picks are stored whole, with the coach's name, and the rest still imports`() = runTest {
        val result = PlanImporter.accept(fixture(), context) as PlanImportResult.Imported
        // Exactly what main imported, plus the picks. See PlanRoadPicksOldDecoderTest.
        assertEquals(1, result.recipeCount)
        assertEquals(2, result.mealCount)
        assertEquals(1, result.routineCount)
        assertEquals(1, result.sessionCount)
        assertEquals(6, result.roadPickCount)

        val stored = SettingsStore.get(context).roadPicks!!
        assertEquals("the list as sent, in order, unknown id and all", fixtureIds, stored.ids)
        assertEquals("Doug", stored.from)
        assertTrue(stored.at > 0)
    }

    @Test fun `a picks-only plan is a send of its own`() = runTest {
        val payload = PlanPayload(
            coachName = "Doug",
            recipes = emptyList(), meals = emptyList(), workouts = emptyList(), sessions = emptyList(),
            rawJson = """{"v":1,"t":"plan","l":"a1b2c3d4","n":"Doug","rf":["wendys-large-chili"]}"""
        )
        assertEquals("1 Road Food pick", PlanImporter.summarize(payload))
        val result = PlanImporter.accept(payload, context) as PlanImportResult.Imported
        assertEquals(1, result.roadPickCount)
        assertEquals(0, result.mealCount)
        assertEquals(0, result.sessionCount)
        assertEquals(listOf("wendys-large-chili"), SettingsStore.get(context).roadPicks!!.ids)
    }

    @Test fun `a plan carrying picks replaces what was stored - one with none leaves it alone`() = runTest {
        val settings = SettingsStore.get(context)
        fun plan(rf: String) = PlanPayload(
            coachName = "Doug", recipes = emptyList(), meals = emptyList(),
            workouts = emptyList(), sessions = emptyList(),
            rawJson = """{"v":1,"t":"plan","l":"a1b2c3d4","n":"Doug"$rf}"""
        )
        PlanImporter.accept(plan(""","rf":["a","b"]"""), context)
        assertEquals(listOf("a", "b"), settings.roadPicks!!.ids)

        PlanImporter.accept(plan(""","rf":["c"]"""), context)
        assertEquals("the newest send is the coach's current answer, whole", listOf("c"), settings.roadPicks!!.ids)

        // No key: silent about picks, not a retraction. Every older Coach and
        // every "here is a recipe" send looks exactly like this.
        val quiet = PlanImporter.accept(plan(""), context) as PlanImportResult.Imported
        assertEquals(0, quiet.roadPickCount)
        assertEquals(listOf("c"), settings.roadPicks!!.ids)

        val empty = PlanImporter.accept(plan(""","rf":[]"""), context) as PlanImportResult.Imported
        assertEquals(0, empty.roadPickCount)
        assertEquals(listOf("c"), settings.roadPicks!!.ids)
    }

    @Test fun `the summary names picks beside the other halves`() {
        assertEquals(
            "1 recipe, 2 meals, 1 workout scheduled across 1 day, 6 Road Food picks",
            PlanImporter.summarize(fixture())
        )
    }

    // MARK: - What the fixture asks the list to do

    @Test fun `the fixture's picks resolve at three places, and exactly one id is unknown`() {
        val data = bundledData()
        val everything = data.chains.flatMap { it.items } + data.snacks
        val known = everything.map { it.id }.toSet()
        assertEquals(
            "the deliberate one, so the skip rule is exercised by the fixture itself",
            listOf("wendys-item-withdrawn-2019"),
            fixtureIds.filterNot { it in known }
        )
        assertEquals(
            listOf("wendys", "chickfila"),
            data.chains.filter { c -> c.items.any { it.id in fixtureIds } }.map { it.id }
        )
        assertTrue("and a gas-station snack", data.snacks.any { it.id in fixtureIds })
        assertEquals(5, RoadFood.pickCount(everything, fixtureIds))
    }

    @Test fun `the unknown id is skipped where a list is drawn, and never counted`() {
        val wendys = bundledData().chains.first { it.id == "wendys" }
        val picked = RoadFood.withPicks(RoadFood.rank(wendys.items, null), fixtureIds)
        assertEquals(2, picked.count)
        assertEquals(wendys.items.size, picked.fits.size + picked.over.size)
        assertFalse(picked.picked.contains("wendys-item-withdrawn-2019"))
        assertEquals(
            "the two picks lead the chain's list, in the order the ranking had them",
            listOf("wendys-large-chili", "wendys-grilled-chicken-ranch-wrap").sorted(),
            picked.fits.take(2).map { it.id }.sorted()
        )
    }
}
