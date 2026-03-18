package io.stamethyst.backend.render

import io.stamethyst.config.LauncherConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class MobileGluesPresetTest {
    @Test
    fun closestTo_returnsExactPresetWhenSettingsMatch() {
        val preset = MobileGluesPreset.COMPATIBILITY_PRIORITY
        assertEquals(preset, MobileGluesPreset.closestTo(preset.settings))
    }

    @Test
    fun fromSliderIndex_clampsOutOfRangeValues() {
        assertEquals(
            MobileGluesPreset.PERFORMANCE_PRIORITY,
            MobileGluesPreset.fromSliderIndex(-10)
        )
        assertEquals(
            MobileGluesPreset.COMPATIBILITY_PRIORITY,
            MobileGluesPreset.fromSliderIndex(100)
        )
    }

    @Test
    fun launcherDefaults_matchPerformancePriorityPreset() {
        val defaultSettings = MobileGluesSettings(
            anglePolicy = LauncherConfig.DEFAULT_MOBILEGLUES_ANGLE_POLICY,
            noErrorPolicy = LauncherConfig.DEFAULT_MOBILEGLUES_NO_ERROR_POLICY,
            multidrawMode = LauncherConfig.DEFAULT_MOBILEGLUES_MULTIDRAW_MODE,
            extComputeShaderEnabled = LauncherConfig.DEFAULT_MOBILEGLUES_EXT_COMPUTE_SHADER_ENABLED,
            extTimerQueryEnabled = LauncherConfig.DEFAULT_MOBILEGLUES_EXT_TIMER_QUERY_ENABLED,
            extDirectStateAccessEnabled =
                LauncherConfig.DEFAULT_MOBILEGLUES_EXT_DIRECT_STATE_ACCESS_ENABLED,
            glslCacheSizePreset = LauncherConfig.DEFAULT_MOBILEGLUES_GLSL_CACHE_SIZE_PRESET,
            angleDepthClearFixMode = LauncherConfig.DEFAULT_MOBILEGLUES_ANGLE_DEPTH_CLEAR_FIX_MODE,
            customGlVersion = LauncherConfig.DEFAULT_MOBILEGLUES_CUSTOM_GL_VERSION,
            fsr1QualityPreset = LauncherConfig.DEFAULT_MOBILEGLUES_FSR1_QUALITY_PRESET,
        )

        assertEquals(MobileGluesPreset.PERFORMANCE_PRIORITY.settings, defaultSettings)
    }
}
