package io.stamethyst.ui.main

import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.stamethyst.BuildConfig
import io.stamethyst.R
import io.stamethyst.backend.render.RendererBackendResolver
import io.stamethyst.backend.steamcloud.SteamCloudSyncDirection
import io.stamethyst.backend.steamcloud.SteamCloudUserWarning
import io.stamethyst.backend.steamcloud.SteamCloudUploadPlan
import io.stamethyst.backend.workshop.WorkshopUpdateCheckCoordinator
import io.stamethyst.backend.workshop.WorkshopUpdateCheckUiState
import io.stamethyst.backend.workshop.isActiveDownload
import io.stamethyst.ui.CollapsibleFloatingGlassHeader
import io.stamethyst.ui.FloatingGlassHeader
import io.stamethyst.ui.LauncherTransientNoticeBus
import io.stamethyst.ui.UiText
import io.stamethyst.model.ModItemUi
import io.stamethyst.model.WorkshopModState
import io.stamethyst.ui.Icons
import io.stamethyst.ui.resolve
import io.stamethyst.ui.icon.ArrowBack
import io.stamethyst.ui.icon.Settings
import io.stamethyst.ui.modimport.ModImportRequestBus
import io.stamethyst.ui.preferences.LauncherPreferences
import io.stamethyst.ui.workshop.WorkshopDownloadCenterStore
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private enum class SteamCloudConflictResolutionChoice {
    USE_LOCAL,
    USE_CLOUD,
}

private enum class SteamCloudNetworkPromptAction {
    REFRESH,
    USE_LOCAL,
    USE_CLOUD,
}

private const val MODS_CONTENT_MOUNT_DELAY_MS = 80L

@Composable
private fun LauncherGamePage(
    modifier: Modifier = Modifier,
    uiState: MainScreenViewModel.UiState,
    actions: MainScreenActions,
    updateNotice: LauncherUpdateNoticeUiState?,
    feedbackUnreadCount: Int,
    onOpenFeedbackUpdates: () -> Unit,
    onOpenFeedbackSubscriptions: () -> Unit,
    onUpdateNoticeClick: () -> Unit,
    onEnabledModsClick: () -> Unit,
    onModSizeClick: () -> Unit,
    onSteamCloudClick: () -> Unit,
    onLaunch: () -> Unit,
) {
    val steamCloudIndicator = uiState.steamCloudIndicator
    val enabledMods = remember(uiState.optionalMods) {
        uiState.optionalMods.filter { mod -> mod.enabled && mod.installed && !mod.required }
    }
    val enabledModBytes = remember(enabledMods) {
        enabledMods.sumOf { mod -> File(mod.storagePath).takeIf { it.isFile }?.length() ?: 0L }
    }
    val launchEnabled = !uiState.busy && uiState.storageIssue == null && !uiState.launchInFlight
    val headerActionsEnabled = !uiState.busy &&
        steamCloudIndicator.state != MainScreenViewModel.SteamCloudIndicatorState.SYNCING
    val gameHeaderHazeState = rememberHazeState()
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    val gameHeaderCollapsed = scrollState.value > with(density) { 24.dp.roundToPx() }
    val gameHeaderContentTopInset = 104.dp

    Box(
        modifier = modifier
            .fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = gameHeaderHazeState)
                .verticalScroll(scrollState)
                .padding(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 118.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(gameHeaderContentTopInset))

            if (uiState.busy && !uiState.busyOperation.usesBlockingOverlay()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                uiState.busyMessage?.let { message ->
                    Text(
                        text = message.resolve(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            uiState.storageIssue?.let { issue ->
                StorageIssueCard(
                    issue = issue,
                    retryEnabled = actions.isHostAvailable && !uiState.busy,
                    onRetry = actions.onRetryStorageCheck,
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                GameStatusHeroCard(
                    enabledModCount = enabledMods.size,
                    totalModCount = uiState.optionalMods.size,
                    enabledModBytes = enabledModBytes,
                    gameRunning = uiState.gameProcessRunning,
                    hasStorageIssue = uiState.storageIssue != null,
                    onEnabledModsClick = onEnabledModsClick,
                    onModSizeClick = onModSizeClick,
                )

                if (feedbackUnreadCount > 0) {
                    FeedbackReplyUpdateCard(
                        unreadCount = feedbackUnreadCount,
                        onView = onOpenFeedbackSubscriptions,
                    )
                }

                updateNotice?.let { notice ->
                    LauncherUpdateNoticeCard(
                        notice = notice,
                        onClick = onUpdateNoticeClick,
                    )
                }

                SteamCloudOverviewCard(
                    indicator = steamCloudIndicator,
                    onClick = onSteamCloudClick,
                )
            }
        }

        GameLaunchActionBar(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 28.dp, bottom = 120.dp),
            enabled = launchEnabled,
            gameRunning = uiState.gameProcessRunning,
            onLaunch = onLaunch,
        )

        CollapsibleFloatingGlassHeader(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
            hazeState = gameHeaderHazeState,
            collapsed = gameHeaderCollapsed,
            shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
            contentPadding = PaddingValues(0.dp),
            pinnedContent = {
                GameHeader(
                    feedbackUnreadCount = feedbackUnreadCount,
                    headerActionsEnabled = headerActionsEnabled,
                    onOpenFeedbackUpdates = onOpenFeedbackUpdates,
                )
            },
        )
    }
}

@Composable
private fun GameHeader(
    feedbackUnreadCount: Int,
    headerActionsEnabled: Boolean,
    onOpenFeedbackUpdates: () -> Unit,
) {
    HeaderPinnedRow(
        iconResId = R.drawable.ic_dock_game,
        iconContentDescription = null,
        iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
        iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        title = stringResource(R.string.main_app_title),
        subtitle = "v${BuildConfig.VERSION_NAME}",
        iconSize = 30.dp,
    ) {
        if (feedbackUnreadCount > 0) {
            CompactTopBarIconButton(
                onClick = onOpenFeedbackUpdates,
                enabled = headerActionsEnabled,
            ) {
                NotificationBadge(
                    count = feedbackUnreadCount,
                    badgeShape = RoundedCornerShape(999.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_feedback_updates),
                        contentDescription = stringResource(R.string.main_feedback_updates_content_description),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderPinnedRow(
    @DrawableRes iconResId: Int,
    iconContentDescription: String?,
    iconContainerColor: Color,
    iconContentColor: Color,
    title: String,
    subtitle: String,
    iconSize: Dp,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            modifier = Modifier.size(52.dp),
            shape = RoundedCornerShape(18.dp),
            color = iconContainerColor,
            contentColor = iconContentColor,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(iconResId),
                    contentDescription = iconContentDescription,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        actions()
    }
}

@Composable
private fun LoadingStateCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.main_loading_mods),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GameInfoChip(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.44f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun GameLaunchActionBar(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    gameRunning: Boolean,
    onLaunch: () -> Unit,
) {
    Button(
        onClick = onLaunch,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .height(56.dp),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 2.dp,
            pressedElevation = 1.dp,
            disabledElevation = 0.dp,
        ),
        contentPadding = PaddingValues(horizontal = 22.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_dock_game),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (gameRunning) {
                stringResource(R.string.main_restart_game)
            } else {
                stringResource(R.string.main_launch_game)
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ModsHeaderPinnedContent(
    folderControlsEnabled: Boolean,
    dragLocked: Boolean,
    hostAvailable: Boolean,
    feedbackUnreadCount: Int,
    workshopUpdateCheckState: WorkshopUpdateCheckUiState,
    onToggleDragLocked: () -> Unit,
    onAddFolderClick: () -> Unit,
    onCheckWorkshopUpdates: () -> Unit,
    onOpenFeedbackUpdates: () -> Unit,
) {
    val canEditFolders = folderControlsEnabled && hostAvailable
    HeaderPinnedRow(
        iconResId = R.drawable.ic_dock_mods,
        iconContentDescription = null,
        iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        title = stringResource(R.string.main_mods_title),
        subtitle = "",
        iconSize = 28.dp,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (feedbackUnreadCount > 0) {
                CompactTopBarIconButton(
                    onClick = onOpenFeedbackUpdates,
                    enabled = true,
                ) {
                    NotificationBadge(
                        count = feedbackUnreadCount,
                        badgeShape = RoundedCornerShape(999.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_feedback_updates),
                            contentDescription = stringResource(R.string.main_feedback_updates_content_description),
                        )
                    }
                }
            }
            WorkshopUpdateCheckButton(
                state = workshopUpdateCheckState,
                enabled = canEditFolders,
                onClick = onCheckWorkshopUpdates,
            )
            CompactTopBarIconButton(
                onClick = onToggleDragLocked,
                enabled = canEditFolders,
            ) {
                DragLockStateIcon(dragLocked = dragLocked)
            }
            CompactTopBarIconButton(
                onClick = onAddFolderClick,
                enabled = canEditFolders,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_folder_add),
                    contentDescription = stringResource(R.string.main_action_add_folder),
                )
            }
        }
    }
}

@Composable
private fun WorkshopUpdateCheckButton(
    state: WorkshopUpdateCheckUiState,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val checking = state.checking
    CompactTopBarIconButton(
        onClick = onClick,
        enabled = enabled && !checking,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_check_circle),
                contentDescription = if (checking) stringResource(R.string.main_workshop_checking) else stringResource(R.string.main_workshop_up_to_date),
            )
            if (checking) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(12.dp),
                    strokeWidth = 1.6.dp,
                )
            }
        }
    }
}

@Composable
private fun ModsHeaderExpandedContent(
    folderControlsEnabled: Boolean,
    dragLocked: Boolean,
    hostAvailable: Boolean,
    enabledCount: Int,
    totalCount: Int,
    folderCount: Int,
    importEnabled: Boolean,
    profiles: List<ModLaunchProfile>,
    activeProfileId: String,
    profileEnabled: Boolean,
    onImportMods: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onAddProfile: (String) -> Unit,
    onRenameProfile: (String, String) -> Unit,
    onDeleteProfile: (String) -> Unit,
) {
    val canEditFolders = folderControlsEnabled && hostAvailable

//    if (!canEditFolders) {
//        Text(
//            text = stringResource(R.string.main_mod_folder_controls_unavailable),
//            style = MaterialTheme.typography.bodySmall,
//            color = MaterialTheme.colorScheme.onSurfaceVariant,
//        )
//    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onImportMods,
            enabled = importEnabled,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
        ) {
            Text(
                text = stringResource(R.string.main_import_mods),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.56f)),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.36f),
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                ModLaunchProfileMenu(
                    modifier = Modifier.fillMaxSize(),
                    profiles = profiles,
                    activeProfileId = activeProfileId,
                    enabled = profileEnabled,
                    onSelectProfile = onSelectProfile,
                    onAddProfile = onAddProfile,
                    onRenameProfile = onRenameProfile,
                    onDeleteProfile = onDeleteProfile,
                )
            }
        }
    }
}

@Composable
private fun GameStatusHeroCard(
    enabledModCount: Int,
    totalModCount: Int,
    enabledModBytes: Long,
    gameRunning: Boolean,
    hasStorageIssue: Boolean,
    onEnabledModsClick: () -> Unit,
    onModSizeClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.main_game_overview_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GameMetricCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.main_game_loaded_mods),
                    value = "$enabledModCount / $totalModCount",
                    onClick = onEnabledModsClick,
                )
                GameMetricCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.main_game_mod_size),
                    value = formatLauncherByteSize(enabledModBytes),
                    onClick = onModSizeClick,
                )
            }
            TextButton(onClick = onEnabledModsClick) {
                Text(
                    text = when {
                        hasStorageIssue -> stringResource(R.string.main_status_storage_unavailable_os_issue)
                        gameRunning -> stringResource(R.string.main_status_game_running)
                        else -> stringResource(R.string.main_status_mods_ok)
                    },
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun GameMetricCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    val surfaceModifier = if (onClick == null) {
        modifier
    } else {
        modifier.clickable(onClick = onClick)
    }
    Surface(
        modifier = surfaceModifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.56f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun FeedbackReplyUpdateCard(
    unreadCount: Int,
    onView: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f),
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f),
                    contentColor = MaterialTheme.colorScheme.tertiary,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_feedback_updates),
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.main_feedback_reply_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (unreadCount == 1) {
                            stringResource(R.string.main_feedback_reply_card_single)
                        } else {
                            stringResource(R.string.main_feedback_reply_card_multiple, unreadCount)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.82f),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = onView) {
                    Text(text = stringResource(R.string.main_feedback_reply_card_action))
                }
            }
        }
    }
}

@Composable
private fun LauncherUpdateNoticeCard(
    notice: LauncherUpdateNoticeUiState,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_update_available),
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.main_update_notice_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        R.string.main_update_notice_card_version,
                        notice.currentVersion,
                        notice.latestVersion,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
//                Text(
//                    text = stringResource(R.string.main_update_notice_card_summary),
//                    style = MaterialTheme.typography.bodySmall,
//                    color = MaterialTheme.colorScheme.onSurfaceVariant,
//                )
            }
        }
    }
}

@Composable
private fun SteamCloudOverviewCard(
    indicator: MainScreenViewModel.SteamCloudIndicatorUi,
    onClick: () -> Unit,
) {
    val visibleIndicator = indicator.visible
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        enabled = visibleIndicator,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (visibleIndicator) {
                    steamCloudIndicatorTint(indicator.state).copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (visibleIndicator) {
                    steamCloudIndicatorTint(indicator.state)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Icon(
                    painter = painterResource(
                        if (visibleIndicator) {
                            steamCloudButtonIcon(indicator.state)
                        } else {
                            R.drawable.ic_cloud_off
                        }
                    ),
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.main_steam_cloud_status_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (visibleIndicator) {
                        steamCloudActionBarTitle(indicator.state)
                    } else {
                        stringResource(R.string.main_steam_cloud_not_enabled_or_signed_in)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = if (visibleIndicator) {
                        steamCloudActionBarSummary(indicator)
                    } else {
                        stringResource(R.string.main_steam_cloud_disabled_summary)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private enum class LauncherMainContentMode {
    GAME,
    MODS,
}

data class LauncherUpdateNoticeUiState(
    val currentVersion: String,
    val latestVersion: String,
)

private const val STEAM_CLOUD_AUTO_RETRY_INITIAL_DELAY_SECONDS = 5
private const val STEAM_CLOUD_AUTO_RETRY_MAX_DELAY_SECONDS = 300
private const val STEAM_CLOUD_AUTO_RETRY_STORED_ATTEMPT_CAP = 7

private data class SteamCloudAutoRetryUiState(
    val inProgress: Boolean = false,
    val delaySeconds: Int = STEAM_CLOUD_AUTO_RETRY_INITIAL_DELAY_SECONDS,
    val countdownSeconds: Int = STEAM_CLOUD_AUTO_RETRY_INITIAL_DELAY_SECONDS,
)

internal fun steamCloudAutoRetryDelaySeconds(attemptIndex: Int): Int {
    var delaySeconds = STEAM_CLOUD_AUTO_RETRY_INITIAL_DELAY_SECONDS
    repeat(attemptIndex.coerceAtLeast(0)) {
        if (delaySeconds >= STEAM_CLOUD_AUTO_RETRY_MAX_DELAY_SECONDS) {
            return STEAM_CLOUD_AUTO_RETRY_MAX_DELAY_SECONDS
        }
        delaySeconds = (delaySeconds * 2).coerceAtMost(STEAM_CLOUD_AUTO_RETRY_MAX_DELAY_SECONDS)
    }
    return delaySeconds
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherMainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel,
    onOpenFeedback: () -> Unit = {},
    onOpenWorkshop: () -> Unit = {},
    onOpenWorkshopDetails: (ModItemUi) -> Unit = {},
    updateNotice: LauncherUpdateNoticeUiState? = null,
    feedbackUnreadCount: Int = 0,
    onOpenFeedbackUpdates: () -> Unit = {},
    onOpenFeedbackSubscriptions: () -> Unit = {},
    onUpdateNoticeClick: () -> Unit = {},
) {
    LauncherMainRoute(
        modifier = modifier,
        viewModel = viewModel,
        onOpenWorkshop = onOpenWorkshop,
        onOpenWorkshopDetails = onOpenWorkshopDetails,
    ) { routeModifier, uiState, actions ->
        LauncherGameScreenContent(
            modifier = routeModifier,
            uiState = uiState,
            actions = actions,
            onOpenFeedback = onOpenFeedback,
            updateNotice = updateNotice,
            feedbackUnreadCount = feedbackUnreadCount,
            onOpenFeedbackUpdates = onOpenFeedbackUpdates,
            onOpenFeedbackSubscriptions = onOpenFeedbackSubscriptions,
            onUpdateNoticeClick = onUpdateNoticeClick,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherModsScreen(
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel,
    onOpenFeedback: () -> Unit = {},
    onOpenWorkshop: () -> Unit = {},
    onOpenWorkshopDetails: (ModItemUi) -> Unit = {},
    feedbackUnreadCount: Int = 0,
    onOpenFeedbackUpdates: () -> Unit = {},
    onBatchSelectionModeChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val workshopUpdateCheckState by WorkshopUpdateCheckCoordinator.uiState.collectAsState()

    LaunchedEffect(context) {
        WorkshopUpdateCheckCoordinator.bind(context.applicationContext)
    }

    LaunchedEffect(workshopUpdateCheckState.lastCompletedAtMs) {
        val hostActivity = context as? Activity
        if (hostActivity != null && workshopUpdateCheckState.lastCompletedAtMs > 0L) {
            viewModel.refresh(hostActivity)
        }
    }

    LauncherMainRoute(
        modifier = modifier,
        viewModel = viewModel,
        onOpenWorkshop = onOpenWorkshop,
        onOpenWorkshopDetails = onOpenWorkshopDetails,
    ) { routeModifier, uiState, actions ->
        LauncherModsScreenContent(
            modifier = routeModifier,
            uiState = uiState,
            actions = actions,
            onOpenFeedback = onOpenFeedback,
            onOpenWorkshop = onOpenWorkshop,
            feedbackUnreadCount = feedbackUnreadCount,
            onOpenFeedbackUpdates = onOpenFeedbackUpdates,
            workshopUpdateCheckState = workshopUpdateCheckState,
            onBatchSelectionModeChange = onBatchSelectionModeChange,
            onCheckWorkshopUpdates = {
                WorkshopUpdateCheckCoordinator.requestCheck(
                    context = context.applicationContext,
                    force = true,
                    notifyResult = true,
                )
                LauncherTransientNoticeBus.show(UiText.StringResource(R.string.main_workshop_checking_notice))
            },
        )
    }
}

@Composable
fun LauncherCrashRecoveryScreen(
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFeedback: () -> Unit,
    onReturnToMainMenu: () -> Unit,
) {
    val context = LocalContext.current
    val hostActivity = context as? Activity
    val uiState = viewModel.uiState
    var effectDialog by remember { mutableStateOf<MainScreenViewModel.Effect.ShowDialog?>(null) }

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
                is MainScreenViewModel.Effect.ShowModNameMigrationFailureDialog -> Unit
                is MainScreenViewModel.Effect.OpenExportModPicker -> Unit
                is MainScreenViewModel.Effect.LaunchIntent -> hostActivity?.startActivity(effect.intent)
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

    val crashRecovery = uiState.crashRecovery
    if (crashRecovery != null) {
        CrashRecoveryScreen(
            modifier = modifier,
            crashRecovery = crashRecovery,
            busy = uiState.busy,
            busyMessage = uiState.busyMessage?.resolve(),
            onBack = onBack,
            onOpenRecoverySettings = onOpenSettings,
            onOpenFeedback = onOpenFeedback,
            onAskAi = { hostActivity?.let(viewModel::copyCrashRecoveryAiPrompt) },
            onCopyReport = { hostActivity?.let(viewModel::copyCrashRecoveryReport) },
            onShareLogs = { hostActivity?.let(viewModel::shareCrashRecoveryReport) },
            onReturnToMainMenu = onReturnToMainMenu
        )
    } else {
        Box(modifier = modifier.background(MaterialTheme.colorScheme.background))
    }
}

@Composable
internal fun LauncherMainRoute(
    modifier: Modifier,
    viewModel: MainScreenViewModel,
    onOpenWorkshop: () -> Unit,
    onOpenWorkshopDetails: (ModItemUi) -> Unit = {},
    handleEffects: Boolean = true,
    pollWorkshopDownloads: Boolean = true,
    content: @Composable (
        modifier: Modifier,
        uiState: MainScreenViewModel.UiState,
        actions: MainScreenActions,
    ) -> Unit,
) {
    val context = LocalContext.current
    val hostActivity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState = viewModel.uiState
    val hasActiveWorkshopDownloads = uiState.optionalMods.any { mod ->
        mod.workshop?.state == WorkshopModState.Downloading
    } || WorkshopDownloadCenterStore.tasks.any { task ->
        task.status.isActiveDownload()
    }
    var effectDialog by remember { mutableStateOf<MainScreenViewModel.Effect.ShowDialog?>(null) }
    var modNameMigrationFailureDialog by remember {
        mutableStateOf<MainScreenViewModel.Effect.ShowModNameMigrationFailureDialog?>(null)
    }
    var pendingExportModSourcePath by remember { mutableStateOf<String?>(null) }
    val importModsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        ModImportRequestBus.requestImport(uris.orEmpty())
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
        importModsLauncher = importModsLauncher,
        onOpenWorkshop = onOpenWorkshop,
        onOpenWorkshopDetails = onOpenWorkshopDetails,
    )

    LaunchedEffect(hostActivity) {
        if (hostActivity != null) {
            viewModel.refresh(hostActivity)
            viewModel.syncModSuggestionsIfNeeded(hostActivity)
            viewModel.syncSteamCloudIndicatorIfNeeded(hostActivity, force = true)
            if (pollWorkshopDownloads && !viewModel.uiState.launchInFlight) {
                viewModel.refreshWorkshopDownloadCards(hostActivity)
            }
        }
    }

    LaunchedEffect(hostActivity, hasActiveWorkshopDownloads, pollWorkshopDownloads) {
        val activity = hostActivity ?: return@LaunchedEffect
        if (!pollWorkshopDownloads || !hasActiveWorkshopDownloads || viewModel.uiState.launchInFlight) return@LaunchedEffect
        while (true) {
            delay(1000L)
            if (viewModel.uiState.launchInFlight) return@LaunchedEffect
            val stillActive = viewModel.refreshWorkshopDownloadCards(activity)
            if (!stillActive) {
                viewModel.refresh(activity)
            }
            if (!stillActive) break
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
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }

    LaunchedEffect(viewModel, hostActivity, handleEffects) {
        if (!handleEffects) {
            return@LaunchedEffect
        }
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
                is MainScreenViewModel.Effect.ShowModNameMigrationFailureDialog -> {
                    modNameMigrationFailureDialog = effect
                }
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

    modNameMigrationFailureDialog?.let { dialog ->
        val activity = hostActivity
        ModNameMigrationFailureDialog(
            failedMods = dialog.failedMods,
            onRetry = {
                modNameMigrationFailureDialog = null
                if (activity != null) {
                    viewModel.retryFailedModNameMigration(activity)
                }
            },
            onAbandon = {
                modNameMigrationFailureDialog = null
                if (activity != null) {
                    viewModel.abandonFailedModNameMigration(activity)
                }
            }
        )
    }

    if (uiState.showModFileNameRemovalNotice && hostActivity != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.main_mod_file_name_display_removed_title)) },
            text = { Text(stringResource(R.string.main_mod_file_name_display_removed_message)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.onApplyFileNameAliasesForRemovedShowFileName(hostActivity) }
                ) {
                    Text(stringResource(R.string.main_mod_file_name_display_removed_apply))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.onDismissRemovedShowFileNameNotice(hostActivity) }
                ) {
                    Text(stringResource(R.string.main_mod_file_name_display_removed_skip))
                }
            }
        )
    }

    if (uiState.pendingMtsComponentUpdate != null && hostActivity != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = viewModel::dismissMtsComponentUpdatePrompt,
            title = { Text(text = stringResource(R.string.main_mts_component_update_title)) },
            text = { Text(text = stringResource(R.string.main_mts_component_update_message)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.installMtsComponentUpdate(hostActivity) }
                ) {
                    Text(text = stringResource(R.string.main_mts_component_update_action_update))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissMtsComponentUpdatePrompt) {
                    Text(text = stringResource(R.string.update_dialog_action_later))
                }
            }
        )
    }

    if (uiState.expectedBackExitNoticeVisible) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = viewModel::dismissExpectedBackExitNotice,
            title = { Text(text = stringResource(R.string.main_expected_back_exit_title)) },
            text = { Text(text = stringResource(R.string.main_expected_back_exit_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissExpectedBackExitNotice) {
                    Text(text = stringResource(R.string.common_action_confirm))
                }
            }
        )
    }

    uiState.pendingEnabledModSizeLaunchWarning?.let { warning ->
        val activity = hostActivity
        if (activity != null) {
            EnabledModSizeLaunchWarningDialog(
                totalBytes = warning.totalBytes,
                onDismiss = viewModel::cancelLaunchWithEnabledModSizeWarning,
                onConfirm = { dontRemindAgain ->
                    viewModel.confirmLaunchWithEnabledModSizeWarning(activity, dontRemindAgain)
                },
            )
        }
    }

    uiState.pendingWorkshopJarSelection?.let { selection ->
        val activity = hostActivity
        if (activity != null && selection.candidates.isNotEmpty()) {
            WorkshopJarSelectionDialog(
                selection = selection,
                onConfirm = { selectedCandidateIds ->
                    viewModel.confirmWorkshopJarSelection(
                        host = activity,
                        requestId = selection.requestId,
                        selectedCandidateIds = selectedCandidateIds,
                    )
                },
            )
        }
    }

    content(
        modifier,
        uiState,
        actions,
    )
}

@Composable
private fun ModNameMigrationFailureDialog(
    failedMods: List<MainScreenViewModel.FailedModNameMigrationUi>,
    onRetry: () -> Unit,
    onAbandon: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        title = {
            Text(text = stringResource(R.string.main_mod_name_migration_failed_title))
        },
        text = {
            Column {
                Text(
                    text = stringResource(
                        R.string.main_mod_name_migration_failed_message,
                        failedMods.size
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        failedMods.forEach { mod ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "- ${mod.displayName}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                if (mod.storagePath.isNotBlank()) {
                                    Text(
                                        text = mod.storagePath,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (mod.reason.isNotBlank()) {
                                    Text(
                                        text = stringResource(
                                            R.string.main_mod_name_migration_failed_reason,
                                            mod.reason
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRetry) {
                Text(text = stringResource(R.string.main_mod_name_migration_failed_retry))
            }
        },
        dismissButton = {
            TextButton(onClick = onAbandon) {
                Text(text = stringResource(R.string.main_mod_name_migration_failed_abandon))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun LauncherMainScreenPreview() {
    LauncherGameScreenContent(
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
        feedbackUnreadCount = 2,
        onOpenFeedbackSubscriptions = {},
    )
}

@Composable
internal fun LauncherGameScreenContent(
    modifier: Modifier = Modifier,
    uiState: MainScreenViewModel.UiState,
    actions: MainScreenActions = MainScreenActions(isHostAvailable = false),
    onOpenFeedback: () -> Unit = {},
    updateNotice: LauncherUpdateNoticeUiState? = null,
    feedbackUnreadCount: Int = 0,
    onOpenFeedbackUpdates: () -> Unit = {},
    onOpenFeedbackSubscriptions: () -> Unit = {},
    onUpdateNoticeClick: () -> Unit = {},
    showSteamCloudBottomSheetHost: Boolean = true,
) {
    LauncherMainScreenContent(
        modifier = modifier,
        uiState = uiState,
        actions = actions,
        contentMode = LauncherMainContentMode.GAME,
        onOpenFeedback = onOpenFeedback,
        updateNotice = updateNotice,
        feedbackUnreadCount = feedbackUnreadCount,
        onOpenFeedbackUpdates = onOpenFeedbackUpdates,
        onOpenFeedbackSubscriptions = onOpenFeedbackSubscriptions,
        onUpdateNoticeClick = onUpdateNoticeClick,
        showSteamCloudBottomSheetHost = showSteamCloudBottomSheetHost,
    )
}

@Composable
internal fun LauncherModsScreenContent(
    modifier: Modifier = Modifier,
    uiState: MainScreenViewModel.UiState,
    actions: MainScreenActions = MainScreenActions(isHostAvailable = false),
    onOpenFeedback: () -> Unit = {},
    onOpenWorkshop: () -> Unit = {},
    feedbackUnreadCount: Int = 0,
    onOpenFeedbackUpdates: () -> Unit = {},
    workshopUpdateCheckState: WorkshopUpdateCheckUiState = WorkshopUpdateCheckUiState(),
    onBatchSelectionModeChange: (Boolean) -> Unit = {},
    onCheckWorkshopUpdates: () -> Unit = {},
    showSteamCloudBottomSheetHost: Boolean = true,
) {
    LauncherMainScreenContent(
        modifier = modifier,
        uiState = uiState,
        actions = actions,
        contentMode = LauncherMainContentMode.MODS,
        onOpenFeedback = onOpenFeedback,
        onOpenWorkshop = onOpenWorkshop,
        feedbackUnreadCount = feedbackUnreadCount,
        onOpenFeedbackUpdates = onOpenFeedbackUpdates,
        workshopUpdateCheckState = workshopUpdateCheckState,
        onBatchSelectionModeChange = onBatchSelectionModeChange,
        onCheckWorkshopUpdates = onCheckWorkshopUpdates,
        showSteamCloudBottomSheetHost = showSteamCloudBottomSheetHost,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LauncherMainScreenContent(
    modifier: Modifier = Modifier,
    uiState: MainScreenViewModel.UiState,
    actions: MainScreenActions = MainScreenActions(isHostAvailable = false),
    contentMode: LauncherMainContentMode,
    onOpenFeedback: () -> Unit = {},
    onOpenWorkshop: () -> Unit = {},
    updateNotice: LauncherUpdateNoticeUiState? = null,
    feedbackUnreadCount: Int = 0,
    onOpenFeedbackUpdates: () -> Unit = {},
    onOpenFeedbackSubscriptions: () -> Unit = {},
    onUpdateNoticeClick: () -> Unit = {},
    workshopUpdateCheckState: WorkshopUpdateCheckUiState = WorkshopUpdateCheckUiState(),
    onBatchSelectionModeChange: (Boolean) -> Unit = {},
    onCheckWorkshopUpdates: () -> Unit = {},
    showSteamCloudBottomSheetHost: Boolean = true,
) {
    val density = LocalDensity.current
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showSteamCloudBottomSheet by remember { mutableStateOf(false) }
    var showSteamCloudLaunchWarning by remember { mutableStateOf(false) }
    var showEnabledModsDialog by remember { mutableStateOf(false) }
    var showModSizeDialog by remember { mutableStateOf(false) }
    var pendingSteamCloudConflictChoice by remember {
        mutableStateOf<SteamCloudConflictResolutionChoice?>(null)
    }
    var pendingSteamCloudNetworkPromptAction by remember {
        mutableStateOf<SteamCloudNetworkPromptAction?>(null)
    }
    val showInitializing = uiState.initializing
    val hazeState = rememberHazeState()
    val pendingLaunchUnreadSuggestionModNames = uiState.pendingLaunchUnreadSuggestionModNames
    val enabledMods = remember(uiState.optionalMods) {
        uiState.optionalMods.filter { mod -> mod.enabled && mod.installed && !mod.required }
    }
    val enabledModSizeItems = remember(enabledMods) {
        enabledMods.map { mod ->
            EnabledModSizeItem(
                name = resolveModDisplayName(mod),
                bytes = File(mod.storagePath).takeIf { it.isFile }?.length() ?: 0L,
            )
        }
    }
    val enabledModNames = remember(enabledModSizeItems) {
        enabledModSizeItems.map { it.name }
    }
    val steamCloudIndicator = uiState.steamCloudIndicator
    var steamCloudAutoRetryAttemptIndex by remember { mutableIntStateOf(0) }
    var steamCloudAutoRetryCurrentDelaySeconds by remember {
        mutableIntStateOf(STEAM_CLOUD_AUTO_RETRY_INITIAL_DELAY_SECONDS)
    }
    var steamCloudAutoRetryCountdownSeconds by remember {
        mutableIntStateOf(STEAM_CLOUD_AUTO_RETRY_INITIAL_DELAY_SECONDS)
    }
    val steamCloudAutoRetryInProgress =
        steamCloudIndicator.state == MainScreenViewModel.SteamCloudIndicatorState.CONNECTION_FAILED &&
            !showSteamCloudLaunchWarning
    val steamCloudAutoRetryState = SteamCloudAutoRetryUiState(
        inProgress = steamCloudAutoRetryInProgress,
        delaySeconds = steamCloudAutoRetryCurrentDelaySeconds,
        countdownSeconds = steamCloudAutoRetryCountdownSeconds,
    )
    val steamCloudBottomSheetVisible =
        showSteamCloudBottomSheetHost && showSteamCloudBottomSheet && steamCloudIndicator.visible
    val steamCloudBottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var batchEditBarState by remember { mutableStateOf<BatchEditBarState?>(null) }
    var batchEditBarHeightPx by remember { mutableIntStateOf(0) }
    val batchSelectionMode = batchEditBarState != null
    val batchEditBarContentPadding = with(density) { batchEditBarHeightPx.toDp() }
    val launcherDockContentPadding = 108.dp

    fun runSteamCloudNetworkPromptAction(action: SteamCloudNetworkPromptAction) {
        when (action) {
            SteamCloudNetworkPromptAction.REFRESH -> actions.onRefreshSteamCloudStatus()
            SteamCloudNetworkPromptAction.USE_LOCAL -> actions.onUseLocalSteamCloudProgress()
            SteamCloudNetworkPromptAction.USE_CLOUD -> actions.onUseCloudSteamCloudProgress()
        }
    }

    fun requestSteamCloudNetworkAction(action: SteamCloudNetworkPromptAction) {
        if (actions.shouldPromptSteamCloudDirectMode()) {
            pendingSteamCloudNetworkPromptAction = action
        } else {
            runSteamCloudNetworkPromptAction(action)
        }
    }

    LaunchedEffect(steamCloudIndicator.state) {
        when (steamCloudIndicator.state) {
            MainScreenViewModel.SteamCloudIndicatorState.HIDDEN,
            MainScreenViewModel.SteamCloudIndicatorState.UP_TO_DATE,
            MainScreenViewModel.SteamCloudIndicatorState.CONFLICT -> {
                steamCloudAutoRetryAttemptIndex = 0
                steamCloudAutoRetryCurrentDelaySeconds = STEAM_CLOUD_AUTO_RETRY_INITIAL_DELAY_SECONDS
                steamCloudAutoRetryCountdownSeconds = STEAM_CLOUD_AUTO_RETRY_INITIAL_DELAY_SECONDS
            }

            MainScreenViewModel.SteamCloudIndicatorState.CHECKING,
            MainScreenViewModel.SteamCloudIndicatorState.SYNCING,
            MainScreenViewModel.SteamCloudIndicatorState.CONNECTION_FAILED -> Unit
        }
    }

    LaunchedEffect(
        steamCloudIndicator.state,
        steamCloudIndicator.errorSummary,
        steamCloudIndicator.lastCheckedAtMs,
        showSteamCloudLaunchWarning
    ) {
        if (steamCloudIndicator.state != MainScreenViewModel.SteamCloudIndicatorState.CONNECTION_FAILED ||
            showSteamCloudLaunchWarning
        ) {
            return@LaunchedEffect
        }
        val nextRetryDelaySeconds = steamCloudAutoRetryDelaySeconds(steamCloudAutoRetryAttemptIndex)
        steamCloudAutoRetryAttemptIndex = (steamCloudAutoRetryAttemptIndex + 1)
            .coerceAtMost(STEAM_CLOUD_AUTO_RETRY_STORED_ATTEMPT_CAP)
        steamCloudAutoRetryCurrentDelaySeconds = nextRetryDelaySeconds
        steamCloudAutoRetryCountdownSeconds = nextRetryDelaySeconds
        repeat(nextRetryDelaySeconds) {
            delay(1000L)
            steamCloudAutoRetryCountdownSeconds =
                (steamCloudAutoRetryCountdownSeconds - 1).coerceAtLeast(0)
        }
        requestSteamCloudNetworkAction(SteamCloudNetworkPromptAction.REFRESH)
    }

    LaunchedEffect(batchSelectionMode) {
        onBatchSelectionModeChange(batchSelectionMode)
    }

    DisposableEffect(Unit) {
        onDispose { onBatchSelectionModeChange(false) }
    }

    LaunchedEffect(steamCloudIndicator.visible) {
        if (!steamCloudIndicator.visible) {
            showSteamCloudBottomSheet = false
            showSteamCloudLaunchWarning = false
            pendingSteamCloudConflictChoice = null
        }
    }

    LaunchedEffect(steamCloudIndicator.state) {
        if (steamCloudIndicator.state != MainScreenViewModel.SteamCloudIndicatorState.CONFLICT) {
            pendingSteamCloudConflictChoice = null
        }
    }

    LaunchedEffect(uiState.launchInFlight, pendingLaunchUnreadSuggestionModNames) {
        if (uiState.launchInFlight || pendingLaunchUnreadSuggestionModNames.isNotEmpty()) {
            showSteamCloudBottomSheet = false
        }
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
            when (contentMode) {
                    LauncherMainContentMode.GAME -> {
                        LauncherGamePage(
                            modifier = Modifier.fillMaxSize(),
                            uiState = uiState,
                            actions = actions,
                            updateNotice = updateNotice,
                            feedbackUnreadCount = feedbackUnreadCount,
                            onOpenFeedbackUpdates = onOpenFeedbackUpdates,
                            onOpenFeedbackSubscriptions = onOpenFeedbackSubscriptions,
                            onUpdateNoticeClick = onUpdateNoticeClick,
                            onEnabledModsClick = { showEnabledModsDialog = true },
                            onModSizeClick = { showModSizeDialog = true },
                            onSteamCloudClick = {
                                showSteamCloudBottomSheet = true
                            },
                            onLaunch = {
                                if (actions.onLaunch() == LaunchRequestAction.OPEN_STEAM_CLOUD_SHEET) {
                                    showSteamCloudBottomSheet = true
                                }
                            },
                        )
                    }

                    LauncherMainContentMode.MODS -> {
                        var modsHeaderHeightPx by remember { mutableIntStateOf(0) }
                        var modsHeaderCollapsed by remember { mutableStateOf(false) }
                        var modsContentMountReady by remember { mutableStateOf(false) }
                        val measuredModsHeaderHeight = with(density) { modsHeaderHeightPx.toDp() }
                        val modsHeaderContentTopInset =
                            (if (modsHeaderHeightPx == 0) 232.dp else measuredModsHeaderHeight) - 20.dp

                        LaunchedEffect(Unit) {
                            delay(MODS_CONTENT_MOUNT_DELAY_MS)
                            modsContentMountReady = true
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag(MODS_SCREEN_ROOT_TAG)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .hazeSource(state = hazeState)
                                    .padding(start = 16.dp, top = 18.dp, end = 16.dp),
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
                                    showInitializing = showInitializing || !modsContentMountReady,
                                    contentTopInset = modsHeaderContentTopInset,
                                    actionBarBottomPadding = launcherDockContentPadding + batchEditBarContentPadding,
                                    onHeaderCollapsedChange = { modsHeaderCollapsed = it },
                                    onBatchEditBarStateChange = { batchEditBarState = it },
                                    actions = actions
                                )
                            }

                            CollapsibleFloatingGlassHeader(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .fillMaxWidth(),
                                hazeState = hazeState,
                                collapsed = modsHeaderCollapsed,
                                shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                                contentPadding = PaddingValues(0.dp),
                                onHeightChanged = {
                                    if (!modsHeaderCollapsed) {
                                        modsHeaderHeightPx = maxOf(modsHeaderHeightPx, it)
                                    }
                                },
                                pinnedContent = {
                                    ModsHeaderPinnedContent(
                                        folderControlsEnabled = uiState.controlsEnabled && !batchSelectionMode,
                                        dragLocked = uiState.dragLocked,
                                        hostAvailable = actions.isHostAvailable,
                                        feedbackUnreadCount = feedbackUnreadCount,
                                        workshopUpdateCheckState = workshopUpdateCheckState,
                                        onToggleDragLocked = actions.onToggleDragLocked,
                                        onAddFolderClick = { showCreateFolderDialog = true },
                                        onCheckWorkshopUpdates = {
                                            if (!batchSelectionMode) {
                                                onCheckWorkshopUpdates()
                                            }
                                        },
                                        onOpenFeedbackUpdates = onOpenFeedbackUpdates,
                                    )
                                },
                                expandedContent = {
                                    ModsHeaderExpandedContent(
                                        folderControlsEnabled = uiState.controlsEnabled && !batchSelectionMode,
                                        dragLocked = uiState.dragLocked,
                                        hostAvailable = actions.isHostAvailable,
                                        enabledCount = uiState.optionalMods.count { it.enabled },
                                        totalCount = uiState.optionalMods.size,
                                        folderCount = uiState.modFolders.size,
                                        importEnabled = !batchSelectionMode && !uiState.busy && uiState.storageIssue == null,
                                        profiles = uiState.modLaunchProfiles,
                                        activeProfileId = uiState.activeModLaunchProfileId,
                                        profileEnabled = !batchSelectionMode && actions.isHostAvailable && uiState.controlsEnabled && uiState.storageIssue == null,
                                        onImportMods = actions.onImportMods,
                                        onSelectProfile = actions.onSelectModLaunchProfile,
                                        onAddProfile = actions.onAddModLaunchProfile,
                                        onRenameProfile = actions.onRenameModLaunchProfile,
                                        onDeleteProfile = actions.onDeleteModLaunchProfile,
                                    )
                                },
                            )
                        }

                        ModBatchEditBottomBar(
                            modifier = Modifier.align(Alignment.BottomCenter),
                            batchEditBarState = batchEditBarState,
                            onHeightChanged = { batchEditBarHeightPx = it },
                        )
                    }
            }
        }
    }

    if (steamCloudBottomSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = {
                showSteamCloudBottomSheet = false
                showSteamCloudLaunchWarning = false
            },
            sheetState = steamCloudBottomSheetState
        ) {
            SteamCloudBottomSheetContent(
                indicator = steamCloudIndicator,
                onRefresh = { requestSteamCloudNetworkAction(SteamCloudNetworkPromptAction.REFRESH) },
                onLaunch = {
                    val action = actions.onLaunch()
                    if (action != LaunchRequestAction.OPEN_STEAM_CLOUD_SHEET) {
                        showSteamCloudBottomSheet = false
                    }
                },
                onLaunchAfterError = { showSteamCloudLaunchWarning = true },
                onCancelCheck = actions.onCancelSteamCloudCheck,
                onCancelSync = actions.onCancelSteamCloudSync,
                onBackgroundUploadAndLaunch = {
                    showSteamCloudBottomSheet = false
                    showSteamCloudLaunchWarning = false
                    actions.onBackgroundSteamCloudSyncAndLaunch()
                },
                onUseLocal = {
                    pendingSteamCloudConflictChoice = SteamCloudConflictResolutionChoice.USE_LOCAL
                },
                onUseCloud = {
                    pendingSteamCloudConflictChoice = SteamCloudConflictResolutionChoice.USE_CLOUD
                },
                autoRetryState = steamCloudAutoRetryState,
            )
        }
    }

    pendingSteamCloudConflictChoice?.let { choice ->
        SteamCloudConflictConfirmationDialog(
            choice = choice,
            plan = steamCloudIndicator.plan,
            onDismiss = { pendingSteamCloudConflictChoice = null },
            onConfirm = {
                pendingSteamCloudConflictChoice = null
                when (choice) {
                    SteamCloudConflictResolutionChoice.USE_LOCAL ->
                        requestSteamCloudNetworkAction(SteamCloudNetworkPromptAction.USE_LOCAL)
                    SteamCloudConflictResolutionChoice.USE_CLOUD ->
                        requestSteamCloudNetworkAction(SteamCloudNetworkPromptAction.USE_CLOUD)
                }
            },
            onBackgroundUploadAndLaunch = {
                pendingSteamCloudConflictChoice = null
                showSteamCloudBottomSheet = false
                showSteamCloudLaunchWarning = false
                actions.onBackgroundUseLocalSteamCloudProgressAndLaunch()
            },
        )
    }

    pendingSteamCloudNetworkPromptAction?.let { action ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingSteamCloudNetworkPromptAction = null },
            title = { Text(text = stringResource(R.string.main_steam_cloud_direct_mode_prompt_title)) },
            text = { Text(text = stringResource(R.string.main_steam_cloud_direct_mode_prompt_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingSteamCloudNetworkPromptAction = null
                        actions.onSwitchSteamCloudDirectMode()
                        runSteamCloudNetworkPromptAction(action)
                    }
                ) {
                    Text(text = stringResource(R.string.main_steam_cloud_direct_mode_prompt_switch_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingSteamCloudNetworkPromptAction = null
                        runSteamCloudNetworkPromptAction(action)
                    }
                ) {
                    Text(text = stringResource(R.string.main_steam_cloud_direct_mode_prompt_keep_action))
                }
            }
        )
    }

    if (showEnabledModsDialog) {
        EnabledModsDialog(
            modNames = enabledModNames,
            onDismiss = { showEnabledModsDialog = false }
        )
    }

    if (showModSizeDialog) {
        ModSizeBreakdownDialog(
            items = enabledModSizeItems,
            onDismiss = { showModSizeDialog = false }
        )
    }

    if (showSteamCloudLaunchWarning) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSteamCloudLaunchWarning = false },
            title = { Text(text = stringResource(R.string.main_steam_cloud_launch_warning_title)) },
            text = { Text(text = stringResource(R.string.main_steam_cloud_launch_warning_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSteamCloudLaunchWarning = false
                        showSteamCloudBottomSheet = false
                        actions.onLaunchAfterSteamCloudError()
                    }
                ) {
                    Text(text = stringResource(R.string.main_steam_cloud_action_start_without_sync))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSteamCloudLaunchWarning = false }) {
                    Text(text = stringResource(R.string.main_steam_cloud_launch_warning_cancel))
                }
            }
        )
    }
}

@Composable
private fun EnabledModSizeLaunchWarningDialog(
    totalBytes: Long,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    var dontRemindAgain by remember(totalBytes) { mutableStateOf(false) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.main_launch_enabled_mod_size_warning_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(
                        R.string.main_launch_enabled_mod_size_warning_message,
                        formatLauncherByteSize(totalBytes)
                    )
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = dontRemindAgain,
                            role = Role.Checkbox,
                            onValueChange = { dontRemindAgain = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = dontRemindAgain,
                        onCheckedChange = null,
                    )
                    Text(
                        text = stringResource(R.string.main_launch_enabled_mod_size_warning_dont_remind),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(dontRemindAgain) }) {
                Text(text = stringResource(R.string.main_launch_enabled_mod_size_warning_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.main_launch_enabled_mod_size_warning_cancel))
            }
        }
    )
}

@Composable
private fun WorkshopJarSelectionDialog(
    selection: MainScreenViewModel.PendingWorkshopJarSelection,
    onConfirm: (Set<String>) -> Unit,
) {
    var selectedCandidateIds by remember(selection.requestId) {
        mutableStateOf<Set<String>>(emptySet())
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = {
            Text(text = stringResource(R.string.main_mod_workshop_jar_selection_title))
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.main_mod_workshop_jar_selection_message,
                        selection.title,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    selection.candidates.forEach { candidate ->
                        val checked = candidate.id in selectedCandidateIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    selectedCandidateIds = if (checked) {
                                        selectedCandidateIds - candidate.id
                                    } else {
                                        selectedCandidateIds + candidate.id
                                    }
                                }
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    selectedCandidateIds = if (isChecked) {
                                        selectedCandidateIds + candidate.id
                                    } else {
                                        selectedCandidateIds - candidate.id
                                    }
                                },
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = candidate.displayPath,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = formatLauncherByteSize(candidate.sizeBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
                if (selectedCandidateIds.isEmpty()) {
                    Text(
                        text = stringResource(R.string.main_mod_workshop_jar_selection_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedCandidateIds) },
                enabled = selectedCandidateIds.isNotEmpty(),
            ) {
                Text(text = stringResource(R.string.main_mod_workshop_jar_selection_confirm))
            }
        },
    )
}

@Composable
private fun EnabledModsDialog(
    modNames: List<String>,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.main_enabled_mods_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (modNames.isEmpty()) {
                    Text(
                        text = stringResource(R.string.main_enabled_mods_dialog_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    modNames.forEach { modName ->
                        Text(
                            text = "- $modName",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.common_action_close))
            }
        }
    )
}

private data class EnabledModSizeItem(
    val name: String,
    val bytes: Long,
)

@Composable
private fun ModSizeBreakdownDialog(
    items: List<EnabledModSizeItem>,
    onDismiss: () -> Unit,
) {
    val sortedItems = remember(items) {
        items.sortedWith(
            compareByDescending<EnabledModSizeItem> { it.bytes }
                .thenBy { it.name.lowercase(Locale.getDefault()) }
        )
    }
    val totalBytes = sortedItems.sumOf { it.bytes.coerceAtLeast(0L) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.main_mod_size_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (sortedItems.isEmpty()) {
                    Text(
                        text = stringResource(R.string.main_mod_size_dialog_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(
                            R.string.main_mod_size_dialog_total,
                            formatLauncherByteSize(totalBytes)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (totalBytes > 0L) {
                        ModSizeStackedProgressBar(
                            items = sortedItems,
                            totalBytes = totalBytes,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.main_mod_size_dialog_zero_total),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        sortedItems.forEachIndexed { index, item ->
                            ModSizeBreakdownRow(
                                item = item,
                                totalBytes = totalBytes,
                                color = modSizeSegmentColor(index),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.common_action_close))
            }
        }
    )
}

@Composable
private fun ModSizeStackedProgressBar(
    items: List<EnabledModSizeItem>,
    totalBytes: Long,
) {
    val positiveItems = items.withIndex().filter { it.value.bytes > 0L }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        var startX = 0f
        positiveItems.forEachIndexed { index, indexedItem ->
            val item = indexedItem.value
            val width = if (index == positiveItems.lastIndex) {
                size.width - startX
            } else {
                size.width * (item.bytes.toDouble() / totalBytes.toDouble()).toFloat()
            }.coerceAtLeast(0f)
            drawRect(
                color = modSizeSegmentColor(indexedItem.index),
                topLeft = Offset(startX, 0f),
                size = Size(width, size.height),
            )
            startX += width
        }
    }
}

@Composable
private fun ModSizeBreakdownRow(
    item: EnabledModSizeItem,
    totalBytes: Long,
    color: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(12.dp),
            shape = CircleShape,
            color = color,
            content = {}
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatLauncherByteSize(item.bytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = formatModSizePercent(item.bytes, totalBytes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun modSizeSegmentColor(index: Int): Color {
    val colors = listOf(
        Color(0xFF2E7D32),
        Color(0xFF1565C0),
        Color(0xFFC62828),
        Color(0xFFEF6C00),
        Color(0xFF6A1B9A),
        Color(0xFF00838F),
        Color(0xFFAD1457),
        Color(0xFF558B2F),
        Color(0xFF5D4037),
        Color(0xFF455A64),
        Color(0xFFF9A825),
        Color(0xFF283593),
    )
    return colors[index.floorMod(colors.size)]
}

private fun formatModSizePercent(bytes: Long, totalBytes: Long): String {
    if (bytes <= 0L || totalBytes <= 0L) {
        return "0%"
    }
    val percent = bytes.toDouble() * 100.0 / totalBytes.toDouble()
    return if (percent >= 9.95) {
        String.format(Locale.US, "%.0f%%", percent)
    } else {
        String.format(Locale.US, "%.1f%%", percent)
    }
}

private fun Int.floorMod(modulus: Int): Int {
    return ((this % modulus) + modulus) % modulus
}

internal fun shouldShowSteamCloudBackgroundUploadAction(
    indicator: MainScreenViewModel.SteamCloudIndicatorUi,
): Boolean {
    return indicator.state == MainScreenViewModel.SteamCloudIndicatorState.SYNCING &&
        indicator.syncDirection == SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD
}

internal fun shouldAutoLaunchAfterSteamCloudUpdate(
    indicator: MainScreenViewModel.SteamCloudIndicatorUi,
): Boolean {
    return indicator.state == MainScreenViewModel.SteamCloudIndicatorState.UP_TO_DATE ||
        shouldShowSteamCloudBackgroundUploadAction(indicator)
}

private const val BOTTOM_BAR_SWITCH_ANIMATION_MS = 220

@Composable
private fun SteamCloudConflictConfirmationDialog(
    choice: SteamCloudConflictResolutionChoice,
    plan: SteamCloudUploadPlan?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onBackgroundUploadAndLaunch: () -> Unit,
) {
    val localChangeCount = plan?.uploadCandidates?.size ?: 0
    val cloudOnlyChangeCount = plan?.remoteOnlyChanges?.size ?: 0
    val directConflictCount = plan?.conflicts?.size ?: 0
    val showBackgroundUploadAction =
        choice == SteamCloudConflictResolutionChoice.USE_LOCAL && plan != null
    val titleRes = when (choice) {
        SteamCloudConflictResolutionChoice.USE_LOCAL ->
            R.string.main_steam_cloud_conflict_confirm_local_title
        SteamCloudConflictResolutionChoice.USE_CLOUD ->
            R.string.main_steam_cloud_conflict_confirm_cloud_title
    }
    val messageRes = when (choice) {
        SteamCloudConflictResolutionChoice.USE_LOCAL ->
            R.string.main_steam_cloud_conflict_confirm_local_message
        SteamCloudConflictResolutionChoice.USE_CLOUD ->
            R.string.main_steam_cloud_conflict_confirm_cloud_message
    }
    val confirmRes = when (choice) {
        SteamCloudConflictResolutionChoice.USE_LOCAL ->
            R.string.main_steam_cloud_conflict_confirm_local_action
        SteamCloudConflictResolutionChoice.USE_CLOUD ->
            R.string.main_steam_cloud_conflict_confirm_cloud_action
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(
                        messageRes,
                        localChangeCount,
                        cloudOnlyChangeCount,
                        directConflictCount,
                    )
                )
                AnimatedVisibility(
                    visible = showBackgroundUploadAction,
                    enter = fadeIn(animationSpec = tween(durationMillis = 220)) +
                        slideInVertically(
                            animationSpec = tween(durationMillis = 220),
                            initialOffsetY = { it / 3 },
                        ) +
                        scaleIn(
                            animationSpec = tween(durationMillis = 220),
                            initialScale = 0.96f,
                        ),
                    exit = fadeOut(animationSpec = tween(durationMillis = 180)) +
                        slideOutVertically(
                            animationSpec = tween(durationMillis = 180),
                            targetOffsetY = { it / 4 },
                        ) +
                        scaleOut(
                            animationSpec = tween(durationMillis = 180),
                            targetScale = 0.98f,
                        ),
                ) {
                    Text(
                        text = stringResource(R.string.main_steam_cloud_background_upload_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedVisibility(
                    visible = showBackgroundUploadAction,
                    enter = fadeIn(animationSpec = tween(durationMillis = 220)) +
                        slideInVertically(
                            animationSpec = tween(durationMillis = 220),
                            initialOffsetY = { it / 2 },
                        ) +
                        scaleIn(
                            animationSpec = tween(durationMillis = 220),
                            initialScale = 0.94f,
                        ),
                    exit = fadeOut(animationSpec = tween(durationMillis = 180)) +
                        slideOutVertically(
                            animationSpec = tween(durationMillis = 180),
                            targetOffsetY = { it / 3 },
                        ) +
                        scaleOut(
                            animationSpec = tween(durationMillis = 180),
                            targetScale = 0.98f,
                        ),
                ) {
                    OutlinedButton(onClick = onBackgroundUploadAndLaunch) {
                        Text(text = stringResource(R.string.main_steam_cloud_action_background_upload))
                    }
                }
                Button(onClick = onConfirm) {
                    Text(text = stringResource(confirmRes))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.main_steam_cloud_conflict_confirm_cancel))
            }
        },
    )
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
    onBack: () -> Unit,
    onOpenRecoverySettings: () -> Unit,
    onOpenFeedback: () -> Unit,
    onAskAi: () -> Unit,
    onCopyReport: () -> Unit,
    onShareLogs: () -> Unit,
    onReturnToMainMenu: () -> Unit,
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.ArrowBack,
                    contentDescription = stringResource(R.string.common_content_desc_back)
                )
            }
            Text(
                text = stringResource(R.string.sts_crash_page_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }
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
                        onClick = onAskAi,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.sts_crash_page_action_ask_ai))
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
                        onClick = onReturnToMainMenu,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.sts_crash_page_action_return_main_menu))
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
    onOpenWorkshop: () -> Unit,
    onOpenFeedbackUpdates: () -> Unit,
    onSteamCloudClick: () -> Unit,
) {
    FloatingGlassHeader(
        modifier = modifier
            .fillMaxWidth(),
        hazeState = hazeState,
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
        contentPadding = PaddingValues(0.dp),
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
                    CompactTopBarIconButton(
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
                CompactTopBarIconButton(
                    onClick = onToggleDragLocked,
                    enabled = folderControlsEnabled && hostAvailable
                ) {
                    DragLockStateIcon(dragLocked = dragLocked)
                }
                CompactTopBarIconButton(
                    onClick = onAddFolderClick,
                    enabled = folderControlsEnabled && hostAvailable
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_folder_add),
                        contentDescription = stringResource(R.string.main_action_add_folder)
                    )
                }
                CompactTopBarIconButton(
                    onClick = onOpenWorkshop,
                    enabled = hostAvailable
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_cloud),
                        contentDescription = stringResource(R.string.main_open_workshop)
                    )
                }
                CompactTopBarIconButton(
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
private fun DragLockStateIcon(dragLocked: Boolean) {
    val iconRotation by animateFloatAsState(
        targetValue = if (dragLocked) 0f else -12f,
        animationSpec = tween(durationMillis = 220),
        label = "dragLockIconRotation"
    )
    AnimatedContent(
        targetState = dragLocked,
        transitionSpec = {
            (fadeIn(animationSpec = tween(durationMillis = 140)) +
                scaleIn(initialScale = 0.82f, animationSpec = tween(durationMillis = 180))) togetherWith
                (fadeOut(animationSpec = tween(durationMillis = 90)) +
                    scaleOut(targetScale = 0.82f, animationSpec = tween(durationMillis = 90)))
        },
        label = "dragLockIcon"
    ) { locked ->
        Icon(
            painter = painterResource(if (locked) R.drawable.ic_lock else R.drawable.ic_lock_open),
            modifier = Modifier
                .size(26.dp)
                .graphicsLayer {
                    rotationZ = iconRotation
                    scaleX = if (locked) 1f else 1.04f
                    scaleY = if (locked) 1f else 1.04f
                },
            tint = MaterialTheme.colorScheme.onSurface,
            contentDescription = stringResource(
                if (locked) {
                    R.string.main_action_unlock_drag
                } else {
                    R.string.main_action_lock_drag
                }
            )
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
    CompactTopBarIconButton(onClick = onClick, enabled = enabled) {
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
private fun CompactTopBarIconButton(
    onClick: () -> Unit,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp)
    ) {
        content()
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
    onLaunch: () -> Unit,
    onLaunchAfterError: () -> Unit,
    onCancelCheck: () -> Unit,
    onCancelSync: () -> Unit,
    onBackgroundUploadAndLaunch: () -> Unit,
    onUseLocal: () -> Unit,
    onUseCloud: () -> Unit,
    autoRetryState: SteamCloudAutoRetryUiState,
) {
    val tint = steamCloudIndicatorTint(indicator.state)
    val title = steamCloudActionBarTitle(indicator.state)
    val summary = steamCloudActionBarSummary(indicator)
    val autoRetryInProgress = autoRetryState.inProgress
    val retryProgressFraction = if (autoRetryInProgress) {
        val retryDelaySeconds = autoRetryState.delaySeconds.coerceAtLeast(1)
        (retryDelaySeconds - autoRetryState.countdownSeconds)
            .coerceIn(0, retryDelaySeconds) /
            retryDelaySeconds.toFloat()
    } else {
        0f
    }
    val progressFraction = indicator.progressPercent
        ?.coerceIn(0, 100)
        ?.div(100f)
    val animatedProgress by animateFloatAsState(
        targetValue = progressFraction ?: 0f,
        animationSpec = tween(durationMillis = 360),
        label = "steam_cloud_indicator_progress"
    )
    val animatedRetryProgress by animateFloatAsState(
        targetValue = retryProgressFraction,
        animationSpec = tween(durationMillis = 360),
        label = "steam_cloud_auto_retry_progress"
    )
    val conflictCardSummaries = remember(indicator.plan) {
        indicator.plan?.takeIf { it.conflicts.isNotEmpty() }?.let {
            buildSteamCloudConflictCardSummaries(it)
        }
    }
    val showBackgroundUploadAction = shouldShowSteamCloudBackgroundUploadAction(indicator)

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
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        if (autoRetryInProgress) {
            LinearProgressIndicator(
                progress = { animatedRetryProgress },
                modifier = Modifier.fillMaxWidth()
            )
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
                text = localizedSteamCloudPlanWarning(warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        when (indicator.state) {
            MainScreenViewModel.SteamCloudIndicatorState.HIDDEN -> {
                Button(
                    onClick = onRefresh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.main_steam_cloud_action_recheck))
                }
            }

            MainScreenViewModel.SteamCloudIndicatorState.CONNECTION_FAILED -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onLaunchAfterError,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = stringResource(R.string.main_steam_cloud_action_start_without_sync))
                    }
                    Button(
                        onClick = onRefresh,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.main_steam_cloud_action_retry_auto,
                                autoRetryState.countdownSeconds,
                            )
                        )
                    }
                }
            }

            MainScreenViewModel.SteamCloudIndicatorState.UP_TO_DATE -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onRefresh,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = stringResource(R.string.main_steam_cloud_action_recheck))
                    }
                    Button(
                        onClick = onLaunch,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = stringResource(R.string.main_launch_game))
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
                    }
                }
            }

            MainScreenViewModel.SteamCloudIndicatorState.SYNCING -> {
                if (indicator.syncDirection == SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AnimatedVisibility(
                            visible = showBackgroundUploadAction,
                            enter = fadeIn(animationSpec = tween(durationMillis = 220)) +
                                slideInVertically(
                                    animationSpec = tween(durationMillis = 220),
                                    initialOffsetY = { it / 3 },
                                ) +
                                scaleIn(
                                    animationSpec = tween(durationMillis = 220),
                                    initialScale = 0.96f,
                                ),
                            exit = fadeOut(animationSpec = tween(durationMillis = 180)) +
                                slideOutVertically(
                                    animationSpec = tween(durationMillis = 180),
                                    targetOffsetY = { it / 4 },
                                ) +
                                scaleOut(
                                    animationSpec = tween(durationMillis = 180),
                                    targetScale = 0.98f,
                                ),
                        ) {
                            Text(
                                text = stringResource(R.string.main_steam_cloud_background_upload_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateContentSize(animationSpec = tween(durationMillis = 220)),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                onClick = onCancelSync,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(text = stringResource(R.string.main_steam_cloud_action_cancel_upload))
                            }
                            AnimatedVisibility(
                                visible = showBackgroundUploadAction,
                                modifier = Modifier.weight(1f),
                                enter = fadeIn(animationSpec = tween(durationMillis = 220)) +
                                    slideInVertically(
                                        animationSpec = tween(durationMillis = 220),
                                        initialOffsetY = { it / 2 },
                                    ) +
                                    scaleIn(
                                        animationSpec = tween(durationMillis = 220),
                                        initialScale = 0.94f,
                                    ),
                                exit = fadeOut(animationSpec = tween(durationMillis = 180)) +
                                    slideOutVertically(
                                        animationSpec = tween(durationMillis = 180),
                                        targetOffsetY = { it / 3 },
                                    ) +
                                    scaleOut(
                                        animationSpec = tween(durationMillis = 180),
                                        targetScale = 0.98f,
                                    ),
                            ) {
                                Button(
                                    onClick = onBackgroundUploadAndLaunch,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(text = stringResource(R.string.main_steam_cloud_action_background_upload))
                                }
                            }
                        }
                    }
                }
            }

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
    val timestampMs: Long?,
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
    plan: SteamCloudUploadPlan,
): SteamCloudConflictCardSummaries {
    return SteamCloudConflictCardSummaries(
        local = buildSteamCloudConflictCardSummary(
            plan = plan,
            side = SteamCloudConflictCardSide.LOCAL,
        ),
        cloud = buildSteamCloudConflictCardSummary(
            plan = plan,
            side = SteamCloudConflictCardSide.CLOUD,
        ),
    )
}

private fun buildSteamCloudConflictCardSummary(
    plan: SteamCloudUploadPlan,
    side: SteamCloudConflictCardSide,
): SteamCloudConflictCardSummary {
    return when (side) {
        SteamCloudConflictCardSide.LOCAL -> {
            val currentEntries = plan.conflicts.mapNotNull { conflict ->
                conflict.currentLocal?.let { conflict.localRelativePath to it }
            }
            val latestEntry = currentEntries.maxByOrNull { (_, entry) -> entry.lastModifiedMs }
            SteamCloudConflictCardSummary(
                side = side,
                timestampMs = latestEntry?.second?.lastModifiedMs,
                totalBytes = currentEntries.sumOf { (_, entry) -> entry.fileSize },
            )
        }

        SteamCloudConflictCardSide.CLOUD -> {
            val currentEntries = plan.conflicts.mapNotNull { conflict ->
                conflict.currentRemote?.let { conflict.localRelativePath to it }
            }
            SteamCloudConflictCardSummary(
                side = side,
                timestampMs = plan.remoteManifestFetchedAtMs.takeIf { it > 0L },
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
    val timestampLabelResId = when (summary.side) {
        SteamCloudConflictCardSide.LOCAL -> R.string.main_steam_cloud_conflict_card_latest_modified
        SteamCloudConflictCardSide.CLOUD -> R.string.main_steam_cloud_conflict_card_manifest_refreshed_at
    }
    val timestampText = summary.timestampMs
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
                    timestampLabelResId,
                    timestampText
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

@Composable
private fun localizedSteamCloudPlanWarning(
    warning: String,
): String {
    val resources = LocalResources.current
    return when (val parsed = SteamCloudUserWarning.parse(warning)) {
        is SteamCloudUserWarning.UnsupportedLocalPath -> {
            resources.getString(
                R.string.main_steam_cloud_warning_unsupported_local_path,
                parsed.localRelativePath
            )
        }

        is SteamCloudUserWarning.FailedToMapLocalFile -> {
            resources.getString(
                R.string.main_steam_cloud_warning_failed_to_map_local_file,
                parsed.localRelativePath
            )
        }

        SteamCloudUserWarning.BaselineRequired -> {
            resources.getString(R.string.main_steam_cloud_warning_baseline_required)
        }

        is SteamCloudUserWarning.IgnoredLocalDeletions -> {
            resources.getQuantityString(
                R.plurals.main_steam_cloud_warning_ignored_local_deletions,
                parsed.count,
                parsed.count
            )
        }

        is SteamCloudUserWarning.UnsupportedRemotePath -> {
            resources.getString(
                R.string.main_steam_cloud_warning_unsupported_remote_path,
                parsed.remotePath
            )
        }

        null -> warning
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

private fun formatLauncherByteSize(bytes: Long): String {
    if (bytes <= 0L) {
        return "0 B"
    }
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    return when {
        bytes >= gib -> String.format(Locale.US, "%.1f GB", bytes / gib)
        bytes >= mib -> String.format(Locale.US, "%.1f MB", bytes / mib)
        bytes >= kib -> String.format(Locale.US, "%.1f KB", bytes / kib)
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
private fun ModBatchEditBottomBar(
    modifier: Modifier = Modifier,
    batchEditBarState: BatchEditBarState?,
    onHeightChanged: (Int) -> Unit,
) {
    AnimatedContent(
        targetState = batchEditBarState != null,
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { onHeightChanged(it.height) },
        transitionSpec = {
            val slideIn = slideInVertically(
                initialOffsetY = { fullHeight -> fullHeight / 2 },
                animationSpec = tween(durationMillis = BOTTOM_BAR_SWITCH_ANIMATION_MS)
            ) + fadeIn(animationSpec = tween(durationMillis = BOTTOM_BAR_SWITCH_ANIMATION_MS))
            val slideOut = slideOutVertically(
                targetOffsetY = { fullHeight -> fullHeight / 2 },
                animationSpec = tween(durationMillis = BOTTOM_BAR_SWITCH_ANIMATION_MS)
            ) + fadeOut(animationSpec = tween(durationMillis = BOTTOM_BAR_SWITCH_ANIMATION_MS))
            slideIn togetherWith slideOut using SizeTransform(clip = false)
        },
        label = "modBatchEditBottomBar"
    ) { showingBatchBar ->
        if (showingBatchBar) {
            val state = batchEditBarState
            if (state != null) {
                BatchEditToolbar(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    selectedCount = state.selectedCount,
                    controlsEnabled = state.controlsEnabled,
                    onMove = state.onMove,
                    onDelete = state.onDelete,
                    onEnable = state.onEnable,
                    onDisable = state.onDisable,
                    onCancel = state.onCancel
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.MainContentSwitcher(
    uiState: MainScreenViewModel.UiState,
    showInitializing: Boolean,
    contentTopInset: Dp = 0.dp,
    actionBarBottomPadding: Dp,
    onHeaderCollapsedChange: (Boolean) -> Unit = {},
    onBatchEditBarStateChange: (BatchEditBarState?) -> Unit,
    actions: MainScreenActions
) {
    DisposableEffect(Unit) {
        onDispose { onBatchEditBarStateChange(null) }
    }
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
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(MODS_CONTENT_PREPARING_TAG),
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
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(MODS_CONTENT_READY_TAG),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                uiState.storageIssue?.let { issue ->
                    StorageIssueCard(
                        issue = issue,
                        retryEnabled = actions.isHostAvailable && !uiState.busy,
                        onRetry = actions.onRetryStorageCheck
                    )
                }

                ModFolderSection(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    uiState = uiState,
                    contentTopInset = contentTopInset,
                    contentBottomInset = actionBarBottomPadding,
                    hostAvailable = actions.isHostAvailable,
                    onHeaderCollapsedChange = onHeaderCollapsedChange,
                    onBatchEditBarStateChange = onBatchEditBarStateChange,
                    callbacks = ModFolderSectionCallbacks(
                        onToggleMod = actions.onToggleMod,
                        onSetPriority = actions.onSetPriority,
                        onSetModFavorite = actions.onSetModFavorite,
                        onDeleteMod = actions.onDeleteMod,
                        onDeleteMods = actions.onDeleteMods,
                        onExportMod = actions.onExportMod,
                        onShareMod = actions.onShareMod,
                        onRenameModAlias = actions.onRenameModAlias,
                        onRestoreModOriginalName = actions.onRestoreModOriginalName,
                        onPatchWorkshopMod = actions.onPatchWorkshopMod,
                        onRetryWorkshopDownload = actions.onRetryWorkshopDownload,
                        onUpdateWorkshopMod = actions.onUpdateWorkshopMod,
                        onOpenWorkshopDetails = actions.onOpenWorkshopDetails,
                        onAssociateMods = actions.onAssociateMods,
                        onRemoveModAssociation = actions.onRemoveModAssociation,
                        onClearModAssociationGroup = actions.onClearModAssociationGroup,
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
                        onAssignModsToFolder = actions.onAssignModsToFolder,
                        onMoveModsToUnassigned = actions.onMoveModsToUnassigned,
                        onSetModsSelected = actions.onSetModsSelected,
                        onRevealFolderToken = actions.onRevealFolderToken
                    )
                )
            }
        }
    }
}

@Composable
private fun ModLaunchProfileMenu(
    modifier: Modifier = Modifier,
    profiles: List<ModLaunchProfile>,
    activeProfileId: String,
    enabled: Boolean,
    onSelectProfile: (String) -> Unit,
    onAddProfile: (String) -> Unit,
    onRenameProfile: (String, String) -> Unit,
    onDeleteProfile: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var creatingProfile by remember { mutableStateOf(false) }
    var renamingProfile by remember { mutableStateOf<ModLaunchProfile?>(null) }
    val activeProfileName = profiles.firstOrNull { it.id == activeProfileId }?.displayName()
        ?: stringResource(R.string.main_mod_launch_profile_default)
    Box(modifier = modifier) {
        TextButton(
            modifier = Modifier.fillMaxSize(),
            onClick = { expanded = true },
            enabled = enabled,
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
        ) {
            Text(
                text = stringResource(R.string.main_mod_launch_profile_button, activeProfileName),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            profiles.forEach { profile ->
                val profileName = profile.displayName()
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_mod_profile),
                            contentDescription = null,
                            tint = if (profile.id == activeProfileId) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    },
                    text = {
                        Text(
                            text = if (profile.id == activeProfileId) {
                                stringResource(R.string.main_mod_launch_profile_selected_item, profileName)
                            } else {
                                profileName
                            }
                        )
                    },
                    trailingIcon = if (profile.id == DEFAULT_MOD_LAUNCH_PROFILE_ID) {
                        null
                    } else {
                        {
                            Row {
                                IconButton(
                                    onClick = {
                                        expanded = false
                                        renamingProfile = profile
                                    },
                                    enabled = enabled
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_edit),
                                        contentDescription = stringResource(
                                            R.string.main_mod_launch_profile_rename,
                                            profileName
                                        )
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        expanded = false
                                        onDeleteProfile(profile.id)
                                    },
                                    enabled = enabled
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete),
                                        contentDescription = stringResource(
                                            R.string.main_mod_launch_profile_delete,
                                            profileName
                                        )
                                    )
                                }
                            }
                        }
                    },
                    enabled = enabled,
                    onClick = {
                        if (enabled) {
                            expanded = false
                            onSelectProfile(profile.id)
                        }
                    }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.main_mod_launch_profile_add)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_add_circle),
                        contentDescription = null
                    )
                },
                onClick = {
                    if (enabled) {
                        expanded = false
                        creatingProfile = true
                    }
                },
                enabled = enabled
            )
        }
    }
    ProfileNameDialog(
        visible = creatingProfile,
        title = stringResource(R.string.main_mod_launch_profile_create_title),
        initialText = "",
        onDismiss = { creatingProfile = false },
        onConfirm = { name ->
            creatingProfile = false
            onAddProfile(name)
        }
    )
    renamingProfile?.let { profile ->
        ProfileNameDialog(
            visible = true,
            title = stringResource(R.string.main_mod_launch_profile_rename_title),
            initialText = profile.name,
            onDismiss = { renamingProfile = null },
            onConfirm = { name ->
                renamingProfile = null
                onRenameProfile(profile.id, name)
            }
        )
    }
}

@Composable
private fun ProfileNameDialog(
    visible: Boolean,
    title: String,
    initialText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    FolderNameDialog(
        visible = visible,
        title = title,
        initialText = initialText,
        label = stringResource(R.string.main_mod_launch_profile_name_hint),
        emptyError = stringResource(R.string.main_mod_launch_profile_name_empty),
        onDismiss = onDismiss,
        onConfirm = onConfirm
    )
}

@Composable
private fun ModLaunchProfile.displayName(): String {
    return if (id == DEFAULT_MOD_LAUNCH_PROFILE_ID) {
        stringResource(R.string.main_mod_launch_profile_default)
    } else {
        name
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
