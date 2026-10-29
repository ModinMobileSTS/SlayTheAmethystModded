package io.stamethyst.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.mods.CatalogueLoadPhase
import io.stamethyst.backend.mods.CatalogueLoadProgress
import io.stamethyst.ui.LoadStageProgress
import io.stamethyst.ui.LoadStageProgressBar

/**
 * Narrates a community patch catalogue load directly under the screen's top bar.
 *
 * The catalogue uses the update-source mirror chain. Show the source being attempted and failures
 * using the market's stage bar instead of an indeterminate spinner.
 *
 * @param revealHeight animated height owned by the caller, so the bar's slot and the list below
 * grow from one shared value instead of reacting to the row's measured size.
 */
@Composable
internal fun CatalogueLoadProgressBar(
    progress: CatalogueLoadProgress?,
    revealHeight: Dp,
    modifier: Modifier = Modifier,
) {
    // Keep the last non-null progress so the collapse animation plays with its text intact instead
    // of snapping to empty the moment the request settles.
    var lastProgress by remember { mutableStateOf<CatalogueLoadProgress?>(null) }
    if (progress != null) {
        lastProgress = progress
    }
    val rendered = progress ?: lastProgress
    if (revealHeight <= 0.dp || rendered == null) {
        return
    }
    val phase = rendered.phase
    LoadStageProgressBar(
        progress = LoadStageProgress(
            label = phase.progressLabel(rendered),
            fraction = phase.completionFraction(),
            detail = rendered.detailLine(),
            accentColor = catalogueLoadPhaseAccentColor(phase),
            failure = phase == CatalogueLoadPhase.Failed,
            animating = !phase.isTerminal,
        ),
        revealHeight = revealHeight,
        modifier = modifier,
    )
}

@Composable
private fun catalogueLoadPhaseAccentColor(phase: CatalogueLoadPhase): Color = when {
    phase == CatalogueLoadPhase.Failed -> MaterialTheme.colorScheme.error
    phase == CatalogueLoadPhase.FailingOver || phase == CatalogueLoadPhase.FallingBack ->
        MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

private fun CatalogueLoadPhase.completionFraction(): Float = when (this) {
    CatalogueLoadPhase.Preparing -> 0.08f
    CatalogueLoadPhase.ResolvingRoute -> 0.28f
    CatalogueLoadPhase.ProbingNodes -> 0.42f
    CatalogueLoadPhase.Connecting -> 0.56f
    // Recoveries deliberately do not rewind the bar: work already done is still valid, and a bar
    // that jumps backwards reads as a bug rather than as a retry.
    CatalogueLoadPhase.FailingOver -> 0.60f
    CatalogueLoadPhase.FallingBack -> 0.64f
    CatalogueLoadPhase.Parsing -> 0.88f
    CatalogueLoadPhase.Completed -> 1f
    CatalogueLoadPhase.Failed -> 1f
}

@Composable
private fun CatalogueLoadPhase.progressLabel(progress: CatalogueLoadProgress?): String = when (this) {
    CatalogueLoadPhase.Preparing -> stringResource(R.string.catalogue_load_phase_preparing)
    CatalogueLoadPhase.ResolvingRoute -> stringResource(R.string.catalogue_load_phase_resolving_route)
    CatalogueLoadPhase.ProbingNodes -> stringResource(R.string.catalogue_load_phase_probing_nodes)
    CatalogueLoadPhase.Connecting -> stringResource(R.string.catalogue_load_phase_connecting)
    CatalogueLoadPhase.FailingOver -> stringResource(
        R.string.workshop_load_phase_failing_over,
        progress?.failedTargetCount ?: 1,
    )
    CatalogueLoadPhase.FallingBack -> stringResource(R.string.catalogue_load_phase_falling_back)
    CatalogueLoadPhase.Parsing -> stringResource(R.string.catalogue_load_phase_parsing)
    CatalogueLoadPhase.Completed -> stringResource(R.string.catalogue_load_phase_completed)
    CatalogueLoadPhase.Failed -> stringResource(R.string.catalogue_load_phase_failed)
}

@Composable
private fun CatalogueLoadProgress.detailLine(): String? {
    val host = target?.substringAfter("://")?.substringBefore('/')?.takeIf { it.isNotBlank() }
    return when (phase) {
        CatalogueLoadPhase.Connecting -> host?.let {
            stringResource(R.string.workshop_load_progress_node, it)
        }
        CatalogueLoadPhase.FailingOver -> {
            val reason = detail?.takeIf { it.isNotBlank() }
            when {
                host != null && reason != null ->
                    stringResource(R.string.workshop_load_progress_node_failed_reason, host, reason)
                host != null -> stringResource(R.string.workshop_load_progress_node_failed, host)
                else -> null
            }
        }
        CatalogueLoadPhase.FallingBack -> stringResource(R.string.catalogue_load_progress_official_origin)
        CatalogueLoadPhase.Failed -> detail?.takeIf { it.isNotBlank() }
        else -> null
    }
}
