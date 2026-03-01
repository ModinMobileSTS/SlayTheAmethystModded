package io.stamethyst.backend.launch

import android.content.Context
import io.stamethyst.backend.crash.CrashDiagnostics
import io.stamethyst.backend.crash.ProcessExitSummary

@Deprecated(
    message = "Use io.stamethyst.backend.crash.CrashDiagnostics directly.",
    replaceWith = ReplaceWith("CrashDiagnostics")
)
object CrashReportStore {
    @JvmStatic
    fun clear(context: Context) = CrashDiagnostics.clear(context)

    @JvmStatic
    fun captureLatestProcessExitInfo(context: Context, stage: String): ProcessExitSummary? {
        return CrashDiagnostics.captureLatestProcessExitInfo(context, stage)
    }

    @JvmStatic
    fun recordLaunchResult(
        context: Context,
        stage: String,
        code: Int,
        isSignal: Boolean,
        detail: String?
    ) = CrashDiagnostics.recordLaunchResult(context, stage, code, isSignal, detail)

    @JvmStatic
    fun recordThrowable(context: Context, stage: String, error: Throwable) {
        CrashDiagnostics.recordThrowable(context, stage, error)
    }
}
