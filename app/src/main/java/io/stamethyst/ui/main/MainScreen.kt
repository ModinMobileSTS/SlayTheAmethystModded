package io.stamethyst.ui.main

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.stamethyst.R
import io.stamethyst.model.ModItemUi
import io.stamethyst.ui.Icons
import io.stamethyst.ui.UiBusyOperation
import io.stamethyst.ui.icon.Settings
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherMainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = MainScreenViewModel(),
    onOpenSettings: () -> Unit = {},
    feedbackUnreadCount: Int = 0,
    onOpenFeedbackUpdates: () -> Unit = {},
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
    val actions = rememberMainScreenActions(
        viewModel = viewModel,
        hostActivity = hostActivity,
        importModsLauncher = importModsLauncher
    )

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
        actions = actions,
        onOpenSettings = onOpenSettings,
        feedbackUnreadCount = feedbackUnreadCount,
        onOpenFeedbackUpdates = onOpenFeedbackUpdates
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
        ),
        actions = MainScreenActions(isHostAvailable = true)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LauncherMainScreenContent(
    modifier: Modifier = Modifier,
    uiState: MainScreenViewModel.UiState,
    actions: MainScreenActions = MainScreenActions(isHostAvailable = false),
    onOpenSettings: () -> Unit = {},
    feedbackUnreadCount: Int = 0,
    onOpenFeedbackUpdates: () -> Unit = {},
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

    FolderNameDialog(
        visible = showCreateFolderDialog,
        title = stringResource(R.string.main_folder_dialog_create_title),
        initialText = actions.onSuggestNextFolderName(),
        onDismiss = { showCreateFolderDialog = false },
        onConfirm = { name ->
            showCreateFolderDialog = false
            actions.onAddFolder(name)
        }
    )

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            MainTopBar(
                folderControlsEnabled = uiState.controlsEnabled,
                settingsEnabled = !uiState.busy,
                hostAvailable = actions.isHostAvailable,
                feedbackUnreadCount = feedbackUnreadCount,
                onAddFolderClick = { showCreateFolderDialog = true },
                onOpenSettings = onOpenSettings,
                onOpenFeedbackUpdates = onOpenFeedbackUpdates
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            MainBottomFixedActions(
                importEnabled = !uiState.busy,
                launchEnabled = !uiState.busy,
                onImportMods = actions.onImportMods,
                onLaunch = actions.onLaunch,
                onMeasured = { actionBarHeightPx = it }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (uiState.busy && uiState.busyOperation != UiBusyOperation.MOD_IMPORT) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                uiState.busyMessage?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Text(
                text = stringResource(R.string.main_select_mods),
                style = MaterialTheme.typography.titleMedium
            )

            MainContentSwitcher(
                uiState = uiState,
                showInitializing = showInitializing,
                actionBarBottomPadding = actionBarBottomPadding,
                actions = actions
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MainTopBar(
    folderControlsEnabled: Boolean,
    settingsEnabled: Boolean,
    hostAvailable: Boolean,
    feedbackUnreadCount: Int,
    onAddFolderClick: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFeedbackUpdates: () -> Unit
) {
    TopAppBar(
        title = { Text(stringResource(R.string.main_app_title)) },
        actions = {
            if (feedbackUnreadCount > 0) {
                IconButton(
                    onClick = onOpenFeedbackUpdates,
                    enabled = settingsEnabled
                ) {
                    NotificationBadge(
                        count = feedbackUnreadCount,
                        badgeShape = RoundedCornerShape(999.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_feedback_updates),
                            contentDescription = "反馈有更新"
                        )
                    }
                }
            }
            IconButton(
                onClick = onAddFolderClick,
                enabled = folderControlsEnabled && hostAvailable
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_folder_add),
                    contentDescription = stringResource(R.string.main_action_add_folder)
                )
            }
            IconButton(
                onClick = onOpenSettings,
                enabled = settingsEnabled
            ) {
                Icon(
                    imageVector = Icons.Settings,
                    contentDescription = stringResource(R.string.main_open_settings)
                )
            }
        }
    )
}

@Composable
private fun NotificationBadge(
    count: Int,
    badgeShape: Shape,
    content: @Composable () -> Unit
) {
    Box {
        content()
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 6.dp, y = (-4).dp)
                .background(
                    color = MaterialTheme.colorScheme.error,
                    shape = badgeShape
                )
                .padding(horizontal = 5.dp, vertical = 1.dp)
        ) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                color = MaterialTheme.colorScheme.onError,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun MainBottomFixedActions(
    importEnabled: Boolean,
    launchEnabled: Boolean,
    onImportMods: () -> Unit,
    onLaunch: () -> Unit,
    onMeasured: (Int) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .onSizeChanged { onMeasured(it.height) },
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        val buttonShape = RoundedCornerShape(16.dp)
        val buttonWidth = 120.dp
        val buttonHeight = 44.dp
        val flatButtonElevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            disabledElevation = 0.dp
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onImportMods,
                enabled = importEnabled,
                shape = buttonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                ),
                elevation = flatButtonElevation,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(buttonWidth)
                    .height(buttonHeight)
            ) {
                Text(
                    text = stringResource(R.string.main_import_mods),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Button(
                onClick = onLaunch,
                enabled = launchEnabled,
                shape = buttonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                ),
                elevation = flatButtonElevation,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(buttonWidth)
                    .height(buttonHeight)
            ) {
                Text(
                    text = stringResource(R.string.main_launch_game),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.MainContentSwitcher(
    uiState: MainScreenViewModel.UiState,
    showInitializing: Boolean,
    actionBarBottomPadding: Dp,
    actions: MainScreenActions
) {
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
                    hostAvailable = actions.isHostAvailable,
                    callbacks = ModFolderSectionCallbacks(
                        onToggleMod = actions.onToggleMod,
                        onDeleteMod = actions.onDeleteMod,
                        onExportMod = actions.onExportMod,
                        onShareMod = actions.onShareMod,
                        onRenameModFile = actions.onRenameModFile,
                        onRenameFolder = actions.onRenameFolder,
                        onDeleteFolder = actions.onDeleteFolder,
                        onSetFolderSelected = actions.onSetFolderSelected,
                        onSetUnassignedSelected = actions.onSetUnassignedSelected,
                        onToggleFolderCollapsed = actions.onToggleFolderCollapsed,
                        onToggleUnassignedCollapsed = actions.onToggleUnassignedCollapsed,
                        onToggleStatusSummaryCollapsed = actions.onToggleStatusSummaryCollapsed,
                        onMoveFolderUp = actions.onMoveFolderUp,
                        onMoveFolderDown = actions.onMoveFolderDown,
                        onMoveUnassignedUp = actions.onMoveUnassignedUp,
                        onMoveUnassignedDown = actions.onMoveUnassignedDown,
                        onMoveFolderTokenToIndex = actions.onMoveFolderTokenToIndex,
                        onAssignModToFolder = actions.onAssignModToFolder,
                        onMoveModToUnassigned = actions.onMoveModToUnassigned,
                        onCollapseAllFoldersForModDrag = actions.onCollapseAllFoldersForModDrag,
                        onExpandOnlySourceFolderAfterModDrag = actions.onExpandOnlySourceFolderAfterModDrag,
                        onCollapseAllFoldersForDragWithSnapshot = actions.onCollapseAllFoldersForDragWithSnapshot,
                        onRestoreFolderCollapseSnapshot = actions.onRestoreFolderCollapseSnapshot
                    )
                )
            }
        }
    }
}
