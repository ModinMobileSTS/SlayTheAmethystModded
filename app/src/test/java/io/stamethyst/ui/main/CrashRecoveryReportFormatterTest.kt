package io.stamethyst.ui.main

import io.stamethyst.backend.launch.LaunchPreparationFailureMessageResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashRecoveryReportFormatterTest {
    @Test
    fun format_prefersDetailedReport_andExtractsFirstUsefulSummaryLine() {
        val report = CrashRecoveryReportFormatter.format(
            detail = """
                Game crashed.
                Cause:
                java.lang.IndexOutOfBoundsException: Index: 6, Size: 6
                	at com.megacrit.cardcrawl.core.CardCrawlGame.loadPlayerSave(CardCrawlGame.java:1112)
            """.trimIndent(),
            fallbackMessage = "Game crashed."
        )

        assertEquals(
            "java.lang.IndexOutOfBoundsException: Index: 6, Size: 6",
            report.summaryText
        )
        assertEquals(
            """
                Game crashed.
                Cause:
                java.lang.IndexOutOfBoundsException: Index: 6, Size: 6
                	at com.megacrit.cardcrawl.core.CardCrawlGame.loadPlayerSave(CardCrawlGame.java:1112)
            """.trimIndent(),
            report.reportText
        )
        assertFalse(report.isLaunchPreparationProcessDisconnected)
    }

    @Test
    fun format_fallsBackToMessage_whenDetailedReportMissing() {
        val report = CrashRecoveryReportFormatter.format(
            detail = "   ",
            fallbackMessage = "Game process exited unexpectedly, exit code -1."
        )

        assertEquals("Game process exited unexpectedly, exit code -1.", report.summaryText)
        assertEquals("Game process exited unexpectedly, exit code -1.", report.reportText)
        assertFalse(report.isLaunchPreparationProcessDisconnected)
    }

    @Test
    fun format_detectsPrepProcessDisconnect_andStripsInternalMarker() {
        val report = CrashRecoveryReportFormatter.format(
            detail = LaunchPreparationFailureMessageResolver.markPrepProcessFailureDetail(
                "The launcher lost contact with the startup preparation process before launch could continue."
            ),
            fallbackMessage = "Game crashed."
        )

        assertEquals(
            "The launcher lost contact with the startup preparation process before launch could continue.",
            report.summaryText
        )
        assertEquals(
            "The launcher lost contact with the startup preparation process before launch could continue.",
            report.reportText
        )
        assertTrue(report.isLaunchPreparationProcessDisconnected)
    }
}
