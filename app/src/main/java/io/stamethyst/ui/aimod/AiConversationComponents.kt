package io.stamethyst.ui.aimod

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import io.stamethyst.R
import io.stamethyst.backend.llm.LlmReasoningEffort
import io.stamethyst.ui.Icons
import io.stamethyst.ui.FrostedGlassChrome
import io.stamethyst.ui.icon.AttachFile
import io.stamethyst.ui.icon.Close
import io.stamethyst.ui.icon.KeyboardArrowUp
import io.stamethyst.ui.icon.Pending
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun AiChatComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    attachments: List<AiAttachment>,
    onRemoveAttachment: (AiAttachment) -> Unit,
    onAttach: () -> Unit,
    configured: Boolean,
    busy: Boolean,
    modelName: String,
    models: List<String>,
    effort: LlmReasoningEffort,
    onEffortChange: (LlmReasoningEffort) -> Unit,
    onModelSelect: (String) -> Unit,
    onStop: () -> Unit,
    onSend: () -> Unit,
    hazeState: HazeState,
    onComposerHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var effortMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val placeholder = stringResource(R.string.ai_mod_editor_placeholder)
    Box(
        modifier = modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        FrostedGlassChrome(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()
                .onSizeChanged { onComposerHeightChanged(it.height) },
            hazeState = hazeState,
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (attachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        attachments.forEach { attachment ->
                            Surface(shape = RoundedCornerShape(8.dp), color = colors.surfaceContainerHigh) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
                                    Icon(Icons.AttachFile, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                                    Text(
                                        attachment.name,
                                        modifier = Modifier.widthIn(max = 180.dp).padding(start = 6.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    AiMessageAction(
                                        Icons.Close,
                                        stringResource(R.string.ai_mod_editor_remove_attachment, attachment.name),
                                        { onRemoveAttachment(attachment) },
                                        enabled = !busy,
                                    )
                                }
                            }
                        }
                    }
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    enabled = configured,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)
                        .heightIn(min = 64.dp, max = 160.dp)
                        .semantics { contentDescription = placeholder },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface, lineHeight = 24.sp, letterSpacing = 0.sp),
                    cursorBrush = SolidColor(colors.onSurface),
                    maxLines = 6,
                    decorationBox = { innerTextField ->
                        Box {
                            if (draft.isEmpty()) Text(placeholder, color = colors.onSurfaceVariant.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyLarge)
                            innerTextField()
                        }
                    },
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AiMessageAction(Icons.AttachFile, stringResource(R.string.ai_mod_editor_attach), onAttach, configured && !busy)
                    Box(Modifier.weight(1f)) {
                        TextButton(onClick = { effortMenu = true }, enabled = !busy) {
                            Icon(Icons.Pending, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                            Text(aiEffortLabel(effort), modifier = Modifier.padding(start = 6.dp), color = colors.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = effortMenu, onDismissRequest = { effortMenu = false }) {
                            LlmReasoningEffort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(aiEffortLabel(option)) },
                                    onClick = { effortMenu = false; onEffortChange(option) },
                                )
                            }
                        }
                    }
                    AiMessageAction(
                        icon = ImageVector.vectorResource(if (busy) R.drawable.ic_stop else R.drawable.ic_arrow_upward),
                        label = stringResource(if (busy) R.string.ai_mod_editor_stop else R.string.ai_mod_editor_send),
                        onClick = if (busy) onStop else onSend,
                        enabled = busy || (configured && (draft.isNotBlank() || attachments.isNotEmpty())),
                        prominent = true,
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val modelLabel = stringResource(R.string.llm_select_model)
                    Box(Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !busy && models.isNotEmpty(), onClick = { modelMenu = true })
                                .semantics { contentDescription = modelLabel }
                                .padding(horizontal = 8.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                modelName.ifBlank { modelLabel },
                                modifier = Modifier.weight(1f, fill = false),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.onSurface,
                            )
                            Icon(Icons.KeyboardArrowUp, null, Modifier.size(14.dp).rotate(180f), tint = colors.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                            if (models.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.llm_models_empty)) },
                                    onClick = { modelMenu = false },
                                    enabled = false,
                                )
                            } else {
                                models.forEach { model ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                model,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = if (model == modelName) colors.primary else colors.onSurface,
                                            )
                                        },
                                        onClick = { modelMenu = false; onModelSelect(model) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun aiEffortLabel(effort: LlmReasoningEffort): String = stringResource(
    when (effort) {
        LlmReasoningEffort.OFF -> R.string.settings_llm_reasoning_off
        LlmReasoningEffort.LOW -> R.string.settings_llm_reasoning_low
        LlmReasoningEffort.MEDIUM -> R.string.settings_llm_reasoning_medium
        LlmReasoningEffort.HIGH -> R.string.settings_llm_reasoning_high
    },
)

private val AGENT_PATCH_ID_PATTERN = Regex("patch-\\d+-[0-9a-f]{8}/?")

/**
 * Agent tool paths are relative to the workspace root one level above the versioned patch
 * directory, so they carry the raw launcher patch id. It is internal bookkeeping and should not
 * surface in the tool-call record.
 */
private fun withoutPatchId(value: String): String =
    value.replace(AGENT_PATCH_ID_PATTERN, "")
        .replace(Regex("/{2,}"), "/")
        .removePrefix("/")

@Composable
internal fun AiToolCallRow(tool: AiToolCall, streaming: Boolean) {
    var expanded by rememberSaveable(tool.id) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val path = remember(tool.arguments) {
        val parsed = runCatching {
            Json.parseToJsonElement(tool.arguments).jsonObject["path"]?.jsonPrimitive?.content
        }.getOrNull()
        withoutPatchId(parsed ?: tool.arguments)
    }
    val status = stringResource(
        when {
            tool.failed -> R.string.ai_mod_editor_tool_failed
            tool.result != null -> R.string.ai_mod_editor_tool_completed
            streaming -> R.string.ai_mod_editor_tool_running
            else -> R.string.ai_mod_editor_tool_stopped
        },
    )
    val title = if (tool.name == "read_workspace_file") stringResource(R.string.ai_mod_editor_read_file) else tool.name
    val codeStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }
                .semantics { stateDescription = status }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(agentToolIcon(tool.name), null, Modifier.size(16.dp), tint = if (tool.failed) colors.error else colors.onSurfaceVariant)
            if (tool.result == null && streaming && !tool.failed) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp, color = colors.onSurfaceVariant)
            }
            Text(title, modifier = Modifier.widthIn(max = 160.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            Text(path, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            if (tool.failed || (tool.result == null && !streaming)) {
                Text(status, style = MaterialTheme.typography.labelSmall, color = if (tool.failed) colors.error else colors.onSurfaceVariant)
            }
            Icon(Icons.KeyboardArrowUp, null, Modifier.size(14.dp).rotate(if (expanded) 0f else 180f), tint = colors.onSurfaceVariant)
        }
        if (expanded) {
            Column(Modifier.padding(start = 24.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(status, color = if (tool.failed) colors.error else colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(withoutPatchId(tool.arguments), style = codeStyle, color = colors.onSurfaceVariant)
                        tool.result?.let { result ->
                            Text(result, style = codeStyle, color = colors.onSurface)
                        }
                    }
                }
                if (tool.truncated) Text(stringResource(R.string.ai_mod_editor_tool_truncated), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}
