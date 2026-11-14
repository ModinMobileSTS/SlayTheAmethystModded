package io.stamethyst.ui.aimod

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.llm.AgentCacheStats
import io.stamethyst.backend.llm.AgentContextState
import java.text.NumberFormat

/** Keep the overview scannable; accounting explanations are available on demand. */
@Composable
internal fun AiContextUsagePanel(
    tokens: Int?,
    limit: Int,
    busy: Boolean,
    state: AgentContextState?,
    cache: AgentCacheStats,
) {
    val colors = MaterialTheme.colorScheme
    val ratio = tokens?.takeIf { limit > 0 }?.let { it.toDouble() / limit }
    var showExplanation by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = MaterialTheme.shapes.medium, color = colors.surfaceContainerHighest) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ai_mod_editor_context_estimate),
                        Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    if (busy) {
                        val updating = stringResource(R.string.ai_mod_editor_context_busy)
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp).semantics { contentDescription = updating },
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(formatAiCacheRate(ratio), style = MaterialTheme.typography.titleMedium)
                }
                Text("${formatAiTokenCount(tokens?.toLong())} / ${formatAiTokenCount(limit.toLong())} tokens",
                    style = MaterialTheme.typography.bodyMedium)
                // Unknown occupancy is not presented as an active load or a measured zero.
                if (ratio != null) LinearProgressIndicator(
                    progress = { ratio.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (ratio >= 0.9) colors.error else colors.primary,
                    trackColor = colors.surfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ContextMetric(stringResource(R.string.ai_mod_editor_context_input),
                        formatAiTokenCount(state?.lastInputTokens?.toLong()), "context-input", Modifier.weight(1f))
                    ContextMetric(stringResource(R.string.ai_mod_editor_context_output),
                        formatAiTokenCount(state?.lastOutputTokens?.toLong()), "context-output", Modifier.weight(1f))
                    ContextMetric(stringResource(R.string.ai_mod_editor_context_compactions),
                        formatAiTokenCount(state?.compactionCount?.toLong()), "context-compactions", Modifier.weight(1f))
                }
            }
        }
        Text(stringResource(R.string.ai_mod_editor_cache_title), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CacheRateMetric(stringResource(R.string.ai_mod_editor_cache_last), cache.lastRate,
                "cache-last", Modifier.weight(1f))
            CacheRateMetric(stringResource(R.string.ai_mod_editor_cache_total), cache.totalRate,
                "cache-total", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ContextMetric(stringResource(R.string.ai_mod_editor_cache_read_input),
                "${formatAiTokenCount(cache.lastReadTokens)} / ${formatAiTokenCount(cache.lastInputTokens)}",
                "cache-tokens", Modifier.weight(1f))
            ContextMetric(stringResource(R.string.ai_mod_editor_cache_coverage),
                "${formatAiTokenCount(cache.reportedRequests)} / ${formatAiTokenCount(cache.requests)}",
                "cache-coverage", Modifier.weight(1f))
        }
        if (cache.inputExcludesCached) Surface(
            shape = MaterialTheme.shapes.small,
            color = colors.secondaryContainer,
            contentColor = colors.onSecondaryContainer,
        ) {
            Text(stringResource(R.string.ai_mod_editor_cache_normalized_badge),
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
        }
        val explanationState = stringResource(if (showExplanation)
            R.string.ai_mod_editor_context_explanation_expanded else R.string.ai_mod_editor_context_explanation_collapsed)
        TextButton(
            onClick = { showExplanation = !showExplanation },
            modifier = Modifier.testTag("context-explanation-toggle").semantics { stateDescription = explanationState },
        ) {
            Icon(painterResource(R.drawable.ic_info_outline), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.ai_mod_editor_context_explanation), Modifier.padding(horizontal = 8.dp))
            Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null,
                modifier = Modifier.size(18.dp).rotate(if (showExplanation) 180f else 0f))
        }
        AnimatedVisibility(showExplanation) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (busy) R.string.ai_mod_editor_context_updating else R.string.ai_mod_editor_context_source),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text(stringResource(R.string.ai_mod_editor_cache_hint),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (cache.inputExcludesCached) Text(stringResource(R.string.ai_mod_editor_cache_normalized),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text(stringResource(R.string.ai_agent_budget_input_hint),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ContextMetric(label: String, value: String, tag: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.testTag(tag), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CacheRateMetric(label: String, rate: Double?, tag: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(Modifier.padding(12.dp).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatAiCacheRate(rate), Modifier.testTag(tag), style = MaterialTheme.typography.titleLarge)
            if (rate != null) LinearProgressIndicator(
                progress = { rate.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

internal fun formatAiTokenCount(value: Long?): String = value?.let {
    NumberFormat.getIntegerInstance().format(it)
} ?: "--"
