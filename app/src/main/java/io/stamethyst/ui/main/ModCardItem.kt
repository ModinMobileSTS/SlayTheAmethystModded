package io.stamethyst.ui.main

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import io.stamethyst.R
import io.stamethyst.model.ModItemUi
import kotlin.math.max
import kotlin.math.roundToInt

internal data class ModCardDragStartInfo(
    val pointerWindow: Offset,
    val cardTopLeftWindow: Offset,
    val cardSizePx: IntSize
)

internal data class ModCardDragOverlayState(
    val mod: ModItemUi,
    val pointerWindow: Offset,
    val touchOffsetInsideCard: Offset,
    val cardSizePx: IntSize,
    val isExpanded: Boolean
)

@Composable
internal fun ModCard(
    mod: ModItemUi,
    isExpanded: Boolean,
    isDraggedInOverlay: Boolean,
    showModFileName: Boolean,
    setExpanded: (Boolean) -> Unit,
    controlsEnabled: Boolean,
    onToggleMod: (ModItemUi, Boolean) -> Unit,
    onDeleteMod: (ModItemUi) -> Unit,
    onExportMod: (ModItemUi) -> Unit,
    onShareMod: (ModItemUi) -> Unit,
    onRenameModFile: (ModItemUi, String) -> Unit,
    onDragStart: (ModCardDragStartInfo) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: (Offset) -> Unit,
    onDragCancel: () -> Unit
) {
    val context = LocalContext.current
    val resolvedName = resolveModDisplayName(mod, showModFileName = showModFileName)
    val resolvedModId = mod.manifestModId.ifBlank { mod.modId }
    val resolvedVersion = mod.version.ifBlank { stringResource(R.string.main_mod_unknown_version) }
    val resolvedDescription = mod.description.ifBlank { stringResource(R.string.main_mod_no_description) }
    val dependencies = mod.dependencies
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    var manageMenuExpanded by remember(mod.storagePath) { mutableStateOf(false) }
    var lastDragPointerWindow by remember(mod.storagePath) { mutableStateOf<Offset?>(null) }
    var handleCoordinates by remember(mod.storagePath) { mutableStateOf<LayoutCoordinates?>(null) }
    var cardCoordinates by remember(mod.storagePath) { mutableStateOf<LayoutCoordinates?>(null) }
    var showActionsDialog by remember(mod.storagePath) { mutableStateOf(false) }
    var showRenameDialog by remember(mod.storagePath) { mutableStateOf(false) }
    var renameInput by remember(mod.storagePath) {
        mutableStateOf(resolveModFileNameWithoutExtension(mod.storagePath))
    }
    val cardShape = RoundedCornerShape(10.dp)

    val handleModifier = Modifier
        .size(26.dp)
        .onGloballyPositioned { handleCoordinates = it }
        .pointerInput(mod.storagePath) {
            detectDragGestures(
                onDragStart = { start ->
                    val pointerPosition = handleCoordinates?.localToWindow(start)
                    val cardBounds = cardCoordinates?.boundsInWindow()
                    if (pointerPosition != null && cardBounds != null) {
                        lastDragPointerWindow = pointerPosition
                        onDragStart(
                            ModCardDragStartInfo(
                                pointerWindow = pointerPosition,
                                cardTopLeftWindow = cardBounds.topLeft,
                                cardSizePx = IntSize(
                                    width = max(1, cardBounds.width.roundToInt()),
                                    height = max(1, cardBounds.height.roundToInt())
                                )
                            )
                        )
                        onDragMove(pointerPosition)
                    } else {
                        onDragCancel()
                    }
                },
                onDrag = { change, _ ->
                    change.consume()
                    val position = handleCoordinates?.localToWindow(change.position)
                    if (position != null) {
                        lastDragPointerWindow = position
                        onDragMove(position)
                    }
                },
                onDragEnd = {
                    val position = lastDragPointerWindow
                    lastDragPointerWindow = null
                    if (position != null) {
                        onDragEnd(position)
                    } else {
                        onDragCancel()
                    }
                },
                onDragCancel = {
                    lastDragPointerWindow = null
                    onDragCancel()
                }
            )
        }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { cardCoordinates = it }
            .graphicsLayer {
                alpha = if (isDraggedInOverlay) 0f else 1f
            }
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clip(cardShape)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                cardShape
            )
            .background(
                if (mod.enabled) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                RoundedCornerShape(10.dp)
            )
            .clickable { setExpanded(!isExpanded) }
            .animateContentSize(
                animationSpec = tween(
                    durationMillis = MOVE_ANIMATION_MS,
                    easing = LinearEasing
                )
            )
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_image_mod),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = resolvedName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.main_mod_modid_format, resolvedModId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            if (controlsEnabled) {
                Checkbox(
                    checked = mod.enabled,
                    onCheckedChange = { checked ->
                        if (checked != mod.enabled) {
                            triggerToggleVibration(context)
                        }
                        onToggleMod(mod, checked)
                    }
                )
            } else {
                Checkbox(
                    checked = mod.enabled,
                    onCheckedChange = null,
                    enabled = false
                )
            }
            Box {
                IconButton(
                    onClick = { manageMenuExpanded = true },
                    enabled = controlsEnabled
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.main_folder_manage)
                    )
                }
                DropdownMenu(
                    expanded = manageMenuExpanded,
                    onDismissRequest = { manageMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.main_mod_delete)) },
                        onClick = {
                            manageMenuExpanded = false
                            onDeleteMod(mod)
                        }
                    )
                }
            }
            Box(
                modifier = handleModifier,
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_drag_handle),
                    contentDescription = stringResource(R.string.main_mod_drag),
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }

        Spacer(modifier = Modifier.width(2.dp))
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
        if (isExpanded) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = {
                        if (controlsEnabled) {
                            showActionsDialog = true
                        }
                    },
                    enabled = controlsEnabled
                ) {
                    Text(text = stringResource(R.string.main_mod_actions))
                }
            }
        }
    }

    if (showActionsDialog) {
        Dialog(
            onDismissRequest = { showActionsDialog = false },
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
                    Text(
                        text = stringResource(R.string.main_mod_actions_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    HorizontalDivider()
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ModActionDialogListItem(
                            text = stringResource(R.string.main_mod_export),
                            enabled = controlsEnabled
                        ) {
                            showActionsDialog = false
                            onExportMod(mod)
                        }
                        ModActionDialogListItem(
                            text = stringResource(R.string.main_mod_share),
                            enabled = controlsEnabled
                        ) {
                            showActionsDialog = false
                            onShareMod(mod)
                        }
                        if (showModFileName) {
                            ModActionDialogListItem(
                                text = stringResource(R.string.main_mod_rename),
                                enabled = controlsEnabled
                            ) {
                                showActionsDialog = false
                                renameInput = resolveModFileNameWithoutExtension(mod.storagePath)
                                showRenameDialog = true
                            }
                        }
                        ModActionDialogListItem(
                            text = stringResource(R.string.main_mod_delete),
                            enabled = controlsEnabled
                        ) {
                            showActionsDialog = false
                            onDeleteMod(mod)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        PillCancelButton(onClick = { showActionsDialog = false }) {
                            Text(stringResource(R.string.main_folder_dialog_cancel))
                        }
                    }
                }
            }
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(text = stringResource(R.string.main_mod_rename_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.main_mod_rename_hint)) },
                    enabled = controlsEnabled
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRenameDialog = false
                        onRenameModFile(mod, renameInput)
                    },
                    enabled = controlsEnabled
                ) {
                    Text(stringResource(R.string.main_folder_dialog_confirm))
                }
            },
            dismissButton = {
                PillCancelButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.main_folder_dialog_cancel))
                }
            }
        )
    }
}

@Composable
private fun ModActionDialogListItem(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(text = text) },
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
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
    )
}

@Composable
private fun PillCancelButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        content = { content() }
    )
}

@Composable
internal fun DraggingModCardOverlay(
    overlayState: ModCardDragOverlayState?,
    showModFileName: Boolean,
    overlayHostTopLeftWindow: Offset
) {
    if (overlayState == null) {
        return
    }

    val mod = overlayState.mod
    val resolvedName = resolveModDisplayName(mod, showModFileName = showModFileName)
    val resolvedModId = mod.manifestModId.ifBlank { mod.modId }
    val resolvedVersion = mod.version.ifBlank { stringResource(R.string.main_mod_unknown_version) }
    val resolvedDescription = mod.description.ifBlank { stringResource(R.string.main_mod_no_description) }
    val dependencies = mod.dependencies
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    val density = LocalDensity.current
    val cardShape = RoundedCornerShape(10.dp)
    val cardWidth = with(density) {
        max(1, overlayState.cardSizePx.width).toDp()
    }
    val cardLeft = overlayState.pointerWindow.x - overlayState.touchOffsetInsideCard.x
    val cardTop = overlayState.pointerWindow.y - overlayState.touchOffsetInsideCard.y
    val popupOffset = IntOffset(
        x = (cardLeft - overlayHostTopLeftWindow.x).roundToInt(),
        y = (cardTop - overlayHostTopLeftWindow.y).roundToInt()
    )

    Popup(
        alignment = Alignment.TopStart,
        offset = popupOffset,
        properties = PopupProperties(
            focusable = false,
            clippingEnabled = false
        )
    ) {
        Column(
            modifier = Modifier
                .width(cardWidth)
                .zIndex(DRAGGED_CARD_Z_INDEX + 20f)
                .graphicsLayer {
                    shadowElevation = 18.dp.toPx()
                }
                .clip(cardShape)
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.tertiary,
                    shape = cardShape
                )
                .background(
                    color = if (mod.enabled) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.68f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    shape = cardShape
                )
                .padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_image_mod),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = resolvedName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.main_mod_modid_format, resolvedModId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Checkbox(
                    checked = mod.enabled,
                    onCheckedChange = null,
                    enabled = false
                )
                Icon(
                    painter = painterResource(R.drawable.ic_drag_handle),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = stringResource(R.string.main_mod_version_format, resolvedVersion),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                text = resolvedDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = if (overlayState.isExpanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis
            )
            if (overlayState.isExpanded && dependencies.isNotEmpty()) {
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = stringResource(R.string.main_mod_dependencies_format, dependencies.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

private fun triggerToggleVibration(context: Context) {
    runCatching {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (!vibrator.hasVibrator()) {
            return
        }
        vibrator.vibrate(VibrationEffect.createOneShot(14L, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

private fun resolveModFileNameWithoutExtension(storagePath: String): String {
    val slash = maxOf(storagePath.lastIndexOf('/'), storagePath.lastIndexOf('\\'))
    val fileName = if (slash >= 0 && slash < storagePath.length - 1) {
        storagePath.substring(slash + 1)
    } else {
        storagePath
    }.trim()
    if (fileName.endsWith(".jar", ignoreCase = true) && fileName.length > 4) {
        return fileName.substring(0, fileName.length - 4)
    }
    return fileName
}
