package io.stamethyst.ui.main

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import io.stamethyst.ui.LauncherSwitch as Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import io.stamethyst.ui.LauncherAlertDialog as AlertDialog
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
import androidx.compose.ui.platform.testTag
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
private val MOD_ENABLE_CHECKBOX_SLOT_WIDTH = 56.dp
private val FavoriteCardBorderColor = Color(0xFFF2A8CB)
private const val WORKSHOP_UPDATE_CHANGE_NOTES_TIMEOUT_MS = 8_000L

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
    val onRemoveAssociation: (ModItemUi) -> Unit = {},
    val onAssociationBadgeClick: (ModItemUi) -> Unit = {},
    val onPatchWorkshopMod: (ModItemUi) -> Unit = {},
    val onRetryWorkshopDownload: (ModItemUi) -> Unit = {},
    val onUpdateWorkshopMod: (ModItemUi) -> Unit = {},
    val onUpgradeWorkshopImportPatches: (ModItemUi) -> Unit = {},
    val onOpenWorkshopDetails: (ModItemUi) -> Unit = {},
    val onOpenAiEditor: (ModItemUi) -> Unit = {},
    val onSetAgentPatchEnabled: (ModItemUi, io.stamethyst.model.AgentPatchModUi, Boolean) -> Unit = { _, _, _ -> },
    val onExportAgentPatch: (ModItemUi, io.stamethyst.model.AgentPatchModUi) -> Unit = { _, _ -> },
    val onShareAgentPatch: (ModItemUi, io.stamethyst.model.AgentPatchModUi) -> Unit = { _, _ -> },
    val onRenameAgentPatch: (ModItemUi, io.stamethyst.model.AgentPatchModUi, String, String) -> Unit = { _, _, _, _ -> },
    val onRefreshAgentPatchMods: () -> Unit = {},
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
    isDraggedInOverlay: Boolean,
    showModFileName: Boolean,
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
    val showAiEditorNewBadge = AiEditorNewBadgeStore.shouldShow(context)
    var handleCoordinates by remember(mod.storagePath) { mutableStateOf<LayoutCoordinates?>(null) }
    var cardCoordinates by remember(mod.storagePath) { mutableStateOf<LayoutCoordinates?>(null) }
    var showDetailsDialog by remember(mod.storagePath) { mutableStateOf(false) }
    var detailsStartAtActions by remember(mod.storagePath) { mutableStateOf(false) }
    var showPriorityDialog by remember(mod.storagePath) { mutableStateOf(false) }
    var showRenameDialog by remember(mod.storagePath) { mutableStateOf(false) }
    var showSuggestionDialog by remember(mod.storagePath, suggestionText) { mutableStateOf(false) }
    var showImportPatchDialog by remember(mod.storagePath) { mutableStateOf(false) }
    var showAgentPatchCatalogue by remember(mod.storagePath) { mutableStateOf(false) }
    var agentPatchDialogLocalOnly by remember(mod.storagePath) { mutableStateOf(false) }
    var showAgentPatchContributions by remember(mod.storagePath) { mutableStateOf(false) }
    var agentPatchActionsOnly by remember(mod.storagePath) { mutableStateOf(false) }
    var agentPatchDetails by remember(mod.storagePath) { mutableStateOf<io.stamethyst.model.AgentPatchModUi?>(null) }
    var initialLocalActionPatchId by remember(mod.storagePath) { mutableStateOf<String?>(null) }
    var initialLocalUploadPatchId by remember(mod.storagePath) { mutableStateOf<String?>(null) }
    var initialRemotePatchModId by remember(mod.storagePath) { mutableStateOf<String?>(null) }
    var showWorkshopUpdateConfirmDialog by remember(mod.storagePath) { mutableStateOf(false) }
    var updateChangeNotesReloadToken by remember(mod.storagePath) { mutableStateOf(0) }
    var updateChangeNotesState by remember(mod.storagePath) {
        mutableStateOf<WorkshopUpdateChangeNotesState>(WorkshopUpdateChangeNotesState.Idle)
    }
    val cardShape = RoundedCornerShape(4.dp)
    val batchSelectionAnimationProgress = if (batchSelectionMode) {
        batchSelectionProgress?.value ?: 1f
    } else {
        batchSelectionProgress?.value ?: 0f
    }.coerceIn(0f, 1f)
    val normalControlProgress = 1f - batchSelectionAnimationProgress
    val dragAffordanceVisible = showDragHandle
    val deleteWorkshopDownloadEnabled = mod.canDeleteDownloadedWorkshopMod()
    val actionsEnabled = fileActionsEnabled || deleteWorkshopDownloadEnabled
    val dragAffordanceProgress = if (dragAffordanceVisible) {
            (dragAffordanceAlpha?.value ?: 1f)
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
                detailsStartAtActions = true
                showDetailsDialog = true
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
            .padding(start = 22.dp, end = 8.dp, top = 1.dp, bottom = 1.dp)
            .onGloballyPositioned { cardCoordinates = it }
            .clip(cardShape)
            .border(
                if (batchSelected || mod.newlyImported) 1.dp else 0.dp,
                if (batchSelected) {
                    MaterialTheme.colorScheme.primary
                } else if (mod.favorite) {
                    FavoriteCardBorderColor
                } else if (mod.newlyImported) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    Color.Transparent
                },
                cardShape
            )
            .background(
                if (batchSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else if (mod.newlyImported) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
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
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        ModCardBodyContent(
            mod = mod,
            isExpanded = false,
            showModFileName = showModFileName,
            compact = true,
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
            agentPatchMods = mod.agentPatchMods,
            onSetAgentPatchEnabled = { patch, enabled -> callbacks.onSetAgentPatchEnabled(mod, patch, enabled) },
            onOpenAgentPatchCatalogue = {
                initialRemotePatchModId = null
                showAgentPatchContributions = false
                agentPatchDialogLocalOnly = false
                agentPatchActionsOnly = false
                initialLocalActionPatchId = null
                initialLocalUploadPatchId = null
                agentPatchDetails = null
                showAgentPatchCatalogue = true
            },
            onOpenAgentPatchContributions = {
                initialRemotePatchModId = null
                showAgentPatchContributions = true
                agentPatchDialogLocalOnly = false
                agentPatchActionsOnly = false
                initialLocalActionPatchId = null
                initialLocalUploadPatchId = null
                showAgentPatchCatalogue = true
            },
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
                        .widthIn(min = MOD_ENABLE_CHECKBOX_SLOT_WIDTH)
                        .testTag("mod_card_primary_control")
                        .clipToBounds(),
                    contentAlignment = Alignment.Center
                ) {
                    if (mod.showsInstallControl()) {
                        Button(
                            onClick = { callbacks.onPatchWorkshopMod(mod) },
                            enabled = !batchSelectionMode,
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.testTag("mod_card_install"),
                        ) {
                            Text(stringResource(R.string.main_mod_workshop_action_install), maxLines = 1)
                        }
                    } else if (mod.showsEnableControl()) {
                        Switch(
                            checked = mod.enabled,
                            onCheckedChange = { checked ->
                                if (selectionEnabled) {
                                    if (checked != mod.enabled) {
                                        LauncherHaptics.vibrateToggle(context)
                                    }
                                    callbacks.onToggleMod(mod, checked)
                                }
                            },
                            enabled = selectionEnabled,
                            modifier = Modifier.testTag("mod_card_enable").graphicsLayer {
                                alpha = 1f
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

        // Installation lives in the header's enable-control slot, never a second full-width row.
        if (!mod.showsInstallControl()) {
            ModWorkshopActions(
                mod = mod,
                enabled = !batchSelectionMode,
                onInstall = { callbacks.onPatchWorkshopMod(mod) },
                onRetryDownload = { callbacks.onRetryWorkshopDownload(mod) },
                onManualHandle = {
                    detailsStartAtActions = true
                    showDetailsDialog = true
                },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                    .graphicsLayer { alpha = normalControlProgress },
            )
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
        WorkshopModUpdateDialog(
            modName = resolveModDisplayName(mod, showModFileName = false),
            changeNotesState = updateChangeNotesState,
            onRetryChangeNotes = { updateChangeNotesReloadToken++ },
            onUpdate = {
                showWorkshopUpdateConfirmDialog = false
                callbacks.onUpdateWorkshopMod(mod)
            },
            onDismiss = { showWorkshopUpdateConfirmDialog = false },
        )
    }

    if (showDetailsDialog) {
        val openAiEditor = {
            showDetailsDialog = false
            AiEditorNewBadgeStore.markSeen(context)
            callbacks.onOpenAiEditor(mod)
        }
        val openCatalogue = {
            showAgentPatchContributions = false
            agentPatchDialogLocalOnly = false
            agentPatchActionsOnly = false
            initialLocalActionPatchId = null
            initialLocalUploadPatchId = null
            initialRemotePatchModId = null
            showAgentPatchCatalogue = true
        }
        val openWorkshop = {
            showDetailsDialog = false
            callbacks.onOpenWorkshopDetails(mod)
        }
        ModDetailsDialog(
            mod = mod,
            showModFileName = showModFileName,
            fileActionsEnabled = fileActionsEnabled,
            startAtActions = detailsStartAtActions,
            onDismiss = { showDetailsDialog = false },
            onFavoriteChange = { callbacks.onSetFavorite(mod, it) },
            suggestionContent = {
                ModDetailsSuggestion(suggestionText, suggestionRead) {
                    onSuggestionRead()
                    showSuggestionDialog = true
                }
            },
            badgesContent = { onScrollToAi ->
                ModDetailsBadges(
                    mod = mod,
                    associationBadge = associationBadge,
                    associationEnabled = associationBadgeEnabled,
                    onImportPatch = { showImportPatchDialog = true },
                    onAssociation = {
                        showDetailsDialog = false
                        callbacks.onAssociationBadgeClick(mod)
                    },
                    onUpdate = {
                        updateChangeNotesState = WorkshopUpdateChangeNotesState.Idle
                        updateChangeNotesReloadToken++
                        showWorkshopUpdateConfirmDialog = true
                    },
                    onWorkshop = openWorkshop,
                    onCatalogue = openCatalogue,
                    onContributions = {
                        openCatalogue()
                        showAgentPatchContributions = true
                    },
                    onAiMods = onScrollToAi,
                )
            },
            aiContent = {
                ModDetailsAiSection(
                    mod = mod,
                    controlsEnabled = fileActionsEnabled,
                    onNew = openAiEditor,
                    onToggle = { patch, enabled -> callbacks.onSetAgentPatchEnabled(mod, patch, enabled) },
                    onDetails = { agentPatchDetails = it },
                    onManage = {
                        initialLocalActionPatchId = it.patchId
                        initialLocalUploadPatchId = null
                        initialRemotePatchModId = null
                        agentPatchDialogLocalOnly = true
                        agentPatchActionsOnly = true
                        showAgentPatchCatalogue = true
                    },
                    onCommunityDetails = {
                        openCatalogue()
                        initialRemotePatchModId = it.patchModId
                    },
                    onCatalogueRefreshed = callbacks.onRefreshAgentPatchMods,
                )
            },
            actionsContent = {
                mod.detailsStatusText()?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                ModWorkshopActions(
                    mod = mod,
                    enabled = !batchSelectionMode,
                    onInstall = {
                        showDetailsDialog = false
                        callbacks.onPatchWorkshopMod(mod)
                    },
                    onRetryDownload = { callbacks.onRetryWorkshopDownload(mod) },
                )
                ModActionsList(
                    controlsEnabled = fileActionsEnabled,
                    favorite = mod.favorite,
                    deleteEnabled = actionsEnabled,
                    onFavoriteChange = { callbacks.onSetFavorite(mod, it) },
                    onEditPriority = { showPriorityDialog = true },
                    showOpenWorkshopDetails = mod.workshop != null,
                    onOpenWorkshopDetails = openWorkshop,
                    showAiEditorNewBadge = showAiEditorNewBadge,
                    onOpenAiEditor = openAiEditor,
                    onAssociate = {
                        showDetailsDialog = false
                        callbacks.onAssociateMod(mod)
                    },
                    associationPresent = associationBadge != null,
                    onRemoveAssociation = { callbacks.onRemoveAssociation(mod) },
                    onExport = {
                        showDetailsDialog = false
                        callbacks.onExportMod(mod)
                    },
                    onShare = {
                        showDetailsDialog = false
                        callbacks.onShareMod(mod)
                    },
                    onRename = { showRenameDialog = true },
                    onDelete = {
                        showDetailsDialog = false
                        callbacks.onDeleteMod(mod)
                    },
                    showMove = false,
                    moveEnabled = selectionEnabled && !mod.required && mod.canUseFolderOrganization(),
                    onMove = {
                        showDetailsDialog = false
                        callbacks.onMoveFolderPickerRequest(mod)
                    },
                )
            },
        )
    }

    if (showAgentPatchCatalogue && !batchSelectionMode) {
        AgentPatchCatalogueDialog(
            mod = mod,
            localPatches = mod.agentPatchMods,
            workshopId = mod.workshop?.publishedFileId,
            initialLocalActionPatchId = initialLocalActionPatchId,
            initialLocalUploadPatchId = initialLocalUploadPatchId,
            initialRemotePatchModId = initialRemotePatchModId,
            localOnly = agentPatchDialogLocalOnly,
            contributionsOnly = showAgentPatchContributions,
            actionsOnly = agentPatchActionsOnly,
            onDismiss = { showAgentPatchCatalogue = false },
            onInstalled = callbacks.onRefreshAgentPatchMods,
            onCatalogueRefreshed = callbacks.onRefreshAgentPatchMods,
            onExport = { patch -> callbacks.onExportAgentPatch(mod, patch) },
            onShare = { patch -> callbacks.onShareAgentPatch(mod, patch) },
            onRename = { patch, name, description -> callbacks.onRenameAgentPatch(mod, patch, name, description) },
        )
    }

    agentPatchDetails?.let { patch ->
        if (!batchSelectionMode) {
            AgentPatchDetailsDialog(
                patch = patch,
                onDismiss = { agentPatchDetails = null },
            )
        }
    }

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
    overlayHostTopLeftWindow: Offset,
    movingCount: Int = 1,
) {
    DraggingModCardOverlay(
        dragSession = dragSession,
        showModFileName = showModFileName,
        movingCount = movingCount,
        overlayHostTopLeftWindow = overlayHostTopLeftWindow
    )
}

@Composable
internal fun DraggingModCardOverlay(
    dragSession: ModDragSession?,
    showModFileName: Boolean,
    overlayHostTopLeftWindow: Offset,
    movingCount: Int = 1,
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
            if (movingCount > 1) {
                Text(stringResource(R.string.mod_drag_count, movingCount), style = MaterialTheme.typography.titleSmall)
            }
            ModCardBodyContent(
                mod = mod,
                isExpanded = dragSession.isExpanded,
                showModFileName = showModFileName,
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
