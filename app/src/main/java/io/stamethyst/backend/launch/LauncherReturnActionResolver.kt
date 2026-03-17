package io.stamethyst.backend.launch

import io.stamethyst.backend.crash.ProcessExitSummary

internal data class CrashReturnPayload(
    val code: Int,
    val isSignal: Boolean,
    val detail: String?
)

internal data class LauncherReturnSnapshot(
    val explicitCrash: CrashReturnPayload? = null,
    val processExitCrash: ProcessExitSummary? = null,
    val heapPressureWarning: Boolean = false,
    val expectedBackExitRecent: Boolean = false
)

internal sealed interface LauncherReturnAction {
    data object None : LauncherReturnAction
    data object ExpectedBackExit : LauncherReturnAction
    data object HeapPressureWarning : LauncherReturnAction
    data class ExplicitCrash(val payload: CrashReturnPayload) : LauncherReturnAction
    data class ProcessExitCrash(val summary: ProcessExitSummary) : LauncherReturnAction
}

internal object LauncherReturnActionResolver {
    fun resolve(snapshot: LauncherReturnSnapshot): LauncherReturnAction {
        val explicitCrash = snapshot.explicitCrash
        if (explicitCrash != null) {
            return LauncherReturnAction.ExplicitCrash(explicitCrash)
        }
        if (snapshot.expectedBackExitRecent) {
            return LauncherReturnAction.ExpectedBackExit
        }
        val processExitCrash = snapshot.processExitCrash
        if (processExitCrash != null) {
            return LauncherReturnAction.ProcessExitCrash(processExitCrash)
        }
        if (snapshot.heapPressureWarning) {
            return LauncherReturnAction.HeapPressureWarning
        }
        return LauncherReturnAction.None
    }
}
