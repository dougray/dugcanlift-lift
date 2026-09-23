package com.dugcanlift.macrocalc.data

import com.dugcanlift.macrocalc.MacroResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

/**
 * A port of LIFT web's `lift/road-food.test.mjs`, test for test, so the two
 * rankings are held to the same cases -- plus what only Android has: the
 * [FoodEntry] a logged item becomes, [remainingFor] as Home and Road Food both
 * read it, and the file parser.
 */
class RoadFoodTest {

    private fun item(name: String, kcal: Double?, proteinG: Double?, sodiumMg: Double? = null) =
        RoadFoodItem(id = name, name = name, kcal = kcal, proteinG = proteinG, sodiumMg = sodiumMg)

    private fun item(name: String, kcal: Int, proteinG: Int, sodiumMg: Int) =
        item(name, kcal.toDouble(), proteinG.toDouble(), sodiumMg.toDouble())

    private fun names(list: List<RoadFoodItem>) = list.map { it.name }

    // MARK: - No goal

    @Test fun `with no goal, everything is ranked by protein per 100 kcal alone`() {
        val r = RoadFood.rank(listOf(
            item("low", 500, 10, 100),      // 2 per 100
            item("high", 200, 40, 900),     // 20 per 100
            item("mid", 400, 30, 300),      // 7.5 per 100
            item("huge", 2000, 120, 3000),  // 6 per 100 -- no calorie cut without a goal
        ), null)
        assertEquals(RoadFood.Mode.NO_GOAL, r.mode)
        assertEquals(listOf("high", "mid", "huge", "low"), names(r.fits))
        assertEquals(emptyList<RoadFoodItem>(), r.over)
    }

    @Test fun `no goal is what remainingFor gives with no goal set, and the screen gets no fit line`() {
        assertNull(remainingFor(null as MacroResult?, emptyList()))
        assertNull(RoadFood.fitHeadline(null))
        assertEquals(RoadFood.Mode.NO_GOAL, RoadFood.rank(listOf(item("a", 100, 10, 1)), null).mode)
    }

    // MARK: - Fits and a little over

    @Test fun `everything fits - one ranked group and nothing over`() {
        val r = RoadFood.rank(listOf(item("a", 300, 15, 500), item("b", 200, 30, 400), item("c", 640, 40, 1000)), 640)
        assertEquals(RoadFood.Mode.GOAL, r.mode)
        assertEquals(listOf("b", "c", "a"), names(r.fits))
        assertTrue(r.over.isEmpty())
    }

    @Test fun `nothing fits - fits is empty, and only the 10 percent band is offered`() {
        val r = RoadFood.rank(listOf(item("near", 330, 30, 500), item("far", 900, 60, 1200)), 300)
        assertTrue(r.fits.isEmpty())
        assertEquals(listOf("near"), names(r.over))
    }

    @Test fun `a day already over its calories - only a zero-calorie item fits, and nothing is a little over`() {
        val r = RoadFood.rank(listOf(item("cola", 0, 0, 40), item("bar", 200, 20, 180)), -150)
        assertEquals("a zero-calorie drink still fits nothing left", listOf("cola"), names(r.fits))
        assertTrue(r.over.isEmpty())
    }

    @Test fun `the 10 percent boundary - exactly 10 percent over is a little over, one calorie past it is hidden`() {
        val r = RoadFood.rank(listOf(
            item("exactly-left", 640, 30, 500),
            item("exactly-10", 704, 30, 500),
            item("past-10", 705, 30, 500),
        ), 640)
        assertEquals(listOf("exactly-left"), names(r.fits))
        assertEquals(listOf("exactly-10"), names(r.over))
        // 30 x 1.1 is 33.000000000000004 in floating point; the whole-number
        // comparison must not let that decide it either way.
        val small = RoadFood.rank(listOf(item("33", 33, 1, 1), item("34", 34, 1, 1)), 30)
        assertEquals(listOf("33"), names(small.over))
    }

    @Test fun `the over group is ranked by the same rule as fits`() {
        val r = RoadFood.rank(listOf(item("lean", 700, 60, 900), item("fatty", 690, 20, 900)), 640)
        assertEquals(listOf("lean", "fatty"), names(r.over))
    }

    // MARK: - Blank stays blank

    @Test fun `missing protein ranks after every known protein, never as zero`() {
        val r = RoadFood.rank(listOf(
            item("unknown", 300.0, null, 100.0),
            item("diet-cola", 0, 0, 40),
            item("some", 300, 5, 900),
        ), 640)
        assertEquals(listOf("some", "diet-cola", "unknown"), names(r.fits))
        assertNull(RoadFood.proteinPer100(item("x", 300.0, null)))
    }

    @Test fun `missing calories cannot fit, and ranks last with no goal`() {
        val noKcal = item("no-kcal", null, 30.0)
        val r = RoadFood.rank(listOf(noKcal, item("ok", 300, 10, 1)), 640)
        assertEquals(listOf("ok"), names(r.fits))
        val n = RoadFood.rank(listOf(noKcal, item("ok", 300, 10, 1)), null)
        assertEquals(listOf("ok", "no-kcal"), names(n.fits))
        assertFalse("nothing to log without a calorie figure", RoadFood.canLog(noKcal))
    }

    @Test fun `ties go to lower sodium, and a missing sodium loses the tie rather than winning as zero`() {
        val r = RoadFood.rank(listOf(
            item("salty", 200, 20, 1200),
            item("unlisted", 200.0, 20.0, null),
            item("mild", 200, 20, 300),
        ), 640)
        assertEquals(listOf("mild", "salty", "unlisted"), names(r.fits))
    }

    @Test fun `density, not grams - a small high-protein item beats a big one`() {
        assertEquals(listOf("small", "big"), names(RoadFood.rank(listOf(item("big", 600, 45, 1), item("small", 150, 25, 1)), null).fits))
        assertEquals(15.0, RoadFood.proteinPer100(item("x", 200.0, 30.0))!!, 0.0)
        assertEquals(0.0, RoadFood.proteinPer100(item("x", 0.0, 0.0))!!, 0.0)
        assertEquals(Double.POSITIVE_INFINITY, RoadFood.proteinPer100(item("x", 0.0, 5.0))!!, 0.0)
    }

    // MARK: - A coach's picks
    //
    // A port of `road-food.test.mjs`'s own picks block, case for case.

    @Test fun `picks go to the top of a group and change nothing underneath`() {
        val items = listOf(
            item("a", 300, 15, 500),   // 5 per 100
            item("b", 200, 30, 400),   // 15
            item("c", 640, 40, 1000),  // 6.25
            item("d", 100, 2, 200),    // 2
        )
        val plain = RoadFood.rank(items, 640)
        assertEquals(listOf("b", "c", "a", "d"), names(plain.fits))

        val picked = RoadFood.withPicks(plain, listOf("a", "d"))
        assertEquals(listOf("a", "d", "b", "c"), names(picked.fits))
        assertEquals(2, picked.count)
        assertEquals(setOf("a", "d"), picked.picked)
        // Within each part the nutrition order is exactly what it was: the
        // picks in their own order, then everything else in theirs.
        assertEquals(names(plain.fits).filter { it == "a" || it == "d" }, names(picked.fits).take(2))
        assertEquals(names(plain.fits).filter { it != "a" && it != "d" }, names(picked.fits).drop(2))
        assertEquals(RoadFood.Mode.GOAL, picked.mode)
    }

    @Test fun `an id the bundled data does not have is skipped silently, never a row`() {
        val items = listOf(item("a", 300, 15, 500), item("b", 200, 30, 400))
        val picked = RoadFood.withPicks(RoadFood.rank(items, 640), listOf("gone-2019", "a", ""))
        assertEquals(listOf("a", "b"), names(picked.fits))
        assertEquals("only what is here is counted, so no card promises a missing row", 1, picked.count)
        assertEquals("nothing is added for an id nothing knows", 2, picked.fits.size)
    }

    @Test fun `no picks at all leaves the ranking exactly as it was`() {
        val items = listOf(item("a", 300, 15, 500), item("b", 200, 30, 400), item("c", 700, 60, 100))
        val plain = RoadFood.rank(items, 640)
        val picked = RoadFood.withPicks(plain, emptyList())
        assertEquals(names(plain.fits), names(picked.fits))
        assertEquals(names(plain.over), names(picked.over))
        assertEquals(0, picked.count)
    }

    @Test fun `a pick that is a little over stays a little over - the fit rule is the day talking`() {
        val items = listOf(item("fits", 300, 15, 500), item("over", 700, 60, 100))
        val picked = RoadFood.withPicks(RoadFood.rank(items, 640), listOf("over"))
        assertEquals(listOf("fits"), names(picked.fits))
        assertEquals("floated to the top of its own group, not out of it", listOf("over"), names(picked.over))
        assertEquals(1, picked.count)
    }

    @Test fun `an item too far over is hidden whether or not it was picked`() {
        val items = listOf(item("fits", 300, 15, 500), item("way-over", 2000, 60, 100))
        val picked = RoadFood.withPicks(RoadFood.rank(items, 640), listOf("way-over"))
        assertEquals(listOf("fits"), names(picked.fits))
        assertTrue(picked.over.isEmpty())
        assertEquals("a pick nobody can see is not counted as shown", 0, picked.count)
    }

    @Test fun `with no goal the picks lead the one ranked list`() {
        val items = listOf(item("a", 500, 10, 100), item("b", 200, 40, 900), item("c", 400, 30, 300))
        val picked = RoadFood.withPicks(RoadFood.rank(items, null), listOf("a"))
        assertEquals(RoadFood.Mode.NO_GOAL, picked.mode)
        assertEquals(listOf("a", "b", "c"), names(picked.fits))
    }

    @Test fun `pickCount counts a place without drawing its list`() {
        val items = listOf(item("a", 1, 1, 1), item("b", 1, 1, 1))
        assertEquals(1, RoadFood.pickCount(items, listOf("b", "gone")))
        assertEquals(0, RoadFood.pickCount(items, emptyList()))
        assertEquals(0, RoadFood.pickCount(emptyList(), listOf("a")))
    }

    // MARK: - How old the numbers are

    @Test fun `six calendar months is the line, and a missing date is said to be missing`() {
        assertEquals("exactly six months is not over", false, RoadFood.isStale("2026-03-20", "2026-09-20"))
        assertEquals(true, RoadFood.isStale("2026-03-20", "2026-09-21"))
        assertEquals(false, RoadFood.isStale("2026-09-20", "2026-09-20"))
        assertEquals("end of month clamps, not rolls", false, RoadFood.isStale("2026-03-31", "2026-09-30"))
        assertEquals(true, RoadFood.isStale("2026-03-31", "2026-10-01"))
        assertNull(RoadFood.isStale(null, "2026-09-20"))
        assertNull(RoadFood.isStale("2026-02-30", "2026-09-20"))
    }

    @Test fun `the warning keys off the chain's own document date, and falls back to when it was read`() {
        // Burger King: a NOVEMBER 2022 chart read this morning. The day it was
        // read says nothing about how old the numbers are, so it is not what is
        // measured.
        val bk = chain(publishedOn = "2022-11", checkedOn = "2026-09-23")
        assertEquals("2022-11", RoadFood.ageDate(bk))
        assertEquals(true, RoadFood.isStale(RoadFood.ageDate(bk), "2026-09-23"))
        // A chain whose document states no date is exactly as it was before this:
        // the day a person read it is all there is to go on.
        val undated = chain(checkedOn = "2026-09-20")
        assertEquals("2026-09-20", RoadFood.ageDate(undated))
        assertEquals(false, RoadFood.isStale(RoadFood.ageDate(undated), "2026-09-23"))
        assertEquals(true, RoadFood.isStale(RoadFood.ageDate(chain(checkedOn = "2025-12-01")), "2026-09-23"))
        // A fresh document read long ago is not stale, and a stale document read
        // this morning is: the document is the fact, not the reading.
        val fresh = chain(publishedOn = "2026-09-02", checkedOn = "2025-01-01")
        assertEquals(false, RoadFood.isStale(RoadFood.ageDate(fresh), "2026-09-23"))
        // Neither date is no date, which the screen says in its own words.
        assertNull(RoadFood.ageDate(chain()))
        assertNull(RoadFood.ageDate(null))
    }

    @Test fun `a month-only document date is read as the first of that month`() {
        // "NOVEMBER 2022" is all Burger King's chart says, so no day is invented:
        // the first of the month can only make a document look older, never fresher.
        assertEquals(LocalDate.of(2022, 11, 1), RoadFood.parseDocDay("2022-11"))
        assertEquals(LocalDate.of(2021, 3, 29), RoadFood.parseDocDay("2021-03-29"))
        assertEquals("exactly six months is not over", false, RoadFood.isStale("2026-03", "2026-09-01"))
        assertEquals(true, RoadFood.isStale("2026-03", "2026-09-02"))
        assertNull(RoadFood.parseDocDay("2022-13"))
        assertNull(RoadFood.parseDocDay("2022"))
        assertNull(RoadFood.parseDocDay(null))
        assertNull(RoadFood.isStale("2022-13", "2026-09-23"))
    }

    private fun chain(publishedOn: String? = null, checkedOn: String? = null) =
        RoadFoodChain(id = "c", name = "Chain", publishedOn = publishedOn, checkedOn = checkedOn, items = emptyList())

    // MARK: - Rules, picker, logging

    @Test fun `plain rules apply everywhere, kinded ones only to their kind`() {
        val rules = parseRoadFood("""
            {"chains": [], "rules": ["Grilled over fried", {"text": "Bowl not tortilla", "kinds": ["mexican"]},
              {"text": "Anywhere"}, 7, {"text": "Buy the jerky", "kinds": ["snacks"]}]}
        """).rules
        assertEquals(listOf("Grilled over fried", "Anywhere"), RoadFood.rulesFor(rules, "burgers"))
        assertEquals(listOf("Grilled over fried", "Bowl not tortilla", "Anywhere"), RoadFood.rulesFor(rules, "mexican"))
        assertEquals(emptyList<String>(), RoadFood.rulesFor(emptyList(), "x"))
        assertEquals("the gas station gets only rules written for it", listOf("Buy the jerky"), RoadFood.snackRules(rules))
    }

    @Test fun `recent chains first, most recent first, the rest by name`() {
        val chains = listOf("c" to "Cee", "a" to "Aye", "b" to "Bee").map { (id, name) -> RoadFoodChain(id, name, items = emptyList()) }
        val o = RoadFood.orderChains(chains, listOf("b", "gone", "b"))
        assertEquals(listOf("b"), o.recent.map { it.id })
        assertEquals(listOf("a", "c"), o.rest.map { it.id })
        assertEquals(listOf("c", "a", "b"), RoadFood.remember(listOf("a", "b", "c"), "c", 3))
        assertEquals(listOf("d", "a", "b"), RoadFood.remember(listOf("a", "b", "c"), "d", 3))
    }

    @Test fun `a logged item is an ordinary entry, and what it does not list is absent, not zero`() {
        val sandwich = RoadFoodItem(id = "s", name = "Grilled Test Sandwich", kcal = 370.0, proteinG = 34.0,
            fatG = 10.0, carbsG = 37.0, saturatedFatG = 2.04, sodiumMg = 930.4)
        val e = RoadFood.entryFor(sandwich, "Sample Burger Co", Meal.LUNCH, date = "2026-09-20", loggedAt = 5L, id = "x")
        assertEquals(FoodEntry(
            id = "x", name = "Grilled Test Sandwich (Sample Burger Co)", servings = 1.0,
            calories = 370, proteinG = 34, fatG = 10, carbsG = 37, fiberG = 0,
            date = "2026-09-20", loggedAt = 5L, meal = "LUNCH",
            saturatedFatG = 2.0, sugarG = null, sodiumMg = 930.0
        ), e)
        assertNull("sugar not listed stays not recorded", e.sugarG)

        val cola = RoadFood.entryFor(RoadFoodItem(id = "c", name = "Diet Test Cola", kcal = 0.0, proteinG = 0.0, sugarG = 0.0), null, Meal.SNACK)
        assertEquals("a listed zero is a value and is kept", 0.0, cola.sugarG!!, 0.0)
        assertEquals(0, cola.calories)
        assertEquals("a snack has no place in its name", "Diet Test Cola", cola.name)
        assertNull(cola.sodiumMg)
    }

    @Test fun `a logged item reaches the day's totals and its nutrient coverage like any food`() {
        val typed = FoodEntry(name = "Eggs", calories = 150, proteinG = 12, fatG = 10, carbsG = 1, date = "2026-09-20")
        val logged = RoadFood.entryFor(
            RoadFoodItem(id = "n", name = "Grilled Pretend Nuggets", kcal = 200.0, proteinG = 38.0, fatG = 5.0,
                carbsG = 2.0, sodiumMg = 720.0),
            "Example Chicken Shack", Meal.DINNER, date = "2026-09-20"
        )
        val day = listOf(typed, logged)
        assertEquals(350, day.totals().calories)
        val fx = day.nutrientTotals()!!
        assertEquals(720.0, fx.sodiumMg!!, 0.0)
        assertEquals("sodium from 1 of 2 foods, not 2 of 2", 1, fx.withSodium)
    }

    // MARK: - What is left, from the one function Home uses

    @Test fun `the fit line reads what Home reads`() {
        val goal = MacroResult(calories = 2200, proteinG = 180, fatG = 70, carbsG = 220, fiberG = 30)
        val eaten = listOf(FoodEntry(name = "Lunch", calories = 1560, proteinG = 125, fatG = 50, carbsG = 150))
        val left = remainingFor(goal, eaten)
        assertEquals(640, left.calories)
        assertEquals(55, left.proteinG)
        assertEquals("Fits your remaining 640 kcal · 55 g protein", RoadFood.fitHeadline(left))

        val over = remainingFor(goal, eaten + FoodEntry(name = "Dinner", calories = 900, proteinG = 60, fatG = 1, carbsG = 1))
        assertEquals(-260, over.calories)
        assertEquals("Fits your remaining 0 kcal · protein goal met", RoadFood.fitHeadline(over))
    }

    @Test fun `row text names what is not listed rather than showing zero`() {
        assertEquals("410 kcal · protein not listed · C 44 g · F 16 g",
            RoadFood.macroLine(RoadFoodItem(id = "w", name = "w", kcal = 410.0, fatG = 16.0, carbsG = 44.0)))
        assertEquals("9.2 g protein per 100 kcal", RoadFood.densityText(item("s", 370, 34, 1)))
        assertEquals("20 g protein per 100 kcal", RoadFood.densityText(item("s", 200, 40, 1)))
        assertNull("a zero-calorie drink has no density to state", RoadFood.densityText(item("c", 0, 0, 1)))
    }

    // MARK: - The file

    @Test fun `numbers that are absent, negative, blank or not numbers are not recorded, never zero`() {
        val data = parseRoadFood("""
            {"chains": [{"id": "c", "name": "C", "items": [
              {"id": "i", "name": "I", "kcal": "370", "proteinG": -1, "fatG": "", "carbsG": true, "sodiumMg": null}
            ]}, {"name": "no id", "items": []}, {"id": "no-items"}]}
        """)
        assertEquals("a chain with no id or no items is dropped", listOf("c"), data.chains.map { it.id })
        val i = data.chains.single().items.single()
        assertEquals(370.0, i.kcal!!, 0.0)
        assertNull(i.proteinG)
        assertNull(i.fatG)
        assertNull(i.carbsG)
        assertNull(i.fiberG)
        assertNull(i.sodiumMg)
        assertTrue(data.snacks.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a file with no chains is not a Road Food file`() {
        parseRoadFood("""{"version": 1}""")
    }

    @Test fun `the debug fixture uses only obviously fake names, in the spec shape`() {
        // Gradle runs unit tests from the module directory.
        val data = parseRoadFood(File("src/debug/assets/${RoadFoodStore.SAMPLE_ASSET}").readText())
        assertTrue(data.chains.isNotEmpty())
        assertTrue(data.chains.all { fake.containsMatchIn(it.name) })
        assertTrue(data.chains.all { it.checkedOn != null && it.source != null && it.items.isNotEmpty() })
        assertTrue(data.snacks.isNotEmpty() && data.rules.isNotEmpty())
        assertEquals(listOf("jerky", "protein bars", "string cheese"), data.snackCategories)
        assertTrue(data.chains.any { RoadFood.isStale(it.checkedOn, "2026-09-20") == true })
        // One chain of each kind, so the sample shows all three states: a
        // document dated to the day, one that names only a month, and one that
        // states none.
        val by = { id: String -> data.chains.first { it.id == id } }
        assertEquals("2026-09-02", by("sample-burger-co").publishedOn)
        assertEquals("2024-10", by("fictional-taco-stand").publishedOn)
        assertNull(by("example-chicken-shack").publishedOn)
        // And the month-only one is old on its document date while its checked
        // date is recent, which is the whole point of the field.
        assertEquals(true, RoadFood.isStale(RoadFood.ageDate(by("fictional-taco-stand")), "2026-09-23"))
        assertEquals(false, RoadFood.isStale(by("fictional-taco-stand").checkedOn, "2026-09-23"))
    }

    @Test fun `nothing fake can ship - the sample is in debug only, and a real file carries no sample names`() {
        assertFalse(File("src/main/assets/${RoadFoodStore.SAMPLE_ASSET}").exists())
        val real = File("src/main/assets/${RoadFoodStore.ASSET}")
        if (real.exists()) {
            val data = parseRoadFood(real.readText())
            assertTrue("the real file has chains", data.chains.isNotEmpty())
            val names = data.chains.map { it.name } + data.chains.flatMap { c -> c.items.map { it.name } } + data.snacks.map { it.name }
            assertEquals(emptyList<String>(), names.filter { fake.containsMatchIn(it) })
            // Every chain's dates are usable, and a document date is never
            // after the day someone read it.
            for (c in data.chains) {
                assertNotNull("${c.id} has no usable checkedOn", RoadFood.parseDay(c.checkedOn))
                c.publishedOn?.let {
                    assertNotNull("${c.id} has an unusable publishedOn", RoadFood.parseDocDay(it))
                    assertFalse("${c.id} claims a document published after it was read",
                        RoadFood.parseDocDay(it)!!.isAfter(RoadFood.parseDay(c.checkedOn)))
                }
            }
            // The three charts this field exists for: each looks fresh by the
            // day it was read and is old by its own date.
            for (id in listOf("burgerking", "whataburger", "chipotle")) {
                val c = data.chains.first { it.id == id }
                assertEquals("$id looks fresh by checkedOn alone", false, RoadFood.isStale(c.checkedOn, "2026-09-23"))
                assertEquals("$id should be old by its own chart", true, RoadFood.isStale(RoadFood.ageDate(c), "2026-09-23"))
            }
        }
    }

    // MARK: - The copies are the same bytes

    /**
     * road-food.json is curated once in `dugcanlift-kit/data/` and copied byte
     * for byte into five app repos. Nothing used to check that they matched:
     * CLAUDE.md says "copied unchanged, never edit it here alone", and the
     * tests above check the data's *shape* -- every chain has a usable
     * checkedOn, the three dated charts are dated -- which a copy three chains
     * behind passes cleanly, so nothing here would ever say it had fallen
     * behind.
     *
     * Item ids are the contract a coach's road picks travel on, and this app
     * skips an id it does not have in silence, by design, so that is a real
     * failure rather than an untidiness.
     *
     * So the kit writes the sha256 of the bytes to road-food.sha256, and that
     * file is copied across with the JSON. Hashing the asset here catches both
     * ways the copy can rot: taking the JSON without the hash, and editing the
     * JSON in place.
     */
    private fun sha256(file: File) =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    /** Reads a pinned checksum, failing on anything that is not one bare hash. */
    private fun pinned(path: String): String {
        val file = File(path)
        assertTrue("$path is missing -- copy it from the kit beside the JSON", file.exists())
        val sum = file.readText().trim()
        assertTrue("$path should be one bare sha256 and nothing else",
            Regex("^[0-9a-f]{64}$").matches(sum))
        return sum
    }

    @Test fun `the bundled road-food json is the kit's file, byte for byte`() {
        // Gradle runs unit tests from the module directory, and these are the
        // bytes packaged into the APK.
        val json = File("src/main/assets/${RoadFoodStore.ASSET}")
        assertTrue("src/main/assets/${RoadFoodStore.ASSET} is missing", json.exists())
        assertEquals(
            "src/main/assets/${RoadFoodStore.ASSET} does not match src/main/assets/road-food.sha256.\n" +
                "Copy dugcanlift-kit/data/road-food.json AND data/road-food.sha256 over together.\n" +
                "Never edit either file here, and never re-write the checksum by hand to make this\n" +
                "pass: the kit writes it with `node data/validate-road-food.mjs --write-checksum`,\n" +
                "and the other four app repos pin the same one, so a hand-written hash only moves\n" +
                "the failure somewhere further away.",
            pinned("src/main/assets/road-food.sha256"),
            sha256(json),
        )
    }

    @Test fun `the debug fixture is the same bytes the other apps hold`() {
        // Shared with LIFT web's lift/fixtures/road-food-sample.json and
        // lift-ios's Tests/Fixtures copy, which is in turn pinned against the
        // inline copy in RoadFoodSample.swift.
        val json = File("src/debug/assets/${RoadFoodStore.SAMPLE_ASSET}")
        assertEquals(
            "src/debug/assets/${RoadFoodStore.SAMPLE_ASSET} does not match its checksum.\n" +
                "The fixture is shared with dugcanlift-site and lift-ios. Change it in all three,\n" +
                "and re-write all three checksums:\n" +
                "  shasum -a 256 app/src/debug/assets/road-food-sample.json | cut -d' ' -f1 \\\n" +
                "    > app/src/debug/assets/road-food-sample.sha256",
            pinned("src/debug/assets/road-food-sample.sha256"),
            sha256(json),
        )
    }

    private val fake = Regex("""^(Sample|Example|Fictional|Placeholder|Test) """)
}
