package io.stamethyst.backend.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class LatestLogCrashDetectorTest {
    @Test
    fun detect_returnsNull_whenOnlyExceptionStackPresentWithoutCrashMarker() {
        val logFile = createTempLog(
            """
            java.lang.NumberFormatException: null
            	at VUPShionMod.util.ShionConfig.getInt(ShionConfig.java:66)
            	at VUPShionMod.util.SaveHelper.loadSkins(SaveHelper.java:259)
            11:08:40.866 INFO saveAndContinue.SaveAndContinue> KamiShion save does NOT exist!
            """.trimIndent()
        )

        val summary = LatestLogCrashDetector.detect(logFile)

        assertNull(summary)
    }

    @Test
    fun detect_extractsExceptionDetail_afterCrashMarker() {
        val logFile = createTempLog(
            """
            11:08:40.866 INFO saveAndContinue.SaveAndContinue> KamiShion save does NOT exist!
            Game crashed.
            Cause:
            java.lang.IllegalStateException: boom
            	at example.Test.render(Test.java:42)
            """.trimIndent()
        )

        val summary = LatestLogCrashDetector.detect(logFile)

        assertEquals(
            "java.lang.IllegalStateException: boom at example.Test.render(Test.java:42)",
            summary?.detail
        )
    }

    @Test
    fun readLastNonBlankLine_returnsLastNonEmptyLine() {
        val logFile = createTempLog(
            """
            first

            second
            """.trimIndent() + "\n\n"
        )

        val lastLine = LatestLogCrashDetector.readLastNonBlankLine(logFile)

        assertEquals("second", lastLine)
    }

    private fun createTempLog(content: String): File {
        val file = Files.createTempFile("latest-log-crash-detector", ".log").toFile()
        file.writeText(content, StandardCharsets.UTF_8)
        file.deleteOnExit()
        return file
    }
}
