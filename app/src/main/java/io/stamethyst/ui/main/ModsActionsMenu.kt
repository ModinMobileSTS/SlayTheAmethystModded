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
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.main_action_add_folder)) },
                    enabled = canEditFolders,
                    onClick = { dismiss(); onAddFolderClick() },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_folder_add), contentDescription = null) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.community_patches_view_all)) },
                    onClick = { dismiss(); onOpenCommunityPatches() },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_build), contentDescription = null) },
                )
                DropdownMenuItem(
                    text = {
                        Text(stringResource(if (checkingUpdates) R.string.main_workshop_checking else R.string.main_mod_check_updates))
                    },
                    enabled = canEditFolders && !checkingUpdates,
                    onClick = { dismiss(); onCheckWorkshopUpdates() },
                    leadingIcon = {
                        if (checkingUpdates) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null)
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.main_mod_filter_menu)) },
                    onClick = { filtersExpanded = true },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_filter_list), contentDescription = null) },
                    trailingIcon = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(if (dragLocked) R.string.main_action_unlock_drag else R.string.main_action_lock_drag)) },
                    enabled = canEditFolders,
                    onClick = { dismiss(); onToggleDragLocked() },
                    leadingIcon = { Icon(painterResource(if (dragLocked) R.drawable.ic_lock else R.drawable.ic_lock_open), contentDescription = null) },
                )
                if (selectedMods.isNotEmpty() && batchActions != null) {
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.main_mod_favorite_add)) },
                        enabled = batchActions.controlsEnabled && selectedMods.any { !it.favorite },
                        onClick = { dismiss(); batchActions.onFavorite(true) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_favorite_heart), contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.main_mod_favorite_remove)) },
                        enabled = batchActions.controlsEnabled && selectedMods.any { it.favorite },
                        onClick = { dismiss(); batchActions.onFavorite(false) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_favorite_heart), contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.main_mod_priority_adjust)) },
                        enabled = batchActions.controlsEnabled,
                        onClick = { dismiss(); batchActions.onEditPriority() },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_speed), contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.main_mod_associate)) },
                        enabled = batchActions.controlsEnabled && batchActions.canAssociate,
                        onClick = { dismiss(); batchActions.onAssociate() },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_link), contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.mod_remove_association)) },
                        enabled = batchActions.controlsEnabled && batchActions.associationPresent,
                        onClick = { dismiss(); batchActions.onRemoveAssociation() },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_link), contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.main_mod_export)) },
                        enabled = batchActions.controlsEnabled,
                        onClick = { dismiss(); batchActions.onExport() },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_workshop_download), contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.main_mod_share)) },
                        enabled = batchActions.controlsEnabled,
                        onClick = { dismiss(); batchActions.onShare() },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_lan_room_share), contentDescription = null) },
                    )
                    HorizontalDivider()
                    ModDeleteMenuItem(batchActions.controlsEnabled) { dismiss(); batchActions.onDelete() }
                }
                if (feedbackUnreadCount > 0) {
                    HorizontalDivider()
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
            }
        }
    }
}

@Composable
internal fun ModDeleteMenuItem(enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.main_mod_delete), color = MaterialTheme.colorScheme.error) },
        enabled = enabled,
        onClick = onClick,
        leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null,
            tint = MaterialTheme.colorScheme.error) },
    )
}
