package io.stamethyst.ui.aimod

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.llm.AgentStepKind
import io.stamethyst.backend.llm.AgentStepStatus
import io.stamethyst.ui.Icons
import io.stamethyst.ui.LauncherPanelDialog
import io.stamethyst.ui.icon.Close
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/** Reference equality avoids a deep snapshot comparison on the composition thread. */
private class PreparedAiHistory(val snapshot: AiHistorySnapshot)

@Composable
internal fun AiHistorySpeedButton(speed: Double?, onClick: () -> Unit) {
    val label = stringResource(R.string.ai_history_open)
    Surface(onClick = onClick, color = Color.Transparent, shape = RoundedCornerShape(10.dp),
        modifier = Modifier.heightIn(min = 48.dp).testTag("ai-history-speed").semantics { contentDescription = label }) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AiHistoryGlyph(Modifier.size(14.dp))
            Text(speed?.takeIf { it.isFinite() && it >= 0 }?.let { String.format(Locale.ROOT, "%.1f token/s", it) } ?: "— token/s",
                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
internal fun AiExecutionHistoryDialog(
    sessionId: String,
    revision: Int,
    messages: List<AiEditorMessage>,
    onDismiss: () -> Unit,
) {
    val builder = remember(sessionId) { AiHistoryBuilder() }
    val prepared by produceState<PreparedAiHistory?>(null, sessionId, revision) {
        val source = messages.toList()
        value = withContext(Dispatchers.Default) { PreparedAiHistory(builder.build(source)) }
    }
    AiExecutionHistoryPanel(prepared?.snapshot, onDismiss)
}

@Composable
internal fun AiExecutionHistoryPanel(snapshot: AiHistorySnapshot?, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var expandedTurn by rememberSaveable { mutableStateOf<Long?>(null) }
    var expandedStep by rememberSaveable { mutableStateOf<String?>(null) }
    var scrollTarget by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    // Open the latest turn once. New streaming steps never steal the reader's scroll position.
    var initialized by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(snapshot != null) {
        if (!initialized && snapshot != null) {
            expandedTurn = snapshot.turns.lastOrNull()?.id
            initialized = true
        }
    }
    LaunchedEffect(scrollTarget) {
        val id = scrollTarget ?: return@LaunchedEffect
        val index = snapshot?.turns?.asReversed()?.indexOfFirst { it.id == id } ?: -1
        if (index >= 0) listState.animateScrollToItem(index + 3)
        scrollTarget = null
    }
    LauncherPanelDialog(onDismissRequest = onDismiss, maxWidth = 760.dp, containerColor = colors.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = colors.primaryContainer) {
                Icon(painterResource(R.drawable.ic_ai_execution_history), null, Modifier.padding(10.dp).size(24.dp),
                    tint = colors.onPrimaryContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.ai_history_title), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.ai_history_subtitle), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("ai-history-close")) {
                Icon(Icons.Close, stringResource(R.string.ai_history_close))
            }
        }
        if (snapshot == null) {
            Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            }
        } else if (snapshot.turns.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AiHistoryGlyph(Modifier.size(52.dp))
                Text(stringResource(R.string.ai_history_empty), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.ai_history_empty_hint), style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant)
            }
        } else LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).testTag("ai-history-list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "summary", contentType = "summary") { AiHistorySummary(snapshot) }
            item(key = "overview", contentType = "overview") {
                AiHistoryOverview(snapshot.overview, expandedTurn) { id ->
                    expandedTurn = id
                    expandedStep = null
                    scrollTarget = id
                }
            }
            item(key = "turn-label", contentType = "label") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ai_history_turns), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.ai_history_step_count, snapshot.stepCount),
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            snapshot.turns.asReversed().forEach { turn ->
                item(key = "turn:${turn.id}", contentType = "turn") {
                    AiHistoryTurnHeader(turn, expandedTurn == turn.id) {
                        expandedTurn = if (expandedTurn == turn.id) null else turn.id
                        expandedStep = null
                    }
                }
                if (expandedTurn == turn.id) {
                    itemsIndexed(turn.steps, key = { _, step -> step.key }, contentType = { _, _ -> "step" }) { index, step ->
                        AiHistoryStepRow(step, index + 1, expandedStep == step.key,
                            Modifier.animateItem(fadeInSpec = tween(160), placementSpec = null, fadeOutSpec = null)) {
                            expandedStep = if (expandedStep == step.key) null else step.key
                        }
                    }
                    if (turn.steps.isEmpty()) item(key = "pending:${turn.id}") {
                        Text(stringResource(if (turn.running) R.string.ai_history_waiting else R.string.ai_history_no_steps),
                            Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            item(key = "accounting", contentType = "note") {
                Text(stringResource(R.string.ai_history_accounting), Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AiHistorySummary(snapshot: AiHistorySnapshot) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HistoryMetric("Turn", snapshot.turns.size.toString(), Modifier.weight(0.8f))
            HistoryMetric(stringResource(R.string.ai_history_duration), formatAiHistoryDuration(snapshot.elapsedMs), Modifier.weight(1f))
            HistoryMetric(stringResource(R.string.ai_history_tokens), historyUsageLabel(snapshot.usage), Modifier.weight(1.3f),
                accent = true, tag = "ai-history-total-tokens")
        }
    }
}

@Composable
private fun HistoryMetric(label: String, value: String, modifier: Modifier = Modifier, accent: Boolean = false, tag: String = "") {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.testTag(tag), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun historyUsageLabel(usage: AiHistoryUsage): String = usage.tokens?.let {
    (if (usage.partial) "≥ " else "") + formatAiTokenCount(it)
} ?: "—"

@Composable
private fun AiHistoryOverview(columns: List<AiHistoryColumn>, selectedTurn: Long?, onSelect: (Long) -> Unit) {
    val palette = historyPalette()
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val chartDescription = stringResource(R.string.ai_history_chart_description)
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(360)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.ai_history_activity), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.ai_history_chart_hint), style = MaterialTheme.typography.labelSmall, color = muted)
        }
        Canvas(Modifier.fillMaxWidth().height(70.dp).testTag("ai-history-chart")
            .semantics { contentDescription = chartDescription }
            .pointerInput(columns) {
                detectTapGestures { point ->
                    if (columns.isNotEmpty()) {
                        val index = (point.x / size.width * columns.size).toInt().coerceIn(0, columns.lastIndex)
                        onSelect(columns[index].turnId)
                    }
                }
            }) {
            if (columns.isEmpty()) return@Canvas
            val slot = size.width / columns.size
            val gap = minOf(4.dp.toPx(), slot * 0.22f)
            val width = slot - gap
            val height = size.height - 8.dp.toPx()
            val maxWeight = columns.maxOf { it.segments.sumOf { segment -> segment.weight.toDouble() } }.coerceAtLeast(1.0)
            columns.forEachIndexed { index, column ->
                val x = index * slot + gap / 2
                val total = column.segments.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(1f)
                val columnHeight = height * (0.2f + 0.8f * kotlin.math.sqrt(total / maxWeight).toFloat()) * reveal.value
                drawRoundRect(track, Offset(x, size.height - 2.dp.toPx() - columnHeight), Size(width, columnHeight), CornerRadius(3.dp.toPx()))
                var y = size.height - 2.dp.toPx()
                column.segments.forEach { segment ->
                    val segmentHeight = columnHeight * segment.weight / total
                    y -= segmentHeight
                    drawRoundRect(palette.getValue(segment.kind).copy(alpha = if (selectedTurn == null || selectedTurn == column.turnId) 1f else 0.6f),
                        Offset(x, y + 0.6f), Size(width, (segmentHeight - 1.2f).coerceAtLeast(0.5f)), CornerRadius(2.dp.toPx()))
                }
                if (column.turnId == selectedTurn || column.running) drawCircle(palette.getValue(AgentStepKind.MODEL),
                    radius = 2.dp.toPx(), center = Offset(x + width / 2, size.height - columnHeight - 6.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AgentStepKind.entries.forEach { kind ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(palette.getValue(kind)))
                    Text(historyKindLabel(kind), style = MaterialTheme.typography.labelSmall, color = muted)
                }
            }
        }
    }
}

@Composable
private fun AiHistoryTurnHeader(turn: AiHistoryTurn, expanded: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(if (expanded) colors.primaryContainer.copy(alpha = 0.38f) else colors.surfaceContainerHigh,
        tween(180), label = "history turn selection")
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(180), label = "history turn chevron")
    val stateLabel = stringResource(if (expanded) R.string.ai_history_expanded else R.string.ai_history_collapsed)
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = background,
        modifier = Modifier.fillMaxWidth().testTag("ai-history-turn-${turn.id}").semantics { stateDescription = stateLabel }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Turn ${turn.number.toString().padStart(2, '0')}", style = MaterialTheme.typography.labelMedium,
                    color = colors.primary, fontFamily = FontFamily.Monospace)
                HistoryStatus(when { turn.running -> AgentStepStatus.RUNNING; turn.failed -> AgentStepStatus.FAILED; else -> AgentStepStatus.COMPLETED })
                Spacer(Modifier.weight(1f))
                Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(18.dp).rotate(rotation), tint = colors.onSurfaceVariant)
            }
            Text(turn.prompt.ifBlank { stringResource(R.string.ai_history_maintenance) }, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ai_history_step_count, turn.steps.size), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Text(formatAiHistoryDuration(turn.elapsedMs), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Text("${historyUsageLabel(turn.usage)} tokens", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            HistorySpectrum(turn.spectrum, Modifier.fillMaxWidth().height(5.dp))
        }
    }
}

@Composable
private fun AiHistoryStepRow(step: AiHistoryStep, number: Int, expanded: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent = historyPalette().getValue(step.kind)
    val elapsed by produceState(step.elapsedMs, step.key, step.status, step.elapsedMs) {
        if (step.status == AgentStepStatus.RUNNING && step.startedAtMs != null) {
            val initial = (System.currentTimeMillis() - step.startedAtMs).coerceAtLeast(0)
            val start = android.os.SystemClock.elapsedRealtime()
            while (true) {
                value = initial + android.os.SystemClock.elapsedRealtime() - start
                delay(1_000)
            }
        }
    }
    Surface(onClick = onClick, color = if (step.kind == AgentStepKind.ERROR) colors.errorContainer.copy(alpha = 0.35f)
        else colors.surfaceContainer.copy(alpha = 0.7f), shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth().padding(start = 10.dp).testTag("ai-history-step-${step.key}")) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = RoundedCornerShape(10.dp), color = accent.copy(alpha = 0.13f)) {
                val icon = if (step.kind == AgentStepKind.TOOL) agentToolIcon(step.name) else null
                if (icon != null) Icon(icon, null, Modifier.padding(8.dp).size(18.dp), tint = accent)
                else Icon(painterResource(when (step.kind) {
                    AgentStepKind.MODEL -> R.drawable.ic_ai_execution_history
                    AgentStepKind.COMPACTION -> R.drawable.ic_inventory
                    AgentStepKind.ERROR -> R.drawable.ic_error_outline
                    else -> R.drawable.ic_refresh
                }), null, Modifier.padding(8.dp).size(18.dp), tint = accent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Step ${number.toString().padStart(2, '0')}", style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace, color = accent)
                    HistoryStatus(step.status)
                    Spacer(Modifier.weight(1f))
                    Text(formatAiHistoryDuration(elapsed), style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant)
                }
                Text(if (step.kind == AgentStepKind.TOOL) step.name else historyKindLabel(step.kind),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = step.detail.ifBlank { stringResource(when (step.kind) {
                    AgentStepKind.MODEL -> R.string.ai_history_model_detail
                    AgentStepKind.TOOL -> R.string.ai_history_tool_detail
                    AgentStepKind.COMPACTION -> R.string.ai_history_compaction_detail
                    AgentStepKind.RETRY -> R.string.ai_history_retry_detail
                    AgentStepKind.ERROR -> R.string.ai_history_error_detail
                }) }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    maxLines = if (expanded) 8 else 2, overflow = TextOverflow.Ellipsis)
                Text("${step.totalTokens?.let(::formatAiTokenCount) ?: "—"} tokens",
                    style = MaterialTheme.typography.labelSmall, color = accent)
                AnimatedVisibility(expanded, enter = fadeIn(tween(160)) + expandVertically(tween(200)),
                    exit = fadeOut(tween(100)) + shrinkVertically(tween(160))) {
                    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (step.kind == AgentStepKind.ERROR) {
                            if (step.name.isNotBlank()) Text(stringResource(R.string.ai_history_error_source, step.name),
                                style = MaterialTheme.typography.labelSmall, color = accent)
                            step.relatedStepNumber?.let { Text(stringResource(R.string.ai_history_related_step, it),
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            HistoryMetric(stringResource(R.string.ai_history_input), step.inputTokens?.let(::formatAiTokenCount) ?: "—", Modifier.weight(1f))
                            HistoryMetric(stringResource(R.string.ai_history_output), step.outputTokens?.let(::formatAiTokenCount) ?: "—", Modifier.weight(1f))
                        }
                        if (step.billable && step.modelName.isNotBlank()) Text(step.modelName,
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        if (step.kind == AgentStepKind.MODEL && (step.elapsedMs ?: 0) > 0 && step.outputTokens != null) {
                            Text(String.format(Locale.ROOT, "%.1f token/s", step.outputTokens * 1000.0 / step.elapsedMs!!),
                                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = accent)
                        }
                        if (step.legacy || step.inputTokens == null || step.elapsedMs == null && step.status != AgentStepStatus.RUNNING)
                            Text(stringResource(R.string.ai_history_unrecorded), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        if (!step.billable) Text(stringResource(R.string.ai_history_no_model_tokens),
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryStatus(status: AgentStepStatus) {
    val colors = MaterialTheme.colorScheme
    val color = when (status) {
        AgentStepStatus.RUNNING -> colors.primary
        AgentStepStatus.FAILED -> colors.error
        else -> colors.onSurfaceVariant
    }
    Text(stringResource(when (status) {
        AgentStepStatus.RUNNING -> R.string.ai_history_running
        AgentStepStatus.COMPLETED -> R.string.ai_history_completed
        AgentStepStatus.FAILED -> R.string.ai_history_failed
        AgentStepStatus.CANCELLED -> R.string.ai_history_cancelled
    }), style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
private fun HistorySpectrum(segments: List<AiHistorySegment>, modifier: Modifier) {
    val palette = historyPalette()
    val failure = MaterialTheme.colorScheme.error
    Canvas(modifier.clip(RoundedCornerShape(3.dp))) {
        val total = segments.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(1f)
        var x = 0f
        segments.forEach { segment ->
            val width = size.width * segment.weight / total
            drawRoundRect(if (segment.failed) failure else palette.getValue(segment.kind), Offset(x, 0f),
                Size((width - 1.dp.toPx()).coerceAtLeast(0.5f), size.height), CornerRadius(2.dp.toPx()))
            x += width
        }
    }
}

/** A tiny spectrum beside token/s makes the new entry point discoverable without visual noise. */
@Composable
internal fun AiHistoryGlyph(modifier: Modifier = Modifier) {
    val palette = historyPalette().values.toList()
    Canvas(modifier) {
        val slot = size.width / 4
        val heights = floatArrayOf(0.45f, 0.85f, 0.65f, 1f)
        heights.forEachIndexed { index, fraction ->
            val height = size.height * fraction
            drawRoundRect(palette[index], Offset(index * slot, size.height - height), Size(slot * 0.68f, height),
                CornerRadius(slot * 0.25f))
        }
    }
}

@Composable
private fun historyPalette(): Map<AgentStepKind, Color> {
    val colors = MaterialTheme.colorScheme
    // Primary follows the launcher's dynamic theme; supporting tones remain distinct in both modes.
    val dark = colors.surface.red + colors.surface.green + colors.surface.blue < 1.5f
    return remember(colors.primary, colors.error, dark) { linkedMapOf(
        AgentStepKind.MODEL to colors.primary,
        AgentStepKind.TOOL to if (dark) Color(0xFF77DCC3) else Color(0xFF087E6A),
        AgentStepKind.COMPACTION to if (dark) Color(0xFFF1C879) else Color(0xFFA66B11),
        AgentStepKind.RETRY to if (dark) Color(0xFFE9A1BC) else Color(0xFFB34E78),
        AgentStepKind.ERROR to colors.error,
    ) }
}

@Composable
private fun historyKindLabel(kind: AgentStepKind): String = stringResource(when (kind) {
    AgentStepKind.MODEL -> R.string.ai_history_model
    AgentStepKind.TOOL -> R.string.ai_history_tool
    AgentStepKind.COMPACTION -> R.string.ai_history_compaction
    AgentStepKind.RETRY -> R.string.ai_history_retry
    AgentStepKind.ERROR -> R.string.ai_history_error
})
