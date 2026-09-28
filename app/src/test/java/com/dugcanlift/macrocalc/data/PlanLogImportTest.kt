package com.dugcanlift.macrocalc.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dugcanlift.kit.PlanPayload
import com.dugcanlift.kit.PlanMeal
import com.dugcanlift.kit.PlanRecipe
import com.dugcanlift.kit.PlanSession
import com.dugcanlift.kit.PlanSet
import com.dugcanlift.kit.PlanWorkout
import com.dugcanlift.kit.PlanWorkoutExercise
import com.dugcanlift.kit.ShareSide
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * The week card against a plan that arrived as a link.
 *
 * `PlanLogTest` builds its routines by hand. This one accepts a real plan through [PlanImporter] and
 * reads the card off what the importer actually stored, because the two things the card most easily
 * gets wrong are both decided there: **a ramp must not read as three identical sets** (which is what
 * [RoutineExercise]'s flattened targets say, and what the importer wrote before `plan-set-fidelity`
 * fixed it), and **each side must double what it asks for**.
 */
@RunWith(RobolectricTestRunner::class)
class PlanLogImportTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private var previousLocale: Locale = Locale.getDefault()

    @Before
    @After
    fun resetSingletons() {
        listOf(RecipeRepository::class.java, RoutineRepository::class.java, WorkoutRepository::class.java,
            ScheduledSessionRepository::class.java, ImportedPlanStore::class.java, SettingsStore::class.java)
            .forEach {
                val field = it.getDeclaredField("instance")
                field.isAccessible = true
                field.set(null, null)
            }
    }

    @Before fun pinLocale() { previousLocale = Locale.getDefault(); Locale.setDefault(Locale.US) }
    @After fun restoreLocale() { Locale.setDefault(previousLocale) }

    private val monday = "2026-10-12"

    private fun accept(vararg exercises: PlanWorkoutExercise) = accept(
        rawJson = """{"v":1,"t":"plan","l":"x","n":"Doug"}""",
        exercises = exercises.toList()
    )

    /**
     * A plan through the real importer. [rawJson] is what the head keys actually are — the coach's
     * name is read from it and not from [PlanPayload.coachName], which the pinned kit fills with the
     * placeholder "Your coach" when a link named nobody.
     */
    private fun accept(rawJson: String, exercises: List<PlanWorkoutExercise>) = runBlocking {
        PlanImporter.accept(
            PlanPayload(
                coachName = PlanLinkHeadName(rawJson),
                recipes = emptyList(),
                meals = emptyList(),
                workouts = listOf(PlanWorkout(name = "Lower A", exercises = exercises)),
                sessions = listOf(PlanSession(date = monday, workoutIndex = 0)),
                rawJson = rawJson
            ),
            context
        )
    }

    /** The kit's own reading of `n`: the name, or its placeholder when the link carried none. */
    @Suppress("FunctionName")
    private fun PlanLinkHeadName(rawJson: String): String =
        org.json.JSONObject(rawJson).optString("n", "Your coach")

    private fun bookings() = PlanLog.bookings(
        ScheduledSessionRepository.get(context).sessions.value,
        RoutineRepository.get(context).routines.value
    )

    /** The card, read off the stores the import wrote, exactly as Train reads them. */
    private fun card(sessions: List<WorkoutSession> = emptyList(), today: String = monday) =
        PlanLog.compare(bookings(), sessions, today, monday)

    /** The signature above the head, read the way `WorkoutScreen` reads it. */
    private fun signature(today: String = monday): String =
        PlanLog.sentBy(card(today = today)!!, bookings())

    @Test
    fun `a ramp a coach sent reads back as the ramp, not as its most common set`() {
        accept(PlanWorkoutExercise(
            name = "Back Squat", equipment = "Barbell",
            sets = listOf(
                PlanSet(weightLb = 225.0, reps = 5),
                PlanSet(weightLb = 225.0, reps = 5),
                PlanSet(weightLb = 245.0, reps = 3)
            )
        ))
        val logged = listOf(WorkoutSession(date = monday, name = "Lower A", exercises = listOf(
            LoggedExercise(name = "Back Squat", equipment = "Barbell", sets = listOf(
                WorkoutSet(weightLb = 225.0, reps = 5),
                WorkoutSet(weightLb = 225.0, reps = 5),
                WorkoutSet(weightLb = 245.0, reps = 2)
            ))
        )))
        val day = card(logged)!!.days.first()
        assertEquals("Mon 12 Oct · Lower A · logged", day.text)
        assertEquals("225 x 5 · 225 x 5 · 245 x 3", day.exercises[0].asked!!.text)
        assertEquals("225 x 5 · 225 x 5 · 245 x 2", day.exercises[0].logged!!.text)
    }

    @Test
    fun `a weight the coach left to the lifter stays blank on the asked row`() {
        accept(PlanWorkoutExercise(
            name = "Back Squat", equipment = "Barbell",
            sets = listOf(PlanSet(reps = 5), PlanSet(weightLb = 225.0, reps = 5))
        ))
        val day = card()!!.days.first()
        assertEquals("5 reps · 225 x 5", day.exercises[0].asked!!.text)
    }

    @Test
    fun `an each-side lift a coach sent asks for twice its tuples`() {
        accept(PlanWorkoutExercise(
            name = "Split Squat", equipment = "Dumbbell", eachSide = true,
            sets = listOf(PlanSet(weightLb = 40.0, reps = 8), PlanSet(weightLb = 40.0, reps = 8))
        ))
        val day = card()!!.days.first()
        assertEquals("Split Squat (Dumbbell) · each side", day.exercises[0].title)
        assertEquals("Each side · L 2 · R 2", day.exercises[0].sideLine)
        assertEquals("40 x 8 · 40 x 8 each side", day.exercises[0].asked!!.text)
    }

    @Test
    fun `a set the coach put on one side is asked for on that side`() {
        accept(PlanWorkoutExercise(
            name = "Calf Raise", equipment = "Machine",
            sets = listOf(PlanSet(weightLb = 90.0, reps = 12),
                PlanSet(weightLb = 90.0, reps = 12, side = ShareSide.LEFT))
        ))
        val logged = listOf(WorkoutSession(date = monday, name = "Lower A", exercises = listOf(
            LoggedExercise(name = "Calf Raise", equipment = "Machine", sets = listOf(
                WorkoutSet(weightLb = 90.0, reps = 12),
                WorkoutSet(weightLb = 90.0, reps = 12, side = SetSide.LEFT)
            ))
        )))
        val day = card(logged)!!.days.first()
        assertEquals("L 1/1 · 1/1 both", day.exercises[0].sideLine)
        assertEquals("L 90 x 12   Both 90 x 12", day.exercises[0].asked!!.text)
    }

    @Test
    fun `a booked day answers the session that starting it writes`() {
        // Starting a booked routine is the ordinary "start a routine" path, and it lays the
        // prescription out as set rows. Before a single number is edited, the two rows agree -- which
        // is correct, and is why the ask is read from the routine rather than from the session.
        accept(PlanWorkoutExercise(
            name = "Back Squat", equipment = "Barbell",
            sets = listOf(PlanSet(weightLb = 225.0, reps = 5), PlanSet(weightLb = 245.0, reps = 3))
        ))
        val routine = RoutineRepository.get(context).routines.value.last()
        val day = card(listOf(routine.toSession(monday)))!!.days.first()
        assertNotNull(day.exercises[0].logged)
        assertEquals("225 x 5 · 245 x 3", day.exercises[0].asked!!.text)
        assertEquals("225 x 5 · 245 x 3", day.exercises[0].logged!!.text)
    }

    /* ---------------- who sent it ---------------- */

    @Test
    fun `a plan that carried a name signs the week with it`() {
        accept(
            rawJson = """{"v":1,"t":"plan","l":"x","n":"Coach Sam"}""",
            exercises = listOf(PlanWorkoutExercise(
                name = "Back Squat", equipment = "Barbell", sets = listOf(PlanSet(weightLb = 225.0, reps = 5))
            ))
        )
        assertEquals("Coach Sam", ScheduledSessionRepository.get(context).sessions.value.last().fromCoach)
        assertEquals("From Coach Sam", signature())
    }

    @Test
    fun `a plan that carried no name signs the week the way it always did`() {
        // No `n` at all. The kit hands the importer its placeholder "Your coach" for this link, which
        // is exactly why the importer reads the raw JSON instead: a placeholder stored as a name
        // would sign the week "From Your coach".
        accept(
            rawJson = """{"v":1,"t":"plan","l":"x"}""",
            exercises = listOf(PlanWorkoutExercise(
                name = "Back Squat", equipment = "Barbell", sets = listOf(PlanSet(weightLb = 225.0, reps = 5))
            ))
        )
        assertNull(ScheduledSessionRepository.get(context).sessions.value.last().fromCoach)
        assertEquals("From your coach", signature())
    }

    @Test
    fun `a name arrives as text and nothing else`() {
        // Free text from somebody else's app: whitespace collapsed the way a browser collapses it,
        // so the line reads the same in both, and no other meaning taken from it.
        accept(
            rawJson = """{"v":1,"t":"plan","l":"x","n":"  Coach\n\t Sam  "}""",
            exercises = listOf(PlanWorkoutExercise(name = "Back Squat", equipment = "Barbell",
                sets = listOf(PlanSet(weightLb = 225.0, reps = 5))))
        )
        assertEquals("From Coach Sam", signature())
    }

    @Test
    fun `a name with no end to it is stored whole`() {
        val long = "Coach " + "Wolfeschlegelsteinhausenbergerdorff ".repeat(20).trim()
        accept(
            rawJson = org.json.JSONObject()
                .put("v", 1).put("t", "plan").put("l", "x").put("n", long).toString(),
            exercises = listOf(PlanWorkoutExercise(name = "Back Squat", equipment = "Barbell",
                sets = listOf(PlanSet(weightLb = 225.0, reps = 5))))
        )
        assertEquals("From $long", signature())
        // And nowhere below the signature.
        PlanLog.lines(card()).forEach { line -> assertFalse(line.contains("Wolfeschlegel")) }
    }

    /* ---------------- which session answered the booking ---------------- */

    /** Train's Start button, end to end: the session it writes, and the link it records. */
    private fun start(): WorkoutSession = runBlocking {
        val repo = ScheduledSessionRepository.get(context)
        val booking = repo.sessions.value.last()
        val routine = RoutineRepository.get(context).routines.value.first { it.id == booking.routineId }
        val started = routine.toSession(booking.date)
        WorkoutRepository.get(context).save(started)
        repo.markStarted(booking.id, started.id)
        started
    }

    @Test
    fun `starting a booked session records which session it became`() {
        accept(PlanWorkoutExercise(
            name = "Back Squat", equipment = "Barbell",
            sets = listOf(PlanSet(weightLb = 225.0, reps = 5), PlanSet(weightLb = 245.0, reps = 3))
        ))
        val started = start()
        assertEquals(
            started.id,
            ScheduledSessionRepository.get(context).sessions.value.last().startedSessionId
        )
        // And it survives the file: this is the one fact a relaunch has to still know.
        runBlocking { ScheduledSessionRepository.get(context).load() }
        assertEquals(
            started.id,
            ScheduledSessionRepository.get(context).sessions.value.last().startedSessionId
        )
    }

    @Test
    fun `a second session on the booked day is Also logged, not part of the answer`() {
        accept(PlanWorkoutExercise(
            name = "Back Squat", equipment = "Barbell",
            sets = listOf(PlanSet(weightLb = 225.0, reps = 5), PlanSet(weightLb = 245.0, reps = 3))
        ))
        val started = start()
        val own = WorkoutSession(date = monday, name = "Arms", exercises = listOf(
            LoggedExercise(name = "Barbell Curl", equipment = "Barbell",
                sets = listOf(WorkoutSet(weightLb = 65.0, reps = 10)))
        ))
        val day = card(listOf(started, own))!!.days.first()
        assertEquals("225 x 5 · 245 x 3", day.exercises[0].logged!!.text)
        assertEquals(listOf("Barbell Curl (Barbell) · 1 set"), day.alsoLogged.map { it.text })
    }

    @Test
    fun `a booking whose started session was deleted is pooled again`() {
        accept(PlanWorkoutExercise(
            name = "Back Squat", equipment = "Barbell",
            sets = listOf(PlanSet(weightLb = 225.0, reps = 5), PlanSet(weightLb = 245.0, reps = 3))
        ))
        val started = start()
        runBlocking { WorkoutRepository.get(context).delete(started.id) }
        // The booking still names it; the card resolves the id against the sessions it holds, finds
        // nothing, and reads the day -- which is what a booking nobody started does.
        val logged = listOf(WorkoutSession(date = monday, name = "Arms", exercises = listOf(
            LoggedExercise(name = "Back Squat", equipment = "Barbell",
                sets = listOf(WorkoutSet(weightLb = 225.0, reps = 5)))
        )))
        val day = card(logged)!!.days.first()
        assertEquals("logged", day.state)
        assertEquals("225 x 5", day.exercises[0].logged!!.text)
        assertEquals(emptyList<String>(), day.alsoLogged.map { it.text })
    }

    /* ---------------- the meals a coach booked ---------------- */

    /**
     * A plan that books meals, accepted for real: `m` becomes [PlannedMeal]s marked as the coach's,
     * so the card shows what they booked and leaves the lifter's own planned meals alone -- and so a
     * week that booked only food is signed and is a card at all.
     */
    @Test
    fun `a plan that books meals reaches the card, and the lifter's own do not`() = runBlocking {
        PlanImporter.accept(
            PlanPayload(
                coachName = "Doug",
                recipes = listOf(PlanRecipe(name = "Beef Chilli", servings = 4.0)),
                meals = listOf(PlanMeal(date = monday, mealSlot = 2, recipeIndex = 0, servings = 2.0)),
                workouts = emptyList(),
                sessions = emptyList(),
                rawJson = """{"v":1,"t":"plan","l":"x","n":"Doug","test":"meals-1"}"""
            ),
            context
        )
        // And one the lifter placed for themselves, in Cook, on the same day.
        val repo = RecipeRepository.get(context)
        val mine = Recipe(name = "Overnight Oats", servings = 1.0)
        repo.addRecipe(mine)
        repo.plan(recipe = mine, date = monday, meal = Meal.BREAKFAST)

        val plan = repo.plan.value
        assertEquals(2, plan.size)
        assertEquals(1, plan.count { it.fromCoach })

        val week = PlanLog.compare(bookings(), emptyList(), monday, monday, plan)!!
        assertEquals(
            listOf(
                // `today` here is the Monday itself, so the dinner is still ahead of the lifter.
                "Booked 1 day, 12–18 Oct · 1 meal booked · 1 to do",
                "Mon 12 Oct · 1 meal booked · to do",
                "Meals",
                "Dinner · Beef Chilli · 2 servings",
                PlanLog.FOOTER
            ),
            PlanLog.lines(week)
        )
        assertEquals("From Doug", PlanLog.sentBy(week, bookings(), plan))
    }

    /**
     * A meal a coach booked survives the backup file, under LIFT web's own `fromCoach` spelling: the
     * coach's name, or `true` for a plan that named nobody, and nothing at all for the lifter's own.
     * Without it a restore would leave the meals in the store and empty the meals half of the card.
     */
    @Test
    fun `which meals a coach booked survives the backup file`() = runBlocking {
        val repo = RecipeRepository.get(context)
        val recipe = Recipe(name = "Beef Chilli", servings = 4.0)
        repo.addRecipe(recipe)
        repo.plan(recipe = recipe, date = monday, meal = Meal.DINNER, servings = 2.0,
            fromCoach = true, coachName = "Doug")
        repo.plan(recipe = recipe, date = monday, meal = Meal.LUNCH, fromCoach = true, coachName = null)
        repo.plan(recipe = recipe, date = monday, meal = Meal.BREAKFAST)

        val rows = repo.plan.value.map { it.toJson() }
        val byMeal = rows.associateBy { it.getString("meal") }
        assertEquals("Doug", byMeal.getValue("DINNER").getString("fromCoach"))
        assertEquals(true, byMeal.getValue("LUNCH").getBoolean("fromCoach"))
        assertFalse("a meal you placed yourself carries no coach at all",
            byMeal.getValue("BREAKFAST").has("fromCoach"))

        val back = rows.map { plannedMealFromJson(it) }
        assertEquals(listOf(true, true, false), back.map { it.fromCoach })
        assertEquals(listOf("Doug", null, null), back.map { it.coachName })
        assertEquals(
            listOf("Lunch · Beef Chilli · 1 serving", "Dinner · Beef Chilli · 2 servings"),
            PlanLog.bookedMeals(back).map { it.title }
        )
    }

    /** A file written before any of this says nothing about coaches, so every meal in it is the
     *  lifter's own -- and the card reads exactly as it did. */
    @Test
    fun `a planned meal written before the marker is the lifter's own`() {
        val old = org.json.JSONObject(
            """{"id":"m1","recipeId":"r1","date":"$monday","meal":"DINNER","servings":2,
               "recipeName":"Beef Chilli"}"""
        )
        val meal = plannedMealFromJson(old)
        assertFalse(meal.fromCoach)
        assertNull(meal.coachName)
        assertEquals(emptyList<PlanLog.MealRow>(), PlanLog.bookedMeals(listOf(meal)))
        assertNull(PlanLog.compare(emptyList(), emptyList(), monday, monday, listOf(meal)))
    }

}
