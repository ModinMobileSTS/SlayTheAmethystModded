package io.stamethyst.backend.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayPerformanceControllerTest {
    @Test
    fun choosePreferredMode_returnsExactMatch_whenAvailable() {
        val currentMode = mode(modeId = 1, refreshRate = 60f)
        val preferred = DisplayPerformanceController.choosePreferredMode(
            modes = listOf(
                currentMode,
                mode(modeId = 2, refreshRate = 120f),
                mode(modeId = 3, refreshRate = 144f)
            ),
            currentMode = currentMode,
            targetFps = 120
        )

        assertEquals(2, preferred?.modeId)
        assertEquals(120f, preferred?.refreshRate)
    }

    @Test
    fun choosePreferredMode_keepsCurrentMode_whenNoExactMatchExists() {
        val currentMode = mode(modeId = 1, refreshRate = 60f)
        val preferred = DisplayPerformanceController.choosePreferredMode(
            modes = listOf(
                currentMode,
                mode(modeId = 2, refreshRate = 90f),
                mode(modeId = 3, refreshRate = 144f)
            ),
            currentMode = currentMode,
            targetFps = 120
        )

        assertSame(currentMode, preferred)
    }

    @Test
    fun choosePreferredMode_doesNotPromoteToHigherMode_whenTargetExceedsCurrent() {
        val currentMode = mode(modeId = 2, refreshRate = 120f)
        val preferred = DisplayPerformanceController.choosePreferredMode(
            modes = listOf(
                mode(modeId = 1, refreshRate = 60f),
                currentMode,
                mode(modeId = 3, refreshRate = 144f)
            ),
            currentMode = currentMode,
            targetFps = 240
        )

        assertSame(currentMode, preferred)
    }

    @Test
    fun choosePreferredMode_returnsNull_whenNoModesAndNoCurrentMode() {
        val preferred = DisplayPerformanceController.choosePreferredMode(
            modes = emptyList(),
            currentMode = null,
            targetFps = 120
        )

        assertNull(preferred)
    }

    private fun mode(
        modeId: Int,
        refreshRate: Float,
        physicalWidth: Int = 2400,
        physicalHeight: Int = 1080
    ) = DisplayPerformanceController.DisplayModeSpec(
        refreshRate = refreshRate,
        modeId = modeId,
        physicalWidth = physicalWidth,
        physicalHeight = physicalHeight
    )
}
