package io.stamethyst.config

import org.junit.Assert.assertFalse
import org.junit.Test

class LauncherConfigTextureResidencyDefaultsTest {
    @Test
    fun textureResidencyManager_defaultsDisabled() {
        assertFalse(LauncherConfig.DEFAULT_TEXTURE_RESIDENCY_MANAGER_COMPAT_ENABLED)
    }

    @Test
    fun largeTextureDownscale_defaultsDisabled() {
        assertFalse(LauncherConfig.DEFAULT_LARGE_TEXTURE_DOWNSCALE_COMPAT_ENABLED)
    }
}
