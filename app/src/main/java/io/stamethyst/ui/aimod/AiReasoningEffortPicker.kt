package io.stamethyst.ui.aimod

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.chrisbanes.haze.HazeState
import io.stamethyst.R
import io.stamethyst.backend.llm.LlmReasoningEffort
import io.stamethyst.ui.FrostedGlassChrome
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal fun resolveAiReasoningEffort(
    effort: LlmReasoningEffort,
    options: List<LlmReasoningEffort>,
): LlmReasoningEffort = effort.takeIf { it in options || it == LlmReasoningEffort.OFF }
    ?: LlmReasoningEffort.normalize(options).first()

internal fun aiReasoningPickerOptions(
    effort: LlmReasoningEffort,
    options: List<LlmReasoningEffort>,
): List<LlmReasoningEffort> =
    (if (effort == LlmReasoningEffort.OFF) listOf(effort) else emptyList()) +
        LlmReasoningEffort.normalize(options)

internal fun aiReasoningSliderIndex(position: Float, count: Int): Int =
    position.roundToInt().coerceIn(0, (count - 1).coerceAtLeast(0))

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiReasoningEffortPicker(
    effort: LlmReasoningEffort,
    options: List<LlmReasoningEffort>,
    onEffortChange: (LlmReasoningEffort) -> Unit,
    onConfigure: () -> Unit,
    enabled: Boolean,
    hazeState: HazeState,
) {
    // Old "off" omitted the provider parameter. Keep that saved selection until the user
    // explicitly configures new presets; it is not the same wire value as "none".
    val available = aiReasoningPickerOptions(effort, options)
    val selected = available.indexOf(resolveAiReasoningEffort(effort, available))
    var expanded by remember { mutableStateOf(false) }
    val popupState = remember { MutableTransitionState(false) }
    popupState.targetState = expanded
    val transition = updateTransition(popupState, label = "effort popup visibility")
    val reveal by transition.animateFloat(
        transitionSpec = { tween(160) }, label = "effort popup",
    ) { if (it) 1f else 0f }
    var position by remember(available) { mutableStateOf(selected.toFloat()) }
    var dragging by remember(available) { mutableStateOf(false) }
    LaunchedEffect(selected, expanded) { if (!dragging) position = selected.toFloat() }
    val thumbPosition by animateFloatAsState(
        position, animationSpec = if (dragging) snap() else spring(dampingRatio = 0.85f, stiffness = 600f),
        label = "effort snap",
    )
    val preview = available[aiReasoningSliderIndex(position, available.size)]
    val label = stringResource(R.string.settings_llm_reasoning)
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val popupWidth = with(density) {
        (window.width.toDp() - 24.dp).coerceAtLeast(48.dp)
            .coerceAtMost(if (available.size > 4) 360.dp else 246.dp)
    }
    val maxHeight = with(density) { (window.height.toDp() - 24.dp).coerceAtLeast(48.dp) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box {
        TextButton(onClick = { expanded = !expanded }, enabled = enabled,
            modifier = Modifier.testTag("ai-reasoning-picker").semantics {
                contentDescription = label
                stateDescription = available[selected].name.lowercase()
            }) {
            Crossfade(if (expanded) preview else available[selected], animationSpec = tween(180), label = "effort icon") {
                ReasoningEnergyIcon(it, enabled)
            }
            Text((if (expanded) preview else available[selected]).name.lowercase(),
                Modifier.padding(start = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Retain the original frosted slider and energy icon, including its exit animation.
        if (popupState.currentState || popupState.targetState || !popupState.isIdle) Popup(
            popupPositionProvider = remember { EffortPopupPositionProvider() },
            onDismissRequest = { dragging = false; expanded = false },
            properties = PopupProperties(focusable = true),
        ) {
            FrostedGlassChrome(
                modifier = Modifier.width(popupWidth).graphicsLayer {
                    alpha = reveal
                    scaleX = 0.96f + reveal * 0.04f
                    scaleY = scaleX
                    translationY = (1f - reveal) * 6.dp.toPx()
                },
                hazeState = hazeState,
                shape = RoundedCornerShape(22.dp),
                shadowElevation = 8.dp,
            ) {
                Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 10.dp)) {
                    val accent by animateColorAsState(effortColor(preview), tween(180), label = "effort color")
                    Slider(
                        value = (if (dragging) position else thumbPosition).coerceIn(0f, available.lastIndex.toFloat()),
                        onValueChange = { dragging = true; position = it },
                        onValueChangeFinished = {
                            val index = aiReasoningSliderIndex(position, available.size)
                            dragging = false
                            position = index.toFloat()
                            onEffortChange(available[index])
                        },
                        // A one-tier configuration has no range and must not divide by zero.
                        valueRange = 0f..available.lastIndex.coerceAtLeast(1).toFloat(),
                        steps = 0,
                        enabled = enabled && expanded && available.size > 1,
                        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                        modifier = Modifier.testTag("ai-reasoning-slider").semantics {
                            contentDescription = label
                            stateDescription = preview.name.lowercase()
                        },
                    )
                    // Keep every label tappable on narrow screens and at larger font scales.
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        available.forEachIndexed { index, option ->
                            Box(Modifier.testTag("ai-reasoning-${option.name.lowercase()}")
                                .selectable(selected = preview == option, enabled = enabled && expanded,
                                    role = Role.RadioButton, onClick = {
                                        dragging = false
                                        position = index.toFloat()
                                        onEffortChange(option)
                                    })
                                .widthIn(min = 48.dp).heightIn(min = 48.dp).padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center) {
                                Text(option.name.lowercase(), style = MaterialTheme.typography.labelMedium,
                                    color = if (preview == option) accent else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (preview == LlmReasoningEffort.OFF) Text(
                        stringResource(R.string.ai_agent_budget_reasoning_off_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { expanded = false; onConfigure() },
                        modifier = Modifier.testTag("ai-reasoning-configure")) {
                        Text(stringResource(R.string.ai_editor_configure_efforts))
                    }
                }
            }
        }
    }
}

private class EffortPopupPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize,
    ): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left
            else anchorBounds.right - popupContentSize.width
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (anchorBounds.top - popupContentSize.height)
                .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}

private fun effortColor(effort: LlmReasoningEffort): Color = when (effort) {
    LlmReasoningEffort.OFF, LlmReasoningEffort.NONE -> Color(0xFF92929C)
    LlmReasoningEffort.MINIMAL -> Color(0xFFA48E7D)
    LlmReasoningEffort.LOW -> Color(0xFFBD8867)
    LlmReasoningEffort.XHIGH -> Color(0xFFCF8B95)
    LlmReasoningEffort.MAX -> Color(0xFFB58AD5)
    else -> Color(0xFFD89556)
}

/** Keep animated drawing invalidation isolated from the conversation list. */
@Composable
private fun ReasoningEnergyIcon(effort: LlmReasoningEffort, enabled: Boolean) {
    val transition = rememberInfiniteTransition(label = "reasoning energy")
    val phase by transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart), label = "energy phase")
    val color = effortColor(effort).copy(alpha = if (enabled) 1f else 0.38f)
    Canvas(Modifier.size(32.dp)) {
        val unit = size.minDimension / 32f
        val p = if (enabled && effort != LlmReasoningEffort.OFF && effort != LlmReasoningEffort.NONE) phase else 0f
        val breath = (sin(p * 2 * PI).toFloat() + 1f) / 2f
        val radius = 6.6f * unit * (1f + breath * 0.08f)
        fun point(angle: Double, distance: Float) = Offset(
            center.x + cos(angle).toFloat() * distance, center.y + sin(angle).toFloat() * distance)
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.20f), Color.Transparent), center, 16f * unit),
            radius = 16f * unit)
        if (effort == LlmReasoningEffort.HIGH || effort == LlmReasoningEffort.XHIGH) {
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
