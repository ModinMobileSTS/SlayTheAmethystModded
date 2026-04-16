package io.stamethyst.config

import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherConfigTextureResidencyDefaultsTest {
    @Test
    fun textureResidencyManager_defaultsEnabled() {
        assertTrue(LauncherConfig.DEFAULT_TEXTURE_RESIDENCY_MANAGER_COMPAT_ENABLED)
    }
}
