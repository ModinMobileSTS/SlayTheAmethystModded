package io.stamethyst.backend.diag

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RollingTextLogWriterTest {
    @Test
    fun appendLine_rotatesAndKeepsNewestFiles() {
        val tempDir = createTempDirectory("rolling-log-writer-test").toFile()
        val baseFile = File(tempDir, "logcat_capture.log")

        RollingTextLogWriter(
            baseFile = baseFile,
            maxBytesPerFile = 18L,
            maxFiles = 3
        ).use { writer ->
            writer.appendLine("line-1")
            writer.appendLine("line-2")
            writer.appendLine("line-3")
            writer.appendLine("line-4")
        }

        assertTrue(baseFile.isFile)
        assertTrue(File(tempDir, "logcat_capture.log.1").isFile)
        assertFalse(File(tempDir, "logcat_capture.log.2").isFile)
        assertFalse(File(tempDir, "logcat_capture.log.3").exists())

        assertEquals("line-3\nline-4\n", baseFile.readText())
        assertEquals("line-1\nline-2\n", File(tempDir, "logcat_capture.log.1").readText())
    }
}
