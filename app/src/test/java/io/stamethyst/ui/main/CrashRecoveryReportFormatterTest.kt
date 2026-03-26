package io.stamethyst.ui.main

import org.junit.Assert.assertEquals
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
    }

    @Test
    fun format_fallsBackToMessage_whenDetailedReportMissing() {
        val report = CrashRecoveryReportFormatter.format(
            detail = "   ",
            fallbackMessage = "Game process exited unexpectedly, exit code -1."
        )

        assertEquals("Game process exited unexpectedly, exit code -1.", report.summaryText)
        assertEquals("Game process exited unexpectedly, exit code -1.", report.reportText)
    }
}
