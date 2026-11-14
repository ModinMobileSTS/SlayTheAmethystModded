package io.stamethyst.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

val LocalChromeBackgroundOpacity = compositionLocalOf { 0f }
val LocalLauncherBlurDisabled = compositionLocalOf { false }

@Composable
@OptIn(ExperimentalHazeApi::class, ExperimentalHazeMaterialsApi::class)
fun FrostedGlassChrome(
    modifier: Modifier = Modifier,
    hazeState: HazeState,
    shape: Shape,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    shadowElevation: Dp = 0.dp,
    showBorder: Boolean = true,
    blurRadius: Dp = 12.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val opaqueBackgroundAlpha = LocalChromeBackgroundOpacity.current.coerceIn(0f, 1f)
    val launcherBlurEnabled = !LocalLauncherBlurDisabled.current
    val opaqueBackgroundColor =
        MaterialTheme.colorScheme.surface.copy(alpha = opaqueBackgroundAlpha)
    val materialStyle = HazeMaterials.ultraThin()
    Surface(
        modifier = modifier
            // dropShadow draws the shadow without introducing a graphics layer, so it stays
            // outside the clip/haze chain and renders identically on Android 10 and newer.
            .then(
                if (shadowElevation > 0.dp) {
                    Modifier.dropShadow(shape = shape) {
                        radius = shadowElevation.toPx()
                        color = Color.Black.copy(alpha = 0.26f)
                        offset = Offset(0f, shadowElevation.toPx() * 0.5f)
                    }
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .hazeEffect(
                state = hazeState,
                style = materialStyle,
            ) {
                blurEnabled = launcherBlurEnabled && hazeState.blurEnabled
                this.blurRadius = blurRadius
                inputScale = HazeInputScale.Auto
                // Keep the grain texture from being enlarged by input downsampling.
                noiseFactor = 0f
                blurredEdgeTreatment = BlurredEdgeTreatment(shape)
            },
        shape = shape,
        color = Color.Transparent,
        border = if (showBorder) {
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f),
            )
        } else {
            null
        },
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(opaqueBackgroundColor)
                .padding(contentPadding),
        ) {
            content()
        }
    }
}
