package io.stamethyst.ui.workshop

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Offline compilation is supported; pixel assertions require separately authorized device execution. */
class WorkshopDetailArtworkInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private fun verifyMask(surface: Color) {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.RED)
        }
        compose.setContent {
            Box(Modifier.size(240.dp).background(surface).testTag("artwork")) {
                WorkshopDetailCornerArtwork(bitmap)
            }
        }
        val pixels = compose.onNodeWithTag("artwork").captureToImage().toPixelMap()
        fun pixel(x: Float, y: Float) = pixels[(pixels.width * x).toInt(), (pixels.height * y).toInt()]
        val corner = pixel(0.96f, 0.04f)
        assertEquals(1f, corner.red, 0.03f)
        assertEquals(0f, corner.green, 0.03f)
        val middle = pixel(0.67f, 0.33f)
        // At an intermediate radius the image is partially transparent on either theme.
        val middleOpacity = if (surface == Color.White) 1f - middle.green else middle.red
        assertTrue(middleOpacity in 0.2f..0.65f)
        // The lower-left diagonal disappears earlier, not at a hard rectangular image boundary.
        listOf(pixel(0.40f, 0.60f), pixel(0.04f, 0.96f), pixel(0.96f, 0.96f)).forEach {
            assertEquals(surface.red, it.red, 0.03f)
            assertEquals(surface.green, it.green, 0.03f)
            assertEquals(surface.blue, it.blue, 0.03f)
        }
    }

    @Test fun cornerIsOpaqueAndLowerLeftFadesEarlyOnLightSurface() = verifyMask(Color.White)

    @Test fun transparentPixelsRevealDarkSurfaceWithoutLightHalo() = verifyMask(Color.Black)

    @Test fun detailCardsNoLongerRequireAnArtworkBackdropAndKeepTheirActions() {
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                WorkshopDetailCard(onClick = { clicks++ }) {
                    Text("前置模组")
                }
            }
        }
        compose.onNodeWithText("前置模组").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
