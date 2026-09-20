package io.stamethyst.ui.aimod

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.stamethyst.R
import java.text.NumberFormat
import java.util.Locale

/** One tool exposed to the agent, shown in the context dialog. */
internal data class AiToolInfo(
    val name: String,
    val descriptionRes: Int,
)

/**
 * Localized one-line description for an agent tool, keyed by its stable tool name. Unknown tools
 * fall back to the generic "no description" string.
 */
internal fun agentToolDescriptionRes(name: String): Int = when (name) {
    "list_agent_workspace" -> R.string.ai_mod_editor_tool_desc_list_agent_workspace
    "read_agent_workspace_file" -> R.string.ai_mod_editor_tool_desc_read_agent_workspace_file
    "create_agent_patch_mod" -> R.string.ai_mod_editor_tool_desc_create_agent_patch_mod
    "write_agent_workspace_file" -> R.string.ai_mod_editor_tool_desc_write_agent_workspace_file
    "delete_agent_workspace_file" -> R.string.ai_mod_editor_tool_desc_delete_agent_workspace_file
    "decompile_agent_mod_source" -> R.string.ai_mod_editor_tool_desc_decompile_agent_mod_source
    "compile_agent_patch_source" -> R.string.ai_mod_editor_tool_desc_compile_agent_patch_source
    "package_agent_patch_mod" -> R.string.ai_mod_editor_tool_desc_package_agent_patch_mod
    "update_agent_patch_mod" -> R.string.ai_mod_editor_tool_desc_update_agent_patch_mod
    "set_agent_patch_mod_enabled" -> R.string.ai_mod_editor_tool_desc_set_agent_patch_mod_enabled
    "list_agent_patch_mods" -> R.string.ai_mod_editor_tool_desc_list_agent_patch_mods
    "delete_agent_patch_mod" -> R.string.ai_mod_editor_tool_desc_delete_agent_patch_mod
    "smoke_test_agent_patch_mod" -> R.string.ai_mod_editor_tool_desc_smoke_test_agent_patch_mod
    "read_agent_skill" -> R.string.ai_mod_editor_tool_desc_read_agent_skill
    "search_agent_api" -> R.string.ai_mod_editor_tool_desc_search_agent_api
    "describe_agent_api_class" -> R.string.ai_mod_editor_tool_desc_describe_agent_api_class
    else -> R.string.ai_mod_editor_tool_desc_unknown
}

@Composable
internal fun AiContextCounter(
    tokens: Int?,
    modelKey: String,
    busy: Boolean,
    tools: List<AiToolInfo> = emptyList(),
) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("ai_context_limits", Context.MODE_PRIVATE) }
    var limit by remember(modelKey) { mutableStateOf(preferences.getInt(modelKey, 0)) }
    var showDetails by rememberSaveable(modelKey) { mutableStateOf(false) }
    var limitDraft by rememberSaveable(modelKey) { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    val progress = if (tokens != null && limit > 0) (tokens.toFloat() / limit).coerceIn(0f, 1f) else 0f
    val usageLabel = stringResource(R.string.ai_mod_editor_context)
    val countLabel = tokens?.let { NumberFormat.getIntegerInstance().format(it) }
        ?: stringResource(R.string.ai_mod_editor_context_unknown)
    val compactCount = when {
        tokens == null -> "--"
        tokens >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", tokens / 1_000_000.0)
        tokens >= 1_000 -> String.format(Locale.ROOT, "%.1fk", tokens / 1_000.0)
        else -> tokens.toString()
    }
    IconButton(
        onClick = {
            limitDraft = limit.takeIf { it > 0 }?.toString().orEmpty()
            showDetails = true
        },
        modifier = Modifier.size(48.dp).semantics { contentDescription = "$usageLabel: $countLabel" },
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(34.dp),
                strokeWidth = 3.dp,
                color = if (progress >= 0.9f) colors.error else colors.primary,
                trackColor = colors.outlineVariant.copy(alpha = 0.6f),
            )
            Text(compactCount, fontSize = 9.sp, letterSpacing = 0.sp, color = colors.onSurfaceVariant, maxLines = 1)
        }
    }
    if (showDetails) {
        val validLimit = limitDraft.isBlank() || (limitDraft.toIntOrNull() ?: 0) > 0
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text(usageLabel) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.ai_mod_editor_context_count, countLabel))
                    if (tokens != null && limit > 0) {
                        Text(stringResource(R.string.ai_mod_editor_context_fraction, tokens.toLong() * 100 / limit, NumberFormat.getIntegerInstance().format(limit)))
                    }
                    Text(
                        stringResource(if (busy) R.string.ai_mod_editor_context_updating else R.string.ai_mod_editor_context_source),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = limitDraft,
                        onValueChange = { limitDraft = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.ai_mod_editor_context_limit)) },
                        supportingText = { Text(stringResource(R.string.ai_mod_editor_context_limit_hint)) },
                        singleLine = true,
                        isError = !validLimit,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    Text(
                        stringResource(R.string.ai_mod_editor_tools_available, tools.size),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (tools.isEmpty()) {
                        Text(
                            stringResource(R.string.ai_mod_editor_tools_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    } else {
                        tools.forEach { tool ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    tool.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    color = colors.onSurface,
                                )
                                Text(
                                    stringResource(tool.descriptionRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = validLimit, onClick = {
                    limit = limitDraft.toIntOrNull() ?: 0
                    preferences.edit().putInt(modelKey, limit).apply()
                    showDetails = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDetails = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}
