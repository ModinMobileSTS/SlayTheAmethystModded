package io.stamethyst.macrobenchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModFolderExpandBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun expandCollapseFolder() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.WARM,
        iterations = 1,
        setupBlock = {
            startLauncherActivity()
            Thread.sleep(STARTUP_SETTLE_MS)
        }
    ) {
        repeat(6) {
            tapFirstFolderToggle()
            Thread.sleep(COLLAPSE_ANIMATION_SETTLE_MS)
        }
    }

    private fun MacrobenchmarkScope.startLauncherActivity() {
        val intent = Intent().apply {
            setClassName(TARGET_PACKAGE, "$TARGET_PACKAGE.ui.main.ModFolderBenchmarkActivity")
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivityAndWait(intent)
    }

    private fun MacrobenchmarkScope.tapFirstFolderToggle() {
        val x = (device.displayWidth * 0.90f).toInt()
        val y = (device.displayHeight * 0.16f).toInt()
        device.click(x, y)
    }

    private companion object {
        private const val TARGET_PACKAGE = "io.stamethyst"
        private const val STARTUP_SETTLE_MS = 2_000L
        private const val COLLAPSE_ANIMATION_SETTLE_MS = 380L
    }
}
