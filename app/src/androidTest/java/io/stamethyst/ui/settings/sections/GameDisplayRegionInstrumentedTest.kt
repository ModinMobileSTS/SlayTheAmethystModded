package io.stamethyst.ui.settings.sections

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import io.stamethyst.backend.render.DisplayPixelRect
import io.stamethyst.backend.render.DisplayRegionCorner
import io.stamethyst.backend.render.GameDisplayRegion
import io.stamethyst.backend.render.VirtualResolutionMode
import io.stamethyst.config.LauncherConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameDisplayRegionInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val initial = GameDisplayRegion(.1f, .1f, .9f, .9f)
    private val geometry = DeviceDisplayGeometry(2400, 1080,
        listOf(DisplayPixelRect(0, 470, 70, 610)))

    @Test fun allFourHandlesCanDragAndKeepTheOppositeCornerFixed() {
        var result = initial
        compose.setContent {
            MaterialTheme {
                var region by remember { mutableStateOf(initial) }
                GameDisplayRegionPreview(region, geometry) { region = it; result = it }
            }
        }
        DisplayRegionCorner.entries.forEach { corner ->
            val before = result
            compose.onNodeWithTag("display_region_${corner.name.lowercase()}").performTouchInput {
                val delta = Offset(if (corner.isLeft) -16f else 16f, if (corner.isTop) -8f else 8f)
                swipe(center, center + delta)
            }
            compose.runOnIdle {
                assertNotEquals(before, result)
                assertEquals(if (corner.isLeft) before.right else before.left,
                    if (corner.isLeft) result.right else result.left, .0001f)
                assertEquals(if (corner.isTop) before.bottom else before.top,
                    if (corner.isTop) result.bottom else result.top, .0001f)
            }
        }
    }

    @Test fun draggingTheMiddleDoesNotResizeAnything() {
        var result = initial
        compose.setContent { MaterialTheme { GameDisplayRegionPreview(initial, geometry) { result = it } } }
        compose.onNodeWithTag("game_display_region_preview").performTouchInput {
            swipe(center, center + Offset(30f, 15f))
        }
        compose.runOnIdle { assertEquals(initial, result) }
    }

    @Test fun cancelDiscardsDraftWithoutSaving() {
        var saved: GameDisplayRegion? = null
        var dismissed = false
        compose.setContent {
            MaterialTheme { GameDisplayRegionDialog(initial, geometry, VirtualResolutionMode.FULLSCREEN_FILL,
                true, { dismissed = true }, { saved = it }) }
        }
        compose.onNodeWithText(context.getString(R.string.settings_game_display_reset)).performClick()
        compose.onNodeWithText(context.getString(R.string.main_folder_dialog_cancel)).performClick()
        compose.runOnIdle { assertTrue(dismissed); assertNull(saved) }
    }

    @Test fun saveCommitsTheDraftAndLegacyCroppingIsDisabled() {
        var saved: GameDisplayRegion? = null
        compose.setContent {
            MaterialTheme { GameDisplayRegionDialog(initial, geometry, VirtualResolutionMode.FULLSCREEN_FILL,
                true, {}, { saved = it }) }
        }
        compose.onNodeWithText(context.getString(R.string.settings_game_display_reset)).performClick()
        compose.onNodeWithText(context.getString(R.string.common_action_save)).performClick()
        compose.runOnIdle { assertEquals(GameDisplayRegion.FULLSCREEN, saved) }
        val preferences = context.getSharedPreferences("sts_launcher_prefs", 0)
        // Persistence is exercised without leaving changes in the user's test installation.
        val keys = listOf("game_display_region", "avoid_display_cutout", "crop_screen_bottom")
        val original = keys.associateWith { preferences.all[it] }
        try {
            LauncherConfig.saveGameDisplayRegion(context, initial)
            assertEquals(initial, LauncherConfig.readGameDisplayRegion(context))
            assertFalse(LauncherConfig.isDisplayCutoutAvoidanceEnabled(context))
            assertFalse(LauncherConfig.isScreenBottomCropEnabled(context))
        } finally {
            val editor = preferences.edit()
            original.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    else -> editor.remove(key)
                }
            }
            editor.commit()
        }
    }

    @Test fun systemSafeInsetsAvoidAnInsetPunchHoleWithoutInventingACamera() {
        val insetHole = DeviceDisplayGeometry(2400, 1080,
            listOf(DisplayPixelRect(20, 480, 80, 600)),
            cutoutSafeRegion = GameDisplayRegion(80f / 2400, 0f, 1f, 1f))
        assertEquals(80, insetHole.legacyRegion(true, false).toPixels(2400, 1080).left)
        assertTrue(DeviceDisplayGeometry(2400, 1080).cutoutBounds.isEmpty())
        assertEquals(GameDisplayRegion.FULLSCREEN, DeviceDisplayGeometry(2400, 1080).legacyRegion(true, false))
    }
}
