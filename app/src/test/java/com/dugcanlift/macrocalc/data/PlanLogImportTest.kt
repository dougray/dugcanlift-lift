package com.dugcanlift.macrocalc.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dugcanlift.kit.PlanPayload
import com.dugcanlift.kit.PlanSession
import com.dugcanlift.kit.PlanSet
import com.dugcanlift.kit.PlanWorkout
import com.dugcanlift.kit.PlanWorkoutExercise
import com.dugcanlift.kit.ShareSide
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    private fun accept(vararg exercises: PlanWorkoutExercise) = runBlocking {
        PlanImporter.accept(
            PlanPayload(
                coachName = "Doug",
                recipes = emptyList(),
                meals = emptyList(),
                workouts = listOf(PlanWorkout(name = "Lower A", exercises = exercises.toList())),
                sessions = listOf(PlanSession(date = monday, workoutIndex = 0)),
                rawJson = """{"v":1,"t":"plan","l":"x","n":"Doug"}"""
            ),
            context
        )
    }

    /** The card, read off the stores the import wrote, exactly as Train reads them. */
    private fun card(sessions: List<WorkoutSession> = emptyList(), today: String = monday) = PlanLog.compare(
        PlanLog.bookings(
            ScheduledSessionRepository.get(context).sessions.value,
            RoutineRepository.get(context).routines.value
        ),
        sessions,
        today,
        monday
    )

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
}
