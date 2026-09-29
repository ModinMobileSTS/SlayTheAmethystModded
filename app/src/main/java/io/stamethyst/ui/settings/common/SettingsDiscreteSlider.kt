package io.stamethyst.ui.settings.common

import android.view.HapticFeedbackConstants
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlin.math.roundToInt

@Composable
internal fun SettingsDiscreteSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    stateDescription: String? = null,
) {
    val view = LocalView.current
    var lastStep by remember(valueRange, steps) {
        mutableIntStateOf(sliderStepIndex(value, valueRange, steps))
    }
    val sliderModifier = if (stateDescription == null) {
        modifier
    } else {
        modifier.semantics { this.stateDescription = stateDescription }
    }

    Slider(
        value = value,
        onValueChange = { changedValue ->
            val changedStep = sliderStepIndex(changedValue, valueRange, steps)
            if (changedStep != lastStep) {
                lastStep = changedStep
                performHapticFeedback(view, HapticFeedbackConstants.CLOCK_TICK)
            }
            onValueChange(changedValue)
        },
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        modifier = sliderModifier,
    )
}

private fun sliderStepIndex(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
): Int {
    val intervals = (steps + 1).coerceAtLeast(1)
    val range = valueRange.endInclusive - valueRange.start
    if (range <= 0f) return 0
    return (((value - valueRange.start) / range) * intervals)
        .roundToInt()
        .coerceIn(0, intervals)
}
