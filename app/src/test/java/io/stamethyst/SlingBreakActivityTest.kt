package io.stamethyst

import org.junit.Assert.assertEquals
import org.junit.Test

class SlingBreakActivityTest {
    @Test
    fun slingBreakGameUrlKeepsNativeModeByDefault() {
        assertEquals(
            "file:///android_asset/slingbreak/index.html?launcher=1",
            slingBreakGameUrl(
                audioDebugEnabled = false,
                launcherMode = true
            )
        )
    }

    @Test
    fun slingBreakGameUrlCanForceCanvasCompatibilityMode() {
        assertEquals(
            "file:///android_asset/slingbreak/index.html?launcher=1&forceCompat=1",
            slingBreakGameUrl(
                audioDebugEnabled = false,
                launcherMode = true,
                forceCompat = true
            )
        )
    }
}
