package io.stamethyst.ui.main

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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

internal data class ModCardCallbacks(
    val onToggleMod: (ModItemUi, Boolean) -> Unit = { _, _ -> },
    val onTogglePriorityLoad: (ModItemUi, Boolean) -> Unit = { _, _ -> },
    val onDeleteMod: (ModItemUi) -> Unit = {},
    val onExportMod: (ModItemUi) -> Unit = {},
    val onShareMod: (ModItemUi) -> Unit = {},
    val onRenameModFile: (ModItemUi, String) -> Unit = { _, _ -> },
    val onDragStart: (ModCardDragStartInfo) -> Unit = {},
    val onDragMove: (Offset) -> Unit = {},
    val onDragEnd: (Offset) -> Unit = {},
    val onDragCancel: () -> Unit = {}
)

@Composable
internal fun ModCard(
    mod: ModItemUi,
    isExpanded: Boolean,
    isDraggedInOverlay: Boolean,
    showModFileName: Boolean,
    setExpanded: (Boolean) -> Unit,
    selectionEnabled: Boolean,
    fileActionsEnabled: Boolean,
    dragEnabled: Boolean,
    callbacks: ModCardCallbacks
) {
    val context = LocalContext.current
    var lastDragPointerWindow by remember(mod.storagePath) { mutableStateOf<Offset?>(null) }
    var handleCoordinates by remember(mod.storagePath) { mutableStateOf<LayoutCoordinates?>(null) }
    var cardCoordinates by remember(mod.storagePath) { mutableStateOf<LayoutCoordinates?>(null) }
    var showActionsDialog by remember(mod.storagePath) { mutableStateOf(false) }
    var showRenameDialog by remember(mod.storagePath) { mutableStateOf(false) }
    var renameInput by remember(mod.storagePath) {
        mutableStateOf(resolveModFileNameWithoutJar(mod.storagePath).orEmpty())
    }
    val cardShape = RoundedCornerShape(10.dp)
    val renameRequiresFileNameMessage =
        stringResource(R.string.main_mod_rename_requires_file_name_display)

    val dragHandleGestureModifier = Modifier
        .onGloballyPositioned { handleCoordinates = it }
        .pointerInput(mod.storagePath, dragEnabled) {
            if (!dragEnabled) {
                return@pointerInput
            }
            detectDragGestures(
                onDragStart = { start ->
                    val pointerPosition = handleCoordinates?.localToWindow(start)
                    val cardBounds = cardCoordinates?.boundsInWindow()
                    if (pointerPosition != null && cardBounds != null) {
                        lastDragPointerWindow = pointerPosition
                        callbacks.onDragStart(
                            ModCardDragStartInfo(
                                pointerWindow = pointerPosition,
                                cardTopLeftWindow = cardBounds.topLeft,
                                cardSizePx = IntSize(
                                    width = max(1, cardBounds.width.roundToInt()),
                                    height = max(1, cardBounds.height.roundToInt())
                                )
                            )
                        )
                        callbacks.onDragMove(pointerPosition)
                    } else {
                        callbacks.onDragCancel()
                    }
                },
                onDrag = { change, _ ->
                    change.consume()
                    val position = handleCoordinates?.localToWindow(change.position)
                    if (position != null) {
                        lastDragPointerWindow = position
                        callbacks.onDragMove(position)
                    }
                },
                onDragEnd = {
                    val position = lastDragPointerWindow
                    lastDragPointerWindow = null
                    if (position != null) {
                        callbacks.onDragEnd(position)
                    } else {
                        callbacks.onDragCancel()
                    }
                },
                onDragCancel = {
                    lastDragPointerWindow = null
                    callbacks.onDragCancel()
                }
            )
        }
    val handleModifier = dragHandleGestureModifier.size(26.dp)

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
                cardShape
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
        ModCardBodyContent(
            mod = mod,
            isExpanded = isExpanded,
            showModFileName = showModFileName,
            showActionsButton = true,
            actionsEnabled = fileActionsEnabled,
            onActionsClick = {
                if (fileActionsEnabled) {
                    showActionsDialog = true
                }
            },
            headerTrailing = {
                if (selectionEnabled) {
                    Checkbox(
                        checked = mod.enabled,
                        onCheckedChange = { checked ->
                            if (checked != mod.enabled) {
                                triggerToggleVibration(context)
                            }
                            callbacks.onToggleMod(mod, checked)
                        }
                    )
                } else {
                    Checkbox(
                        checked = mod.enabled,
                        onCheckedChange = null,
                        enabled = false
                    )
                }
                Box(
                    modifier = handleModifier,
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_drag_handle),
                        contentDescription = stringResource(R.string.main_mod_drag),
                        tint = if (dragEnabled) {
                            MaterialTheme.colorScheme.outline
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)
                        }
                    )
                }
            }
        )
    }

    ModActionsDialog(
        visible = showActionsDialog,
        controlsEnabled = fileActionsEnabled,
        priorityLoad = mod.priorityLoad,
        onDismiss = { showActionsDialog = false },
        onTogglePriorityLoad = { callbacks.onTogglePriorityLoad(mod, !mod.priorityLoad) },
        onExport = { callbacks.onExportMod(mod) },
        onShare = { callbacks.onShareMod(mod) },
        onRename = {
            if (showModFileName) {
                renameInput = resolveModFileNameWithoutJar(mod.storagePath).orEmpty()
                showRenameDialog = true
            } else {
                Toast.makeText(
                    context,
                    renameRequiresFileNameMessage,
                    Toast.LENGTH_SHORT
                ).show()
            }
        },
        onDelete = { callbacks.onDeleteMod(mod) }
    )

    RenameModFileDialog(
        visible = showRenameDialog,
        value = renameInput,
        controlsEnabled = fileActionsEnabled,
        onValueChange = { renameInput = it },
        onDismiss = { showRenameDialog = false },
        onConfirm = {
            showRenameDialog = false
            callbacks.onRenameModFile(mod, renameInput)
        }
    )
}

@Composable
internal fun DraggingModCardOverlayLayer(
    overlayState: State<ModCardDragOverlayState?>,
    showModFileName: Boolean,
    overlayHostTopLeftWindow: Offset
) {
    DraggingModCardOverlay(
        overlayState = overlayState.value,
        showModFileName = showModFileName,
        overlayHostTopLeftWindow = overlayHostTopLeftWindow
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
                .clip(cardShape)
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.tertiary,
                    shape = cardShape
                )
                .background(
                    color = if (mod.enabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    shape = cardShape
                )
                .padding(10.dp)
        ) {
            ModCardBodyContent(
                mod = mod,
                isExpanded = overlayState.isExpanded,
                showModFileName = showModFileName,
                showActionsButton = false,
                actionsEnabled = false,
                onActionsClick = {},
                headerTrailing = {
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
            )
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
