package io.stamethyst

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlingBreakX5DownloadGateTest {
    @Test
    fun firstConsentedDownloadRequest_isAllowed() {
        assertTrue(SlingBreakX5DownloadGate().tryRequest())
    }

    @Test
    fun retries_doNotStartMoreDownloadsInTheSameProcess() {
        val gate = SlingBreakX5DownloadGate()
        var queryCount = 0
        repeat(4) {
            if (gate.tryRequest()) queryCount++
        }
        assertEquals(1, queryCount)
        assertFalse(gate.tryRequest())
    }

    @Test
    fun freshProcess_canRequestDownloadAgain() {
        val oldProcess = SlingBreakX5DownloadGate()
        assertTrue(oldProcess.tryRequest())
        assertFalse(oldProcess.tryRequest())
        assertTrue(SlingBreakX5DownloadGate().tryRequest())
    }
}
