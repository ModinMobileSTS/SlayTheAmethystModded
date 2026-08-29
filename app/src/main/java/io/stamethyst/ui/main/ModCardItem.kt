package io.stamethyst.ui.main

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import io.stamethyst.backend.workshop.WorkshopService
import io.stamethyst.model.ModItemUi
import io.stamethyst.model.WorkshopModState
import io.stamethyst.ui.SimpleMarkdownCard
import io.stamethyst.ui.haptics.LauncherHaptics
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class ModCardDragStartInfo(
    val overlayAnchor: ModDragOverlayAnchor,
    val pointerIdValue: Long
)

private val MOD_DRAG_HANDLE_SLOT_WIDTH = 28.dp
private val MOD_BATCH_CHECKBOX_SLOT_WIDTH = 48.dp
private val MOD_ENABLE_CHECKBOX_SLOT_WIDTH = 48.dp
private val FavoriteCardBorderColor = Color(0xFFF2A8CB)
private const val WORKSHOP_UPDATE_CHANGE_NOTES_TIMEOUT_MS = 8_000L

private sealed interface WorkshopUpdateChangeNotesState {
    data object Idle : WorkshopUpdateChangeNotesState
    data object Loading : WorkshopUpdateChangeNotesState
    data class Loaded(val latestMarkdown: String) : WorkshopUpdateChangeNotesState
    data class Failed(val errorDetail: String) : WorkshopUpdateChangeNotesState
}

@Stable
internal data class ModCardCallbacks(
    val onToggleMod: (ModItemUi, Boolean) -> Unit = { _, _ -> },
    val onSetPriority: (ModItemUi, Int?) -> Unit = { _, _ -> },
    val onSetFavorite: (ModItemUi, Boolean) -> Unit = { _, _ -> },
    val onDeleteMod: (ModItemUi) -> Unit = {},
    val onExportMod: (ModItemUi) -> Unit = {},
    val onShareMod: (ModItemUi) -> Unit = {},
    val onRenameModAlias: (ModItemUi, String) -> Unit = { _, _ -> },
    val onRestoreModOriginalName: (ModItemUi) -> Unit = {},
    val onAssociateMod: (ModItemUi) -> Unit = {},
    val onAssociationBadgeClick: (ModItemUi) -> Unit = {},
    val onPatchWorkshopMod: (ModItemUi) -> Unit = {},
    val onRetryWorkshopDownload: (ModItemUi) -> Unit = {},
    val onUpdateWorkshopMod: (ModItemUi) -> Unit = {},
    val onUpgradeWorkshopImportPatches: (ModItemUi) -> Unit = {},
    val onOpenWorkshopDetails: (ModItemUi) -> Unit = {},
    val onSetImportPatchEnabled: (ModItemUi, String, Boolean) -> Unit = { _, _, _ -> },
    val onDragStart: (ModCardDragStartInfo) -> Unit = {},
    val onDragCancel: () -> Unit = {},
    val onMoveFolderPickerRequest: (ModItemUi) -> Unit = {},
    val onBatchSelectionStart: (ModItemUi) -> Unit = {}
)

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun ModCard(
    mod: ModItemUi,
    suggestionText: String? = null,
    suggestionRead: Boolean = true,
    isExpanded: Boolean,
    isDraggedInOverlay: Boolean,
    showModFileName: Boolean,
    showActionsButton: Boolean = true,
    setExpanded: (Boolean) -> Unit,
    selectionEnabled: Boolean,
    fileActionsEnabled: Boolean,
    dragEnabled: Boolean,
    dragEnabledState: State<Boolean>? = null,
    showDragHandle: Boolean = true,
    dragAffordanceAlpha: State<Float>? = null,
    batchSelectionProgress: State<Float>? = null,
    batchSelectionMode: Boolean = false,
    batchSelected: Boolean = false,
    batchSelectionEnabled: Boolean = false,
    associationBadge: ModAssociationBadgeUi? = null,
    associationBadgeEnabled: Boolean = true,
    onBatchSelectionChange: (Boolean) -> Unit = {},
    onSuggestionRead: () -> Unit = {},
    callbacks: ModCardCallbacks
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var handleCoordinates by remember(mod.storagePath) { mutableStateOf<LayoutCoordinates?>(null) }
    var cardCoordinates by remember(mod.storagePath) { mutableStateOf<LayoutCoordinates?>(null) }
    var showActionsDialog by remember(mod.storagePath) { mutableStateOf(false) }
    var showPriorityDialog by remember(mod.storagePath) { mutableStateOf(false) }
    var showRenameDialog by remember(mod.storagePath) { mutableStateOf(false) }
    var showSuggestionDialog by remember(mod.storagePath, suggestionText) { mutableStateOf(false) }
    var showImportPatchDialog by remember(mod.storagePath) { mutableStateOf(false) }
    var showWorkshopUpdateConfirmDialog by remember(mod.storagePath) { mutableStateOf(false) }
    var updateChangeNotesReloadToken by remember(mod.storagePath) { mutableStateOf(0) }
    var updateChangeNotesState by remember(mod.storagePath) {
        mutableStateOf<WorkshopUpdateChangeNotesState>(WorkshopUpdateChangeNotesState.Idle)
    }
    val cardShape = RoundedCornerShape(10.dp)
    val batchSelectionAnimationProgress = if (batchSelectionMode) {
        batchSelectionProgress?.value ?: 1f
    } else {
        batchSelectionProgress?.value ?: 0f
    }.coerceIn(0f, 1f)
    val normalControlProgress = 1f - batchSelectionAnimationProgress
    val dragAffordanceVisible = showDragHandle && !batchSelectionMode
    val deleteWorkshopDownloadEnabled = mod.canDeleteDownloadedWorkshopMod()
    val actionsEnabled = fileActionsEnabled || deleteWorkshopDownloadEnabled
    val dragAffordanceProgress = if (dragAffordanceVisible) {
        (dragAffordanceAlpha?.value ?: 1f) * normalControlProgress
    } else {
        if (showDragHandle) normalControlProgress else 0f
    }.coerceIn(0f, 1f)
    val dragVisualOffsetFromPointer = remember(density) {
//        Offset(x = 0f, y = with(density) { MOD_DRAG_VISUAL_OFFSET_Y_DP.dp.toPx() })
        Offset(x = 0f, y = 0f)
    }

    val dragHandleGestureModifier = Modifier
        .size(26.dp)
        .onGloballyPositioned { handleCoordinates = it }
        .pointerInput(mod.storagePath) {
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial
                )
                if (dragEnabledState?.value == false || !dragEnabled) {
                    return@awaitEachGesture
                }
                down.consume()
                var dragStartInHandle: Offset? = null
                while (dragStartInHandle == null) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                        ?: event.changes.firstOrNull()
                        ?: break

                    if (change.changedToUpIgnoreConsumed()) {
                        change.consume()
                        break
                    }

                    val dragOffset = change.position - down.position
                    val longPressed = change.uptimeMillis - down.uptimeMillis >=
                        viewConfiguration.longPressTimeoutMillis
                    val movedPastTouchSlop = dragOffset.getDistance() >= viewConfiguration.touchSlop
                    if (longPressed || movedPastTouchSlop) {
                        change.consume()
                        dragStartInHandle = change.position
                    }
                }
                if (dragStartInHandle == null) {
                    callbacks.onMoveFolderPickerRequest(mod)
                    return@awaitEachGesture
                }
                val anchor = buildModCardDragOverlayAnchor(
                    handleCoordinates = handleCoordinates,
                    cardCoordinates = cardCoordinates,
                    startInHandle = dragStartInHandle,
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
    val cardTapModifier = Modifier.combinedClickable(
        onClick = {
            if (batchSelectionMode && batchSelectionEnabled) {
                onBatchSelectionChange(!batchSelected)
            } else {
                setExpanded(!isExpanded)
            }
        },
        onLongClick = {
            if (batchSelectionEnabled) {
                callbacks.onBatchSelectionStart(mod)
            }
        }
    )

    LaunchedEffect(showWorkshopUpdateConfirmDialog, updateChangeNotesReloadToken, mod.workshop?.publishedFileId) {
        val workshop = mod.workshop
        if (!showWorkshopUpdateConfirmDialog || workshop?.state != WorkshopModState.UpdateAvailable) return@LaunchedEffect
        updateChangeNotesState = WorkshopUpdateChangeNotesState.Loading
        updateChangeNotesState = runCatching {
            withContext(Dispatchers.IO) {
                withTimeout(WORKSHOP_UPDATE_CHANGE_NOTES_TIMEOUT_MS) {
                    WorkshopService(context.applicationContext)
                        .getChangeNotes(workshop.publishedFileId)
                        .latestMarkdown
                }
            }
        }.fold(
            onSuccess = { markdown -> WorkshopUpdateChangeNotesState.Loaded(markdown) },
            onFailure = { error ->
                WorkshopUpdateChangeNotesState.Failed(error.message ?: error.javaClass.simpleName)
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = if (isDraggedInOverlay) 0f else 1f
            }
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .onGloballyPositioned { cardCoordinates = it }
            .shadow(elevation = 2.dp, shape = cardShape, clip = false)
            .clip(cardShape)
            .border(
                if (batchSelected || mod.newlyImported) 2.dp else 1.dp,
                if (batchSelected) {
                    MaterialTheme.colorScheme.primary
                } else if (mod.favorite) {
                    FavoriteCardBorderColor
                } else if (mod.newlyImported) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                cardShape
            )
            .background(
                if (batchSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else if (mod.newlyImported) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else if (mod.enabled) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                cardShape
            )
            .then(cardTapModifier)
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
            actionsEnabled = actionsEnabled,
            onActionsClick = {
                if (actionsEnabled) {
                    showActionsDialog = true
                }
            },
            modSuggestionText = suggestionText,
            suggestionUnread = !suggestionRead,
            suggestionBadgeEnabled = !batchSelectionMode,
            onSuggestionClick = {
                onSuggestionRead()
                showSuggestionDialog = true
            },
            importPatchBadgeEnabled = !batchSelectionMode,
            onImportPatchClick = { showImportPatchDialog = true },
            associationBadge = associationBadge,
            associationBadgeEnabled = associationBadgeEnabled && !batchSelectionMode,
            onAssociationBadgeClick = { callbacks.onAssociationBadgeClick(mod) },
            updateBadgeEnabled = !batchSelectionMode,
            onOpenWorkshopDetails = { callbacks.onOpenWorkshopDetails(it) },
            workshopBadgeEnabled = !batchSelectionMode,
            onUpdateBadgeClick = {
                updateChangeNotesState = WorkshopUpdateChangeNotesState.Idle
                updateChangeNotesReloadToken++
                showWorkshopUpdateConfirmDialog = true
            },
            headerLeading = {
                Box(
                    modifier = Modifier
                        .width(MOD_BATCH_CHECKBOX_SLOT_WIDTH * batchSelectionAnimationProgress)
                        .clipToBounds(),
                    contentAlignment = Alignment.Center
                ) {
                    Checkbox(
                        checked = batchSelected,
                        onCheckedChange = { checked ->
                            if (batchSelectionEnabled) {
                                onBatchSelectionChange(checked)
                            }
                        },
                        enabled = batchSelectionEnabled && batchSelectionMode,
                        modifier = Modifier.graphicsLayer {
                            alpha = batchSelectionAnimationProgress
                            scaleX = 0.86f + alpha * 0.14f
                            scaleY = 0.86f + alpha * 0.14f
                        }
                    )
                }
            },
            headerTrailing = {
                Box(
                    modifier = Modifier
                        .width(MOD_ENABLE_CHECKBOX_SLOT_WIDTH * normalControlProgress)
                        .clipToBounds(),
                    contentAlignment = Alignment.Center
                ) {
                    val workshopState = mod.workshop?.state
                    val showEnableCheckbox = (mod.installed || workshopState == WorkshopModState.TexturePackInstalled) && when (workshopState) {
                        WorkshopModState.NotDownloaded,
                        WorkshopModState.ImportedUnpatched,
                        WorkshopModState.Queued,
                        WorkshopModState.Downloading,
                        WorkshopModState.Cancelling,
                        WorkshopModState.DownloadPaused,
                        WorkshopModState.DownloadFailed,
                        WorkshopModState.NonStandardDownloaded,
                        WorkshopModState.FileMissing -> false
                        WorkshopModState.TexturePackInstalled -> true
                        WorkshopModState.ImportedPatched,
                        WorkshopModState.UpdateAvailable,
                        null -> true
                    }
                    if (showEnableCheckbox) {
                        Checkbox(
                            checked = mod.enabled,
                            onCheckedChange = { checked ->
                                if (!batchSelectionMode && selectionEnabled) {
                                    if (checked != mod.enabled) {
                                        LauncherHaptics.vibrateToggle(context)
                                    }
                                    callbacks.onToggleMod(mod, checked)
                                }
                            },
                            enabled = !batchSelectionMode && selectionEnabled,
                            modifier = Modifier.graphicsLayer {
                                alpha = normalControlProgress
                                scaleX = 0.86f + alpha * 0.14f
                                scaleY = 0.86f + alpha * 0.14f
                            }
                        )
                    }
                }
                if (showDragHandle) {
                    Box(
                        modifier = Modifier
                            .width(MOD_DRAG_HANDLE_SLOT_WIDTH * dragAffordanceProgress)
                            .clipToBounds()
                            .then(handleModifier)
                            .graphicsLayer {
                                alpha = dragAffordanceProgress
                                scaleX = 0.92f + alpha * 0.08f
                                scaleY = 0.92f + alpha * 0.08f
                            },
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
            }
        )
        val workshopActionModifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .graphicsLayer { alpha = normalControlProgress }
        // Items the launcher manages itself can never be downloaded from here. Mirror the
        // market's Unavailable treatment: a visibly disabled control instead of a dead button.
        val workshopDownloadBlocked = mod.workshop?.downloadBlocked == true
        when (mod.workshop?.state) {
            WorkshopModState.ImportedUnpatched -> Button(
                onClick = { callbacks.onPatchWorkshopMod(mod) },
                enabled = !batchSelectionMode,
                modifier = workshopActionModifier
            ) {
                Text(stringResource(R.string.main_mod_workshop_action_install))
            }
            WorkshopModState.DownloadFailed,
            WorkshopModState.DownloadPaused,
            WorkshopModState.FileMissing -> {
                val actionLabelResId = when (mod.workshop.state) {
                    WorkshopModState.DownloadPaused -> R.string.main_mod_workshop_action_continue_download
                    WorkshopModState.FileMissing -> R.string.main_mod_workshop_action_redownload
                    else -> R.string.workshop_action_retry
                }
                if (workshopDownloadBlocked) {
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        modifier = workshopActionModifier
                    ) {
                        Text(stringResource(R.string.workshop_download_state_unavailable))
                    }
                } else {
                    Button(
                        onClick = { callbacks.onRetryWorkshopDownload(mod) },
                        enabled = !batchSelectionMode,
                        modifier = workshopActionModifier
                    ) {
                        Text(stringResource(actionLabelResId))
                    }
                }
            }
            WorkshopModState.NonStandardDownloaded -> OutlinedButton(
                onClick = { showActionsDialog = true },
                enabled = !batchSelectionMode,
                modifier = workshopActionModifier
            ) {
                Text(stringResource(R.string.main_mod_workshop_action_manual_handle))
            }
            WorkshopModState.TexturePackInstalled -> Unit
            WorkshopModState.Downloading -> {
                val progress = mod.workshop.downloadProgressPercent
                    ?.coerceIn(0, 100)
                    ?.let { it / 100f }
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = workshopActionModifier
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = workshopActionModifier
                    )
                }
            }
            // Queued and Cancelling deliberately render no progress bar. Nothing is being
            // transferred yet (or any more), so an animating bar would misreport the state.
            // The status text and state badge carry the information instead.
            WorkshopModState.Queued,
            WorkshopModState.Cancelling,
            WorkshopModState.NotDownloaded,
            WorkshopModState.ImportedPatched,
            WorkshopModState.UpdateAvailable,
            null -> Unit
        }
    }

    if (showSuggestionDialog && !suggestionText.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { showSuggestionDialog = false },
            title = {
                Text(
                    text = stringResource(
                        R.string.main_mod_suggestion_dialog_title_format,
                        resolveModDisplayName(mod, showModFileName = false)
                    )
                )
            },
            text = { Text(text = suggestionText) },
            confirmButton = {
                TextButton(onClick = { showSuggestionDialog = false }) {
                    Text(text = stringResource(R.string.common_action_confirm))
                }
            }
        )
    }

    if (showImportPatchDialog &&
        (!mod.importPatchDetails.isNullOrBlank() || mod.importPatches.isNotEmpty())
    ) {
        ModImportPatchDialog(
            mod = mod,
            onDismiss = { showImportPatchDialog = false },
            onSetPatchEnabled = { moduleId, enabled ->
                callbacks.onSetImportPatchEnabled(mod, moduleId, enabled)
            },
            onUpgradeFromWorkshop = { callbacks.onUpgradeWorkshopImportPatches(mod) },
        )
    }

    if (showWorkshopUpdateConfirmDialog && mod.workshop?.state == WorkshopModState.UpdateAvailable) {
        AlertDialog(
            onDismissRequest = { showWorkshopUpdateConfirmDialog = false },
            title = { Text(stringResource(R.string.main_mod_workshop_update_dialog_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.main_mod_workshop_update_dialog_message,
                            resolveModDisplayName(mod, showModFileName = false)
                        )
                    )
                    when (val changeNotesState = updateChangeNotesState) {
                        WorkshopUpdateChangeNotesState.Idle,
                        WorkshopUpdateChangeNotesState.Loading -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                text = stringResource(R.string.workshop_change_notes_loading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        is WorkshopUpdateChangeNotesState.Loaded -> {
                            if (changeNotesState.latestMarkdown.isNotBlank()) {
                                SimpleMarkdownCard(
                                    title = stringResource(R.string.workshop_change_notes_latest_title),
                                    markdown = changeNotesState.latestMarkdown,
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.workshop_change_notes_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        is WorkshopUpdateChangeNotesState.Failed -> {
                            Text(
                                text = stringResource(
                                    R.string.workshop_change_notes_load_failed,
                                    changeNotesState.errorDetail,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            OutlinedButton(
                                onClick = { updateChangeNotesReloadToken++ },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.workshop_action_retry))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = updateChangeNotesState !is WorkshopUpdateChangeNotesState.Idle &&
                        updateChangeNotesState !is WorkshopUpdateChangeNotesState.Loading,
                    onClick = {
                        showWorkshopUpdateConfirmDialog = false
                        callbacks.onUpdateWorkshopMod(mod)
                    }
                ) {
                    Text(stringResource(R.string.workshop_action_update))
                }
            },
            dismissButton = {
                TextButton(onClick = { showWorkshopUpdateConfirmDialog = false }) {
                    Text(stringResource(R.string.main_folder_dialog_cancel))
                }
            }
        )
    }

    ModActionsDialog(
        visible = showActionsDialog,
        controlsEnabled = fileActionsEnabled,
        onDismiss = { showActionsDialog = false },
        favorite = mod.favorite,
        deleteEnabled = actionsEnabled,
        onFavoriteChange = { favorite -> callbacks.onSetFavorite(mod, favorite) },
        onEditPriority = { showPriorityDialog = true },
        showOpenWorkshopDetails = mod.workshop != null,
        onOpenWorkshopDetails = { callbacks.onOpenWorkshopDetails(mod) },
        onAssociate = { callbacks.onAssociateMod(mod) },
        onExport = { callbacks.onExportMod(mod) },
        onShare = { callbacks.onShareMod(mod) },
        onRename = { showRenameDialog = true },
        onDelete = { callbacks.onDeleteMod(mod) }
    )

    ModPriorityDialog(
        visible = showPriorityDialog,
        controlsEnabled = fileActionsEnabled,
        modName = resolveModDisplayName(mod, showModFileName = showModFileName),
        explicitPriority = mod.explicitPriority,
        effectivePriority = mod.effectivePriority,
        onDismiss = { showPriorityDialog = false },
        onClearPriority = {
            showPriorityDialog = false
            callbacks.onSetPriority(mod, null)
        },
        onConfirm = { priority ->
            showPriorityDialog = false
            callbacks.onSetPriority(mod, priority)
        }
    )

    RenameModAliasDialog(
        visible = showRenameDialog,
        value = mod.alias.ifBlank { resolveOriginalModDisplayName(mod) },
        controlsEnabled = fileActionsEnabled,
        showRestoreOriginal = mod.alias.isNotBlank(),
        onDismiss = { showRenameDialog = false },
        onRestoreOriginal = {
            showRenameDialog = false
            callbacks.onRestoreModOriginalName(mod)
        },
        onConfirm = { newAlias ->
            showRenameDialog = false
            callbacks.onRenameModAlias(mod, newAlias)
        }
    )
}

private fun ModItemUi.canDeleteDownloadedWorkshopMod(): Boolean {
    if (installed) return false
    return when (workshop?.state) {
        WorkshopModState.Queued,
        WorkshopModState.Downloading,
        WorkshopModState.Cancelling,
        WorkshopModState.DownloadPaused,
        WorkshopModState.DownloadFailed,
        WorkshopModState.ImportedUnpatched,
        WorkshopModState.NonStandardDownloaded,
        WorkshopModState.TexturePackInstalled,
        WorkshopModState.FileMissing -> true
        else -> false
    }
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
                modSuggestionText = null,
                suggestionBadgeEnabled = false,
                onSuggestionClick = {},
                importPatchBadgeEnabled = false,
                onImportPatchClick = {},
                associationBadge = null,
                associationBadgeEnabled = false,
                onAssociationBadgeClick = {},
                updateBadgeEnabled = false,
                workshopBadgeEnabled = false,
                onUpdateBadgeClick = {},
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
