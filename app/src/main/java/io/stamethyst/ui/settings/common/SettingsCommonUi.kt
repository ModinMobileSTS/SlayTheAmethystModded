package io.stamethyst.ui.settings.common

import io.stamethyst.ui.settings.baidu.*
import io.stamethyst.ui.settings.core.*
import io.stamethyst.ui.settings.files.*
import io.stamethyst.ui.settings.first_run.*
import io.stamethyst.ui.settings.mobileglues.*
import io.stamethyst.ui.settings.native_library.*
import io.stamethyst.ui.settings.sections.*
import io.stamethyst.ui.settings.services.*
import io.stamethyst.ui.settings.steamcloud.*

import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.ui.haptics.LauncherHaptics
import io.stamethyst.ui.resolve


internal data class SettingsSwitchSpec(
    val checked: Boolean,
    val enabled: Boolean,
    val title: String,
    val description: String? = null,
    val chipText: String? = null,
    val onCheckedChange: (Boolean) -> Unit,
)


internal data class SettingsChoiceSpec<T>(
    val title: String,
    val valueText: String,
    val enabled: Boolean,
    val selectedValue: T,
    val options: List<T>,
    val optionLabel: @Composable (T) -> String,
    val onOptionSelected: (T) -> Unit,
    val description: String? = null,
    val dialogTitle: String = title,
    val dialogDescription: String? = description,
    val optionEnabled: (T) -> Boolean = { true },
    val optionDescription: (@Composable (T) -> String?)? = null,
)


@Composable
internal fun SettingsBusyIndicator(
    uiState: SettingsScreenViewModel.UiState
) {
    if (!uiState.busy || uiState.busyOperation.usesBlockingOverlay()) {
        return
    }
    val progressFraction = uiState.busyProgressPercent
        ?.coerceIn(0, 100)
        ?.div(100f)
    if (progressFraction != null) {
        val animatedProgress by animateFloatAsState(
            targetValue = progressFraction,
            animationSpec = tween(durationMillis = 360),
            label = "settings_busy_progress"
        )
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    uiState.busyMessage?.let {
        Text(text = it.resolve(), style = MaterialTheme.typography.bodyMedium)
    }
}


@Composable
internal fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    SettingsSectionCard(title = title, iconResId = null, trailingAction = null, content = content)
}


@Composable
internal fun SettingsSectionCard(
    title: String,
    @DrawableRes iconResId: Int?,
    content: @Composable () -> Unit,
) {
    SettingsSectionCard(title = title, iconResId = iconResId, trailingAction = null, content = content)
}


@Composable
internal fun SettingsSectionCard(
    title: String,
    trailingAction: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    SettingsSectionCard(title = title, iconResId = null, trailingAction = trailingAction, content = content)
}


@Composable
internal fun SettingsSectionCard(
    title: String,
    @DrawableRes iconResId: Int? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (trailingAction != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (iconResId != null) {
                        Icon(
                            painter = painterResource(iconResId),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    trailingAction()
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (iconResId != null) {
                        Icon(
                            painter = painterResource(iconResId),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                }
            }
            HorizontalDivider()
            content()
        }
    }
}


@Composable
internal fun SettingsSwitchItem(spec: SettingsSwitchSpec) {
    SwitchSettingRow(
        checked = spec.checked,
        enabled = spec.enabled,
        title = spec.title,
        description = spec.description,
        onCheckedChange = spec.onCheckedChange,
        chipText = spec.chipText,
    )
}


@Composable
internal fun <T> SettingsChoiceDialogItem(spec: SettingsChoiceSpec<T>) {
    var showDialog by rememberSaveable(spec.title) { mutableStateOf(false) }

    SettingsActionListItem(
        title = spec.title,
        supportingText = spec.valueText,
        enabled = spec.enabled,
        onClick = { showDialog = true }
    )
    spec.description?.let { description ->
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(spec.dialogTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    spec.options.forEach { option ->
                        val optionIsEnabled = spec.enabled && spec.optionEnabled(option)
                        SettingsRadioOptionRow(
                            selected = spec.selectedValue == option,
                            enabled = optionIsEnabled,
                            text = spec.optionLabel(option),
                            onSelect = {
                                spec.onOptionSelected(option)
                                showDialog = false
                            }
                        )
                        spec.optionDescription?.invoke(option)?.let { description ->
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 48.dp)
                            )
                        }
                    }
                    spec.dialogDescription?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                HapticTextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.main_folder_dialog_confirm))
                }
            }
        )
    }
}


@Composable
internal fun SwitchSettingRow(
    checked: Boolean,
    enabled: Boolean,
    title: String,
    description: String?,
    onCheckedChange: (Boolean) -> Unit,
    chipText: String? = null,
) {
    val view = LocalView.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = { changed ->
                onCheckedChange(changed)
                performTapHapticFeedback(view)
            }
        )
        Spacer(modifier = Modifier.width(10.dp))
        if (chipText == null) {
            Text(text = title)
        } else {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f, fill = false)
                )
                SettingsMetadataChip(text = chipText)
            }
        }
    }
    description?.let { value ->
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall
        )
    }
}


@Composable
internal fun SettingsMetadataChip(text: String) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}


@Composable
internal fun SettingsActionListItem(
    title: String,
    supportingText: String? = null,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(text = title)
        },
        supportingContent = supportingText?.let { value ->
            {
                Text(
                    text = value,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        trailingContent = {
            Text(
                text = ">",
                style = MaterialTheme.typography.titleMedium
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(12.dp), clip = false)
            .clip(RoundedCornerShape(12.dp))
            .hapticClickable(
                enabled = enabled,
                onClick = onClick
            )
    )
}


@Composable
internal fun SettingsDangerActionListItem(
    title: String,
    supportingText: String? = null,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.error,
            )
        },
        supportingContent = supportingText?.let { value ->
            {
                Text(
                    text = value,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        trailingContent = {
            Text(
                text = ">",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(12.dp), clip = false)
            .clip(RoundedCornerShape(12.dp))
            .hapticClickable(
                enabled = enabled,
                onClick = onClick
            )
    )
}


@Composable
internal fun SettingsRadioOptionRow(
    selected: Boolean,
    enabled: Boolean,
    text: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hapticToggleable(
                value = selected,
                enabled = enabled,
                onValueChange = { onSelect() }
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            modifier = Modifier.weight(1f)
        )
    }
}


@Composable
internal fun <T> SettingsDropdownField(
    label: String,
    valueText: String,
    enabled: Boolean,
    supportingText: String? = null,
    supportingTextColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    options: List<T>,
    optionEnabled: (T) -> Boolean = { true },
    optionLabel: @Composable (T) -> String,
    optionDescription: (@Composable (T) -> String?)? = null,
    onOptionSelected: (T) -> Unit,
) {
    var showDialog by rememberSaveable(label) { mutableStateOf(false) }

    SettingsActionListItem(
        title = label,
        supportingText = valueText,
        enabled = enabled,
        onClick = { showDialog = true }
    )
    supportingText?.let {
        Text(
            text = it,
            color = supportingTextColor,
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { option ->
                        val optionIsEnabled = enabled && optionEnabled(option)
                        SettingsRadioOptionRow(
                            selected = valueText == optionLabel(option),
                            enabled = optionIsEnabled,
                            text = optionLabel(option),
                            onSelect = {
                                onOptionSelected(option)
                                showDialog = false
                            }
                        )
                        optionDescription?.invoke(option)?.let { description ->
                            Text(
                                text = description,
                                color = if (optionIsEnabled) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 48.dp)
                            )
                        }
                    }
                    supportingText?.let { description ->
                        Text(
                            text = description,
                            color = supportingTextColor,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                HapticTextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.main_folder_dialog_confirm))
                }
            }
        )
    }
}


@Composable
internal fun HapticIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    IconButton(
        onClick = {
            performTapHapticFeedback(view)
            onClick()
        },
        enabled = enabled,
        content = content
    )
}


@Composable
internal fun HapticTextButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val view = LocalView.current
    TextButton(
        onClick = {
            performTapHapticFeedback(view)
            onClick()
        },
        enabled = enabled,
        content = content
    )
}


internal fun Modifier.hapticClickable(
    enabled: Boolean,
    onClick: () -> Unit,
): Modifier = composed {
    val view = LocalView.current
    clickable(
        enabled = enabled,
        onClick = {
            performTapHapticFeedback(view)
            onClick()
        }
    )
}


internal fun Modifier.hapticToggleable(
    value: Boolean,
    enabled: Boolean,
    onValueChange: (Boolean) -> Unit,
): Modifier = composed {
    val view = LocalView.current
    toggleable(
        value = value,
        enabled = enabled,
        onValueChange = { changed ->
            performTapHapticFeedback(view)
            onValueChange(changed)
        }
    )
}


internal fun performTapHapticFeedback(view: android.view.View) {
    performHapticFeedback(view, HapticFeedbackConstants.KEYBOARD_TAP)
}


internal fun performHapticFeedback(view: android.view.View, feedbackConstant: Int) {
    LauncherHaptics.perform(view, feedbackConstant)
}
