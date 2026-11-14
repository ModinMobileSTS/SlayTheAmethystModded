package io.stamethyst.ui.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import io.stamethyst.model.ModItemUi
import io.stamethyst.ui.LocalLauncherBlurDisabled

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun ModDetailsSurface(
    mod: ModItemUi,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bitmap by rememberModPreviewBitmap(mod)
    val hazeState = remember { HazeState() }
    val shape = RoundedCornerShape(28.dp)
    val backdropColor = MaterialTheme.colorScheme.surfaceContainerLow
    val launcherBlurEnabled = !LocalLauncherBlurDisabled.current
    Surface(modifier = modifier, shape = shape, color = backdropColor) {
        Box {
            bitmap?.let { preview ->
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().hazeSource(hazeState),
                )
                // Blur only the artwork, never the foreground text or controls.
                Box(
                    Modifier.matchParentSize()
                        .hazeEffect(state = hazeState, style = HazeMaterials.thin()) {
                            blurEnabled = launcherBlurEnabled && hazeState.blurEnabled
                            blurRadius = 24.dp
                            blurredEdgeTreatment = BlurredEdgeTreatment(shape)
                        }
                        .background(backdropColor.copy(alpha = 0.55f))
                        .testTag("mod_details_frosted_background"),
                )
            }
            Column(content = content)
        }
    }
}
