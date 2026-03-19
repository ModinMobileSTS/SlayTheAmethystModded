package io.stamethyst.backend.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatCaptureServiceTest {
    @Test
    fun buildLogcatCommandForCapture_includesEventsBuffer() {
        assertEquals(
            listOf(
                "logcat",
                "-v", "threadtime",
                "-b", "main",
                "-b", "system",
                "-b", "crash",
                "-b", "events",
                "*:I"
            ),
            LogcatCaptureService.buildLogcatCommandForCapture()
        )
    }

    @Test
    fun extractPidFromThreadtimeLine_parsesThreadtimePid() {
        assertEquals(
            23456,
            LogcatCaptureService.extractPidFromThreadtimeLine(
                "03-19 15:30:12.345 23456 23457 I TestTag: hello"
            )
        )
    }

    @Test
    fun extractPidFromThreadtimeLine_rejectsUnexpectedFormat() {
        assertEquals(
            null,
            LogcatCaptureService.extractPidFromThreadtimeLine(
                "--------- beginning of main"
            )
        )
    }

    @Test
    fun isTrackedProcessName_matchesBaseAndChildProcesses() {
        assertTrue(LogcatCaptureService.isTrackedProcessName("io.stamethyst", "io.stamethyst"))
        assertTrue(LogcatCaptureService.isTrackedProcessName("io.stamethyst:game", "io.stamethyst"))
        assertTrue(LogcatCaptureService.isTrackedProcessName("io.stamethyst:prep", "io.stamethyst"))
        assertFalse(LogcatCaptureService.isTrackedProcessName("other.process", "io.stamethyst"))
        assertFalse(LogcatCaptureService.isTrackedProcessName("io.stamethystx", "io.stamethyst"))
    }

    @Test
    fun trackedAppLogFilter_onlyCapturesTrackedPidsAndRetainsSeenProcesses() {
        var nowMs = 0L
        var trackedPids = setOf(111, 222)
        val filter = LogcatCaptureService.TrackedAppLogFilter(
            trackedPidSupplier = { trackedPids },
            nowProviderMs = { nowMs },
            refreshIntervalMs = 250L
        )

        assertTrue(filter.shouldCapture("=== logcat capture started ==="))
        assertTrue(
            filter.shouldCapture(
                "03-19 15:30:12.345 111 111 I LauncherTag: base process"
            )
        )
        assertFalse(
            filter.shouldCapture(
                "03-19 15:30:12.346 999 999 I OtherTag: foreign process"
            )
        )

        trackedPids = emptySet()
        nowMs = 300L
        assertTrue(
            filter.shouldCapture(
                "03-19 15:30:12.347 222 222 I GameTag: tail after process exit"
            )
        )
    }
}
