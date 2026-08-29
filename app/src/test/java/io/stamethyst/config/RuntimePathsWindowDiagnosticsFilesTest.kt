package io.stamethyst.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePathsWindowDiagnosticsFilesTest {
    @Test
    fun isWindowDiagnosticsFileName_matchesBaseAndRotatedFiles() {
        assertTrue(RuntimePaths.isWindowDiagnosticsFileName("window_diagnostics.log"))
        assertTrue(RuntimePaths.isWindowDiagnosticsFileName("window_diagnostics.log.1"))
        assertTrue(RuntimePaths.isWindowDiagnosticsFileName("window_diagnostics.log.2"))
        assertFalse(RuntimePaths.isWindowDiagnosticsFileName("window_diagnostics.txt"))
        assertFalse(RuntimePaths.isWindowDiagnosticsFileName("latest.log"))
    }

    @Test
    fun compareWindowDiagnosticsFileNames_ordersBaseBeforeRotations() {
        val sorted = listOf(
            "window_diagnostics.log.2",
            "window_diagnostics.log",
            "window_diagnostics.log.10",
            "window_diagnostics.log.1"
        ).sortedWith(RuntimePaths::compareWindowDiagnosticsFileNames)

        assertEquals(
            listOf(
                "window_diagnostics.log",
                "window_diagnostics.log.1",
                "window_diagnostics.log.2",
                "window_diagnostics.log.10"
            ),
            sorted
        )
    }
}
