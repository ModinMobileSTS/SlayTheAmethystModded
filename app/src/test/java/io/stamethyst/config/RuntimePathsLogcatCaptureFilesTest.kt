package io.stamethyst.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePathsLogcatCaptureFilesTest {
    @Test
    fun isLogcatCaptureFileName_matchesCurrentAndLegacyFiles() {
        assertTrue(RuntimePaths.isLogcatCaptureFileName("logcat_app_capture.log"))
        assertTrue(RuntimePaths.isLogcatCaptureFileName("logcat_app_capture.log.1"))
        assertTrue(RuntimePaths.isLogcatCaptureFileName("logcat_system_capture.log"))
        assertTrue(RuntimePaths.isLogcatCaptureFileName("logcat_system_capture.log.2"))
        assertTrue(RuntimePaths.isLogcatCaptureFileName("logcat_capture.log"))
        assertTrue(RuntimePaths.isLogcatCaptureFileName("logcat_capture.log.3"))
        assertFalse(RuntimePaths.isLogcatCaptureFileName("latest.log"))
    }

    @Test
    fun compareLogcatCaptureFileNames_ordersByStreamThenRotation() {
        val sorted = listOf(
            "logcat_system_capture.log.1",
            "logcat_capture.log",
            "logcat_app_capture.log.1",
            "logcat_system_capture.log",
            "logcat_app_capture.log"
        ).sortedWith(RuntimePaths::compareLogcatCaptureFileNames)

        assertEquals(
            listOf(
                "logcat_app_capture.log",
                "logcat_app_capture.log.1",
                "logcat_system_capture.log",
                "logcat_system_capture.log.1",
                "logcat_capture.log"
            ),
            sorted
        )
    }
}
