package com.dugcanlift.macrocalc.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Prints (and saves) the link today's [CoachShare] encoder produces for the
 * fixed inputs in [CoachShareFixture]. This is not a correctness check on
 * its own — it is the oracle Task 6 diffs the extracted codec's output
 * against, via [CoachShareFixture.canonical].
 */
@RunWith(RobolectricTestRunner::class)
class CoachShareCaptureTest {

    @Test
    fun `print the link for the fixture inputs`() {
        val link = CoachShareFixture.buildToday(ApplicationProvider.getApplicationContext())
        val fragment = link.substringAfter('#')

        println("SHARE_FIXTURE $fragment")
        File("build/share-link-android.txt").apply {
            parentFile?.mkdirs()
        }.writeText(fragment)
    }
}
