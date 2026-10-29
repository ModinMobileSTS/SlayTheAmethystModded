package io.stamethyst.ui.aimod

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.chrisbanes.haze.HazeState
import io.stamethyst.ui.FrostedGlassChrome
import io.stamethyst.R
import io.stamethyst.backend.llm.LlmReasoningEffort
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
internal fun AiReasoningEffortPicker(
    effort: LlmReasoningEffort,
    onEffortChange: (LlmReasoningEffort) -> Unit,
    enabled: Boolean,
    hazeState: HazeState,
) {
    val options = LlmReasoningEffort.selectable
    val selected = options.indexOf(effort).takeIf { it >= 0 } ?: 1
    var expanded by remember { mutableStateOf(false) }
    val popupState = remember { MutableTransitionState(false) }
    popupState.targetState = expanded
    val popupTransition = updateTransition(popupState, label = "effort popup visibility")
    val reveal by popupTransition.animateFloat(
        transitionSpec = { tween(160) },
        label = "effort popup",
    ) { visible -> if (visible) 1f else 0f }
    var position by remember { mutableStateOf(selected.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(selected) { if (!dragging) position = selected.toFloat() }
    val thumbPosition by animateFloatAsState(
        targetValue = position,
        animationSpec = if (dragging) snap() else spring(dampingRatio = 0.85f, stiffness = 600f),
        label = "effort snap",
    )
    val preview = options[position.roundToInt().coerceIn(0, options.lastIndex)]
    val label = stringResource(R.string.settings_llm_reasoning)
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box {
        TextButton(
            onClick = { expanded = !expanded },
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = options[selected].name.lowercase()
            },
        ) {
            Crossfade(if (expanded) preview else options[selected], animationSpec = tween(180), label = "effort icon") {
                ReasoningEnergyIcon(it, enabled)
            }
            Text(
                (if (expanded) preview else options[selected]).name.lowercase(),
                Modifier.padding(start = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Keep the popup composed until its exit transition has finished.
        if (popupState.currentState || popupState.targetState || !popupState.isIdle) Popup(
            popupPositionProvider = remember { EffortPopupPositionProvider() },
            onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = true),
        ) {
            FrostedGlassChrome(
                modifier = Modifier.width(246.dp).graphicsLayer {
                    alpha = reveal
                    scaleX = 0.96f + reveal * 0.04f
                    scaleY = scaleX
                    translationY = (1f - reveal) * 6.dp.toPx()
                },
                hazeState = hazeState,
                shape = RoundedCornerShape(22.dp),
                shadowElevation = 8.dp,
            ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                val accent by animateColorAsState(effortColor(preview), tween(180), label = "effort color")
                Slider(
                    value = if (dragging) position else thumbPosition,
                    onValueChange = { dragging = true; position = it },
                    onValueChangeFinished = {
                        val tier = position.roundToInt().coerceIn(0, options.lastIndex)
                        dragging = false
                        position = tier.toFloat()
                        onEffortChange(options[tier])
                    },
                    valueRange = 0f..2f,
                    // Continuous during touch; snap only after release, not on every move.
                    steps = 0,
                    enabled = enabled && expanded,
                    colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                    modifier = Modifier.semantics {
                        contentDescription = label
                        stateDescription = preview.name.lowercase()
                    },
                )
                Row(Modifier.width(210.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    options.forEachIndexed { index, option ->
                        Box(
                            Modifier.selectable(
                                selected = preview == option,
                                enabled = enabled && expanded,
                                role = Role.RadioButton,
                                onClick = { dragging = false; position = index.toFloat(); onEffortChange(option) },
                            ).size(48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                option.name.lowercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (preview == option) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

private class EffortPopupPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left
            else anchorBounds.right - popupContentSize.width
        val y = anchorBounds.top - popupContentSize.height
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}

private fun effortColor(effort: LlmReasoningEffort): Color = when (effort) {
    LlmReasoningEffort.LOW -> Color(0xFFBD8867)
    LlmReasoningEffort.MAX -> Color(0xFFB58AD5)
    else -> Color(0xFFD89556)
}

/** Small, isolated effects keep animation invalidation out of the conversation list. */
@Composable
private fun ReasoningEnergyIcon(effort: LlmReasoningEffort, enabled: Boolean) {
    val transition = rememberInfiniteTransition(label = "reasoning energy")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart),
        label = "energy phase",
    )
    val color = effortColor(effort).copy(alpha = if (enabled) 1f else 0.38f)
    Canvas(Modifier.size(32.dp)) {
        val unit = size.minDimension / 32f
        val p = if (enabled) phase else 0f
        val breath = (sin(p * 2 * PI).toFloat() + 1f) / 2f
        val radius = 6.6f * unit * (1f + breath * 0.08f)
        fun point(angle: Double, distance: Float) = Offset(
            center.x + cos(angle).toFloat() * distance,
            center.y + sin(angle).toFloat() * distance,
        )
        drawCircle(
            brush = Brush.radialGradient(listOf(color.copy(alpha = 0.20f), Color.Transparent), center, 16f * unit),
            radius = 16f * unit,
        )
        if (effort == LlmReasoningEffort.HIGH) {
            repeat(2) { index ->
                val ripple = (p + index * 0.5f) % 1f
                drawCircle(color.copy(alpha = (1f - ripple) * color.alpha * 0.45f),
                    radius = (9f + ripple * 6f) * unit, style = Stroke(unit * 0.7f))
            }
        }
        if (effort == LlmReasoningEffort.MAX) {
            repeat(12) { index ->
                val angle = index * PI / 6 + p * PI / 2
                drawLine(color.copy(alpha = color.alpha * 0.35f), point(angle, 11f * unit),
                    point(angle + 0.10, 11f * unit), unit * 0.7f, StrokeCap.Round)
            }
            repeat(10) { index ->
                val travel = (p + index / 10f) % 1f
                drawCircle(color.copy(alpha = sin(travel * PI).toFloat() * color.alpha * 0.8f),
                    radius = 0.7f * unit, center = point(index * PI / 5, (9f + travel * 7f) * unit))
            }
        }
        repeat(16) { index ->
            val angle = index * PI / 8 + if (effort == LlmReasoningEffort.MAX) p * PI / 2 else 0.0
            drawLine(color.copy(alpha = color.alpha * (0.7f + breath * 0.3f)),
                point(angle, radius * 0.46f), point(angle, radius), unit * 1.2f, StrokeCap.Round)
        }
    }
}
