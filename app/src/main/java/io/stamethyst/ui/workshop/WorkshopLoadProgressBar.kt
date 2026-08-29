package io.stamethyst.ui.workshop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.workshop.WorkshopLoadPhase
import io.stamethyst.backend.workshop.WorkshopLoadProgress

/**
 * Height the progress row occupies when fully revealed.
 *
 * The height is declared rather than measured on purpose. The caller reserves list inset from the
 * same animated value that drives this row, and that only stays in sync if the row never reports its
 * own size back through state: doing so puts the list a frame behind the header and reads as a
 * stutter. Because it is declared, it must also follow the user's font scale, otherwise the phase
 * label and detail line would clip at large text sizes.
 */
@Composable
internal fun workshopLoadProgressBarHeight(): Dp {
    val fontScale = LocalDensity.current.fontScale
    return remember(fontScale) {
        val textScale = fontScale.coerceIn(1f, 2f)
        ProgressLabelRowHeight * textScale +
            ProgressLabelTrackGap +
            ProgressTrackHeight +
            ProgressTrackDetailGap +
            ProgressDetailHeight * textScale +
            ProgressBottomPadding
    }
}

private val ProgressLabelRowHeight = 20.dp
private val ProgressLabelTrackGap = 6.dp
private val ProgressTrackHeight = 4.dp
private val ProgressTrackDetailGap = 4.dp
private val ProgressDetailHeight = 16.dp
private val ProgressBottomPadding = 12.dp
private val ProgressPercentWidth = 44.dp

/**
 * Narrates a market load at the bottom of the header card.
 *
 * The browse pipeline has no byte-level progress to report, so an indeterminate spinner would say
 * nothing about *why* a load is slow. This instead advances a determinate bar through named stages
 * and calls out recoveries (node failover, official fallback), which is the information a user needs
 * when an acceleration node is degraded.
 *
 * @param revealHeight animated height owned by the caller, so the header card and the list inset
 * grow from one shared value instead of reacting to this row's measured size.
 */
@Composable
internal fun WorkshopLoadProgressBar(
    progress: WorkshopLoadProgress?,
    revealHeight: Dp,
    modifier: Modifier = Modifier,
) {
    // Keep the last non-null progress so the collapse animation can play with its text intact
    // instead of snapping to empty as soon as the load finishes.
    var lastProgress by remember { mutableStateOf<WorkshopLoadProgress?>(null) }
    if (progress != null) {
        lastProgress = progress
    }
    val rendered = progress ?: lastProgress
    if (revealHeight <= 0.dp || rendered == null) {
        return
    }

    val fullHeight = workshopLoadProgressBarHeight()
    val textScale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
    val revealFraction = (revealHeight / fullHeight).coerceIn(0f, 1f)
    val phase = rendered.phase
    val isFailure = phase == WorkshopLoadPhase.Failed
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    // Colour is animated rather than switched so a failover reads as the same continuous operation
    // changing state, not as a different widget appearing.
    val accentColor by animateColorAsState(
        targetValue = workshopLoadPhaseAccentColor(phase),
        animationSpec = tween(durationMillis = 320),
        label = "workshopProgressAccent",
    )
    val fraction by animateFloatAsState(
        targetValue = phase.completionFraction(),
        animationSpec = tween(durationMillis = 420, easing = LinearEasing),
        label = "workshopProgressFraction",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(revealHeight)
            .clipToBounds()
            // Fading through a graphics layer keeps the reveal a draw-only change; animating alpha on
            // the children would invalidate layout on every frame.
            .graphicsLayer { alpha = revealFraction },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(fullHeight)
                .padding(start = 16.dp, end = 16.dp, bottom = ProgressBottomPadding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ProgressLabelRowHeight * textScale),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WorkshopProgressPhasePulse(
                    color = accentColor,
                    animating = !phase.isTerminal,
                )
                AnimatedContent(
                    targetState = phase,
                    transitionSpec = {
                        (
                            fadeIn(animationSpec = tween(durationMillis = 200)) +
                                slideInVertically(
                                    animationSpec = tween(durationMillis = 220),
                                ) { height -> height / 2 }
                            ) togetherWith (
                            fadeOut(animationSpec = tween(durationMillis = 140)) +
                                slideOutVertically(
                                    animationSpec = tween(durationMillis = 220),
                                ) { height -> -height / 2 }
                            // Snap the container: the row is a fixed size, so animating it would only
                            // add a layout pass per frame without changing what is drawn.
                            ) using SizeTransform(clip = false) { _, _ -> snap() }
                    },
                    label = "workshopProgressLabel",
                    modifier = Modifier.weight(1f),
                ) { animatedPhase ->
                    Text(
                        text = animatedPhase.progressLabel(rendered),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (isFailure) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(R.string.workshop_load_progress_percent, (fraction * 100f).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                    // The number changes on almost every animation frame. A fixed width stops each
                    // change from re-measuring the row and re-laying out the label beside it.
                    modifier = Modifier.width(ProgressPercentWidth),
                )
            }

            Spacer(modifier = Modifier.height(ProgressLabelTrackGap))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ProgressTrackHeight)
                    .drawBehind {
                        val radius = CornerRadius(size.height / 2f)
                        drawRoundRect(color = trackColor, cornerRadius = radius)
                        // Drawn from x = 0 so the fill always grows left to right. Doing this in the
                        // draw phase also keeps the sweep off the layout path entirely.
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

            Spacer(modifier = Modifier.height(ProgressTrackDetailGap))

            // The detail slot is always reserved. Letting it expand and collapse would animate the
            // card height a second time, on top of the reveal, and the two fought each other.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ProgressDetailHeight * textScale),
                contentAlignment = Alignment.CenterStart,
            ) {
                Crossfade(
                    targetState = rendered.detailLine().orEmpty(),
                    animationSpec = tween(durationMillis = 200),
                    label = "workshopProgressDetail",
                ) { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isFailure) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Breathing dot that conveys liveness without competing with the bar itself. */
@Composable
internal fun WorkshopProgressPhasePulse(
    color: Color,
    animating: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "workshopProgressPulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = if (animating) 0.35f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 720, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "workshopProgressPulseAlpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(color.copy(alpha = if (animating) pulseAlpha else 1f)),
    )
}

/**
 * Accent colour for a load phase, shared by the market header bar and the detail top bar so both
 * surfaces read recoveries and failures identically.
 */
@Composable
internal fun workshopLoadPhaseAccentColor(phase: WorkshopLoadPhase): Color = when {
    phase == WorkshopLoadPhase.Failed -> MaterialTheme.colorScheme.error
    phase == WorkshopLoadPhase.FailingOver || phase == WorkshopLoadPhase.FallingBack ->
        MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

internal fun WorkshopLoadPhase.completionFraction(): Float = when (this) {
    WorkshopLoadPhase.Preparing -> 0.06f
    WorkshopLoadPhase.Authenticating -> 0.18f
    WorkshopLoadPhase.ResolvingRoute -> 0.32f
    WorkshopLoadPhase.ProbingNodes -> 0.44f
    WorkshopLoadPhase.Connecting -> 0.58f
    // Recoveries deliberately do not rewind the bar: work already done is still valid, and a bar
    // that jumps backwards reads as a bug rather than as a retry.
    WorkshopLoadPhase.FailingOver -> 0.62f
    WorkshopLoadPhase.FallingBack -> 0.66f
    WorkshopLoadPhase.Parsing -> 0.82f
    WorkshopLoadPhase.Completed -> 1f
    WorkshopLoadPhase.Failed -> 1f
}

@Composable
internal fun WorkshopLoadPhase.progressLabel(progress: WorkshopLoadProgress?): String = when (this) {
    WorkshopLoadPhase.Preparing -> stringResource(R.string.workshop_load_phase_preparing)
    WorkshopLoadPhase.Authenticating -> stringResource(R.string.workshop_load_phase_authenticating)
    WorkshopLoadPhase.ResolvingRoute -> stringResource(R.string.workshop_load_phase_resolving_route)
    WorkshopLoadPhase.ProbingNodes -> stringResource(R.string.workshop_load_phase_probing_nodes)
    WorkshopLoadPhase.Connecting -> stringResource(R.string.workshop_load_phase_connecting)
    WorkshopLoadPhase.FailingOver -> stringResource(
        R.string.workshop_load_phase_failing_over,
        progress?.failedTargetCount ?: 1,
    )
    WorkshopLoadPhase.FallingBack -> stringResource(R.string.workshop_load_phase_falling_back)
    WorkshopLoadPhase.Parsing -> stringResource(R.string.workshop_load_phase_parsing)
    WorkshopLoadPhase.Completed -> stringResource(R.string.workshop_load_phase_completed)
    WorkshopLoadPhase.Failed -> stringResource(R.string.workshop_load_phase_failed)
}

@Composable
private fun WorkshopLoadProgress.detailLine(): String? {
    val host = target?.substringAfter("://")?.substringBefore('/')?.takeIf { it.isNotBlank() }
    return when (phase) {
        WorkshopLoadPhase.Connecting -> host?.let {
            stringResource(R.string.workshop_load_progress_node, it)
        }
        WorkshopLoadPhase.FailingOver -> {
            val reason = detail?.takeIf { it.isNotBlank() }
            when {
                host != null && reason != null ->
                    stringResource(R.string.workshop_load_progress_node_failed_reason, host, reason)
                host != null -> stringResource(R.string.workshop_load_progress_node_failed, host)
                else -> null
            }
        }
        WorkshopLoadPhase.FallingBack -> stringResource(R.string.workshop_load_progress_official_origin)
        WorkshopLoadPhase.Failed -> detail?.takeIf { it.isNotBlank() }
        else -> null
    }
}
