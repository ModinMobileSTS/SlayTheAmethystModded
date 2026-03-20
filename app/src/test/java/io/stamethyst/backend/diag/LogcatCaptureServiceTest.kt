package io.stamethyst.backend.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatCaptureServiceTest {
    @Test
    fun buildSystemLogcatCommandForCapture_scopesToDiagnosticTags() {
        assertEquals(
            listOf(
                "logcat",
                "-v", "threadtime",
                "-b", "main",
                "-b", "system",
                "-b", "crash",
                "-b", "events",
                "ActivityManager:I",
                "ActivityTaskManager:I",
                "ProcessList:I",
                "lmkd:I",
                "libprocessgroup:I",
                "SurfaceFlinger:I",
                "BLASTBufferQueue:I",
                "BufferQueueProducer:I",
                "WindowManager:I",
                "DEBUG:I",
                "libc:I",
                "AndroidRuntime:I",
                "*:S"
            ),
            LogcatCaptureService.buildSystemLogcatCommandForCapture()
        )
    }

    @Test
    fun buildPidLogcatCommandForCapture_targetsSingleTrackedPid() {
        assertEquals(
            listOf(
                "logcat",
                "-v", "threadtime",
                "-b", "main",
                "-b", "system",
                "-b", "crash",
                "-b", "events",
                "--pid",
                "23456",
                "*:I"
            ),
            LogcatCaptureService.buildPidLogcatCommandForCapture(23456)
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
}
