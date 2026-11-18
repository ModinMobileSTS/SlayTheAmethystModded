package io.stamethyst.ui.settings.llm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.llm.LlmReasoningEffort

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LlmReasoningOptions(
    options: List<LlmReasoningEffort>,
    onChange: (List<LlmReasoningEffort>) -> Unit,
) {
    val selected = LlmReasoningEffort.normalize(options)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ai_editor_available_efforts), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.ai_editor_efforts_description), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LlmReasoningEffort.selectable.forEach { option ->
                val checked = option in selected
                FilterChip(
                    selected = checked,
                    onClick = { if (!checked || selected.size > 1) onChange(
                        LlmReasoningEffort.normalize(if (checked) selected - option else selected + option),
                    ) },
                    label = { Text(option.name.lowercase()) },
                    leadingIcon = if (checked) ({ Icon(painterResource(R.drawable.ic_check_circle), null, Modifier.size(16.dp)) }) else null,
                    modifier = Modifier.testTag("ai-reasoning-option-${option.name.lowercase()}"),
                )
            }
        }
        Text(stringResource(R.string.ai_editor_efforts_warning), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
