package io.stamethyst.backend.diag

import io.stamethyst.backend.crash.LatestLogCrashSummary
import io.stamethyst.backend.crash.ProcessExitSummary

internal object DiagnosticsSummaryFormatter {
    fun buildProcessExitInfoSummary(
        exitSummary: ProcessExitSummary?,
        signalDumpSummary: String?,
        processExitTraceSummary: String? = null
    ): String {
        return buildString {
            if (exitSummary == null) {
                append("processExit=none\n")
            } else {
                append("processExit.pid=").append(exitSummary.pid).append('\n')
                append("processExit.processName=").append(exitSummary.processName).append('\n')
                append("processExit.reason=").append(exitSummary.reasonName).append('\n')
                append("processExit.status=").append(exitSummary.status).append('\n')
                append("processExit.timestamp=").append(exitSummary.timestamp).append('\n')
                append("processExit.description=")
                append(exitSummary.description.ifBlank { "none" })
                append('\n')
            }
            append("processExit.trace.present=").append(!processExitTraceSummary.isNullOrBlank()).append('\n')
            append("processExit.trace.summary=")
            append(processExitTraceSummary ?: "none")
            append('\n')
            append("signalDump.present=").append(!signalDumpSummary.isNullOrBlank()).append('\n')
            append("signalDump.summary=")
            append(signalDumpSummary ?: "none")
            append('\n')
        }
    }

    fun buildLatestLogSummary(
        latestCrash: LatestLogCrashSummary?,
        lastNonBlankLine: String?
    ): String {
        return buildString {
            if (latestCrash == null) {
                append("latestLog.detectedCrashMarker=none\n")
            } else {
                append("latestLog.detectedCrashMarker=").append(latestCrash.marker).append('\n')
                append("latestLog.detectedCrashDetail=").append(latestCrash.detail).append('\n')
            }
            append("latestLog.lastNonBlankLine=")
            append(lastNonBlankLine?.takeIf { it.isNotBlank() } ?: "none")
            append('\n')
        }
    }
}
