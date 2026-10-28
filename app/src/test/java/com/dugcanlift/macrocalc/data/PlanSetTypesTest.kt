package com.dugcanlift.macrocalc.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dugcanlift.kit.PlanDecodeResult
import com.dugcanlift.kit.PlanLinkCodec
import com.dugcanlift.kit.PlanPayload
import com.dugcanlift.kit.PlanSet
import com.dugcanlift.kit.PlanWorkoutExercise
import com.dugcanlift.kit.ShareSide
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Set types end to end: Coach web's link, the routine it becomes, the session it starts and the
 * backup that carries them.
 *
 * `fixtures/web-plan-set-types.txt` is a link **Coach web's own encoder** wrote
 * (dugcanlift-coach `coach/fixtures/`, the same bytes LIFT web checks): a superset, a circuit of
 * three, two drop sets, a rest-pause set, a left-side drop set on an each-side exercise (`10`) and
 * an ordinary lift. Never regenerate it from Kotlin: its value is that a different implementation
 * wrote it.
 */
@RunWith(RobolectricTestRunner::class)
class PlanSetTypesTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun resetSingletons() {
        listOf(RecipeRepository::class.java, RoutineRepository::class.java, WorkoutRepository::class.java,
            ScheduledSessionRepository::class.java, ImportedPlanStore::class.java, SettingsStore::class.java,
            FoodRepository::class.java, CoachStore::class.java, GoalStore::class.java).forEach {
            val field = it.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        }
    }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    private fun payload(name: String): PlanPayload {
        val link = fixture(name).trim()
        val decoded = PlanLinkCodec.decode(link.substringAfter('#'), expectedLifterId = "a1b2c3d4")
        return (decoded as PlanDecodeResult.Success).payload
    }

    private val typed get() = payload("web-plan-set-types.txt")

    private fun routine(): Routine = PlanImporter.routinesOf(typed).single()

    private fun RoutineExercise.kinds() = prescribed?.map { it.kind }

    /** A record's JSON with every `id` and clock reading taken out: both differ per build, by design. */
    private fun withoutIds(value: Any): Any = when (value) {
        is JSONObject -> JSONObject().also { out ->
            value.keys().forEach { k -> if (k !in VOLATILE) out.put(k, withoutIds(value.get(k))) }
        }
        is JSONArray -> JSONArray().also { out -> (0 until value.length()).forEach { out.put(withoutIds(value.get(it))) } }
        else -> value
    }

    private fun hasKey(value: Any, key: String): Boolean = when (value) {
        is JSONObject -> value.has(key) || value.keys().asSequence().any { hasKey(value.get(it), key) }
        is JSONArray -> (0 until value.length()).any { hasKey(value.get(it), key) }
        else -> false
    }

    private val VOLATILE = setOf("id", "createdAt", "startedAt")

    /* ---------- the fixture ---------- */

    @Test
    fun `the fixture is a v1 plan whose raw wire carries g and the kind bits`() {
        val raw = JSONObject(typed.rawJson)
        assertEquals("no version bump, so an old build does not refuse the link", 1, raw.getInt("v"))
        val e = raw.getJSONArray("w").getJSONObject(0).getJSONArray("e")
        assertEquals(listOf(null, 1, 1, 2, 2, 2, null, null, null),
            (0 until e.length()).map { e.getJSONObject(it).opt("g") })
        // The left drop set is 10: side and kind in one number.
        assertEquals(10, e.getJSONObject(8).getJSONArray("s").getJSONArray(2).getInt(5))
    }

    @Test
    fun `the fixture imports with its groups, renumbered, and its kinds`() {
        val r = routine()
        assertEquals(
            listOf("Back Squat", "Dumbbell Bench Press", "Seated Cable Rows", "Kettlebell Swing", "Push-Up",
                "Plank", "Triceps Pushdown", "Barbell Curl", "Single-Arm Dumbbell Row"),
            r.exercises.map { it.name }
        )
        assertEquals(listOf(null, 1, 1, 2, 2, 2, null, null, null), r.exercises.map { it.group })
        val byName = r.exercises.associateBy { it.name }
        assertEquals(listOf(null, null, SetKind.DROP, SetKind.DROP), byName.getValue("Triceps Pushdown").kinds())
        assertEquals(listOf(null, SetKind.REST_PAUSE), byName.getValue("Barbell Curl").kinds())
        // Grouped but straight and alike: still nothing but targets, exactly as before.
        assertNull(byName.getValue("Dumbbell Bench Press").prescribed)
        assertEquals(3, byName.getValue("Dumbbell Bench Press").targetSets)
        assertNull(byName.getValue("Plank").prescribed)
        assertEquals(2, byName.getValue("Plank").targetSets)
        assertNull(byName.getValue("Back Squat").prescribed)
    }

    @Test
    fun `a left drop set keeps its side`() {
        val row = routine().exercises.last()
        assertTrue(row.eachSide)
        val set = row.prescribed!![2]
        assertEquals(SetSide.LEFT, set.side)
        assertEquals(SetKind.DROP, set.kind)
        assertEquals(30.0, set.weightLb!!, 1e-9)
        assertEquals(12, set.reps)
        assertEquals(listOf(null, null), row.prescribed!!.take(2).map { it.side })
    }

    @Test
    fun `the fixture is labelled Superset A and Circuit B, members A1, A2, B1`() {
        val labels = SetTypes.labels(routine().exercises.map { it.group })
        assertEquals(listOf(null, "A1", "A2", "B1", "B2", "B3", null, null, null), labels.map { it?.member })
        assertEquals("Superset A", labels[1]!!.group)
        assertEquals("Circuit B", labels[3]!!.group)
        assertEquals(listOf(null, true, false, true, false, false, null, null, null), labels.map { it?.first })
    }

    @Test
    fun `its sets read side then kind, as Coach web writes them`() {
        val byName = routine().exercises.associateBy { it.name }
        assertEquals("70 x 12, 70 x 12, 50 x 10 drop, 35 x 10 drop", byName.getValue("Triceps Pushdown").summary)
        assertEquals("65 x 8, 65 x 4 rest-pause", byName.getValue("Barbell Curl").summary)
        assertEquals("50 x 10, 50 x 10, 30 x 12 L drop, each side", byName.getValue("Single-Arm Dumbbell Row").summary)
    }

    /* ---------- an old decoder ---------- */

    /**
     * What a build that never heard of set types does with the same link: the pinned kit's decode
     * (which reads neither `g` nor bits 3-4) through main's mapping, frozen below. The exercises,
     * their order, every set's numbers and every side are what this build reads too.
     */
    @Test
    fun `old decoder - with g and bits 3-4 ignored, the exercises, sets, numbers and sides are the same`() {
        val kit = typed.workouts.single().exercises
        val old = kit.map(::mainToRoutineExercise)
        val now = routine().exercises
        assertEquals(old.map { it.name }, now.map { it.name })
        // The left drop set (10) is a left set to the kit: bits 1-2 are masked, whatever is above them.
        assertEquals(ShareSide.LEFT, kit.last().sets[2].side)
        // A drop (8) and a rest-pause (16) are ordinary two-sided sets with the right numbers.
        assertEquals(listOf(null, null, null, null), kit[6].sets.map { it.side })
        assertEquals(listOf(70.0, 70.0, 50.0, 35.0), kit[6].sets.map { it.weightLb })
        assertEquals(listOf(65.0 to 8, 65.0 to 4), kit[7].sets.map { it.weightLb to it.reps })
        // Strip what this build added, and it is exactly what main stores.
        assertEquals(
            old.map { withoutIds(it.toJson()).toString() },
            now.map { e -> withoutIds(e.copy(group = null, prescribed = e.prescribed?.map { it.copy(kind = null) }).toJson()).toString() }
        )
    }

    /* ---------- a plan without set types ---------- */

    @Test
    fun `a plan without set types imports exactly as main imported it`() {
        listOf("web-plan-per-side.txt", "web-plan-road-picks.txt").forEach { name ->
            val p = payload(name)
            val main = p.workouts.map { w -> Routine(name = w.name, exercises = w.exercises.map(::mainToRoutineExercise)) }
            val now = PlanImporter.routinesOf(p)
            assertEquals(name, main.map { withoutIds(it.toJson()).toString() }, now.map { withoutIds(it.toJson()).toString() })
            now.forEach { routine ->
                assertFalse(name, hasKey(routine.toJson(), "group"))
                assertFalse(name, hasKey(routine.toJson(), "kind"))
                // And it starts the session main started.
                val started = routine.toSession("2026-11-02")
                assertEquals(name, withoutIds(mainToSession(routine, "2026-11-02").toJson()).toString(),
                    withoutIds(started.toJson()).toString())
            }
        }
    }

    /* ---------- starting it ---------- */

    @Test
    fun `starting the fixture copies its groups onto the day and its kinds onto the sets`() {
        val session = routine().toSession("2026-11-02")
        assertEquals(listOf(null, 1, 1, 2, 2, 2, null, null, null), session.exercises.map { it.group })
        val byName = session.exercises.associateBy { it.name }
        assertEquals(listOf(null, null, SetKind.DROP, SetKind.DROP), byName.getValue("Triceps Pushdown").sets.map { it.kind })
        assertEquals(listOf(50.0, 35.0), byName.getValue("Triceps Pushdown").sets.drop(2).map { it.weightLb })
        assertEquals(listOf(null, SetKind.REST_PAUSE), byName.getValue("Barbell Curl").sets.map { it.kind })
        // Each side: nothing pre-filled, and the kind rides inside the prescription the header reads.
        val row = byName.getValue("Single-Arm Dumbbell Row")
        assertTrue(row.sets.isEmpty())
        assertEquals(listOf(null, null, SetKind.DROP), row.prescribed!!.map { it.kind })
        // The third left set answers the drop set; the form copies its kind from here.
        val logged = listOf(
            WorkoutSet(weightLb = 50.0, reps = 10, side = SetSide.LEFT),
            WorkoutSet(weightLb = 50.0, reps = 10, side = SetSide.RIGHT),
            WorkoutSet(weightLb = 50.0, reps = 10, side = SetSide.LEFT),
            WorkoutSet(weightLb = 50.0, reps = 10, side = SetSide.RIGHT)
        )
        assertEquals(SetKind.DROP, PerSideLogging.prescribedSetFor(row.prescribed, true, logged, SetSide.LEFT)?.kind)
        assertNull(PerSideLogging.prescribedSetFor(row.prescribed, true, logged, SetSide.RIGHT))
    }

    @Test
    fun `a logged set's kind never reaches the coach - the share tuple has no kind bits`() {
        val session = routine().toSession(todayKey())
        val drops = session.exercises.flatMap { it.sets }.filter { it.kind != null }
        assertEquals(3, drops.size)
        // CoachShare writes a set field by field and the kit's ShareSet has no kind, so the flags
        // byte it writes is the side's alone: 0 for each of these two-sided sets.
        drops.forEach { assertNull(it.side) }
    }

    @Test
    fun `saving a session as a routine keeps its superset, and its kinds as setKinds`() {
        val session = routine().toSession("2026-11-02")
        val saved = session.toRoutine("Again")
        assertEquals(listOf(null, 1, 1, 2, 2, 2, null, null, null), saved.exercises.map { it.group })
        assertTrue(saved.exercises.all { it.prescribed == null })
        val byName = saved.exercises.associateBy { it.name }
        assertEquals(listOf(null, null, SetKind.DROP, SetKind.DROP), byName.getValue("Triceps Pushdown").setKinds)
        assertEquals(listOf(null, SetKind.REST_PAUSE), byName.getValue("Barbell Curl").setKinds)
        assertNull(byName.getValue("Dumbbell Bench Press").setKinds)
        // A superset that lost a member on the day is saved as no group at all.
        val lonely = session.copy(exercises = session.exercises.filterNot { it.name == "Seated Cable Rows" })
        assertEquals(listOf(null, null, 1, 1, 1, null, null, null), lonely.toRoutine("Again").exercises.map { it.group })
    }

    /* ---------- storage, accepting the link ---------- */

    @Test
    fun `accepting the link stores group and kind under BACKUP-FORMAT's names, and reads them back`() = runBlocking {
        PlanImporter.accept(typed, context)
        val file = java.io.File(context.filesDir, "routines.json")
        val stored = JSONArray(file.readText()).getJSONObject(0).getJSONArray("exercises")
        assertEquals(1, stored.getJSONObject(1).getInt("group"))
        assertFalse(stored.getJSONObject(0).has("group"))
        val pushdown = stored.getJSONObject(6).getJSONArray("prescribed")
        assertFalse(pushdown.getJSONObject(0).has("kind"))
        assertEquals("drop", pushdown.getJSONObject(2).getString("kind"))
        assertEquals("restPause", stored.getJSONObject(7).getJSONArray("prescribed").getJSONObject(1).getString("kind"))

        resetSingletons()
        val repo = RoutineRepository.get(context)
        repo.load()
        assertEquals(routine().exercises.map { it.copy(id = "") }, repo.routines.value.single().exercises.map { it.copy(id = "") })
    }

    /* ---------- the backup ---------- */

    @Test
    fun `backup round trip - a routine's groups and a session's kinds come back as they went`() = runBlocking {
        val routine = Routine(id = "r1", name = "Upper B", createdAt = 1L, exercises = listOf(
            RoutineExercise(id = "a", name = "Dumbbell Bench Press", equipment = "Dumbbell", group = 1),
            RoutineExercise(id = "b", name = "Seated Cable Rows", equipment = "Cable", group = 1),
            RoutineExercise(id = "c", name = "Triceps Pushdown", equipment = "Cable", prescribed = listOf(
                PrescribedSet(weightLb = 70.0, reps = 12),
                PrescribedSet(weightLb = 50.0, reps = 10, kind = SetKind.DROP),
                PrescribedSet(weightLb = 50.0, reps = 6, kind = SetKind.REST_PAUSE)
            ))
        ))
        val session = WorkoutSession(id = "s1", date = "2026-11-02", name = "Upper B", startedAt = 2L, exercises = listOf(
            LoggedExercise(id = "e1", name = "Dumbbell Bench Press", group = 1,
                sets = listOf(WorkoutSet(id = "1", weightLb = 60.0, reps = 10))),
            LoggedExercise(id = "e2", name = "Seated Cable Rows", group = 1,
                sets = listOf(WorkoutSet(id = "2", weightLb = 120.0, reps = 12))),
            LoggedExercise(id = "e3", name = "Barbell Curl", sets = listOf(
                WorkoutSet(id = "3", weightLb = 65.0, reps = 8),
                WorkoutSet(id = "4", weightLb = 65.0, reps = 4, kind = SetKind.REST_PAUSE),
                WorkoutSet(id = "5", weightLb = 30.0, reps = 12, side = SetSide.LEFT, kind = SetKind.DROP)
            ))
        ))
        RoutineRepository.get(context).save(routine)
        WorkoutRepository.get(context).save(session)

        val file = BackupStore.build(context)
        val data = JSONObject(file).getJSONObject("data")
        val rex = data.getJSONArray("routines").getJSONObject(0).getJSONArray("exercises")
        assertEquals(1, rex.getJSONObject(0).getInt("group"))
        assertFalse(rex.getJSONObject(2).has("group"))
        val asked = rex.getJSONObject(2).getJSONArray("prescribed")
        assertFalse(asked.getJSONObject(0).has("kind"))
        assertEquals("drop", asked.getJSONObject(1).getString("kind"))
        assertEquals("restPause", asked.getJSONObject(2).getString("kind"))
        val curl = data.getJSONArray("workouts").getJSONObject(0).getJSONArray("exercises").getJSONObject(2)
        assertEquals("left", curl.getJSONArray("sets").getJSONObject(2).getString("side"))
        assertEquals("drop", curl.getJSONArray("sets").getJSONObject(2).getString("kind"))

        WorkoutRepository.get(context).clear()
        RoutineRepository.get(context).delete("r1")
        assertTrue(BackupStore.restore(context, file).ok)
        assertEquals(routine, RoutineRepository.get(context).routines.value.single())
        assertEquals(session, WorkoutRepository.get(context).sessions.value.single())
    }

    @Test
    fun `a backup reads set types leniently - junk is not grouped, or straight`() {
        val routine = routineFromJson(JSONObject("""{"id":"r","name":"R","exercises":[
            {"id":"1","name":"A","group":"1"},
            {"id":"2","name":"B","group":1.5},
            {"id":"3","name":"C","group":2.0},
            {"id":"4","name":"D","group":2},
            {"id":"5","name":"E","group":0},
            {"id":"6","name":"F","group":-3},
            {"id":"7","name":"G","group":true},
            {"id":"8","name":"H","group":9},
            {"id":"9","name":"I","prescribed":[
                {"weightLb":60,"reps":8,"kind":"drop"},
                {"weightLb":60,"reps":8,"kind":"DROP"},
                {"weightLb":60,"reps":8,"kind":"straight"},
                {"weightLb":60,"reps":8,"kind":2},
                {"weightLb":60,"reps":8,"kind":" restpause "}
            ]}
        ]}"""))
        // 2.0 and 2 are one group; a lone 9 is none; everything else is not grouped.
        assertEquals(listOf(null, null, 1, 1, null, null, null, null, null), routine.exercises.map { it.group })
        // A kind on a first set is dropped; its numbers are not.
        assertEquals(listOf(null, SetKind.DROP, null, null, SetKind.REST_PAUSE), routine.exercises.last().kinds())
        assertEquals(60.0, routine.exercises.last().prescribed!!.first().weightLb!!, 1e-9)

        val session = workoutSessionFromJson(JSONObject("""{"id":"s","date":"2026-11-02","exercises":[
            {"id":"1","name":"A","group":4,"sets":[{"id":"x","reps":5,"kind":"drop"},{"id":"y","reps":5,"kind":"drop"}]},
            {"id":"2","name":"B","group":4,"sets":[]}
        ]}"""))
        assertEquals(listOf(1, 1), session.exercises.map { it.group })
        assertEquals(listOf(null, SetKind.DROP), session.exercises.first().sets.map { it.kind })
    }

    @Test
    fun `nothing writes a kind on a first set or a group of one`() {
        val e = LoggedExercise(name = "A", group = 3, sets = listOf(WorkoutSet(reps = 5, kind = SetKind.DROP)))
        val json = WorkoutSession(exercises = listOf(e, LoggedExercise(name = "B"))).toJson()
        assertFalse(hasKey(json, "group"))
        assertFalse(hasKey(json, "kind"))
    }

    @Test
    fun `a backup written before set types restores unchanged, and writes no set type back`() = runBlocking {
        assertTrue(BackupStore.restore(context, fixture("backup-main-no-sides.json")).ok)
        val sessions = WorkoutRepository.get(context).sessions.value
        assertTrue(sessions.isNotEmpty())
        assertTrue(sessions.flatMap { it.exercises }.all { it.group == null })
        assertTrue(sessions.flatMap { it.exercises }.flatMap { it.sets }.all { it.kind == null })
        val rebuilt = JSONObject(BackupStore.build(context))
        assertFalse(hasKey(rebuilt, "group"))
        assertFalse(hasKey(rebuilt, "kind"))
    }

    /* ---------- main, frozen ---------- */

    /** main's `PlanImporter.toRoutineExercise` (5f08747), verbatim in what it does. */
    private fun mainToRoutineExercise(pe: PlanWorkoutExercise): RoutineExercise {
        fun <T> mostCommon(values: List<T?>): T? =
            values.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        fun toPrescribedSet(set: PlanSet) = PrescribedSet(
            weightLb = set.weightLb, reps = set.reps, rpe = set.rpe,
            durationSec = set.durationSec, distanceMeters = set.distanceMeters,
            side = when (set.side) {
                ShareSide.LEFT -> SetSide.LEFT
                ShareSide.RIGHT -> SetSide.RIGHT
                null -> null
            }
        )
        return RoutineExercise(
            name = pe.name,
            equipment = pe.equipment,
            note = pe.note,
            targetSets = pe.sets.size.coerceAtLeast(1),
            targetReps = mostCommon(pe.sets.map { it.reps }),
            targetWeightLb = mostCommon(pe.sets.map { it.weightLb }),
            targetRpe = mostCommon(pe.sets.map { it.rpe }),
            targetDurationSec = mostCommon(pe.sets.map { it.durationSec }),
            targetDistanceMeters = mostCommon(pe.sets.map { it.distanceMeters }),
            prescribed = pe.sets.map(::toPrescribedSet).takeIf { Prescription.needsSetBySet(it, pe.eachSide) },
            eachSide = pe.eachSide
        )
    }

    /** main's `Routine.toSession` (5f08747), verbatim in what it does. */
    private fun mainToSession(routine: Routine, date: String): WorkoutSession = WorkoutSession(
        date = date,
        name = routine.name,
        exercises = routine.exercises.map { template ->
            val prescribed = template.prescribed
            if (prescribed != null) LoggedExercise(
                name = template.name,
                equipment = template.equipment,
                note = template.note,
                prescribed = prescribed.takeIf { PerSideLogging.prescribesSides(it, template.eachSide) },
                eachSide = template.eachSide,
                sets = prescribed.filter { !template.eachSide && it.side == null }.map {
                    WorkoutSet(weightLb = it.weightLb, reps = it.reps, rpe = it.rpe,
                        durationSec = it.durationSec, distanceMeters = it.distanceMeters)
                }
            ) else LoggedExercise(
                name = template.name,
                equipment = template.equipment,
                note = template.note,
                sets = template.askedSets.map {
                    WorkoutSet(weightLb = it.weightLb, reps = it.reps, rpe = it.rpe,
                        durationSec = it.durationSec, distanceMeters = it.distanceMeters)
                }
            )
        }
    )
}
