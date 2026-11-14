package io.stamethyst.backend.launch

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UserExitProcessFallbackTest {
    @Test fun onlyExplicitExitSchedulesAndRepeatedRequestsDoNotPostponeOrDuplicateTermination() {
        val scheduled = mutableListOf<Pair<Runnable, Long>>()
        var terminations = 0
        val fallback = UserExitProcessFallback(
            postDelayed = { task, delay -> scheduled += task to delay },
            delayMs = 1500,
            terminateProcess = { ++terminations }
        )
        assertTrue(scheduled.isEmpty())
        repeat(10) { fallback.request() }
        assertEquals(1, scheduled.size)
        assertEquals(1500L, scheduled.single().second)
        assertEquals(0, terminations)
        scheduled.single().first.run()
        scheduled.single().first.run()
        assertEquals(1, terminations)
    }

    @Test fun allUserExitBranchesCommitFallbackBeforeEarlyReturnsAndDestroyCannotCancelIt() {
        val source = File("src/main/java/io/stamethyst/GameSessionCoordinator.kt").readText()
        val exit = source.substringAfter("private fun requestBackExitToLauncher()")
            .substringBefore("private fun requestJvmCloseSignal()")
        assertTrue(exit.indexOf("userExitProcessFallback.request()") >= 0)
        assertTrue(exit.indexOf("userExitProcessFallback.request()") <
            exit.indexOf("if (!jvmLaunchController.runtimeLifecycleReady)"))
        val destroy = source.substringAfter("fun onDestroy()")
            .substringBefore("fun onResume()")
        assertFalse(destroy.contains("userExitProcessFallback"))
        assertFalse(source.contains("renderView.postDelayed"))
    }

    @Test fun directDebugLaunchStopsIfOldGameProcessCannotBeTerminated() {
        val source = File("src/main/java/io/stamethyst/LauncherActivity.kt").readText()
        val direct = source.substringAfter("private fun startDirectDebugLaunchIfRequested")
        assertTrue(direct.contains("check(GameLaunchReturnTracker.terminateTrackedGameProcessAndWait(this))"))
    }
}
