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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

val LocalChromeBackgroundOpacity = compositionLocalOf { 0f }

@Composable
@OptIn(ExperimentalHazeMaterialsApi::class)
fun FrostedGlassChrome(
    modifier: Modifier = Modifier,
    hazeState: HazeState,
    shape: Shape,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    shadowElevation: Dp = 0.dp,
    showBorder: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val opaqueBackgroundAlpha = LocalChromeBackgroundOpacity.current.coerceIn(0f, 1f)
    val opaqueBackgroundColor =
        MaterialTheme.colorScheme.surface.copy(alpha = opaqueBackgroundAlpha)
    val materialStyle = HazeMaterials.ultraThin()
    Surface(
        modifier = modifier
            .clip(shape)
            .hazeEffect(
                state = hazeState,
                style = materialStyle,
            ) {
                blurRadius = 12.dp
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
        // Keep content out of Modifier.shadow's graphics layer before haze on Android 10.
        shadowElevation = shadowElevation,
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
