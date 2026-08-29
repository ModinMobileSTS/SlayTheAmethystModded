package io.stamethyst

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class GameOrientationPolicyTest {
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
