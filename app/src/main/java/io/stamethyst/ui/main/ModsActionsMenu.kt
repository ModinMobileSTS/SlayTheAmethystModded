package io.stamethyst.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.model.ModItemUi
import androidx.compose.ui.platform.LocalContext
import io.stamethyst.backend.workshop.WorkshopUpdateCheckCoordinator

@Composable
internal fun ModsActionsMenu(
    canEditFolders: Boolean,
    dragLocked: Boolean,
    showEnabledModsOnly: Boolean,
    showUpdateAvailableModsOnly: Boolean,
    feedbackUnreadCount: Int,
    checkingUpdates: Boolean,
    onToggleDragLocked: () -> Unit,
    onShowEnabledModsOnlyChange: (Boolean) -> Unit,
    onShowUpdateAvailableModsOnlyChange: (Boolean) -> Unit,
    onAddFolderClick: () -> Unit,
    onCheckWorkshopUpdates: () -> Unit,
    onOpenFeedbackUpdates: () -> Unit,
    onOpenCommunityPatches: () -> Unit = {},
    selectedMods: List<ModItemUi> = emptyList(),
    batchActions: BatchEditBarState? = null,
) {
    val context = LocalContext.current
    var showCommunityCheck by remember { mutableStateOf(false) }
    if (showCommunityCheck) {
        ScopedCommunityCheckDialog(selectedMods.takeIf { it.isNotEmpty() }) { showCommunityCheck = false }
    }
    var expanded by remember { mutableStateOf(false) }
    var filtersExpanded by remember { mutableStateOf(false) }
    val hasActiveFilter = showEnabledModsOnly || showUpdateAvailableModsOnly
    val dismiss = {
        expanded = false
        filtersExpanded = false
    }
    Box {
        IconButton(
            onClick = {
                filtersExpanded = false
                expanded = true
            },
            modifier = Modifier.size(48.dp),
        ) {
            NotificationBadge(
                count = feedbackUnreadCount,
                badgeShape = RoundedCornerShape(999.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.main_mod_actions_menu),
                    tint = if (hasActiveFilter) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = dismiss) {
            if (filtersExpanded) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.main_mod_filter_menu)) },
                    onClick = { filtersExpanded = false },
                    leadingIcon = {
                        Icon(
                            painterResource(R.drawable.ic_chevron_right),
                            contentDescription = stringResource(R.string.common_content_desc_back),
                            modifier = Modifier.graphicsLayer { rotationZ = 180f },
                        )
                    },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.main_mod_filter_update_available)) },
                    onClick = { onShowUpdateAvailableModsOnlyChange(!showUpdateAvailableModsOnly) },
                    leadingIcon = { Checkbox(checked = showUpdateAvailableModsOnly, onCheckedChange = null) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.main_mod_filter_enabled)) },
                    onClick = { onShowEnabledModsOnlyChange(!showEnabledModsOnly) },
                    leadingIcon = { Checkbox(checked = showEnabledModsOnly, onCheckedChange = null) },
                )
            } else {
                if (selectedMods.isNotEmpty() && batchActions != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.main_mod_favorite_add)) },
                        enabled = canEditFolders,
                        onClick = { dismiss(); batchActions.onFavorite(true) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.main_mod_favorite_remove)) },
                        enabled = canEditFolders,
                        onClick = { dismiss(); batchActions.onFavorite(false) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.main_mod_priority_dialog_clear)) },
                        enabled = canEditFolders,
                        onClick = { dismiss(); batchActions.onResetPriority() },
                    )
                    HorizontalDivider()
                }
                DropdownMenuItem(
                    text = { Text(stringResource(if (selectedMods.isEmpty()) R.string.mod_check_community_all else R.string.mod_check_community_selected)) },
                    onClick = {
                        dismiss()
                        showCommunityCheck = true
                    },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_build), contentDescription = null) },
                )
                if (feedbackUnreadCount > 0) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.main_feedback_updates_content_description)) },
                        onClick = {
                            dismiss()
                            onOpenFeedbackUpdates()
                        },
                        leadingIcon = {
                            NotificationBadge(count = feedbackUnreadCount, badgeShape = RoundedCornerShape(999.dp)) {
                                Icon(painterResource(R.drawable.ic_feedback_updates), contentDescription = null)
                            }
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.main_mod_filter_menu)) },
                    onClick = { filtersExpanded = true },
                    leadingIcon = {
                        Icon(
                            painterResource(R.drawable.ic_filter_list),
                            contentDescription = null,
                            tint = if (hasActiveFilter) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingIcon = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(if (dragLocked) R.string.main_action_unlock_drag else R.string.main_action_lock_drag)) },
                    enabled = canEditFolders,
                    onClick = {
                        dismiss()
                        onToggleDragLocked()
                    },
                    leadingIcon = {
                        Icon(painterResource(if (dragLocked) R.drawable.ic_lock else R.drawable.ic_lock_open), contentDescription = null)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.main_action_add_folder)) },
                    enabled = canEditFolders,
                    onClick = {
                        dismiss()
                        onAddFolderClick()
                    },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_folder_add), contentDescription = null) },
                )
            }
        }
    }
}
