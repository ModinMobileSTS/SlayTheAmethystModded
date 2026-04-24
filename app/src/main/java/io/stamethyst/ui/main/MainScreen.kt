package io.stamethyst.ui.main

import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.stamethyst.BuildConfig
import io.stamethyst.R
import io.stamethyst.backend.render.RendererBackendResolver
import io.stamethyst.backend.steamcloud.SteamCloudConflict
import io.stamethyst.backend.steamcloud.SteamCloudUploadPlan
import io.stamethyst.ui.LauncherTransientNoticeBus
import io.stamethyst.model.ModItemUi
import io.stamethyst.ui.Icons
import io.stamethyst.ui.resolve
import io.stamethyst.ui.icon.Settings
import io.stamethyst.ui.preferences.LauncherPreferences
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import dev.chrisbanes.haze.rememberHazeState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherMainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel,
    onOpenSettings: () -> Unit = {},
    onOpenFeedback: () -> Unit = {},
    feedbackUnreadCount: Int = 0,
    onOpenFeedbackUpdates: () -> Unit = {},
) {
    val context = LocalContext.current
    val hostActivity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState = viewModel.uiState
    var effectDialog by remember { mutableStateOf<MainScreenViewModel.Effect.ShowDialog?>(null) }
    var pendingExportModSourcePath by remember { mutableStateOf<String?>(null) }
    val importModsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (hostActivity != null) {
            viewModel.onModJarsPicked(hostActivity, uris)
        }
    }
    val exportModLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/java-archive")
    ) { uri ->
        val activity = hostActivity
        val sourcePath = pendingExportModSourcePath
        pendingExportModSourcePath = null
        if (activity != null && sourcePath != null) {
            viewModel.onExportModPicked(activity, sourcePath, uri)
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
            viewModel.syncModSuggestionsIfNeeded(hostActivity)
            viewModel.syncSteamCloudIndicatorIfNeeded(hostActivity)
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
                    viewModel.syncModSuggestionsIfNeeded(activity)
                    viewModel.syncSteamCloudIndicatorIfNeeded(activity)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }

    LaunchedEffect(viewModel, hostActivity) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is MainScreenViewModel.Effect.ShowSnackbar ->
                    LauncherTransientNoticeBus.show(
                        message = effect.message,
                        duration = effect.duration,
                        actionLabel = effect.actionLabel,
                        onAction = effect.onAction
                    )
                is MainScreenViewModel.Effect.ShowDialog -> effectDialog = effect
                is MainScreenViewModel.Effect.OpenExportModPicker -> {
                    pendingExportModSourcePath = effect.sourcePath
                    exportModLauncher.launch(effect.suggestedName)
                }
                is MainScreenViewModel.Effect.LaunchIntent -> {
                    val activity = hostActivity ?: return@collect
                    activity.startActivity(effect.intent)
                }
            }
        }
    }

    effectDialog?.let { dialog ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { effectDialog = null },
            title = { Text(dialog.title.resolve()) },
            text = { Text(dialog.message.resolve()) },
            confirmButton = {
                Button(onClick = { effectDialog = null }) {
                    Text(text = stringResource(R.string.common_action_confirm))
                }
            }
        )
    }

    LauncherMainScreenContent(
        modifier = modifier,
        uiState = uiState,
        actions = actions,
        onOpenSettings = onOpenSettings,
        onOpenFeedback = onOpenFeedback,
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
            dependencyMods = listOf(
                ModItemUi(
                    modId = "desktop-1.0.jar",
                    manifestModId = "desktop-1.0.jar",
                    storagePath = "__dependency__/desktop-1.0.jar",
                    name = "desktop-1.0.jar",
                    version = "available",
                    description = "核心运行时 Jar",
                    dependencies = emptyList(),
                    required = true,
                    installed = true,
                    enabled = true,
                    explicitPriority = null,
                    effectivePriority = null
                ),
                ModItemUi(
                    modId = "modthespire",
                    manifestModId = "modthespire",
                    storagePath = "__dependency__/ModTheSpire.jar",
                    name = "ModTheSpire.jar",
                    version = "available",
                    description = "模组加载器",
                    dependencies = emptyList(),
                    required = true,
                    installed = true,
                    enabled = true,
                    explicitPriority = null,
                    effectivePriority = null
                ),
                ModItemUi(
                    modId = "basemod",
                    manifestModId = "BaseMod",
                    storagePath = "__dependency__/BaseMod.jar",
                    name = "BaseMod.jar",
                    version = "available",
                    description = "基础前置模组",
                    dependencies = emptyList(),
                    required = true,
                    installed = true,
                    enabled = true,
                    explicitPriority = null,
                    effectivePriority = null
                ),
                ModItemUi(
                    modId = "stslib",
                    manifestModId = "StSLib",
                    storagePath = "__dependency__/StSLib.jar",
                    name = "StSLib.jar",
                    version = "available",
                    description = "通用库前置模组",
                    dependencies = emptyList(),
                    required = true,
                    installed = true,
                    enabled = true,
                    explicitPriority = null,
                    effectivePriority = null
                )
            ),
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
                    enabled = true,
                    explicitPriority = 0,
                    effectivePriority = 0
                )
            ),
            controlsEnabled = true,
            modFolders = listOf(
                MainScreenViewModel.ModFolder(id = "folder-demo", name = "示例文件夹")
            )
        ),
        actions = MainScreenActions(isHostAvailable = true),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LauncherMainScreenContent(
    modifier: Modifier = Modifier,
    uiState: MainScreenViewModel.UiState,
    actions: MainScreenActions = MainScreenActions(isHostAvailable = false),
    onOpenSettings: () -> Unit = {},
    onOpenFeedback: () -> Unit = {},
    feedbackUnreadCount: Int = 0,
    onOpenFeedbackUpdates: () -> Unit = {},
) {
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showSteamCloudBottomSheet by remember { mutableStateOf(false) }
    var showSteamCloudConflictBypassDialog by remember { mutableStateOf(false) }
    var showSteamCloudFailureBypassDialog by remember { mutableStateOf(false) }
    val showInitializing = uiState.initializing
    val hazeState = rememberHazeState()
    val crashRecovery = uiState.crashRecovery
    val pendingLaunchUnreadSuggestionModNames = uiState.pendingLaunchUnreadSuggestionModNames
    val steamCloudIndicator = uiState.steamCloudIndicator
    val steamCloudBottomSheetVisible = showSteamCloudBottomSheet && steamCloudIndicator.visible
    val steamCloudBottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(steamCloudIndicator.visible) {
        if (!steamCloudIndicator.visible) {
            showSteamCloudBottomSheet = false
        }
    }

    LaunchedEffect(uiState.launchInFlight, pendingLaunchUnreadSuggestionModNames) {
        if (uiState.launchInFlight || pendingLaunchUnreadSuggestionModNames.isNotEmpty()) {
            showSteamCloudBottomSheet = false
        }
    }

    if (crashRecovery != null) {
        BackHandler(onBack = actions.onDismissCrashRecovery)
    }

    if (pendingLaunchUnreadSuggestionModNames.isNotEmpty()) {
        val unreadMessage = buildUnreadSuggestionLaunchWarningMessage(
            pendingLaunchUnreadSuggestionModNames
        )
        androidx.compose.material3.AlertDialog(
            onDismissRequest = actions.onCancelLaunchWithUnreadSuggestions,
            title = { Text(text = stringResource(R.string.main_launch_unread_suggestions_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(text = unreadMessage)
                }
            },
            confirmButton = {
                TextButton(onClick = actions.onConfirmLaunchWithUnreadSuggestions) {
                    Text(text = stringResource(R.string.main_launch_unread_suggestions_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = actions.onCancelLaunchWithUnreadSuggestions) {
                    Text(text = stringResource(R.string.main_launch_unread_suggestions_cancel))
                }
            }
        )
    }

    if (showSteamCloudConflictBypassDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSteamCloudConflictBypassDialog = false },
            title = {
                Text(text = stringResource(R.string.main_steam_cloud_conflict_bypass_dialog_title))
            },
            text = {
                Text(text = stringResource(R.string.main_steam_cloud_conflict_bypass_dialog_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSteamCloudConflictBypassDialog = false
                        showSteamCloudBottomSheet = false
                        actions.onLaunchDirect()
                    }
                ) {
                    Text(text = stringResource(R.string.main_steam_cloud_conflict_bypass_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSteamCloudConflictBypassDialog = false }) {
                    Text(text = stringResource(R.string.main_steam_cloud_conflict_bypass_dialog_cancel))
                }
            }
        )
    }

    if (showSteamCloudFailureBypassDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSteamCloudFailureBypassDialog = false },
            title = {
                Text(text = stringResource(R.string.main_steam_cloud_failure_bypass_dialog_title))
            },
            text = {
                Text(text = stringResource(R.string.main_steam_cloud_failure_bypass_dialog_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSteamCloudFailureBypassDialog = false
                        showSteamCloudBottomSheet = false
                        actions.onLaunchDirect()
                    }
                ) {
                    Text(text = stringResource(R.string.main_steam_cloud_failure_bypass_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSteamCloudFailureBypassDialog = false }) {
                    Text(text = stringResource(R.string.main_steam_cloud_failure_bypass_dialog_cancel))
                }
            }
        )
    }

    FolderNameDialog(
        visible = showCreateFolderDialog,
        title = stringResource(R.string.main_folder_dialog_create_title),
        initialText = actions.onSuggestNextFolderName(),
        onDismiss = { showCreateFolderDialog = false },
        onConfirm = { name ->
            actions.onAddFolder(name)
            showCreateFolderDialog = false
        }
    )

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { scaffoldPaddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(scaffoldPaddingValues)
        ) {
            if (crashRecovery != null) {
                CrashRecoveryScreen(
                    modifier = Modifier.fillMaxSize(),
                    crashRecovery = crashRecovery,
                    busy = uiState.busy,
                    busyMessage = uiState.busyMessage?.resolve(),
                    onOpenRecoverySettings = onOpenSettings,
                    onOpenFeedback = onOpenFeedback,
                    onRetryLaunch = actions.onRetryLaunchAfterCrash,
                    onCopyReport = actions.onCopyCrashReport,
                    onShareLogs = actions.onShareCrashRecoveryReport,
                    onCloseApp = actions.onCloseApp
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(state = hazeState)
                        .padding(start = 16.dp, top = 16.dp, end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (uiState.busy && !uiState.busyOperation.usesBlockingOverlay()) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        uiState.busyMessage?.let {
                            Text(text = it.resolve(), style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    MainContentSwitcher(
                        uiState = uiState,
                        showInitializing = showInitializing,
                        actionBarBottomPadding = 156.dp,
                        actions = actions
                    )
                }

                MainTopBar(
                    modifier = Modifier.align(Alignment.TopCenter),
                    hazeState = hazeState,
                    folderControlsEnabled = uiState.controlsEnabled,
                    dragLocked = uiState.dragLocked,
                    settingsEnabled = !uiState.busy &&
                        steamCloudIndicator.state != MainScreenViewModel.SteamCloudIndicatorState.SYNCING,
                    steamCloudEnabled = !uiState.busy,
                    hostAvailable = actions.isHostAvailable,
                    feedbackUnreadCount = feedbackUnreadCount,
                    steamCloudIndicator = steamCloudIndicator,
                    onToggleDragLocked = actions.onToggleDragLocked,
                    onAddFolderClick = { showCreateFolderDialog = true },
                    onOpenSettings = onOpenSettings,
                    onOpenFeedbackUpdates = onOpenFeedbackUpdates,
                    onSteamCloudClick = {
                        if (steamCloudIndicator.visible) {
                            showSteamCloudBottomSheet = true
                        }
                    }
                )
                MainBottomFixedActions(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    hazeState = hazeState,
                    importEnabled = !uiState.busy && uiState.storageIssue == null,
                    launchEnabled = !uiState.busy &&
                        uiState.storageIssue == null &&
                        !uiState.launchInFlight,
                    onImportMods = actions.onImportMods,
                    onLaunch = {
                        if (actions.onLaunch() == LaunchRequestAction.OPEN_STEAM_CLOUD_SHEET) {
                            showSteamCloudBottomSheet = true
                        }
                    },
                    enabledCount = uiState.optionalMods.count { it.enabled },
                    totalCount = uiState.optionalMods.size,
                    gameRunning = uiState.gameProcessRunning,
                    hasStorageIssue = uiState.storageIssue != null
                )
            }
        }
    }

    if (steamCloudBottomSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { showSteamCloudBottomSheet = false },
            sheetState = steamCloudBottomSheetState
        ) {
            SteamCloudBottomSheetContent(
                indicator = steamCloudIndicator,
                onRefresh = actions.onRefreshSteamCloudStatus,
                onCancelCheck = actions.onCancelSteamCloudCheck,
                onUseLocal = actions.onUseLocalSteamCloudProgress,
                onUseCloud = actions.onUseCloudSteamCloudProgress,
                onContinueWithoutResolvingConflict = {
                    showSteamCloudConflictBypassDialog = true
                },
                onContinueWithoutSteamSync = {
                    showSteamCloudFailureBypassDialog = true
                },
            )
        }
    }
}

@Composable
private fun buildUnreadSuggestionLaunchWarningMessage(modNames: List<String>): String {
    val visibleNames = modNames.take(5)
    val hiddenCount = (modNames.size - visibleNames.size).coerceAtLeast(0)
    return buildString {
        append(stringResource(R.string.main_launch_unread_suggestions_message_intro))
        append("\n\n")
        visibleNames.forEach { modName ->
            append("- ").append(modName).append('\n')
        }
        if (hiddenCount > 0) {
            append(
                stringResource(
                    R.string.main_launch_unread_suggestions_message_more_format,
                    hiddenCount
                )
            )
            append('\n')
        }
        append('\n')
        append(stringResource(R.string.main_launch_unread_suggestions_message_outro))
    }.trimEnd()
}

@Composable
private fun CrashRecoveryScreen(
    modifier: Modifier = Modifier,
    crashRecovery: MainScreenViewModel.CrashRecoveryState,
    busy: Boolean,
    busyMessage: String?,
    onOpenRecoverySettings: () -> Unit,
    onOpenFeedback: () -> Unit,
    onRetryLaunch: () -> Unit,
    onCopyReport: () -> Unit,
    onShareLogs: () -> Unit,
    onCloseApp: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val infoBadges = remember(crashRecovery.code, crashRecovery.isSignal, context, resources) {
        val rendererDecision = RendererBackendResolver.resolve(
            context = context,
            requestedSurfaceBackend = LauncherPreferences.readRenderSurfaceBackend(context),
            selectionMode = LauncherPreferences.readRendererSelectionMode(context),
            manualBackend = LauncherPreferences.readManualRendererBackend(context)
        )
        buildList {
            add(
                resources.getString(
                    R.string.sts_crash_page_chip_renderer_format,
                    rendererDecision.effectiveBackend.displayName
                )
            )
            add(
                resources.getString(
                    R.string.sts_crash_page_chip_android_format,
                    Build.VERSION.RELEASE.orEmpty().ifBlank { "?" },
                    Build.VERSION.SDK_INT
                )
            )
            add(
                resources.getString(
                    R.string.sts_crash_page_chip_build_format,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.BUILD_TYPE
                )
            )
            add(
                if (crashRecovery.isSignal) {
                    resources.getString(
                        R.string.sts_crash_page_chip_signal_format,
                        crashRecovery.code
                    )
                } else {
                    resources.getString(
                        R.string.sts_crash_page_chip_exit_code_format,
                        crashRecovery.code
                    )
                }
            )
        }
    }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(
            text = stringResource(R.string.sts_crash_page_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.sts_crash_page_subtitle),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            busyMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        CrashRecoveryCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(56.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        contentColor = MaterialTheme.colorScheme.primary
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "!",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.sts_crash_page_capture_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = crashRecovery.summaryText,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Text(
                    text = stringResource(
                        if (crashRecovery.isOutOfMemory) {
                            R.string.sts_crash_page_guidance_oom
                        } else if (crashRecovery.isLaunchPreparationProcessDisconnected) {
                            R.string.sts_crash_page_guidance_launch_preparation_process_disconnected
                        } else {
                            R.string.sts_crash_page_guidance_default
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    infoBadges.forEach { label ->
                        CrashInfoChip(text = label)
                    }
                }
            }
        }

        CrashRecoveryCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = stringResource(R.string.sts_crash_page_actions_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onOpenRecoverySettings,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.sts_crash_page_action_recovery))
                    }
                    OutlinedButton(
                        onClick = onRetryLaunch,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.sts_crash_page_action_retry))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onShareLogs,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.sts_crash_page_action_share))
                    }
                    OutlinedButton(
                        onClick = onCopyReport,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.sts_crash_page_action_copy))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenFeedback,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.sts_crash_page_action_feedback))
                    }
                    Button(
                        onClick = onCloseApp,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text(stringResource(R.string.sts_crash_page_action_close))
                    }
                }
            }
        }

        CrashRecoveryCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = stringResource(R.string.sts_crash_page_report_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.sts_crash_page_report_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SelectionContainer {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                        )
                    ) {
                        Text(
                            text = crashRecovery.reportText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CrashRecoveryCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
        ),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun CrashInfoChip(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MainTopBar(
    modifier: Modifier = Modifier,
    hazeState: HazeState,
    folderControlsEnabled: Boolean,
    dragLocked: Boolean,
    settingsEnabled: Boolean,
    steamCloudEnabled: Boolean,
    hostAvailable: Boolean,
    feedbackUnreadCount: Int,
    steamCloudIndicator: MainScreenViewModel.SteamCloudIndicatorUi,
    onToggleDragLocked: () -> Unit,
    onAddFolderClick: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFeedbackUpdates: () -> Unit,
    onSteamCloudClick: () -> Unit,
) {
    FrostedGlassChrome(
        modifier = modifier
            .fillMaxWidth(),
        hazeState = hazeState,
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
    ) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface
            ),
            windowInsets = TopAppBarDefaults.windowInsets,
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.main_app_title))
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            actions = {
                if (steamCloudIndicator.visible) {
                    SteamCloudStatusButton(
                        indicator = steamCloudIndicator,
                        enabled = steamCloudEnabled,
                        onClick = onSteamCloudClick
                    )
                }
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
                                contentDescription = stringResource(R.string.main_feedback_updates_content_description)
                            )
                        }
                    }
                }
                IconButton(
                    onClick = onToggleDragLocked,
                    enabled = folderControlsEnabled && hostAvailable
                ) {
                    Icon(
                        painter = painterResource(
                            if (dragLocked) R.drawable.ic_lock else R.drawable.ic_lock_open
                        ),
                        contentDescription = stringResource(
                            if (dragLocked) {
                                R.string.main_action_unlock_drag
                            } else {
                                R.string.main_action_lock_drag
                            }
                        )
                    )
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
}

@Composable
private fun SteamCloudStatusButton(
    indicator: MainScreenViewModel.SteamCloudIndicatorUi,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = steamCloudIndicatorTint(indicator.state)
    IconButton(onClick = onClick, enabled = enabled) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(steamCloudButtonIcon(indicator.state)),
                contentDescription = steamCloudButtonContentDescription(indicator.state),
                tint = tint
            )
            when (indicator.state) {
                MainScreenViewModel.SteamCloudIndicatorState.CHECKING,
                MainScreenViewModel.SteamCloudIndicatorState.SYNCING -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(12.dp),
                        strokeWidth = 1.6.dp
                    )
                }
                MainScreenViewModel.SteamCloudIndicatorState.HIDDEN,
                MainScreenViewModel.SteamCloudIndicatorState.UP_TO_DATE,
                MainScreenViewModel.SteamCloudIndicatorState.CONFLICT,
                MainScreenViewModel.SteamCloudIndicatorState.CONNECTION_FAILED -> Unit
            }
        }
    }
}

@Composable
private fun steamCloudIndicatorTint(
    state: MainScreenViewModel.SteamCloudIndicatorState,
): Color {
    return when (state) {
        MainScreenViewModel.SteamCloudIndicatorState.HIDDEN ->
            MaterialTheme.colorScheme.onSurfaceVariant
        MainScreenViewModel.SteamCloudIndicatorState.UP_TO_DATE ->
            Color(0xFF2E7D32)
        MainScreenViewModel.SteamCloudIndicatorState.CHECKING ->
            MaterialTheme.colorScheme.tertiary
        MainScreenViewModel.SteamCloudIndicatorState.CONFLICT ->
            Color(0xFFB26A00)
        MainScreenViewModel.SteamCloudIndicatorState.SYNCING ->
            MaterialTheme.colorScheme.primary
        MainScreenViewModel.SteamCloudIndicatorState.CONNECTION_FAILED ->
            MaterialTheme.colorScheme.error
    }
}

@DrawableRes
private fun steamCloudButtonIcon(
    state: MainScreenViewModel.SteamCloudIndicatorState,
): Int {
    return when (state) {
        MainScreenViewModel.SteamCloudIndicatorState.HIDDEN -> R.drawable.ic_cloud_queue
        MainScreenViewModel.SteamCloudIndicatorState.UP_TO_DATE -> R.drawable.ic_cloud_done
        MainScreenViewModel.SteamCloudIndicatorState.CHECKING -> R.drawable.ic_cloud_queue
        MainScreenViewModel.SteamCloudIndicatorState.CONFLICT -> R.drawable.ic_cloud_alert
        MainScreenViewModel.SteamCloudIndicatorState.SYNCING -> R.drawable.ic_cloud_sync
        MainScreenViewModel.SteamCloudIndicatorState.CONNECTION_FAILED -> R.drawable.ic_cloud_off
    }
}

@Composable
private fun steamCloudButtonContentDescription(
    state: MainScreenViewModel.SteamCloudIndicatorState,
): String {
    return when (state) {
        MainScreenViewModel.SteamCloudIndicatorState.HIDDEN ->
            stringResource(R.string.main_steam_cloud_button_hidden)
        MainScreenViewModel.SteamCloudIndicatorState.UP_TO_DATE ->
            stringResource(R.string.main_steam_cloud_button_up_to_date)
        MainScreenViewModel.SteamCloudIndicatorState.CHECKING ->
            stringResource(R.string.main_steam_cloud_button_checking)
        MainScreenViewModel.SteamCloudIndicatorState.CONFLICT ->
            stringResource(R.string.main_steam_cloud_button_conflict)
        MainScreenViewModel.SteamCloudIndicatorState.SYNCING ->
            stringResource(R.string.main_steam_cloud_button_syncing)
        MainScreenViewModel.SteamCloudIndicatorState.CONNECTION_FAILED ->
            stringResource(R.string.main_steam_cloud_button_failed)
    }
}

@Composable
private fun SteamCloudBottomSheetContent(
    indicator: MainScreenViewModel.SteamCloudIndicatorUi,
    onRefresh: () -> Unit,
    onCancelCheck: () -> Unit,
    onUseLocal: () -> Unit,
    onUseCloud: () -> Unit,
    onContinueWithoutResolvingConflict: () -> Unit,
    onContinueWithoutSteamSync: () -> Unit,
) {
    val tint = steamCloudIndicatorTint(indicator.state)
    val title = steamCloudActionBarTitle(indicator.state)
    val summary = steamCloudActionBarSummary(indicator)
    val progressFraction = indicator.progressPercent
        ?.coerceIn(0, 100)
        ?.div(100f)
    val conflictCardSummaries = remember(indicator.plan) {
        indicator.plan?.takeIf { it.conflicts.isNotEmpty() }?.let {
            buildSteamCloudConflictCardSummaries(it.conflicts)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = tint.copy(alpha = 0.12f),
                contentColor = tint
            ) {
                Icon(
                    painter = painterResource(steamCloudButtonIcon(indicator.state)),
                    contentDescription = null,
                    modifier = Modifier.padding(8.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (indicator.state == MainScreenViewModel.SteamCloudIndicatorState.CHECKING ||
            indicator.state == MainScreenViewModel.SteamCloudIndicatorState.SYNCING
        ) {
            if (progressFraction != null) {
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        if (indicator.progressCurrentPath.isNotBlank()) {
            Text(
                text = indicator.progressCurrentPath,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        indicator.plan?.warnings?.firstOrNull()?.let { warning ->
            Text(
                text = warning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        when (indicator.state) {
            MainScreenViewModel.SteamCloudIndicatorState.UP_TO_DATE,
            MainScreenViewModel.SteamCloudIndicatorState.CONNECTION_FAILED -> {
                Button(
                    onClick = onRefresh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(
                            if (indicator.state == MainScreenViewModel.SteamCloudIndicatorState.UP_TO_DATE) {
                                R.string.main_steam_cloud_action_recheck
                            } else {
                                R.string.main_steam_cloud_action_retry
                            }
                        )
                    )
                }
                if (indicator.state == MainScreenViewModel.SteamCloudIndicatorState.CONNECTION_FAILED) {
                    OutlinedButton(
                        onClick = onContinueWithoutSteamSync,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.main_steam_cloud_failure_bypass_action))
                    }
                }
            }

            MainScreenViewModel.SteamCloudIndicatorState.CONFLICT -> {
                if (conflictCardSummaries != null) {
                    SteamCloudConflictChoiceCard(
                        summary = conflictCardSummaries.local,
                        onSelect = onUseLocal,
                        actionLabel = stringResource(R.string.main_steam_cloud_conflict_use_local),
                    )
                    SteamCloudConflictChoiceCard(
                        summary = conflictCardSummaries.cloud,
                        onSelect = onUseCloud,
                        actionLabel = stringResource(R.string.main_steam_cloud_conflict_use_cloud),
                    )
                    OutlinedButton(
                        onClick = onContinueWithoutResolvingConflict,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.main_steam_cloud_conflict_bypass_action))
                    }
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onUseLocal,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(text = stringResource(R.string.main_steam_cloud_conflict_use_local))
                        }
                        OutlinedButton(
                            onClick = onUseCloud,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(text = stringResource(R.string.main_steam_cloud_conflict_use_cloud))
                        }
                        OutlinedButton(
                            onClick = onContinueWithoutResolvingConflict,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(text = stringResource(R.string.main_steam_cloud_conflict_bypass_action))
                        }
                    }
                }
            }

            MainScreenViewModel.SteamCloudIndicatorState.HIDDEN,
            MainScreenViewModel.SteamCloudIndicatorState.SYNCING -> Unit

            MainScreenViewModel.SteamCloudIndicatorState.CHECKING -> {
                OutlinedButton(
                    onClick = onCancelCheck,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.main_steam_cloud_action_cancel_check))
                }
            }
        }
    }
}

private data class SteamCloudConflictCardSummary(
    val side: SteamCloudConflictCardSide,
    val latestModifiedMs: Long?,
    val totalBytes: Long,
)

private data class SteamCloudConflictCardSummaries(
    val local: SteamCloudConflictCardSummary,
    val cloud: SteamCloudConflictCardSummary,
)

private enum class SteamCloudConflictCardSide {
    LOCAL,
    CLOUD,
}

private fun buildSteamCloudConflictCardSummaries(
    conflicts: List<SteamCloudConflict>,
): SteamCloudConflictCardSummaries {
    return SteamCloudConflictCardSummaries(
        local = buildSteamCloudConflictCardSummary(
            conflicts = conflicts,
            side = SteamCloudConflictCardSide.LOCAL,
        ),
        cloud = buildSteamCloudConflictCardSummary(
            conflicts = conflicts,
            side = SteamCloudConflictCardSide.CLOUD,
        ),
    )
}

private fun buildSteamCloudConflictCardSummary(
    conflicts: List<SteamCloudConflict>,
    side: SteamCloudConflictCardSide,
): SteamCloudConflictCardSummary {
    return when (side) {
        SteamCloudConflictCardSide.LOCAL -> {
            val currentEntries = conflicts.mapNotNull { conflict ->
                conflict.currentLocal?.let { conflict.localRelativePath to it }
            }
            val latestEntry = currentEntries.maxByOrNull { (_, entry) -> entry.lastModifiedMs }
            SteamCloudConflictCardSummary(
                side = side,
                latestModifiedMs = latestEntry?.second?.lastModifiedMs,
                totalBytes = currentEntries.sumOf { (_, entry) -> entry.fileSize },
            )
        }

        SteamCloudConflictCardSide.CLOUD -> {
            val currentEntries = conflicts.mapNotNull { conflict ->
                conflict.currentRemote?.let { conflict.localRelativePath to it }
            }
            val latestEntry = currentEntries.maxByOrNull { (_, entry) -> entry.timestamp }
            SteamCloudConflictCardSummary(
                side = side,
                latestModifiedMs = latestEntry?.second?.timestamp,
                totalBytes = currentEntries.sumOf { (_, entry) -> entry.rawSize },
            )
        }
    }
}

@Composable
private fun SteamCloudConflictChoiceCard(
    summary: SteamCloudConflictCardSummary,
    onSelect: () -> Unit,
    actionLabel: String,
) {
    val tint = when (summary.side) {
        SteamCloudConflictCardSide.LOCAL -> MaterialTheme.colorScheme.primary
        SteamCloudConflictCardSide.CLOUD -> MaterialTheme.colorScheme.secondary
    }
    val latestModifiedText = summary.latestModifiedMs
        ?.takeIf { it > 0L }
        ?.let(::formatSteamCloudTimestamp)
        ?: stringResource(R.string.update_unknown_date)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = tint.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.24f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(
                    when (summary.side) {
                        SteamCloudConflictCardSide.LOCAL ->
                            R.string.main_steam_cloud_conflict_card_local_title
                        SteamCloudConflictCardSide.CLOUD ->
                            R.string.main_steam_cloud_conflict_card_cloud_title
                    }
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tint
            )
            SteamCloudConflictMetaLine(
                text = stringResource(
                    R.string.main_steam_cloud_conflict_card_latest_modified,
                    latestModifiedText
                )
            )
            SteamCloudConflictMetaLine(
                text = stringResource(
                    R.string.main_steam_cloud_conflict_card_total_size,
                    formatSteamCloudBytes(summary.totalBytes)
                )
            )
            Button(
                onClick = onSelect,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = actionLabel)
            }
        }
    }
}

@Composable
private fun SteamCloudConflictMetaLine(
    text: String,
    monospace: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = if (monospace) FontFamily.Monospace else null,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun steamCloudActionBarTitle(
    state: MainScreenViewModel.SteamCloudIndicatorState,
): String {
    return when (state) {
        MainScreenViewModel.SteamCloudIndicatorState.HIDDEN ->
            stringResource(R.string.main_steam_cloud_bar_title_hidden)
        MainScreenViewModel.SteamCloudIndicatorState.UP_TO_DATE ->
            stringResource(R.string.main_steam_cloud_bar_title_up_to_date)
        MainScreenViewModel.SteamCloudIndicatorState.CHECKING ->
            stringResource(R.string.main_steam_cloud_bar_title_checking)
        MainScreenViewModel.SteamCloudIndicatorState.CONFLICT ->
            stringResource(R.string.main_steam_cloud_bar_title_conflict)
        MainScreenViewModel.SteamCloudIndicatorState.SYNCING ->
            stringResource(R.string.main_steam_cloud_bar_title_syncing)
        MainScreenViewModel.SteamCloudIndicatorState.CONNECTION_FAILED ->
            stringResource(R.string.main_steam_cloud_bar_title_failed)
    }
}

@Composable
private fun steamCloudActionBarSummary(
    indicator: MainScreenViewModel.SteamCloudIndicatorUi,
): String {
    return when (indicator.state) {
        MainScreenViewModel.SteamCloudIndicatorState.HIDDEN ->
            stringResource(R.string.main_steam_cloud_bar_summary_hidden)
        MainScreenViewModel.SteamCloudIndicatorState.UP_TO_DATE ->
            stringResource(R.string.main_steam_cloud_bar_summary_up_to_date)
        MainScreenViewModel.SteamCloudIndicatorState.CHECKING ->
            stringResource(R.string.main_steam_cloud_bar_summary_checking)
        MainScreenViewModel.SteamCloudIndicatorState.CONFLICT -> {
            if (indicator.plan == null) {
                stringResource(R.string.main_steam_cloud_bar_summary_conflict_missing)
            } else {
                stringResource(R.string.main_steam_cloud_conflict_choice_hint)
            }
        }
        MainScreenViewModel.SteamCloudIndicatorState.SYNCING ->
            indicator.progressMessage.ifBlank {
                stringResource(R.string.main_steam_cloud_bar_summary_syncing)
            }
        MainScreenViewModel.SteamCloudIndicatorState.CONNECTION_FAILED ->
            if (indicator.errorSummary.isNotBlank()) {
                indicator.errorSummary
            } else {
                stringResource(R.string.main_steam_cloud_bar_summary_failed)
            }
    }
}

private fun formatSteamCloudBytes(bytes: Long): String {
    val kib = 1024.0
    val mib = kib * 1024.0
    return when {
        bytes >= mib -> String.format(Locale.US, "%.1f MiB", bytes / mib)
        bytes >= kib -> String.format(Locale.US, "%.1f KiB", bytes / kib)
        else -> "$bytes B"
    }
}

private fun formatSteamCloudTimestamp(timestampMs: Long): String {
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(timestampMs))
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
    modifier: Modifier = Modifier,
    hazeState: HazeState,
    importEnabled: Boolean,
    launchEnabled: Boolean,
    onImportMods: () -> Unit,
    onLaunch: () -> Unit,
    enabledCount: Int,
    totalCount: Int,
    gameRunning: Boolean,
    hasStorageIssue: Boolean
) {
    FrostedGlassChrome(
        modifier = modifier
            .fillMaxWidth(),
        hazeState = hazeState,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        val buttonShape = RoundedCornerShape(16.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.main_enabled_mods_count, enabledCount, totalCount),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = when {
                    hasStorageIssue -> stringResource(R.string.main_status_storage_unavailable_os_issue)
                    gameRunning -> stringResource(R.string.main_status_game_running)
                    else -> stringResource(R.string.main_status_mods_ok)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onImportMods,
                enabled = importEnabled,
                shape = buttonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.42f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(124.dp)
                    .height(46.dp)
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
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f),
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(148.dp)
                    .height(46.dp)
            ) {
                Text(
                    text = if (gameRunning) {
                        stringResource(R.string.main_restart_game)
                    } else {
                        stringResource(R.string.main_launch_game)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        }
    }
}

@Composable
@OptIn(ExperimentalHazeMaterialsApi::class)
private fun FrostedGlassChrome(
    modifier: Modifier = Modifier,
    hazeState: HazeState,
    shape: Shape,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val chromeModifier = modifier
        .clip(shape)
        .hazeEffect(
            state = hazeState,
            style = HazeMaterials.ultraThin()
        ) {
            blurRadius = 12.dp
            blurredEdgeTreatment = BlurredEdgeTreatment(shape)
        }
    Surface(
        modifier = chromeModifier,
        shape = shape,
        color = Color.Transparent,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f)
        ),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding)
        ) {
            content()
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
                uiState.storageIssue?.let { issue ->
                    StorageIssueCard(
                        issue = issue,
                        retryEnabled = actions.isHostAvailable && !uiState.busy,
                        onRetry = actions.onRetryStorageCheck
                    )
                }

                if (uiState.optionalMods.isEmpty() && uiState.storageIssue == null) {
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
                    contentBottomInset = actionBarBottomPadding,
                    hostAvailable = actions.isHostAvailable,
                    callbacks = ModFolderSectionCallbacks(
                        onToggleMod = actions.onToggleMod,
                        onSetPriority = actions.onSetPriority,
                        onDeleteMod = actions.onDeleteMod,
                        onExportMod = actions.onExportMod,
                        onShareMod = actions.onShareMod,
                        onRenameModFile = actions.onRenameModFile,
                        onRenameFolder = actions.onRenameFolder,
                        onDeleteFolder = actions.onDeleteFolder,
                        onMarkModSuggestionRead = actions.onMarkModSuggestionRead,
                        onSetFolderSelected = actions.onSetFolderSelected,
                        onSetUnassignedSelected = actions.onSetUnassignedSelected,
                        onToggleFolderCollapsed = actions.onToggleFolderCollapsed,
                        onToggleUnassignedCollapsed = actions.onToggleUnassignedCollapsed,
                        onToggleDependencyFolderCollapsed = actions.onToggleDependencyFolderCollapsed,
                        onMoveFolderUp = actions.onMoveFolderUp,
                        onMoveFolderDown = actions.onMoveFolderDown,
                        onMoveUnassignedUp = actions.onMoveUnassignedUp,
                        onMoveUnassignedDown = actions.onMoveUnassignedDown,
                        onMoveFolderTokenToIndex = actions.onMoveFolderTokenToIndex,
                        onAssignModToFolder = actions.onAssignModToFolder,
                        onMoveModToUnassigned = actions.onMoveModToUnassigned,
                        onRevealFolderToken = actions.onRevealFolderToken
                    )
                )
            }
        }
    }
}

@Composable
private fun StorageIssueCard(
    issue: MainScreenViewModel.StorageIssueUi,
    retryEnabled: Boolean,
    onRetry: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = issue.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = issue.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = issue.recovery,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Button(
                onClick = onRetry,
                enabled = retryEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onErrorContainer,
                    contentColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(text = stringResource(R.string.main_storage_issue_retry))
            }
        }
    }
}
