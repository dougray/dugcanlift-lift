package com.dugcanlift.macrocalc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A port of LIFT iPhone's `RoadFoodAllergensTests`, case for case, plus the
 * Avoid row's sentence, which the iPhone keeps in its view.
 */
class RoadFoodAllergensTest {
    private fun item(id: String, allergens: List<String>?) =
        RoadFoodItem(id = id, name = id, kcal = 300.0, proteinG = 20.0, fatG = 10.0, carbsG = 30.0, allergens = allergens)

    private val nine = RoadFoodAllergens.ALL
    private val noShellfish = RoadFoodAllergens.ALL.filter { it != "shellfish" }

    @Test fun `avoiding nothing shows everything`() {
        val items = listOf(item("a", listOf("milk")), item("b", null))
        assertEquals(items, RoadFoodAllergens.filter(items, nine, emptySet()).shown)
        assertEquals(RoadFoodAllergens.Verdict.Clear, RoadFoodAllergens.verdict(items[0], nine, emptySet()))
    }

    @Test fun `an item containing what is avoided is hidden and counted`() {
        val items = listOf(item("bun", listOf("milk", "wheat")), item("salad", emptyList()), item("wrap", listOf("wheat")))
        val filtered = RoadFoodAllergens.filter(items, nine, setOf("milk"))
        assertEquals(listOf("salad", "wrap"), filtered.shown.map { it.id })
        assertEquals(1, filtered.hiddenCount)
        assertEquals(
            RoadFoodAllergens.Verdict.Contains(listOf("milk")),
            RoadFoodAllergens.verdict(items[0], nine, setOf("milk", "soy"))
        )
    }

    /** Never claimed free: an item the chart says nothing about stays, and says so. */
    @Test fun `an item not stated is shown and said to be not stated`() {
        val unknown = item("mystery", null)
        val filtered = RoadFoodAllergens.filter(listOf(unknown), nine, setOf("milk"))
        assertEquals(listOf(unknown), filtered.shown)
        val verdict = RoadFoodAllergens.verdict(unknown, nine, setOf("egg", "milk"))
        assertEquals(RoadFoodAllergens.Verdict.NotStated(listOf("milk", "egg")), verdict)
        assertEquals("Milk and egg not stated", RoadFoodAllergens.notStatedLine(verdict))
    }

    /**
     * A chart with no shellfish column says nothing about shellfish, even for an
     * item it lists with no allergens at all.
     */
    @Test fun `an allergen the chart does not cover is not stated, even for an empty list`() {
        val nuggets = item("nuggets", emptyList())
        assertEquals(
            RoadFoodAllergens.Verdict.NotStated(listOf("shellfish")),
            RoadFoodAllergens.verdict(nuggets, noShellfish, setOf("shellfish"))
        )
        assertEquals(RoadFoodAllergens.Verdict.Clear, RoadFoodAllergens.verdict(nuggets, noShellfish, setOf("milk")))
        // A chain with no chart at all: everything is not stated.
        assertEquals(
            RoadFoodAllergens.Verdict.NotStated(listOf("milk")),
            RoadFoodAllergens.verdict(nuggets, emptyList(), setOf("milk"))
        )
    }

    @Test fun `the contains line`() {
        assertEquals("Contains milk, wheat, and soy", RoadFoodAllergens.containsLine(item("a", listOf("milk", "wheat", "soy"))))
        assertEquals("Contains tree nuts", RoadFoodAllergens.containsLine(item("a", listOf("tree-nuts"))))
        assertEquals(
            "Contains none of the allergens its chart lists",
            RoadFoodAllergens.containsLine(item("a", emptyList()))
        )
        assertNull(RoadFoodAllergens.containsLine(item("a", null)))
    }

    @Test fun `unknown strings are dropped and order is fixed`() {
        assertEquals(listOf("milk", "soy"), RoadFoodAllergens.known(listOf("SOY", "gluten", "milk", "milk")))
    }

    /** The iPhone's save/load through `UserDefaults`; here, what `SettingsStore` writes. */
    @Test fun `the avoid list is kept and empty stores no key`() {
        val stored = RoadFoodAllergens.encode(setOf("wheat", "milk"))
        assertEquals(setOf("milk", "wheat"), RoadFoodAllergens.decode(stored))
        assertEquals("milk\nwheat", stored)
        assertNull(RoadFoodAllergens.encode(emptySet()))
        assertNull("only junk is nothing avoided", RoadFoodAllergens.encode(setOf("gluten")))
        assertEquals(emptySet<String>(), RoadFoodAllergens.decode(null))
    }

    @Test fun `the fields decode and junk is dropped`() {
        val json = """
            {"chains":[{"id":"c","name":"C","allergensCovered":["milk","wheat","dairy"],
             "items":[{"id":"c-a","name":"A","kcal":1,"proteinG":1,"fatG":1,"carbsG":1,"allergens":["wheat","nuts"]},
                      {"id":"c-b","name":"B","kcal":1,"proteinG":1,"fatG":1,"carbsG":1}]}]}
        """.trimIndent()
        val chain = parseRoadFood(json).chains.single()
        assertEquals(listOf("milk", "wheat"), chain.allergensCovered)
        assertEquals(listOf("wheat"), chain.items[0].allergens)
        assertNull(chain.items[1].allergens)
    }

    // What only Android adds: the sentence under the Avoid row, which the
    // iPhone writes inline in its view, word for word the same.

    @Test fun `a chain with no allergensCovered covers nothing, and a list that is not strings is not stated`() {
        val json = """
            {"chains":[{"id":"c","name":"C",
             "items":[{"id":"c-a","name":"A","kcal":1,"allergens":["milk",3]},
                      {"id":"c-b","name":"B","kcal":1,"allergens":"milk"}]}]}
        """.trimIndent()
        val chain = parseRoadFood(json).chains.single()
        assertEquals(emptyList<String>(), chain.allergensCovered)
        assertNull(chain.items[0].allergens)
        assertNull(chain.items[1].allergens)
    }

    private fun chain(name: String, source: String?, covered: List<String>) =
        RoadFoodChain(id = name, name = name, items = emptyList(), allergenSource = source, allergensCovered = covered)

    @Test fun `the avoid note counts what is hidden and says what the chart can and cannot clear`() {
        // A chart that says what it covers.
        assertEquals(
            "Nothing here is listed as containing what you avoid. From the allergen chart Wendy's " +
                "publishes, which doesn't cover cross-contact. Check with the restaurant.",
            RoadFoodAllergens.avoidNote(0, chain("Wendy's", "https://example.com/chart", nine))
        )
        // A chart that lists what items contain but never says what it covers.
        assertEquals(
            "1 item is hidden because its chart says it contains something you avoid. From the allergen " +
                "chart Starbucks publishes. It says what items contain, not which allergens it covers, so " +
                "nothing is marked free of anything. It doesn't cover cross-contact either. Check with the " +
                "restaurant.",
            RoadFoodAllergens.avoidNote(1, chain("Starbucks", "https://example.com/chart", emptyList()))
        )
        // No chart read, and the gas station, which has none.
        val none = "This place publishes no allergen chart LIFT can read, so nothing is marked either way."
        assertEquals(
            "3 items are hidden because their chart says they contain something you avoid. $none",
            RoadFoodAllergens.avoidNote(3, chain("Taco Bell", null, emptyList()))
        )
        assertEquals("Nothing here is listed as containing what you avoid. $none", RoadFoodAllergens.avoidNote(0, null))
    }

    @Test fun `allergenSource decodes, and is absent where no chart was read`() {
        val json = """
            {"chains":[{"id":"a","name":"A","allergenSource":"https://example.com/a.pdf","items":[]},
                       {"id":"b","name":"B","items":[]}]}
        """.trimIndent()
        val chains = parseRoadFood(json).chains
        assertEquals("https://example.com/a.pdf", chains[0].allergenSource)
        assertNull(chains[1].allergenSource)
    }

    @Test fun `nothing here ever says safe`() {
        val lines = listOf(
            RoadFoodAllergens.avoidNote(0, chain("C", "https://example.com", nine)),
            RoadFoodAllergens.avoidNote(1, chain("C", "https://example.com", emptyList())),
            RoadFoodAllergens.avoidNote(2, null),
            RoadFoodAllergens.containsLine(item("a", emptyList())),
            RoadFoodAllergens.containsLine(item("a", nine)),
            RoadFoodAllergens.notStatedLine(RoadFoodAllergens.verdict(item("a", null), nine, nine.toSet())),
        ).map { it.orEmpty().lowercase() }
        lines.forEach { assertFalse(it, Regex("""\bsafe\b""").containsMatchIn(it)) }
    }
}
