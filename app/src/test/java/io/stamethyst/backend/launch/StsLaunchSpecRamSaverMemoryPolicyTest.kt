package io.stamethyst.backend.launch

import io.stamethyst.config.GpuResourceGuardianMode
import io.stamethyst.config.LauncherConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StsLaunchSpecRamSaverMemoryPolicyTest {
    @Test
    fun resolveTexturePressureDownscaleEnabled_disablesWhenRamSaverEnabled() {
        assertFalse(
            StsLaunchSpec.resolveTexturePressureDownscaleEnabled(
                ramSaverEnabled = true,
                configuredEnabled = true
            )
        )
    }

    @Test
    fun resolveTexturePressureDownscaleEnabled_preservesConfiguredValueWithoutRamSaver() {
        assertTrue(
            StsLaunchSpec.resolveTexturePressureDownscaleEnabled(
                ramSaverEnabled = false,
                configuredEnabled = true
            )
        )
        assertFalse(
            StsLaunchSpec.resolveTexturePressureDownscaleEnabled(
                ramSaverEnabled = false,
                configuredEnabled = false
            )
        )
    }

    @Test
    fun resolveDisableExplicitGcEnabled_allowsExplicitGcWhenRamSaverEnabled() {
        // Ram Saver's AggressiveGC patch needs System.gc() to clear weak references so the
        // ReferenceQueue drain in RamSaver.update can dispose the backing native textures.
        assertFalse(
            StsLaunchSpec.resolveDisableExplicitGcEnabled(ramSaverEnabled = true)
        )
    }

    @Test
    fun resolveDisableExplicitGcEnabled_suppressesExplicitGcWithoutRamSaver() {
        assertTrue(
            StsLaunchSpec.resolveDisableExplicitGcEnabled(ramSaverEnabled = false)
        )
    }

    @Test
    fun resolveExplicitGcInvokesConcurrentEnabled_redirectsReachableExplicitGcToAConcurrentCycle() {
        // With Ram Saver active, System.gc() is reachable. Left at its default it is a full
        // stop-the-world pause on the render thread, which is a visible frame hitch. The concurrent
        // cycle still clears and enqueues the weak references RamSaver.update drains.
        assertTrue(
            StsLaunchSpec.resolveExplicitGcInvokesConcurrentEnabled(disableExplicitGc = false)
        )
    }

    @Test
    fun resolveExplicitGcInvokesConcurrentEnabled_skippedWhenExplicitGcIsAlreadySuppressed() {
        // -XX:+DisableExplicitGC already turned those calls into no-ops, so the flag would be inert.
        assertFalse(
            StsLaunchSpec.resolveExplicitGcInvokesConcurrentEnabled(disableExplicitGc = true)
        )
    }

    @Test
    fun explicitGcPolicy_neverSuppressesAndRedirectsAtTheSameTime() {
        // The two flags are complementary: exactly one of them applies for a given Ram Saver state.
        for (ramSaverEnabled in listOf(true, false)) {
            val disable = StsLaunchSpec.resolveDisableExplicitGcEnabled(ramSaverEnabled)
            val concurrent =
                StsLaunchSpec.resolveExplicitGcInvokesConcurrentEnabled(disableExplicitGc = disable)
            assertFalse(
                "ramSaverEnabled=$ramSaverEnabled applied both explicit-GC flags",
                disable && concurrent
            )
        }
    }

    @Test
    fun resolveGpuResourceGuardianModeForLaunch_disablesWhenRamSaverEnabled() {
        assertEquals(
            GpuResourceGuardianMode.OFF,
            StsLaunchSpec.resolveGpuResourceGuardianModeForLaunch(
                ramSaverEnabled = true,
                configuredMode = GpuResourceGuardianMode.ULTRA_AGGRESSIVE
            )
        )
    }

    @Test
    fun resolveGpuResourceGuardianModeForLaunch_preservesConfiguredModeWithoutRamSaver() {
        assertEquals(
            GpuResourceGuardianMode.AGGRESSIVE,
            StsLaunchSpec.resolveGpuResourceGuardianModeForLaunch(
                ramSaverEnabled = false,
                configuredMode = GpuResourceGuardianMode.AGGRESSIVE
            )
        )
    }

    @Test
    fun resolveFboPressureDownscaleEnabled_disablesWhenRamSaverEnabled() {
        assertFalse(
            StsLaunchSpec.resolveFboPressureDownscaleEnabled(
                ramSaverEnabled = true,
                configuredEnabled = true,
                offscreenFrameBuffersEnabled = true
            )
        )
    }

    @Test
    fun resolveFboPressureDownscaleEnabled_requiresConfigAndMaterialPolicyWithoutRamSaver() {
        assertTrue(
            StsLaunchSpec.resolveFboPressureDownscaleEnabled(
                ramSaverEnabled = false,
                configuredEnabled = true,
                offscreenFrameBuffersEnabled = true
            )
        )
        assertFalse(
            StsLaunchSpec.resolveFboPressureDownscaleEnabled(
                ramSaverEnabled = false,
                configuredEnabled = false,
                offscreenFrameBuffersEnabled = true
            )
        )
        assertFalse(
            StsLaunchSpec.resolveFboPressureDownscaleEnabled(
                ramSaverEnabled = false,
                configuredEnabled = true,
                offscreenFrameBuffersEnabled = false
            )
        )
    }

    @Test
    fun appendDebugJvmPropertiesForLaunch_doesNotOverrideManagedCompatibilityProperty() {
        val args = mutableListOf("-Damethyst.gdx.gpu_guardian_soft_budget_bytes=1024")

        val result = StsLaunchSpec.appendDebugJvmPropertiesForLaunch(
            args,
            mapOf("amethyst.gdx.gpu_guardian_soft_budget_bytes" to "2048")
        )

        assertEquals(
            listOf("amethyst.gdx.gpu_guardian_soft_budget_bytes"),
            result.skippedManagedKeys
        )
        assertTrue(result.appendedKeys.isEmpty())
        assertEquals(listOf("-Damethyst.gdx.gpu_guardian_soft_budget_bytes=1024"), args)
    }

    @Test
    fun appendDebugJvmPropertiesForLaunch_keepsUnmanagedDebugProperty() {
        val args = mutableListOf("-Damethyst.gdx.gpu_guardian_soft_budget_bytes=1024")

        val result = StsLaunchSpec.appendDebugJvmPropertiesForLaunch(
            args,
            mapOf("amethyst.gdx.debug_leak_interval_frames" to "120")
        )

        assertEquals(listOf("amethyst.gdx.debug_leak_interval_frames"), result.appendedKeys)
        assertTrue(result.skippedManagedKeys.isEmpty())
        assertTrue(args.contains("-Damethyst.gdx.debug_leak_interval_frames=120"))
    }

    @Test
    fun shouldEnableGameProbe_disablesForNormalMtsLaunch() {
        assertFalse(
            StsLaunchSpec.shouldEnableGameProbe(
                launchMode = StsLaunchSpec.LAUNCH_MODE_MTS,
                autoplay = false,
                forceJvmCrash = false,
                forceRuntimeCrash = false,
                performanceDeepDiagnostics = false
            )
        )
    }

    @Test
    fun shouldEnableGameProbe_keepsDebugAndAutomationMtsLaunchesEnabled() {
        assertTrue(
            StsLaunchSpec.shouldEnableGameProbe(
                launchMode = StsLaunchSpec.LAUNCH_MODE_MTS,
                autoplay = true,
                forceJvmCrash = false,
                forceRuntimeCrash = false,
                performanceDeepDiagnostics = false
            )
        )
        assertTrue(
            StsLaunchSpec.shouldEnableGameProbe(
                launchMode = StsLaunchSpec.LAUNCH_MODE_MTS,
                autoplay = false,
                forceJvmCrash = true,
                forceRuntimeCrash = false,
                performanceDeepDiagnostics = false
            )
        )
        assertTrue(
            StsLaunchSpec.shouldEnableGameProbe(
                launchMode = StsLaunchSpec.LAUNCH_MODE_MTS,
                autoplay = false,
                forceJvmCrash = false,
                forceRuntimeCrash = true,
                performanceDeepDiagnostics = false
            )
        )
        assertTrue(
            StsLaunchSpec.shouldEnableGameProbe(
                launchMode = StsLaunchSpec.LAUNCH_MODE_MTS,
                autoplay = false,
                forceJvmCrash = false,
                forceRuntimeCrash = false,
                performanceDeepDiagnostics = true
            )
        )
    }

    @Test
    fun shouldEnableGameProbe_disablesForVanillaEvenWhenDebugFlagsAreSet() {
        assertFalse(
            StsLaunchSpec.shouldEnableGameProbe(
                launchMode = StsLaunchSpec.LAUNCH_MODE_VANILLA,
                autoplay = true,
                forceJvmCrash = true,
                forceRuntimeCrash = true,
                performanceDeepDiagnostics = true
            )
        )
    }

    @Test
    fun resolveGamePerformanceDeepDiagnosticsEnabled_ignoresOverlayState() {
        assertFalse(
            LauncherConfig.resolveGamePerformanceDeepDiagnosticsEnabled(
                gpuResourceDiagEnabled = false
            )
        )
        assertTrue(
            LauncherConfig.resolveGamePerformanceDeepDiagnosticsEnabled(
                gpuResourceDiagEnabled = true
            )
        )
    }
}
