package io.stamethyst.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Material switch with decorative power marks; accessibility stays with the switch itself. */
@Composable
internal fun LauncherSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        thumbContent = {
            // Material supplies the correct icon tint for checked, unchecked and disabled states.
            val color = LocalContentColor.current
            Canvas(modifier = Modifier.size(16.dp)) {
                val strokeWidth = 2.dp.toPx()
                val radius = 4.5.dp.toPx()
                if (checked) {
                    drawLine(
                        color = color,
                        start = Offset(center.x, center.y - radius),
                        end = Offset(center.x, center.y + radius),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                } else {
                    drawCircle(color = color, radius = radius, style = Stroke(width = strokeWidth))
                }
            }
        },
    )
}
