package io.stamethyst.ui.workshop

import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val WorkshopSearchControlsTop = 14.dp
internal val WorkshopSearchControlsBottom = 16.dp
internal val WorkshopSearchControlsSpacing = 12.dp

/** The list reserves space for the field and filters, never for the animated history between them. */
@Stable
internal class WorkshopSearchControlsHeight {
    private var inputHeightPx by mutableIntStateOf(0)
    private var filtersHeightPx by mutableIntStateOf(0)

    val inputModifier: Modifier = Modifier.onSizeChanged { inputHeightPx = it.height }
    val filtersModifier: Modifier = Modifier
        .clipToBounds()
        // On a short screen the growing history can clip the filters; reserve their natural height.
        .wrapContentHeight(align = Alignment.Top, unbounded = true)
        .onSizeChanged { filtersHeightPx = it.height }

    fun collapsedHeight(density: Density): Dp = with(density) {
        if (inputHeightPx == 0 || filtersHeightPx == 0) 148.dp
        else (
            inputHeightPx + filtersHeightPx + WorkshopSearchControlsTop.roundToPx() +
                WorkshopSearchControlsBottom.roundToPx() + WorkshopSearchControlsSpacing.roundToPx()
            ).toDp()
    }
}
