package io.stamethyst.backend.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class LatestLogCleanShutdownDetectorTest {
    @Test
    fun detect_returnsSummary_forMenuQuitFollowedByTeardownAbortNoise() {
        val logFile = createTempLog(
            """
            09:25:52.185 INFO mainMenu.MenuButton> Quit Game button clicked!
            09:25:52.190 INFO core.CardCrawlGame> Game shutting down...
            09:25:52.191 INFO core.CardCrawlGame> Flushing logs to disk. Clean shutdown successful.
            Game closed.FORTIFY: pthread_mutex_lock called on a destroyed mutex (0x7397a0eac8)
            """.trimIndent()
        )

        val summary = LatestLogCleanShutdownDetector.detect(logFile)

        assertTrue(summary?.quitRequestedFromMenu == true)
        assertEquals(
            "09:25:52.191 INFO core.CardCrawlGame> Flushing logs to disk. Clean shutdown successful.",
            summary?.marker
        )
    }

    @Test
    fun detect_returnsSummary_forCleanShutdownWithoutMenuQuitMarker() {
        val logFile = createTempLog(
            """
            09:25:52.190 INFO core.CardCrawlGame> Game shutting down...
            09:25:52.191 INFO core.CardCrawlGame> Flushing logs to disk. Clean shutdown successful.
            Game closed.
            """.trimIndent()
        )

        val summary = LatestLogCleanShutdownDetector.detect(logFile)

        assertFalse(summary?.quitRequestedFromMenu ?: true)
    }

    @Test
    fun detect_returnsNull_whenCrashMarkerExists() {
        val logFile = createTempLog(
            """
            11:08:40.866 INFO saveAndContinue.SaveAndContinue> KamiShion save does NOT exist!
            Game crashed.
            Cause:
            java.lang.IllegalStateException: boom
            	at example.Test.render(Test.java:42)
            11:08:41.123 INFO core.CardCrawlGame> Game shutting down...
            11:08:41.124 INFO core.CardCrawlGame> Flushing logs to disk. Clean shutdown successful.
            """.trimIndent()
        )

        val summary = LatestLogCleanShutdownDetector.detect(logFile)

        assertNull(summary)
    }

    private fun createTempLog(content: String): File {
        val file = Files.createTempFile("latest-log-clean-shutdown-detector", ".log").toFile()
        file.writeText(content, StandardCharsets.UTF_8)
        file.deleteOnExit()
        return file
    }
}
