package io.stamethyst.config

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherConfigGpuResourceGuardianModeTest {
    @Test
    fun gpuResourceGuardian_defaultsSafe() {
        assertEquals(
            GpuResourceGuardianMode.SAFE,
            LauncherConfig.DEFAULT_GPU_RESOURCE_GUARDIAN_MODE
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
    }

    @Test
    fun fromPersistedValue_rejectsUnknownModes() {
        assertEquals(null, GpuResourceGuardianMode.fromPersistedValue(null))
        assertEquals(null, GpuResourceGuardianMode.fromPersistedValue(""))
        assertEquals(null, GpuResourceGuardianMode.fromPersistedValue("unknown"))
    }
}
