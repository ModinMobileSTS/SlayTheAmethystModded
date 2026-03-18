package io.stamethyst.backend.diag

import io.stamethyst.backend.crash.LatestLogCrashSummary
import io.stamethyst.backend.crash.ProcessExitSummary
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsSummaryFormatterTest {
    @Test
    fun buildProcessExitInfoSummary_includesExitFieldsAndSignalState() {
        val text = DiagnosticsSummaryFormatter.buildProcessExitInfoSummary(
            exitSummary = ProcessExitSummary(
                pid = 321,
                processName = "io.stamethyst:game",
                reason = 6,
                reasonName = "REASON_SIGNALED",
                status = 11,
                timestamp = 123456789L,
                description = "",
                isSignal = true
            ),
            signalDumpSummary = "signal 11 at libmobileglues.so"
        )

        assertTrue(text.contains("processExit.pid=321"))
        assertTrue(text.contains("processExit.processName=io.stamethyst:game"))
        assertTrue(text.contains("processExit.reason=REASON_SIGNALED"))
        assertTrue(text.contains("processExit.status=11"))
        assertTrue(text.contains("processExit.description=none"))
        assertTrue(text.contains("signalDump.present=true"))
        assertTrue(text.contains("signalDump.summary=signal 11 at libmobileglues.so"))
    }

    @Test
    fun buildLatestLogSummary_reportsMarkerAndTailLine() {
        val text = DiagnosticsSummaryFormatter.buildLatestLogSummary(
            latestCrash = LatestLogCrashSummary(
                detail = "RuntimeException at foo.Bar.render(Bar.java:12)",
                marker = "Game crashed."
            ),
            lastNonBlankLine = "05:46:22.096 INFO basemod.BaseMod> publishPostDraw"
        )

        assertTrue(text.contains("latestLog.detectedCrashMarker=Game crashed."))
        assertTrue(text.contains("latestLog.detectedCrashDetail=RuntimeException at foo.Bar.render(Bar.java:12)"))
        assertTrue(text.contains("latestLog.lastNonBlankLine=05:46:22.096 INFO basemod.BaseMod> publishPostDraw"))
    }

    @Test
    fun buildLatestLogSummary_reportsMissingCrashMarker() {
        val text = DiagnosticsSummaryFormatter.buildLatestLogSummary(
            latestCrash = null,
            lastNonBlankLine = null
        )

        assertTrue(text.contains("latestLog.detectedCrashMarker=none"))
        assertTrue(text.contains("latestLog.lastNonBlankLine=none"))
    }
}
