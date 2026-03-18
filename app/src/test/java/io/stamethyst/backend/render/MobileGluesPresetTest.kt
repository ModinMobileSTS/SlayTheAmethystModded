package io.stamethyst.backend.render

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
}
