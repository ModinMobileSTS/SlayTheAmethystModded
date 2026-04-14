package io.stamethyst.backend.diag

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryDiagnosticsLoggerTest {
    @Test
    fun trimMemoryLevelName_mapsKnownLevels() {
        assertEquals("TRIM_MEMORY_RUNNING_LOW", MemoryDiagnosticsLogger.trimMemoryLevelName(10))
        assertEquals("TRIM_MEMORY_COMPLETE", MemoryDiagnosticsLogger.trimMemoryLevelName(80))
        assertEquals("LEVEL_999", MemoryDiagnosticsLogger.trimMemoryLevelName(999))
    }

    @Test
    fun buildFileStatsPayload_summarizesLargestFilesFirst() {
        val tempDir = createTempDirectory("mem-diag-stats").toFile()
        val small = File(tempDir, "small.jar").apply { writeText("12") }
        val large = File(tempDir, "large.jar").apply { writeText("123456") }
        val medium = File(tempDir, "medium.jar").apply { writeText("1234") }

        val payload = MemoryDiagnosticsLogger.buildFileStatsPayload(
            files = listOf(small, large, medium),
            topFilesLimit = 2
        )

        assertEquals(3, payload.getInt("count"))
        assertEquals(
            small.length() + large.length() + medium.length(),
            payload.getLong("totalBytes")
        )
        assertEquals("large.jar", payload.getString("largestFileName"))
        assertEquals(large.length(), payload.getLong("largestFileBytes"))

        val topFiles = payload.getJSONArray("topFiles")
        assertEquals(2, topFiles.length())
        assertEquals("large.jar", topFiles.getJSONObject(0).getString("name"))
        assertEquals("medium.jar", topFiles.getJSONObject(1).getString("name"))
    }
}
