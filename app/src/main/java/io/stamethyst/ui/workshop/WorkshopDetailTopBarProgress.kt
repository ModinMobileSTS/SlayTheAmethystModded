package io.stamethyst.ui.workshop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.workshop.WorkshopLoadPhase
import io.stamethyst.backend.workshop.WorkshopLoadProgress

private val DetailTopBarLabelRowHeight = 16.dp
private val DetailTopBarLabelTrackGap = 3.dp
private val DetailTopBarTrackHeight = 2.dp
private val DetailTopBarPercentWidth = 36.dp

/**
 * Narrates a detail load inside the top app bar, in place of the "Workshop details" subtitle.
 *
 * The detail pipeline has the same invisible waits as a market load (session priming, node picks,
 * failovers), so this mirrors the header bar's stage narration at top-bar scale: one line with the
 * current step and percent, and a hairline track underneath that advances through named stages.
 */
@Composable
internal fun WorkshopDetailTopBarLoadProgress(
    progress: WorkshopLoadProgress?,
    modifier: Modifier = Modifier,
) {
    // Keep the last non-null progress so the swap back to the subtitle can fade out with its final
    // frame intact instead of snapping to empty as soon as the load finishes.
    var lastProgress by remember { mutableStateOf<WorkshopLoadProgress?>(null) }
    if (progress != null) {
        lastProgress = progress
    }
    val rendered = progress ?: lastProgress ?: return

    val textScale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
    val phase = rendered.phase
    val isFailure = phase == WorkshopLoadPhase.Failed
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
    val accentColor by animateColorAsState(
        targetValue = workshopLoadPhaseAccentColor(phase),
        animationSpec = tween(durationMillis = 320),
        label = "workshopDetailProgressAccent",
    )
    val fraction by animateFloatAsState(
        targetValue = phase.completionFraction(),
        animationSpec = tween(durationMillis = 420, easing = LinearEasing),
        label = "workshopDetailProgressFraction",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(DetailTopBarLabelRowHeight * textScale),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkshopProgressPhasePulse(
                color = accentColor,
                animating = !phase.isTerminal,
            )
            AnimatedContent(
                targetState = phase,
                transitionSpec = {
                    fadeIn(animationSpec = tween(durationMillis = 180)) togetherWith
                        fadeOut(animationSpec = tween(durationMillis = 120))
                },
                label = "workshopDetailProgressLabel",
                modifier = Modifier.weight(1f),
            ) { animatedPhase ->
                Text(
                    text = animatedPhase.progressLabel(rendered),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (isFailure) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(R.string.workshop_load_progress_percent, (fraction * 100f).toInt()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.End,
                // A fixed width stops each percent tick from re-measuring the row.
                modifier = Modifier.width(DetailTopBarPercentWidth),
            )
        }

        Spacer(modifier = Modifier.height(DetailTopBarLabelTrackGap))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(DetailTopBarTrackHeight)
                .drawBehind {
                    val radius = CornerRadius(size.height / 2f)
                    drawRoundRect(color = trackColor, cornerRadius = radius)
                    // Drawn from x = 0 so the fill always grows left to right, off the layout path.
                    val fillWidth = size.width * fraction
                    if (fillWidth > 0f) {
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(accentColor.copy(alpha = 0.72f), accentColor),
                                startX = 0f,
                                endX = size.width,
                            ),
                            topLeft = Offset.Zero,
                            size = Size(fillWidth.coerceAtMost(size.width), size.height),
                            cornerRadius = radius,
                        )
                    }
                },
        )
    }
}
