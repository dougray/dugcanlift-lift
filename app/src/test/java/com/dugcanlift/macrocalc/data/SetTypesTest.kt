package com.dugcanlift.macrocalc.data

import com.dugcanlift.kit.PlanDecodeResult
import com.dugcanlift.kit.PlanLinkCodec
import com.dugcanlift.kit.ShareSide
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The rules of set types, on their own: a port of the rule cases in LIFT web's
 * `lift/plan-set-types.test.mjs`, whose rules are Coach web's `coach/prescriptions.js`.
 * PLAN-FORMAT "Set types" is the spec. The fixture, the importer and the backup are
 * [PlanSetTypesTest].
 */
class SetTypesTest {

    /* ---------- flags bits 3-4 ---------- */

    @Test
    fun `flags bits 3-4 are 8 drop and 16 rest-pause, and 24 is straight`() {
        assertNull(SetKind.fromFlags(null))
        assertNull(SetKind.fromFlags(0))
        assertEquals(SetKind.DROP, SetKind.fromFlags(8))
        assertEquals(SetKind.REST_PAUSE, SetKind.fromFlags(16))
        assertNull("3 in the kind bits is never written and reads as straight", SetKind.fromFlags(24))
    }

    @Test
    fun `the kind is masked, never compared, and the side the kit reads beside it survives`() {
        // A left-side drop set is 10: side 1 in bits 1-2, kind 1 in bits 3-4. The side is read by the
        // pinned kit's own decoder, the kind here, from the one number.
        val cases = mapOf(
            10 to (ShareSide.LEFT to SetKind.DROP),
            12 to (ShareSide.RIGHT to SetKind.DROP),
            18 to (ShareSide.LEFT to SetKind.REST_PAUSE),
            20 to (ShareSide.RIGHT to SetKind.REST_PAUSE),
            2 to (ShareSide.LEFT to null),
            4 to (ShareSide.RIGHT to null),
            6 to (null to null),
            8 to (null to SetKind.DROP),
            16 to (null to SetKind.REST_PAUSE),
            // Bit 0 (SHARE-FORMAT's warmup) changes neither.
            9 to (null to SetKind.DROP),
            11 to (ShareSide.LEFT to SetKind.DROP)
        )
        val tuples = cases.keys.joinToString(",") { "[40,8,null,null,null,$it]" }
        val raw = """{"v":1,"t":"plan","l":"x","w":[{"n":"A","e":[{"n":"Row","s":[[40,8],$tuples]}]}]}"""
        val link = "1u" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
        val payload = (PlanLinkCodec.decode(link, "x") as PlanDecodeResult.Success).payload
        val sides = payload.workouts.single().exercises.single().sets.drop(1).map { it.side }
        val kinds = SetTypes.planTypes(payload.rawJson).single().kinds.single().drop(1)
        assertEquals(cases.values.map { it.first }, sides)
        assertEquals(cases.values.map { it.second }, kinds)
    }

    /* ---------- BACKUP-FORMAT's spelling ---------- */

    @Test
    fun `a backup kind is read leniently, and only drop or restPause is anything`() {
        assertEquals(SetKind.DROP, SetKind.fromWire("drop"))
        assertEquals(SetKind.DROP, SetKind.fromWire(" Drop "))
        assertEquals(SetKind.REST_PAUSE, SetKind.fromWire("restPause"))
        assertEquals(SetKind.REST_PAUSE, SetKind.fromWire("RESTPAUSE"))
        listOf(null, "", "straight", "rest-pause", "rest pause", 1, 8, true, JSONObject.NULL).forEach {
            assertNull("kind $it", SetKind.fromWire(it))
        }
    }

    /* ---------- groups ---------- */

    @Test
    fun `a group is a JSON positive integer, and 2 point 0 is 2`() {
        assertEquals(1, SetTypes.groupOf(1))
        assertEquals(2, SetTypes.groupOf(2.0))
        assertEquals(7, SetTypes.groupOf(7L))
        listOf(null, 0, -1, 1.5, "2", true, false, Double.NaN, Double.POSITIVE_INFINITY, 3_000_000_000.0, JSONObject.NULL)
            .forEach { assertNull("group $it", SetTypes.groupOf(it)) }
    }

    @Test
    fun `adjacency makes the group, renumbered in order, and a group of one is none`() {
        assertEquals(listOf(1, 1, 2, 2, 2), SetTypes.runs(listOf(7, 7, 3, 3, 3)))
        assertEquals(listOf(null, null, null), SetTypes.runs(listOf(1, null, 1)))
        assertEquals(listOf(null, 1, 1, null), SetTypes.runs(listOf(5, 1, 1, 2)))
        // The number only has to differ from its neighbours.
        assertEquals(listOf(1, 1, 2, 2), SetTypes.runs(listOf(4, 4, 9, 9)))
        assertEquals(listOf<Int?>(), SetTypes.runs(emptyList()))
        assertEquals(listOf(null, null), SetTypes.runs(listOf(null, null)))
    }

    /* ---------- "Superset with next" ---------- */

    @Test
    fun `superset with next joins two, and again makes a circuit of three`() {
        var groups = SetTypes.setGroupedWithNext(listOf(null, null, null, null), 0, true)
        assertEquals(listOf(1, 1, null, null), groups)
        assertTrue(SetTypes.groupedWithNext(groups, 0))
        assertFalse(SetTypes.groupedWithNext(groups, 1))
        groups = SetTypes.setGroupedWithNext(groups, 1, true)
        assertEquals(listOf(1, 1, 1, null), groups)
        assertEquals("Circuit A", SetTypes.labels(groups)[0]!!.group)
        // Joining the exercise before a group takes the whole group.
        assertEquals(listOf(1, 1, 1), SetTypes.setGroupedWithNext(listOf(null, 1, 1), 0, true))
    }

    @Test
    fun `joining two groups makes one`() {
        assertEquals(listOf(1, 1, 1, 1), SetTypes.setGroupedWithNext(listOf(1, 1, 2, 2), 1, true))
        // Already joined: nothing changes.
        assertEquals(listOf(1, 1, null), SetTypes.setGroupedWithNext(listOf(1, 1, null), 0, true))
    }

    @Test
    fun `splitting keeps what is before the split and gives the rest a group of their own`() {
        assertEquals("a superset is left behind", listOf(null, 1, 1, null),
            SetTypes.setGroupedWithNext(listOf(1, 1, 1, null), 0, false))
        assertEquals(listOf(1, 1, 2, 2), SetTypes.setGroupedWithNext(listOf(1, 1, 1, 1), 1, false))
        assertEquals(listOf(null, null), SetTypes.setGroupedWithNext(listOf(1, 1), 0, false))
        // Not joined: nothing to split.
        assertEquals(listOf(null, 1, 1), SetTypes.setGroupedWithNext(listOf(null, 1, 1), 0, false))
    }

    @Test
    fun `the last exercise has no next, and asking changes nothing`() {
        assertEquals(listOf(null, null), SetTypes.setGroupedWithNext(listOf(null, null), 1, true))
        assertFalse(SetTypes.groupedWithNext(listOf(null, null), 1))
        assertEquals(listOf<Int?>(), SetTypes.setGroupedWithNext(emptyList(), 0, true))
    }

    @Test
    fun `the routine helpers join and split in place of the numbers`() {
        val routine = Routine(name = "Upper", exercises = listOf("A", "B", "C").map { RoutineExercise(name = it) })
        val joined = routine.withGroupedWithNext(0, true).withGroupedWithNext(1, true)
        assertEquals(listOf(1, 1, 1), joined.exercises.map { it.group })
        assertTrue(joined.groupedWithNext(1))
        assertEquals(listOf(null, 1, 1), joined.withGroupedWithNext(0, false).exercises.map { it.group })
        // Ids, names and targets are untouched.
        assertEquals(routine.exercises.map { it.copy(group = null) }, joined.exercises.map { it.copy(group = null) })
    }

    /* ---------- labels ---------- */

    @Test
    fun `a group is labelled Superset for two and Circuit for three, and the letters run on past Z`() {
        val labels = SetTypes.labels(listOf(null, 1, 1, 2, 2, 2, 9))
        assertEquals(
            listOf(null, "Superset A · A1", "Superset A · A2", "Circuit B · B1", "Circuit B · B2", "Circuit B · B3", null),
            labels.map { it?.let { l -> "${l.group} · ${l.member}" } }
        )
        assertEquals(listOf(null, true, false, true, false, false, null), labels.map { it?.first })
        assertEquals("Z", SetTypes.letters(25))
        assertEquals("AA", SetTypes.letters(26))
        assertEquals("AB", SetTypes.letters(27))
        val many = (1..28).flatMap { listOf(it, it) }
        assertEquals("Superset AB", SetTypes.labels(many).last()!!.group)
        assertEquals("A1 · Dumbbell Bench Press (Dumbbell)",
            SetTypes.heading("Dumbbell Bench Press (Dumbbell)", labels[1]))
        assertEquals("Plank", SetTypes.heading("Plank", null))
    }

    /* ---------- a set's words ---------- */

    @Test
    fun `a set shown first is straight, whatever it carries`() {
        val sets = listOf(WorkoutSet(kind = SetKind.DROP), WorkoutSet(), WorkoutSet(kind = SetKind.REST_PAUSE))
        assertEquals(listOf(null, null, SetKind.REST_PAUSE), sets.indices.map { SetTypes.kindAt(sets, it) { s -> s.kind } })
        assertNull(SetTypes.kindAt(sets, 9) { it.kind })
    }

    @Test
    fun `a set reads side then kind`() {
        assertEquals("30 x 12 L drop", PrescribedSet(weightLb = 30.0, reps = 12, side = SetSide.LEFT, kind = SetKind.DROP).summary)
        assertEquals("65 x 4 rest-pause", PrescribedSet(weightLb = 65.0, reps = 4, kind = SetKind.REST_PAUSE).summary)
        assertEquals("as written drop", PrescribedSet(kind = SetKind.DROP).summary)
        assertEquals("70 x 12", PrescribedSet(weightLb = 70.0, reps = 12).summary)
        assertEquals("", SetTypes.suffix(null))
        assertEquals(" rest-pause", SetTypes.suffix(SetKind.REST_PAUSE))
    }

    /* ---------- the plan's own JSON ---------- */

    @Test
    fun `a plan's g and kind bits are read by the kit's own walk`() {
        val raw = """{"w":[
            "junk",
            {"n":"A","e":[
              7,
              {"n":"x","g":3,"s":[[60,10,null,null,null,8],"no",[50,10,null,null,null,8]]},
              {"n":"y","g":3.0,"s":[[60,10],[40,10,null,null,null,"16"]]},
              {"n":"z","g":"3","s":[]}
            ]}
        ]}"""
        val types = SetTypes.planTypes(raw).single()
        assertEquals(listOf(1, 1, null), types.groups)
        // The first set is straight whatever it says; a non-array set is skipped, as the kit skips it.
        assertEquals(listOf(listOf(null, SetKind.DROP), listOf(null, SetKind.REST_PAUSE), emptyList()), types.kinds)
        assertEquals(emptyList<SetTypes.WorkoutTypes>(), SetTypes.planTypes("not json"))
        assertEquals(emptyList<SetTypes.WorkoutTypes>(), SetTypes.planTypes("""{"v":1}"""))
    }
}
