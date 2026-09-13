package io.stamethyst.backend.launch

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MtsWarmupPlacementTest {
    @Test
    fun prepProcessPreparationDoesNotRunMtsClasspathWarmup() {
        val source = readSource(
            "app/src/main/java/io/stamethyst/backend/launch/LaunchPreparationService.kt"
        )

        assertFalse(source.contains("MtsClasspathWarmupCoordinator"))
        assertFalse(source.contains("prepareMtsClasspath("))
    }

    @Test
    fun launcherFlowRunsLaunchPreparationBeforeStartingGameActivityProcess() {
        val source = readSource(
            "app/src/main/java/io/stamethyst/ui/main/MainScreenViewModel.kt"
        )
        val preparationIndex =
            source.indexOf("MainProcessLaunchPreparationCoordinator.prepareBeforeLaunch")
        val launchIndex = source.indexOf("            StsGameActivity.launch(")

        assertTrue(preparationIndex >= 0)
        assertTrue(launchIndex >= 0)
        assertTrue(preparationIndex < launchIndex)
    }

    @Test
    fun jvmLaunchControllerDoesNotUsePrepProcessClient() {
        val source = readSource(
            "app/src/main/java/io/stamethyst/backend/launch/JvmLaunchController.kt"
        )

        assertFalse(source.contains("LaunchPreparationProcessClient"))
    }

    @Test
    fun failedGameActivityLaunchClearsLauncherBackgroundFlag() {
        val source = readSource(
            "app/src/main/java/io/stamethyst/ui/main/MainScreenViewModel.kt"
        )

        assertTrue(source.contains("markBackgroundForGameLaunch()"))
        assertTrue(source.contains("clearBackgroundForGameLaunch()"))
    }

    @Test
    fun expectedExitWatchdogStartsAfterRuntimeReadyInsteadOfAtLaunchBegin() {
        val source = readSource(
            "app/src/main/java/io/stamethyst/GameSessionCoordinator.kt"
        )

        val launchBeginSnippet = "jvmLaunchStartedWallTimeMs = System.currentTimeMillis()\n        startExpectedGameExitReturnWatchdog()"

        val runtimeReadyIndex = source.indexOf("onRuntimeReady = {")
        val callbackIndex = source.indexOf("onRuntimeReady()", runtimeReadyIndex)
        val watchdogIndex = source.indexOf("startExpectedGameExitReturnWatchdog()", callbackIndex)

        assertTrue(runtimeReadyIndex >= 0)
        assertTrue(callbackIndex >= runtimeReadyIndex)
        assertTrue(watchdogIndex >= callbackIndex)
        assertFalse(source.contains(launchBeginSnippet))
    }

    private fun readSource(path: String): String {
        val file = listOf(File(path), File("..", path))
            .firstOrNull(File::isFile)
            ?: File(path)
        assertTrue("Missing source file: ${file.absolutePath}", file.isFile)
        return file.readText()
    }
}
