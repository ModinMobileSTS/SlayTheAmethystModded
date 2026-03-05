package io.stamethyst.ui.main

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.stamethyst.R
import io.stamethyst.model.ModItemUi
import io.stamethyst.ui.Icons
import io.stamethyst.ui.icon.Settings
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherMainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = MainScreenViewModel(),
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val hostActivity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState = viewModel.uiState
    val importModsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (hostActivity != null) {
            viewModel.onModJarsPicked(hostActivity, uris)
        }
    }

    LaunchedEffect(hostActivity) {
        if (hostActivity != null) {
            viewModel.refresh(hostActivity)
        }
    }

    DisposableEffect(hostActivity, lifecycleOwner) {
        val activity = hostActivity
        if (activity == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    viewModel.refresh(activity)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }

    LauncherMainScreenContent(
        modifier = modifier,
        uiState = uiState,
        hostActivity = hostActivity,
        onOpenSettings = onOpenSettings,
        onSuggestNextFolderName = { viewModel.suggestNextFolderName() },
        onAddFolder = { name ->
            if (hostActivity != null) {
                viewModel.addFolder(hostActivity, name)
            }
        },
        onRenameFolder = { folderId, name ->
            if (hostActivity != null) {
                viewModel.renameFolder(hostActivity, folderId, name)
            }
        },
        onDeleteFolder = { folderId ->
            if (hostActivity != null) {
                viewModel.deleteFolder(hostActivity, folderId)
            }
        },
        onDeleteMod = { mod ->
            if (hostActivity != null) {
                viewModel.onDeleteMod(hostActivity, mod)
            }
        },
        onExportMod = { mod ->
            if (hostActivity != null) {
                viewModel.onExportMod(hostActivity, mod)
            }
        },
        onShareMod = { mod ->
            if (hostActivity != null) {
                viewModel.onShareMod(hostActivity, mod)
            }
        },
        onRenameModFile = { mod, newFileName ->
            if (hostActivity != null) {
                viewModel.onRenameModFile(hostActivity, mod, newFileName)
            }
        },
        onToggleMod = { mod, checked ->
            if (hostActivity != null) {
                viewModel.onToggleMod(hostActivity, mod, checked)
            }
        },
        onSetFolderSelected = { folderId, selected ->
            if (hostActivity != null) {
                viewModel.setFolderSelected(hostActivity, folderId, selected)
            }
        },
        onSetUnassignedSelected = { selected ->
            if (hostActivity != null) {
                viewModel.setUnassignedSelected(hostActivity, selected)
            }
        },
        onToggleFolderCollapsed = { folderId ->
            if (hostActivity != null) {
                viewModel.toggleFolderCollapsed(hostActivity, folderId)
            }
        },
        onToggleUnassignedCollapsed = {
            if (hostActivity != null) {
                viewModel.toggleUnassignedCollapsed(hostActivity)
            }
        },
        onToggleStatusSummaryCollapsed = {
            if (hostActivity != null) {
                viewModel.toggleStatusSummaryCollapsed(hostActivity)
            }
        },
        onMoveFolderUp = { folderId ->
            if (hostActivity != null) {
                viewModel.moveFolderUp(hostActivity, folderId)
            }
        },
        onMoveFolderDown = { folderId ->
            if (hostActivity != null) {
                viewModel.moveFolderDown(hostActivity, folderId)
            }
        },
        onMoveUnassignedUp = {
            if (hostActivity != null) {
                viewModel.moveUnassignedUp(hostActivity)
            }
        },
        onMoveUnassignedDown = {
            if (hostActivity != null) {
                viewModel.moveUnassignedDown(hostActivity)
            }
        },
        onMoveFolderTokenToIndex = { folderId, targetIndex ->
            if (hostActivity != null) {
                viewModel.moveFolderTokenToIndex(hostActivity, folderId, targetIndex)
            }
        },
        onAssignModToFolder = { mod, folderId ->
            if (hostActivity != null) {
                viewModel.assignModToFolder(hostActivity, mod, folderId)
            }
        },
        onMoveModToUnassigned = { mod ->
            if (hostActivity != null) {
                viewModel.moveModToUnassigned(hostActivity, mod)
            }
        },
        onCollapseAllFoldersForDragWithSnapshot = {
            if (hostActivity != null) {
                viewModel.collapseAllFoldersForDragWithSnapshot(hostActivity)
            } else {
                null
            }
        },
        onRestoreFolderCollapseSnapshot = { snapshot ->
            if (hostActivity != null) {
                viewModel.restoreFolderCollapseSnapshot(hostActivity, snapshot)
            }
        },
        onImportMods = {
            if (hostActivity != null) {
                importModsLauncher.launch(
                    arrayOf("application/java-archive", "application/octet-stream", "*/*")
                )
            }
        },
        onLaunch = {
            if (hostActivity != null) {
                viewModel.onLaunch(hostActivity)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun LauncherMainScreenPreview() {
    LauncherMainScreenContent(
        uiState = MainScreenViewModel.UiState(
            initializing = false,
            busy = false,
            statusSummary = "desktop-1.0.jar: OK\nBaseMod.jar: OK\nStSLib.jar: OK",
            optionalMods = listOf(
                ModItemUi(
                    modId = "samplemod",
                    manifestModId = "SampleMod",
                    storagePath = "C:/mods/SampleMod.jar",
                    name = "Sample Mod",
                    version = "1.0.0",
                    description = "这是一个示例模组",
                    dependencies = listOf("basemod"),
                    required = false,
                    installed = true,
                    enabled = true
                )
            ),
            controlsEnabled = true,
            modFolders = listOf(
                MainScreenViewModel.ModFolder(id = "folder-demo", name = "示例文件夹")
            )
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LauncherMainScreenContent(
    modifier: Modifier = Modifier,
    uiState: MainScreenViewModel.UiState,
    hostActivity: Activity? = null,
    onOpenSettings: () -> Unit = {},
    onSuggestNextFolderName: () -> String = { "文件夹1" },
    onAddFolder: (String) -> Unit = {},
    onRenameFolder: (String, String) -> Unit = { _, _ -> },
    onDeleteFolder: (String) -> Unit = {},
    onDeleteMod: (ModItemUi) -> Unit = {},
    onExportMod: (ModItemUi) -> Unit = {},
    onShareMod: (ModItemUi) -> Unit = {},
    onRenameModFile: (ModItemUi, String) -> Unit = { _, _ -> },
    onToggleMod: (ModItemUi, Boolean) -> Unit = { _, _ -> },
    onSetFolderSelected: (String, Boolean) -> Unit = { _, _ -> },
    onSetUnassignedSelected: (Boolean) -> Unit = {},
    onToggleFolderCollapsed: (String) -> Unit = {},
    onToggleUnassignedCollapsed: () -> Unit = {},
    onToggleStatusSummaryCollapsed: () -> Unit = {},
    onMoveFolderUp: (String) -> Unit = {},
    onMoveFolderDown: (String) -> Unit = {},
    onMoveUnassignedUp: () -> Unit = {},
    onMoveUnassignedDown: () -> Unit = {},
    onMoveFolderTokenToIndex: (String, Int) -> Unit = { _, _ -> },
    onAssignModToFolder: (ModItemUi, String) -> Unit = { _, _ -> },
    onMoveModToUnassigned: (ModItemUi) -> Unit = {},
    onCollapseAllFoldersForDragWithSnapshot: () -> MainScreenViewModel.FolderCollapseSnapshot? = { null },
    onRestoreFolderCollapseSnapshot: (MainScreenViewModel.FolderCollapseSnapshot?) -> Unit = {},
    onImportMods: () -> Unit = {},
    onLaunch: () -> Unit = {},
) {
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var actionBarHeightPx by remember { mutableStateOf(0) }
    var minimumLoadingElapsed by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val actionBarBottomPadding = (
        with(density) { actionBarHeightPx.toDp() }.coerceAtMost(76.dp) + 8.dp
        ).coerceAtLeast(12.dp)
    val showInitializing = uiState.initializing || !minimumLoadingElapsed

    LaunchedEffect(Unit) {
        delay(1_000L)
        minimumLoadingElapsed = true
    }

    if (showCreateFolderDialog) {
        FolderNameInputDialog(
            title = stringResource(R.string.main_folder_dialog_create_title),
            initialText = onSuggestNextFolderName(),
            onDismiss = { showCreateFolderDialog = false },
            onConfirm = { name ->
                showCreateFolderDialog = false
                onAddFolder(name)
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.main_app_title)) },
                actions = {
                    IconButton(
                        onClick = { showCreateFolderDialog = true },
                        enabled = uiState.controlsEnabled && hostActivity != null
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_folder_add),
                            contentDescription = stringResource(R.string.main_action_add_folder)
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Settings,
                            contentDescription = stringResource(R.string.main_open_settings)
                        )
                    }
                }
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            Surface(
                modifier = Modifier
                    .navigationBarsPadding()
                    .onSizeChanged { actionBarHeightPx = it.height },
                color = Color.Transparent,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val floatingButtonElevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 8.dp,
                        pressedElevation = 4.dp,
                        disabledElevation = 0.dp
                    )
                    Button(
                        onClick = onImportMods,
                        enabled = uiState.controlsEnabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        elevation = floatingButtonElevation
                    ) {
                        Text(stringResource(R.string.main_import_mods))
                    }
                    Button(
                        onClick = onLaunch,
                        enabled = uiState.controlsEnabled,
                        elevation = floatingButtonElevation
                    ) {
                        Text(stringResource(R.string.main_launch_game))
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (uiState.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                uiState.busyMessage?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Text(
                text = stringResource(R.string.main_select_mods),
                style = MaterialTheme.typography.titleMedium
            )

            AnimatedContent(
                targetState = showInitializing,
                transitionSpec = {
                    fadeIn(animationSpec = tween(durationMillis = 180, delayMillis = 60)) togetherWith
                        fadeOut(animationSpec = tween(durationMillis = 120))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = "mainLoadingContent"
            ) { loading ->
                if (loading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = stringResource(R.string.main_loading_mods),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (uiState.optionalMods.isEmpty()) {
                            Text(
                                text = stringResource(R.string.main_no_mods_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }

                        ModFolderSection(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            uiState = uiState,
                            statusSummary = uiState.statusSummary,
                            contentBottomInset = actionBarBottomPadding,
                            hostActivity = hostActivity,
                            onToggleMod = onToggleMod,
                            onDeleteMod = onDeleteMod,
                            onExportMod = onExportMod,
                            onShareMod = onShareMod,
                            onRenameModFile = onRenameModFile,
                            onRenameFolder = onRenameFolder,
                            onDeleteFolder = onDeleteFolder,
                            onSetFolderSelected = onSetFolderSelected,
                            onSetUnassignedSelected = onSetUnassignedSelected,
                            onToggleFolderCollapsed = onToggleFolderCollapsed,
                            onToggleUnassignedCollapsed = onToggleUnassignedCollapsed,
                            onToggleStatusSummaryCollapsed = onToggleStatusSummaryCollapsed,
                            onMoveFolderUp = onMoveFolderUp,
                            onMoveFolderDown = onMoveFolderDown,
                            onMoveUnassignedUp = onMoveUnassignedUp,
                            onMoveUnassignedDown = onMoveUnassignedDown,
                            onMoveFolderTokenToIndex = onMoveFolderTokenToIndex,
                            onAssignModToFolder = onAssignModToFolder,
                            onMoveModToUnassigned = onMoveModToUnassigned,
                            onCollapseAllFoldersForDragWithSnapshot = onCollapseAllFoldersForDragWithSnapshot,
                            onRestoreFolderCollapseSnapshot = onRestoreFolderCollapseSnapshot
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderNameInputDialog(
    title: String,
    initialText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember(initialText) { mutableStateOf(initialText) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.main_folder_dialog_name_hint)) }
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    onConfirm(value.trim())
                }
            ) {
                Text(stringResource(R.string.main_folder_dialog_confirm))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.main_folder_dialog_cancel))
            }
        }
    )
}
