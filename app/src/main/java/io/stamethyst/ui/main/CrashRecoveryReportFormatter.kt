package io.stamethyst.ui.main

internal data class CrashRecoveryReport(
    val summaryText: String,
    val reportText: String
)

internal object CrashRecoveryReportFormatter {
    private val skippedSummaryLines = setOf("game crashed.", "cause:")

    fun format(detail: String?, fallbackMessage: String): CrashRecoveryReport {
        val normalizedReport = detail
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: fallbackMessage.trim()
        val summaryText = normalizedReport
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                line.isNotEmpty() && skippedSummaryLines.none { skipped ->
                    line.equals(skipped, ignoreCase = true)
                }
            }
            ?: fallbackMessage.trim()
        return CrashRecoveryReport(
            summaryText = summaryText,
            reportText = normalizedReport
        )
    }
}
