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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
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
    val overlayAnchor: ModDragOverlayAnchor,
    val pointerIdValue: Long
)

internal data class ModCardCallbacks(
    val onToggleMod: (ModItemUi, Boolean) -> Unit = { _, _ -> },
    val onTogglePriorityLoad: (ModItemUi, Boolean) -> Unit = { _, _ -> },
    val onDeleteMod: (ModItemUi) -> Unit = {},
    val onExportMod: (ModItemUi) -> Unit = {},
    val onShareMod: (ModItemUi) -> Unit = {},
    val onRenameModFile: (ModItemUi, String) -> Unit = { _, _ -> },
    val onDragStart: (ModCardDragStartInfo) -> Unit = {},
    val onDragCancel: () -> Unit = {}
)

@Composable
internal fun ModCard(
    mod: ModItemUi,
    isExpanded: Boolean,
    isDraggedInOverlay: Boolean,
    showModFileName: Boolean,
    showActionsButton: Boolean = true,
    setExpanded: (Boolean) -> Unit,
    selectionEnabled: Boolean,
    fileActionsEnabled: Boolean,
    dragEnabled: Boolean,
    callbacks: ModCardCallbacks
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var handleCoordinates by remember(mod.storagePath) { mutableStateOf<LayoutCoordinates?>(null) }
    var cardCoordinates by remember(mod.storagePath) { mutableStateOf<LayoutCoordinates?>(null) }
    var showActionsDialog by remember(mod.storagePath) { mutableStateOf(false) }
    var showRenameDialog by remember(mod.storagePath) { mutableStateOf(false) }
    val cardShape = RoundedCornerShape(10.dp)
    val dragVisualOffsetFromPointer = remember(density) {
//        Offset(x = 0f, y = with(density) { MOD_DRAG_VISUAL_OFFSET_Y_DP.dp.toPx() })
        Offset(x = 0f, y = 0f)
    }

    val dragHandleGestureModifier = Modifier
        .size(26.dp)
        .onGloballyPositioned { handleCoordinates = it }
        .pointerInput(mod.storagePath, dragEnabled) {
            if (!dragEnabled) {
                return@pointerInput
            }
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial
                )
                down.consume()
                val anchor = buildModCardDragOverlayAnchor(
                    handleCoordinates = handleCoordinates,
                    cardCoordinates = cardCoordinates,
                    startInHandle = down.position,
                    visualOffsetFromPointer = dragVisualOffsetFromPointer
                )
                if (anchor == null) {
                    callbacks.onDragCancel()
                    return@awaitEachGesture
                }

                callbacks.onDragStart(
                    ModCardDragStartInfo(
                        overlayAnchor = anchor,
                        pointerIdValue = down.id.value
                    )
                )

                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                        ?: event.changes.firstOrNull()
                        ?: break

                    if (change.changedToUpIgnoreConsumed()) {
                        change.consume()
                        break
                    }

                    change.consume()
                }
            }
        }
    val handleModifier = dragHandleGestureModifier

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = if (isDraggedInOverlay) 0f else 1f
            }
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .onGloballyPositioned { cardCoordinates = it }
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
            showActionsButton = showActionsButton,
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
            showRenameDialog = true
        },
        onDelete = { callbacks.onDeleteMod(mod) }
    )

    RenameModFileDialog(
        visible = showRenameDialog,
        value = resolveModFileNameWithoutJar(mod.storagePath).orEmpty(),
        controlsEnabled = fileActionsEnabled,
        onDismiss = { showRenameDialog = false },
        onConfirm = { newFileName ->
            showRenameDialog = false
            callbacks.onRenameModFile(mod, newFileName)
        }
    )
}

private fun buildModCardDragOverlayAnchor(
    handleCoordinates: LayoutCoordinates?,
    cardCoordinates: LayoutCoordinates?,
    startInHandle: Offset,
    visualOffsetFromPointer: Offset
): ModDragOverlayAnchor? {
    if (handleCoordinates == null || cardCoordinates == null) {
        return null
    }
    val pointerPosition = handleCoordinates.localToWindow(startInHandle)
    val pointerOffsetInsideCard = cardCoordinates.localPositionOf(handleCoordinates, startInHandle)
    val cardSize = cardCoordinates.size
    return ModDragOverlayAnchor(
        pointerWindow = pointerPosition,
        pointerOffsetInsideCard = pointerOffsetInsideCard,
        visualOffsetFromPointer = visualOffsetFromPointer,
        cardSizePx = IntSize(
            width = max(1, cardSize.width),
            height = max(1, cardSize.height)
        )
    )
}

@Composable
internal fun DraggingModCardOverlayLayer(
    dragSession: ModDragSession?,
    showModFileName: Boolean,
    overlayHostTopLeftWindow: Offset
) {
    DraggingModCardOverlay(
        dragSession = dragSession,
        showModFileName = showModFileName,
        overlayHostTopLeftWindow = overlayHostTopLeftWindow
    )
}

@Composable
internal fun DraggingModCardOverlay(
    dragSession: ModDragSession?,
    showModFileName: Boolean,
    overlayHostTopLeftWindow: Offset
) {
    if (dragSession == null) {
        return
    }

    val mod = dragSession.mod
    val density = LocalDensity.current
    val cardShape = RoundedCornerShape(10.dp)
    val cardWidth = with(density) {
        max(1, dragSession.overlayAnchor.cardSizePx.width).toDp()
    }
    val overlayOrigin = dragSession.overlayAnchor.overlayTopLeftInWindow()
    val popupOffset = IntOffset(
        x = (overlayOrigin.x - overlayHostTopLeftWindow.x).roundToInt(),
        y = (overlayOrigin.y - overlayHostTopLeftWindow.y).roundToInt()
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
                isExpanded = dragSession.isExpanded,
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
                    Box(
                        modifier = Modifier.size(26.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_drag_handle),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
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
