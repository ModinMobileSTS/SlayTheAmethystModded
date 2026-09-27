package io.stamethyst.config

import io.stamethyst.backend.launch.StsLaunchSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherConfigJvmTieredStopAtLevelTest {
    @Test
    fun default_enablesC2Compilation() {
        assertEquals(4, LauncherConfig.DEFAULT_JVM_TIERED_STOP_AT_LEVEL)
        assertEquals(
            "-XX:TieredStopAtLevel=4",
            StsLaunchSpec.tieredStopAtLevelArg(LauncherConfig.DEFAULT_JVM_TIERED_STOP_AT_LEVEL)
        )
    }

    @Test
    fun allSelectableLevels_produceTheMatchingJvmFlag() {
        (1..4).forEach { level ->
            assertEquals(level, LauncherConfig.normalizeJvmTieredStopAtLevel(level))
            assertEquals("-XX:TieredStopAtLevel=$level", StsLaunchSpec.tieredStopAtLevelArg(level))
        }
    }

    @Test
    fun outOfRangeLevels_areClampedBeforeLaunching() {
        assertEquals(1, LauncherConfig.normalizeJvmTieredStopAtLevel(-1))
        assertEquals(4, LauncherConfig.normalizeJvmTieredStopAtLevel(5))
        assertEquals("-XX:TieredStopAtLevel=1", StsLaunchSpec.tieredStopAtLevelArg(-1))
        assertEquals("-XX:TieredStopAtLevel=4", StsLaunchSpec.tieredStopAtLevelArg(5))
    }
}
