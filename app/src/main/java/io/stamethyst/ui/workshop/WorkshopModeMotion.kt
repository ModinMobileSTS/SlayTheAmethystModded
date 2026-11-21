package io.stamethyst.ui.workshop

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/** One interruptible clock for the two pages, the search controls and the tab indicator. */
@Composable
internal fun rememberWorkshopModeMotion(ai: Boolean): WorkshopModeMotion {
    val progress = animateFloatAsState(
        if (ai) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 560f, visibilityThreshold = .002f),
        label = "Workshop search mode",
    )
    return remember(progress) { WorkshopModeMotion(progress) }
}

@Stable
internal class WorkshopModeMotion(private val position: State<Float>) {
    val progress: Float get() = position.value.coerceIn(0f, 1f)
    // Read only these endpoints in composition. Per-frame values belong in placement/draw.
    val hasBrowse by derivedStateOf { progress < 1f }
    val hasChat by derivedStateOf { progress > 0f }

    fun opacity(chat: Boolean): Float {
        val p = if (chat) progress else 1f - progress
        return ((p - .16f) / .84f).coerceIn(0f, 1f)
    }
}

internal fun Modifier.workshopModePane(motion: WorkshopModeMotion, chat: Boolean, selected: Boolean): Modifier =
    zIndex(if (selected) 0f else -1f).workshopModeInteraction(selected).graphicsLayer {
        alpha = motion.opacity(chat)
        translationX = 12.dp.toPx() * (if (chat) 1f - motion.progress else -motion.progress)
    }

/** Only the revealed height changes. The search field and its menus keep their full-size layout. */
internal fun Modifier.workshopModeHeader(motion: WorkshopModeMotion, selected: Boolean): Modifier =
    workshopModeInteraction(selected).clipToBounds().layout { measurable, constraints ->
        val child = measurable.measure(constraints)
        val p = motion.progress
        layout(child.width, (child.height * (1f - p)).roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)) {
            child.placeWithLayer(0, (-8.dp.toPx() * p).roundToInt()) { alpha = motion.opacity(false) }
        }
    }

private fun Modifier.workshopModeInteraction(selected: Boolean): Modifier =
    if (selected) this else clearAndSetSemantics {}.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
