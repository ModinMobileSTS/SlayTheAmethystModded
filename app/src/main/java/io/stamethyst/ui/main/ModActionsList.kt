package io.stamethyst.ui.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.Layout
import io.stamethyst.R

/** All tools share a single two-column grid; information and AI management have their own pages. */
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
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ModActionGrid {
            onCheckUpdates?.let { check ->
                ModActionListItem(R.string.mod_check_updates_single, R.string.mod_check_updates_single,
                    R.drawable.ic_refresh, controlsEnabled, onClick = check)
            }
            onCheckCommunity?.let { check ->
                ModActionListItem(R.string.mod_check_community_single, R.string.mod_check_community_single,
                    R.drawable.ic_build, controlsEnabled, onClick = check)
            }
            ModActionListItem(
                if (favorite) R.string.main_mod_favorite_remove else R.string.main_mod_favorite_add,
                if (favorite) R.string.main_mod_action_desc_favorite_remove else R.string.main_mod_action_desc_favorite_add,
                R.drawable.ic_favorite_heart, controlsEnabled,
                onClick = { onFavoriteChange(!favorite) },
            )
            ModActionListItem(
                R.string.main_mod_priority_adjust, R.string.main_mod_action_desc_priority,
                R.drawable.ic_speed, controlsEnabled, onClick = onEditPriority,
            )
            ModActionListItem(
                R.string.main_mod_associate, R.string.main_mod_action_desc_associate,
                R.drawable.ic_link, controlsEnabled, onClick = onAssociate,
            )
            if (showMove) {
                ModActionListItem(
                    R.string.mod_details_move, R.string.mod_details_move_description,
                    R.drawable.ic_move_folder, moveEnabled, onClick = onMove,
                )
            }
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
}

@Composable
private fun ModActionGrid(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Layout(content = { ColumnScopeContent(content) }) { measurables, constraints ->
            val gap = 8.dp.roundToPx()
            val cellWidth = ((constraints.maxWidth - gap) / 2).coerceAtLeast(0)
            val cells = measurables.map {
                it.measure(constraints.copy(minWidth = cellWidth, maxWidth = cellWidth, minHeight = 0))
            }
            val heights = cells.chunked(2).map { row -> row.maxOf { it.height } }
            layout(constraints.maxWidth, heights.sum() + gap * (heights.size - 1).coerceAtLeast(0)) {
                var y = 0
                cells.chunked(2).forEachIndexed { rowIndex, row ->
                    row.forEachIndexed { column, cell -> cell.placeRelative(column * (cellWidth + gap), y) }
                    y += heights[rowIndex] + gap
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ColumnScopeContent(content: @Composable ColumnScope.() -> Unit) {
    content()
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
