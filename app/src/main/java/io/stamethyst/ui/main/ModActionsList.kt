package io.stamethyst.ui.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.ui.LauncherAlertDialog

/** All tools share a single-column list; information and AI management have their own pages. */
@Composable
internal fun ModActionsList(
    controlsEnabled: Boolean,
    favorite: Boolean,
    deleteEnabled: Boolean,
    onFavoriteChange: (Boolean) -> Unit,
    onEditPriority: () -> Unit,
    showOpenWorkshopDetails: Boolean,
    onOpenWorkshopDetails: () -> Unit,
    showAiEditorNewBadge: Boolean,
    onOpenAiEditor: () -> Unit,
    onAssociate: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    showMove: Boolean = false,
    moveEnabled: Boolean = controlsEnabled,
    onMove: () -> Unit = {},
    onCheckUpdates: (() -> Unit)? = null,
    onCheckCommunity: (() -> Unit)? = null,
    associationPresent: Boolean = false,
    onRemoveAssociation: () -> Unit = {},
) {
    var showBindingOptions by remember(associationPresent) { mutableStateOf(false) }
    if (showBindingOptions && associationPresent) {
        LauncherAlertDialog(
            onDismissRequest = { showBindingOptions = false },
            title = { Text(stringResource(R.string.mod_binding_options_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModActionListItem(
                        R.string.mod_remove_association, R.string.mod_remove_association_description,
                        R.drawable.ic_link, controlsEnabled,
                        onClick = { showBindingOptions = false; onRemoveAssociation() },
                    )
                    ModActionListItem(
                        R.string.mod_bind_more, R.string.main_mod_action_desc_associate,
                        R.drawable.ic_link, controlsEnabled,
                        onClick = { showBindingOptions = false; onAssociate() },
                    )
                }
            },
            confirmButton = {
                PillCancelButton(onClick = { showBindingOptions = false }) {
                    Text(stringResource(R.string.main_folder_dialog_cancel))
                }
            },
        )
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ModActionListItem(
            R.string.main_mod_priority_adjust, R.string.main_mod_action_desc_priority,
            R.drawable.ic_speed, controlsEnabled, onClick = onEditPriority,
        )
        ModActionListItem(
            R.string.main_mod_associate, R.string.main_mod_action_desc_associate,
            R.drawable.ic_link, controlsEnabled,
            onClick = { if (associationPresent) showBindingOptions = true else onAssociate() },
        )
        ModActionListItem(
            R.string.main_mod_ai_edit, R.string.main_mod_action_desc_ai_edit,
            R.drawable.ic_code, controlsEnabled,
            badge = if (showAiEditorNewBadge) stringResource(R.string.main_mod_ai_edit_new_badge) else null,
            onClick = onOpenAiEditor,
        )
        ModActionListItem(
            R.string.main_mod_export, R.string.main_mod_action_desc_export,
            R.drawable.ic_workshop_download, controlsEnabled, onClick = onExport,
        )
        ModActionListItem(
            R.string.main_mod_share, R.string.main_mod_action_desc_share,
            R.drawable.ic_lan_room_share, controlsEnabled, onClick = onShare,
        )
        ModActionListItem(
            R.string.main_mod_rename, R.string.main_mod_action_desc_rename,
            R.drawable.ic_edit, controlsEnabled, onClick = onRename,
        )
        ModActionListItem(
            R.string.main_mod_delete, R.string.main_mod_action_desc_delete,
            R.drawable.ic_delete, deleteEnabled, danger = true, onClick = onDelete,
        )
    }
}

@Composable
private fun ModActionListItem(
    @StringRes text: Int,
    @StringRes description: Int,
    @DrawableRes icon: Int,
    enabled: Boolean,
    danger: Boolean = false,
    badge: String? = null,
    onClick: () -> Unit,
) {
    ModActionOptionItem(
        text = stringResource(text), description = stringResource(description), icon = icon,
        enabled = enabled, danger = danger, badge = badge, onClick = onClick,
    )
}
