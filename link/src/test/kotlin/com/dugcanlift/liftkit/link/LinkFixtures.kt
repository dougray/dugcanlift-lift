package com.dugcanlift.liftkit.link

/**
 * SHARED SOURCE — identical in `dugcanlift-lift-watch` and `dugcanlift-lift`, along with
 * `fixtures/link-wire.txt`. See [LinkProtocol]'s file comment.
 *
 * These are the objects `LinkWireFixtureTest` encodes and compares against the committed bytes.
 * They are deliberately awkward: a set that prescribes only reps, a set that prescribes nothing,
 * an exercise with no equipment, a left and a right set. A fixture made of round, complete data
 * would pass while every optional field was being written as a zero.
 */
object LinkFixtures {
    const val PLAN_ID = "9b1d7f5a-3c42-4e18-9a60-7d2c5e8f1b03"
    const val SESSION_ID = "1f4c8a26-70d5-4b93-8e11-6a0b3d9c2457"

    /** 185 lb to the gram. A number that is round in pounds and not in kilograms, on purpose. */
    const val BENCH_KG = 83.915

    val nonce: ByteArray = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    val peerNonce: ByteArray = byteArrayOf(-1, -2, -3, -4, -5, -6, -7, -8)

    val hello = Hello(1, 1, null, "Pixel 8", nonce)
    val helloAck = Hello(1, 1, 1, "Galaxy Watch6", peerNonce)

    val plan = Plan(
        planId = PLAN_ID,
        revision = 3,
        name = "Upper A",
        source = PlanSource.ROUTINE,
        scheduledFor = "2026-09-21",
        exercises = listOf(
            PlanExercise(
                name = "Bench Press",
                equipment = "Barbell",
                sets = listOf(
                    PrescribedSet(weightKg = BENCH_KG, reps = 5, rpe = 8.0, restSeconds = 180),
                    PrescribedSet(reps = 5),                       // "five reps, you pick the weight"
                    PrescribedSet(),                               // prescribes nothing at all
                ),
                lastPerformed = LastPerformed(BENCH_KG, 5, 8.0, "2026-09-14"),
            ),
            PlanExercise(
                name = "Lat Pulldown",
                equipment = "Cable",
                note = "slow eccentric",
                sets = listOf(PrescribedSet(weightKg = 60.0, reps = 10)),
            ),
        ),
    )

    val session = FinishedSession(
        sessionId = SESSION_ID,
        revision = 1,
        day = "2026-09-21",
        name = "Upper A",
        startedAtEpochSeconds = 1758441600L,
        exercises = listOf(
            FinishedExercise(
                name = "Bench Press",
                equipment = "Barbell",
                sets = listOf(
                    LoggedSet(weightKg = BENCH_KG, reps = 5, rpe = 8.0),
                    LoggedSet(reps = 5),
                ),
            ),
            FinishedExercise(
                name = "Split Squat",
                sets = listOf(
                    LoggedSet(weightKg = 40.0, reps = 8, side = LogSide.LEFT),
                    LoggedSet(weightKg = 40.0, reps = 8, side = LogSide.RIGHT),
                ),
            ),
        ),
    )

    val report = LoggedSetReport(
        sessionId = SESSION_ID,
        revision = 1,
        exerciseName = "Bench Press",
        equipment = "Barbell",
        setIndex = 2,
        set = LoggedSet(weightKg = BENCH_KG, reps = 5, rpe = 8.0),
        loggedAtEpochSeconds = 1758445200L,
    )

    val ack = LinkAck(MessageType.SESSION_FINISHED, SESSION_ID, 1, AckOutcome.ACCEPTED)

    val error = LinkErrorMessage(LinkError.UNSUPPORTED_VERSION, "peer speaks 2..2")

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun unhex(text: String): ByteArray {
        val clean = text.trim()
        require(clean.length % 2 == 0) { "odd-length hex" }
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
