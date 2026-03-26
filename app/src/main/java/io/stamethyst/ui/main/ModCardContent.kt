package io.stamethyst.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.model.ModItemUi

@Composable
internal fun ModCardBodyContent(
    mod: ModItemUi,
    isExpanded: Boolean,
    showModFileName: Boolean,
    showActionsButton: Boolean,
    actionsEnabled: Boolean,
    onActionsClick: () -> Unit,
    modSuggestionText: String? = null,
    suggestionBadgeEnabled: Boolean = true,
    onSuggestionClick: () -> Unit = {},
    headerTrailing: @Composable RowScope.() -> Unit
) {
    val resolvedName = resolveModDisplayName(mod, showModFileName = showModFileName)
    val resolvedModId = mod.manifestModId.ifBlank { mod.modId }
    val resolvedVersion = mod.version.ifBlank { stringResource(R.string.main_mod_unknown_version) }
    val resolvedDescription = mod.description.ifBlank { stringResource(R.string.main_mod_no_description) }
    val dependencies = mod.dependencies
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(R.drawable.ic_image_mod),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = resolvedName,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!modSuggestionText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    ModSuggestionIcon(
                        enabled = suggestionBadgeEnabled,
                        onClick = onSuggestionClick
                    )
                }
                if (mod.priorityLoad) {
                    Spacer(modifier = Modifier.width(6.dp))
                    PriorityLoadBadge()
                }
            }
            Text(
                text = stringResource(R.string.main_mod_modid_format, resolvedModId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        headerTrailing()
    }

    Spacer(modifier = Modifier.height(2.dp))
    Text(
        text = stringResource(R.string.main_mod_version_format, resolvedVersion),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    Text(
        text = resolvedDescription,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary,
        maxLines = if (isExpanded) Int.MAX_VALUE else 2,
        overflow = TextOverflow.Ellipsis
    )
    if (isExpanded && dependencies.isNotEmpty()) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.main_mod_dependencies_format, dependencies.joinToString(", ")),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
    if (showActionsButton && isExpanded) {
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(
                onClick = onActionsClick,
                enabled = actionsEnabled
            ) {
                Text(text = stringResource(R.string.main_mod_actions))
            }
        }
    }
}

@Composable
private fun ModSuggestionIcon(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Icon(
        painter = painterResource(R.drawable.ic_error_outline),
        contentDescription = null,
        tint = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline
        },
        modifier = Modifier
            .size(18.dp)
            .clickable(enabled = enabled, onClick = onClick)
    )
}

@Composable
private fun PriorityLoadBadge() {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = stringResource(R.string.main_mod_priority_badge),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
