// LIFT Link's protocol layer: framing, payload codec, pairing code, state machine.
//
// Plain Kotlin on the JVM, with no Android and no dependency at all, so its rules are tested
// without an emulator and the same files compile into the Wear OS app unchanged. The sources are
// a byte-identical copy of dugcanlift-lift-watch's android/liftkit link package (the canonical
// one) -- see LinkProtocol.kt and CLAUDE.md "LIFT Link". Change it there and copy it here.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(libs.junit)
}

tasks.test { useJUnit() }
