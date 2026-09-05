package io.stamethyst.backend.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DisplayRefreshRateControllerTest {
    @Test
    fun resolveAutomaticTargetFps_usesTheHighestStableRateBelow144() {
        assertEquals(60f, DisplayRefreshRateController.resolveAutomaticTargetFps(60f), 0.001f)
        assertEquals(90f, DisplayRefreshRateController.resolveAutomaticTargetFps(90f), 0.001f)
        assertEquals(120f, DisplayRefreshRateController.resolveAutomaticTargetFps(120f), 0.001f)
        assertEquals(119.88f, DisplayRefreshRateController.resolveAutomaticTargetFps(119.88f), 0.001f)
        assertEquals(144f, DisplayRefreshRateController.resolveAutomaticTargetFps(144f), 0.001f)
        assertEquals(82.5f, DisplayRefreshRateController.resolveAutomaticTargetFps(165f), 0.001f)
        assertEquals(120f, DisplayRefreshRateController.resolveAutomaticTargetFps(240f), 0.001f)
    }

    @Test
    fun resolveAutomaticTargetFps_fallsBackTo60WhenRefreshRateIsUnknown() {
        assertEquals(60f, DisplayRefreshRateController.resolveAutomaticTargetFps(0f), 0.001f)
        assertEquals(60f, DisplayRefreshRateController.resolveAutomaticTargetFps(Float.NaN), 0.001f)
    }

    @Test
    fun resolveIdealTargetFpsOptions_listsEverySelectableRefreshDivisor() {
        assertEquals(
            listOf(165f, 82.5f, 55f, 41.25f, 33f, 27.5f),
            DisplayRefreshRateController.resolveIdealTargetFpsOptions(165f)
        )
        val options120Hz = DisplayRefreshRateController.resolveIdealTargetFpsOptions(120f)
        assertEquals(
            listOf(120f, 60f, 40f, 30f, 24f),
            options120Hz
        )
        assertFalse(options120Hz.contains(90f))
    }

    @Test
    fun resolveWindowRefreshPreference_keepsCurrentHighRefreshModeForFractionalAutomaticDivisor() {
        val preference = DisplayRefreshRateController.resolveWindowRefreshPreference(
            targetFpsLimit = 82.5f,
            currentDisplayModeId = 2,
            supportedModes = listOf(
                mode(modeId = 1, width = 2400, height = 1080, refreshRateHz = 90f),
                mode(modeId = 2, width = 2400, height = 1080, refreshRateHz = 165f)
            )
        )

        assertEquals(
            WindowRefreshPreference(
                preferredRefreshRateHz = 165f,
                preferredDisplayModeId = null
            ),
            preference
        )
    }

    @Test
    fun resolveWindowRefreshPreference_requests60HzWhenTargetIs60Fps() {
        val preference = DisplayRefreshRateController.resolveWindowRefreshPreference(
            targetFpsLimit = 60f,
            currentDisplayModeId = 1,
            supportedModes = listOf(
                mode(modeId = 1, width = 2400, height = 1080, refreshRateHz = 60f),
                mode(modeId = 2, width = 2400, height = 1080, refreshRateHz = 120f)
            )
        )

        assertEquals(
            WindowRefreshPreference(
                preferredRefreshRateHz = 60f,
                preferredDisplayModeId = null
            ),
            preference
        )
    }

    @Test
    fun resolveWindowRefreshPreference_mapsSub60TargetsTo60Hz() {
        val preference = DisplayRefreshRateController.resolveWindowRefreshPreference(
            targetFpsLimit = 30f,
            currentDisplayModeId = 1,
            supportedModes = listOf(
                mode(modeId = 1, width = 2400, height = 1080, refreshRateHz = 120f),
                mode(modeId = 2, width = 2400, height = 1080, refreshRateHz = 60f)
            )
        )

        assertEquals(
            WindowRefreshPreference(
                preferredRefreshRateHz = 60f,
                preferredDisplayModeId = 2
            ),
            preference
        )
    }

    @Test
    fun resolveWindowRefreshPreference_canSwitchDownTo60HzSameSizeMode() {
        val preference = DisplayRefreshRateController.resolveWindowRefreshPreference(
            targetFpsLimit = 60f,
            currentDisplayModeId = 2,
            supportedModes = listOf(
                mode(modeId = 1, width = 2400, height = 1080, refreshRateHz = 60f),
                mode(modeId = 2, width = 2400, height = 1080, refreshRateHz = 120f)
            )
        )

        assertEquals(
            WindowRefreshPreference(
                preferredRefreshRateHz = 60f,
                preferredDisplayModeId = 1
            ),
            preference
        )
    }

    @Test
    fun resolveWindowRefreshPreference_prefersSameSizeHighRefreshModeId() {
        val preference = DisplayRefreshRateController.resolveWindowRefreshPreference(
            targetFpsLimit = 120f,
            currentDisplayModeId = 1,
            supportedModes = listOf(
                mode(modeId = 1, width = 2400, height = 1080, refreshRateHz = 60f),
                mode(modeId = 2, width = 2400, height = 1080, refreshRateHz = 120f),
                mode(modeId = 3, width = 1920, height = 864, refreshRateHz = 144f)
            )
        )

        assertEquals(
            WindowRefreshPreference(
                preferredRefreshRateHz = 120f,
                preferredDisplayModeId = 2
            ),
            preference
        )
    }

    @Test
    fun resolveWindowRefreshPreference_prefersNative90HzMode() {
        val preference = DisplayRefreshRateController.resolveWindowRefreshPreference(
            targetFpsLimit = 90f,
            currentDisplayModeId = 1,
            supportedModes = listOf(
                mode(modeId = 1, width = 2400, height = 1080, refreshRateHz = 60f),
                mode(modeId = 2, width = 2400, height = 1080, refreshRateHz = 90f),
                mode(modeId = 3, width = 2400, height = 1080, refreshRateHz = 120f)
            )
        )

        assertEquals(
            WindowRefreshPreference(
                preferredRefreshRateHz = 90f,
                preferredDisplayModeId = 2
            ),
            preference
        )
    }

    @Test
    fun resolveWindowRefreshPreference_canSwitchDownToHighRefreshTargetMode() {
        val preference = DisplayRefreshRateController.resolveWindowRefreshPreference(
            targetFpsLimit = 120f,
            currentDisplayModeId = 2,
            supportedModes = listOf(
                mode(modeId = 1, width = 2400, height = 1080, refreshRateHz = 120f),
                mode(modeId = 2, width = 2400, height = 1080, refreshRateHz = 240f)
            )
        )

        assertEquals(
            WindowRefreshPreference(
                preferredRefreshRateHz = 120f,
                preferredDisplayModeId = 1
            ),
            preference
        )
    }

    @Test
    fun resolveWindowRefreshPreference_fallsBackToTargetRefreshWhenDisplayModesLookStuckAt60() {
        val preference = DisplayRefreshRateController.resolveWindowRefreshPreference(
            targetFpsLimit = 120f,
            currentDisplayModeId = 1,
            supportedModes = listOf(
                mode(modeId = 1, width = 2400, height = 1080, refreshRateHz = 60f)
            )
        )

        assertEquals(
            WindowRefreshPreference(
                preferredRefreshRateHz = 120f,
                preferredDisplayModeId = null
            ),
            preference
        )
    }

    @Test
    fun resolveWindowRefreshPreference_usesGlobalHighRefreshHintWhenOnlyOtherSizesExposeIt() {
        val preference = DisplayRefreshRateController.resolveWindowRefreshPreference(
            targetFpsLimit = 120f,
            currentDisplayModeId = 1,
            supportedModes = listOf(
                mode(modeId = 1, width = 2400, height = 1080, refreshRateHz = 60f),
                mode(modeId = 2, width = 1920, height = 864, refreshRateHz = 120f)
            )
        )

        assertEquals(
            WindowRefreshPreference(
                preferredRefreshRateHz = 120f,
                preferredDisplayModeId = null
            ),
            preference
        )
    }

    @Test
    fun resolveExpectedRefreshRateHz_reportsHighRefreshModeThePanelAdvertises() {
        val refreshRate = DisplayRefreshRateController.resolveExpectedRefreshRateHz(
            targetFpsLimit = 90f,
            currentDisplayRefreshRateHz = 60f,
            currentDisplayModeId = 1,
            supportedModes = listOf(
                mode(modeId = 1, width = 2400, height = 1080, refreshRateHz = 60f),
                mode(modeId = 2, width = 2400, height = 1080, refreshRateHz = 90f)
            )
        )

        assertEquals(90f, refreshRate, 0.001f)
    }

    @Test
    fun resolveExpectedRefreshRateHz_neverReportsRateThePanelCannotDo() {
        val refreshRate = DisplayRefreshRateController.resolveExpectedRefreshRateHz(
            targetFpsLimit = 90f,
            currentDisplayRefreshRateHz = 60f,
            currentDisplayModeId = 1,
            supportedModes = listOf(
                mode(modeId = 1, width = 2400, height = 1080, refreshRateHz = 60f)
            )
        )

        assertEquals(60f, refreshRate, 0.001f)
    }

    @Test
    fun resolveExpectedRefreshRateHz_fallsBackToCurrentRateWhenModesAreUnknown() {
        val refreshRate = DisplayRefreshRateController.resolveExpectedRefreshRateHz(
            targetFpsLimit = 90f,
            currentDisplayRefreshRateHz = 60f,
            currentDisplayModeId = null,
            supportedModes = emptyList()
        )

        assertEquals(60f, refreshRate, 0.001f)
    }

    @Test
    fun resolveExpectedRefreshRateHz_returnsZeroWhenNothingIsKnown() {
        val refreshRate = DisplayRefreshRateController.resolveExpectedRefreshRateHz(
            targetFpsLimit = 90f,
            currentDisplayRefreshRateHz = 0f,
            currentDisplayModeId = null,
            supportedModes = emptyList()
        )

        assertEquals(0f, refreshRate, 0.001f)
    }

    @Test
    fun resolveExpectedRefreshRateHz_reportsUncappedTargetAsCurrentRate() {
        val refreshRate = DisplayRefreshRateController.resolveExpectedRefreshRateHz(
            targetFpsLimit = 0f,
            currentDisplayRefreshRateHz = 120f,
            currentDisplayModeId = 1,
            supportedModes = listOf(
                mode(modeId = 1, width = 2400, height = 1080, refreshRateHz = 120f)
            )
        )

        assertEquals(120f, refreshRate, 0.001f)
    }

    private fun mode(
        modeId: Int,
        width: Int,
        height: Int,
        refreshRateHz: Float
    ) = DisplayModeCandidate(
        modeId = modeId,
        width = width,
        height = height,
        refreshRateHz = refreshRateHz
    )
}
