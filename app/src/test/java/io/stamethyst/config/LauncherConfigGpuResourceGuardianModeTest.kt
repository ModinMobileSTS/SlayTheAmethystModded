package io.stamethyst.config

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherConfigGpuResourceGuardianModeTest {
    @Test
    fun gpuResourceGuardian_highMemoryDefaultSafe() {
        assertEquals(
            GpuResourceGuardianMode.SAFE,
            LauncherConfig.resolveDefaultGpuResourceGuardianMode(13L * 1024L * 1024L * 1024L)
        )
    }

    @Test
    fun gpuResourceGuardian_lowMemoryDefaultAggressive() {
        assertEquals(
            GpuResourceGuardianMode.AGGRESSIVE,
            LauncherConfig.resolveDefaultGpuResourceGuardianMode(12L * 1024L * 1024L * 1024L)
        )
    }

    @Test
    fun gpuResourceGuardian_unknownMemoryDefaultSafe() {
        assertEquals(
            GpuResourceGuardianMode.SAFE,
            LauncherConfig.resolveDefaultGpuResourceGuardianMode(-1L)
        )
    }

    @Test
    fun fromPersistedValue_acceptsKnownModes() {
        assertEquals(GpuResourceGuardianMode.OFF, GpuResourceGuardianMode.fromPersistedValue("off"))
        assertEquals(GpuResourceGuardianMode.SAFE, GpuResourceGuardianMode.fromPersistedValue("safe"))
        assertEquals(
            GpuResourceGuardianMode.AGGRESSIVE,
            GpuResourceGuardianMode.fromPersistedValue("aggressive")
        )
        assertEquals(
            GpuResourceGuardianMode.DIAGNOSTIC,
            GpuResourceGuardianMode.fromPersistedValue("diagnostic")
        )
        assertEquals(GpuResourceGuardianMode.LEGACY, GpuResourceGuardianMode.fromPersistedValue("legacy"))
    }

    @Test
    fun runtimePropertyValue_mapsLegacyToOff() {
        assertEquals("off", GpuResourceGuardianMode.LEGACY.runtimePropertyValue)
        assertEquals("safe", GpuResourceGuardianMode.SAFE.runtimePropertyValue)
    }

    @Test
    fun fromPersistedValue_rejectsUnknownModes() {
        assertEquals(null, GpuResourceGuardianMode.fromPersistedValue(null))
        assertEquals(null, GpuResourceGuardianMode.fromPersistedValue(""))
        assertEquals(null, GpuResourceGuardianMode.fromPersistedValue("unknown"))
    }
}
