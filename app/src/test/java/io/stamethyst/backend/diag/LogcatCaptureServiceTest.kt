package io.stamethyst.backend.diag

import org.junit.Assert.assertEquals
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
}
