package io.stamethyst.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePathsMemoryDiagnosticsFilesTest {
    @Test
    fun isMemoryDiagnosticsFileName_matchesBaseAndRotatedFiles() {
        assertTrue(RuntimePaths.isMemoryDiagnosticsFileName("memory_diagnostics.log"))
        assertTrue(RuntimePaths.isMemoryDiagnosticsFileName("memory_diagnostics.log.1"))
        assertTrue(RuntimePaths.isMemoryDiagnosticsFileName("memory_diagnostics.log.2"))
        assertFalse(RuntimePaths.isMemoryDiagnosticsFileName("memory_diagnostics.txt"))
        assertFalse(RuntimePaths.isMemoryDiagnosticsFileName("latest.log"))
    }

    @Test
    fun compareMemoryDiagnosticsFileNames_ordersBaseBeforeRotations() {
        val sorted = listOf(
            "memory_diagnostics.log.2",
            "memory_diagnostics.log",
            "memory_diagnostics.log.10",
            "memory_diagnostics.log.1"
        ).sortedWith(RuntimePaths::compareMemoryDiagnosticsFileNames)

        assertEquals(
            listOf(
                "memory_diagnostics.log",
                "memory_diagnostics.log.1",
                "memory_diagnostics.log.2",
                "memory_diagnostics.log.10"
            ),
            sorted
        )
    }
}
