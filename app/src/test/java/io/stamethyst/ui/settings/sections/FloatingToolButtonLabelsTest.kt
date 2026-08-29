package io.stamethyst.ui.settings.sections

import io.stamethyst.config.LauncherConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingToolButtonLabelsTest {
    /**
     * The settings dialog looks up every supported id in this map, so a missing entry would fail
     * the requireNotNull instead of quietly hiding the toggle.
     */
    @Test
    fun everySupportedButtonIdHasALabel() {
        assertEquals(
            LauncherConfig.FLOATING_TOOL_BUTTON_IDS.toSet(),
            FLOATING_TOOL_BUTTON_LABELS.keys,
        )
    }

    @Test
    fun labelsResolveToRealResources() {
        assertTrue(FLOATING_TOOL_BUTTON_LABELS.values.all { it != 0 })
    }
}
