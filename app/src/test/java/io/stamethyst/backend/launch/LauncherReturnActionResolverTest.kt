package io.stamethyst.backend.launch

import io.stamethyst.backend.crash.ProcessExitSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherReturnActionResolverTest {
    @Test
    fun resolve_prefersExplicitCrash_overAllOtherSignals() {
        val action = LauncherReturnActionResolver.resolve(
            LauncherReturnSnapshot(
                explicitCrash = CrashReturnPayload(code = -1, isSignal = false, detail = "boom"),
                processExitCrash = sampleProcessExitSummary(),
                heapPressureWarning = true,
                expectedBackExitRecent = true
            )
        )

        assertEquals(
            LauncherReturnAction.ExplicitCrash(
                CrashReturnPayload(code = -1, isSignal = false, detail = "boom")
            ),
            action
        )
    }

    @Test
    fun resolve_prefersExpectedBackExit_overProcessExitCrashAndHeap() {
        val summary = sampleProcessExitSummary()

        val action = LauncherReturnActionResolver.resolve(
            LauncherReturnSnapshot(
                processExitCrash = summary,
                heapPressureWarning = true,
                expectedBackExitRecent = true
            )
        )

        assertEquals(LauncherReturnAction.ExpectedBackExit, action)
    }

    @Test
    fun resolve_prefersProcessExitCrash_overHeapPressure() {
        val summary = sampleProcessExitSummary()

        val action = LauncherReturnActionResolver.resolve(
            LauncherReturnSnapshot(
                processExitCrash = summary,
                heapPressureWarning = true
            )
        )

        assertEquals(LauncherReturnAction.ProcessExitCrash(summary), action)
    }

    @Test
    fun resolve_prefersExpectedBackExit_overHeapPressure() {
        val action = LauncherReturnActionResolver.resolve(
            LauncherReturnSnapshot(
                heapPressureWarning = true,
                expectedBackExitRecent = true
            )
        )

        assertEquals(LauncherReturnAction.ExpectedBackExit, action)
    }

    @Test
    fun resolve_returnsExpectedBackExit_whenItIsOnlyPendingSignal() {
        val action = LauncherReturnActionResolver.resolve(
            LauncherReturnSnapshot(expectedBackExitRecent = true)
        )

        assertEquals(LauncherReturnAction.ExpectedBackExit, action)
    }

    @Test
    fun resolve_returnsNone_whenNoLauncherReturnSignalExists() {
        val action = LauncherReturnActionResolver.resolve(LauncherReturnSnapshot())

        assertEquals(LauncherReturnAction.None, action)
    }

    private fun sampleProcessExitSummary(): ProcessExitSummary {
        return ProcessExitSummary(
            pid = 42,
            processName = "io.stamethyst:game",
            reason = 6,
            reasonName = "REASON_CRASH",
            status = -11,
            timestamp = 1234L,
            description = "native crash",
            isSignal = true
        )
    }
}
