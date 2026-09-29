package io.stamethyst.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePathsWebViewDiagnosticsFilesTest {
    @Test
    fun isWebViewDiagnosticsFileName_matchesFiveRollingSlots() {
        assertTrue(RuntimePaths.isWebViewDiagnosticsFileName("webview_diagnostics.log"))
        assertTrue(RuntimePaths.isWebViewDiagnosticsFileName("webview_diagnostics.log.1"))
        assertTrue(RuntimePaths.isWebViewDiagnosticsFileName("webview_diagnostics.log.4"))
        assertFalse(RuntimePaths.isWebViewDiagnosticsFileName("webview_diagnostics.txt"))
        assertFalse(RuntimePaths.isWebViewDiagnosticsFileName("latest.log"))
    }

    @Test
    fun compareWebViewDiagnosticsFileNames_ordersCurrentBeforeOlderSlots() {
        val sorted = listOf(
            "webview_diagnostics.log.2",
            "webview_diagnostics.log",
            "webview_diagnostics.log.4",
            "webview_diagnostics.log.1"
        ).sortedWith(RuntimePaths::compareWebViewDiagnosticsFileNames)

        assertEquals(
            listOf(
                "webview_diagnostics.log",
                "webview_diagnostics.log.1",
                "webview_diagnostics.log.2",
                "webview_diagnostics.log.4"
            ),
            sorted
        )
    }
}
