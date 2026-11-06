package io.stamethyst

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameOrientationPolicyTest {
    @Test
    fun godotBootStaysPortraitUntilEntryThenReturnsToLandscape() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            GameOrientationPolicy.resolveRequestedOrientation(false, preparingGodotBoot = true)
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            GameOrientationPolicy.resolveRequestedOrientation(false, preparingGodotBoot = false)
        )
    }

    @Test
    fun godotBootStillDefersToMultiWindowHost() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            GameOrientationPolicy.resolveRequestedOrientation(true, preparingGodotBoot = true)
        )
    }

    @Test
    fun portraitGodotBootDoesNotWaitForUnwantedLandscapeRotation() {
        assertFalse(GameOrientationPolicy.shouldWaitForLandscape(1080, 2400, false, true))
        assertTrue(GameOrientationPolicy.shouldWaitForLandscape(1080, 2400, false, false))
        assertFalse(GameOrientationPolicy.shouldWaitForLandscape(1080, 2400, true, false))
        assertFalse(GameOrientationPolicy.shouldWaitForLandscape(2400, 1080, false, false))
    }

    @Test
    fun resolveRequestedOrientation_keepsLandscapeOutsideMultiWindow() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            GameOrientationPolicy.resolveRequestedOrientation(isInMultiWindowMode = false)
        )
    }

    @Test
    fun resolveRequestedOrientation_defersToFreeformHostInMultiWindow() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            GameOrientationPolicy.resolveRequestedOrientation(isInMultiWindowMode = true)
        )
    }
}
