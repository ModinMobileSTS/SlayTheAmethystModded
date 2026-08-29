package io.stamethyst.ui.quickstart

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.steam.SteamStsJarDownloadPhase
import io.stamethyst.ui.Icons
import io.stamethyst.ui.icon.Pause
import io.stamethyst.ui.icon.PlayArrow
import io.stamethyst.ui.resolve
import io.stamethyst.ui.settings.core.SettingsScreenViewModel
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun QuickStartScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
    onOpenAutoImport: () -> Unit,
    onOpenSteamLogin: () -> Unit,
    onOpenJarImport: () -> Unit,
    onOpenSteamDownload: () -> Unit,
    onImportSuccess: () -> Unit
) {
    QuickStartImportContent(
        viewModel = viewModel,
        modifier = modifier,
        showWelcome = true,
        onOpenAutoImport = onOpenAutoImport,
        onOpenSteamLogin = onOpenSteamLogin,
        onOpenJarImport = onOpenJarImport,
        onOpenSteamDownload = onOpenSteamDownload,
        onImportSuccess = onImportSuccess
    )
}

@Composable
private fun QuickStartImportContent(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
    showWelcome: Boolean,
    onOpenAutoImport: () -> Unit,
    onOpenSteamLogin: () -> Unit,
    onOpenJarImport: () -> Unit,
    onOpenSteamDownload: () -> Unit,
    onImportSuccess: () -> Unit
) {
    val activity = requireNotNull(LocalActivity.current)
    val uriHandler = LocalUriHandler.current
    val quickStartDownloadUrl = stringResource(R.string.update_dialog_quark_download_url)
    val uiState = viewModel.uiState
    var imported by rememberSaveable { mutableStateOf(false) }
    var showJarSourceOverlay by rememberSaveable { mutableStateOf(false) }
    var showJarSourceDialog by rememberSaveable { mutableStateOf(false) }
    val noopInteraction = remember { MutableInteractionSource() }
    val closeJarSourceDialog = {
        showJarSourceOverlay = false
        showJarSourceDialog = false
    }
    val pickJarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.onJarPicked(activity, uri) { success ->
                if (success) {
                    imported = true
                    closeJarSourceDialog()
                }
            }
        }
    }

    fun startSteamImport() {
        if (uiState.busy) {
            return
        }
        if (!uiState.steamCloudRefreshTokenConfigured) {
            onOpenSteamLogin()
            return
        }
        onOpenSteamDownload()
    }

    LaunchedEffect(Unit) {
        viewModel.bind(activity)
    }

    LaunchedEffect(showJarSourceDialog, imported) {
        if (showJarSourceDialog && !imported) {
            showJarSourceOverlay = false
            delay(100)
            if (showJarSourceDialog && !imported) {
                showJarSourceOverlay = true
            }
        } else {
            showJarSourceOverlay = false
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 380.dp)
                    .animateContentSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                AnimatedContent(
                    targetState = imported,
                    label = "quickstart-title"
                ) { isImported ->
                    Text(
                        text = stringResource(
                            if (isImported) {
                                R.string.quick_start_title_done
                            } else if (!showWelcome) {
                                R.string.quick_start_title
                            } else {
                                R.string.quick_start_welcome_title
                            }
                        ),
                        style = if (isImported) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center
                    )
                }

                AnimatedContent(
                    targetState = imported,
                    label = "quickstart-subtitle"
                ) { isImported ->
                    Text(
                        text = if (isImported) {
                            ""
                        } else if (!showWelcome) {
                            stringResource(R.string.quick_start_subtitle)
                        } else {
                            stringResource(R.string.quick_start_welcome_subtitle)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                AnimatedVisibility(visible = !imported && showWelcome, label = "quickstart-start-options") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        QuickStartChoiceCard(
                            title = stringResource(R.string.quick_start_auto_import_title),
                            subtitle = stringResource(R.string.quick_start_auto_import_subtitle),
                            enabled = !uiState.busy,
                            onClick = onOpenAutoImport,
                        )
                        QuickStartChoiceCard(
                            title = stringResource(R.string.quick_start_start_from_file_title),
                            subtitle = stringResource(R.string.quick_start_start_from_file_subtitle),
                            enabled = !uiState.busy,
                            onClick = onOpenJarImport
                        )
                        QuickStartChoiceCard(
                            title = stringResource(R.string.quick_start_start_from_steam_title),
                            subtitle = stringResource(R.string.quick_start_start_from_steam_subtitle),
                            enabled = !uiState.busy,
                            onClick = ::startSteamImport
                        )
                    }
                }

                AnimatedVisibility(visible = !imported && !showWelcome, label = "quickstart-import-box") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.64f)
                            .widthIn(max = 232.dp)
                            .clip(shape = RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .clickable(enabled = !uiState.busy) {
                                pickJarLauncher.launch(
                                    arrayOf("application/java-archive", "application/octet-stream", "*/*")
                                )
                            }
                            .animateContentSize()
                            .padding(horizontal = 12.dp, vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.quick_start_import_button),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                AnimatedVisibility(visible = !imported, label = "quickstart-jar-source-toggle") {
                    Text(
                        text = stringResource(R.string.quick_start_missing_jar_link),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable(enabled = !uiState.busy) {
                            showJarSourceDialog = true
                        }
                    )
                }

                if (uiState.busy) {
                    val busyProgress = uiState.busyProgressPercent
                        ?.coerceIn(0, 100)
                        ?.div(100f)
                    QuickStartAnimatedLinearProgressIndicator(
                        progress = busyProgress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    uiState.busyMessage?.let { busyMessage ->
                        Text(
                            text = busyMessage.resolve(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                AnimatedVisibility(visible = imported, label = "quickstart-continue-button") {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(
                                enabled = !uiState.busy,
                                onClick = onImportSuccess
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "→",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !imported && showJarSourceOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            label = "quickstart-jar-source-overlay"
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(onClick = closeJarSourceDialog)
            )
        }

        AnimatedVisibility(
            visible = !imported && showJarSourceDialog,
            enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessHigh)) +
                scaleIn(
                    initialScale = 0.8f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ),
            exit = fadeOut() + scaleOut(targetScale = 0.95f),
            label = "quickstart-jar-source-dialog"
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .widthIn(max = 360.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp
                ) {
                    Box(
                        modifier = Modifier
                            .clickable(
                                interactionSource = noopInteraction,
                                indication = null
                            ) { }
                            .padding(horizontal = 18.dp, vertical = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.quick_start_missing_jar_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.quick_start_missing_jar_intro),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(R.string.quick_start_missing_jar_howto),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.quick_start_missing_jar_step1),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(R.string.quick_start_missing_jar_step2),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(
                                        R.string.quick_start_missing_jar_download_link,
                                        quickStartDownloadUrl
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                    modifier = Modifier.clickable {
                                        uriHandler.openUri(quickStartDownloadUrl)
                                    }
                                )
                            }
                            Text(
                                text = stringResource(R.string.quick_start_acknowledge),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                textDecoration = TextDecoration.Underline,
                                modifier = Modifier.clickable(onClick = closeJarSourceDialog)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuickStartJarImportScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
    onImportSuccess: () -> Unit,
) {
    QuickStartImportContent(
        viewModel = viewModel,
        modifier = modifier,
        showWelcome = false,
        onOpenAutoImport = {},
        onOpenSteamLogin = {},
        onOpenJarImport = {},
        onOpenSteamDownload = {},
        onImportSuccess = onImportSuccess
    )
}

@Composable
fun QuickStartAutomaticImportScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
    onOpenSteamLogin: () -> Unit,
    onChooseAnotherImportMode: () -> Unit,
    onImportSuccess: () -> Unit,
) {
    val activity = requireNotNull(LocalActivity.current)
    val uiState = viewModel.uiState
    var imported by rememberSaveable { mutableStateOf(false) }
    var downloadStarted by rememberSaveable { mutableStateOf(false) }
    var pendingAccelerationEnabled by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var showExitConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var exiting by rememberSaveable { mutableStateOf(false) }

    fun requestExit() {
        if (!imported) {
            showExitConfirmDialog = true
        }
    }

    fun confirmExit() {
        showExitConfirmDialog = false
        exiting = true
        downloadStarted = false
        viewModel.onExitQuickStartAutomaticImport(activity)
        onChooseAnotherImportMode()
    }

    BackHandler(enabled = !imported) {
        requestExit()
    }

    LaunchedEffect(Unit) {
        viewModel.bind(activity)
    }

    LaunchedEffect(uiState.busy, uiState.quickStartSteamFailed, imported, downloadStarted, exiting) {
        if (!exiting && !uiState.busy && !uiState.quickStartSteamFailed && !downloadStarted && !imported) {
            downloadStarted = true
            viewModel.onStartQuickStartAutomaticImport(activity) { success ->
                if (success) {
                    imported = true
                } else {
                    downloadStarted = false
                }
            }
        }
    }

    QuickStartSteamImportProgressContent(
        modifier = modifier,
        uiState = uiState,
        imported = imported,
        titleResId = R.string.quick_start_auto_import_page_title,
        subtitleResId = R.string.quick_start_auto_import_page_subtitle,
        showAccelerationSwitch = true,
        onImportSuccess = onImportSuccess,
        onPause = { viewModel.onQuickStartSteamPauseChanged(true) },
        onResume = { viewModel.onQuickStartSteamPauseChanged(false) },
        onRetry = {
            downloadStarted = true
            viewModel.onRetryQuickStartSteamImport(activity)
        },
        showExitAction = !imported,
        onExit = ::requestExit,
        showSteamLoginFallback = uiState.quickStartAutomaticImportRequiresLogin,
        onOpenSteamLogin = onOpenSteamLogin,
        onRequestAccelerationChange = { enabled ->
            pendingAccelerationEnabled = enabled
        },
    )

    QuickStartSteamAccelerationConfirmDialog(
        pendingAccelerationEnabled = pendingAccelerationEnabled,
        onDismiss = { pendingAccelerationEnabled = null },
        onConfirm = { enabled ->
            pendingAccelerationEnabled = null
            viewModel.onQuickStartSteamAccelerationChanged(activity, enabled)
        },
    )

    QuickStartAutomaticImportExitConfirmDialog(
        visible = showExitConfirmDialog,
        onDismiss = { showExitConfirmDialog = false },
        onConfirm = ::confirmExit,
    )
}

@Composable
fun QuickStartSteamDownloadScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
    onImportSuccess: () -> Unit,
) {
    val activity = requireNotNull(LocalActivity.current)
    val uiState = viewModel.uiState
    var imported by rememberSaveable { mutableStateOf(false) }
    var downloadStarted by rememberSaveable { mutableStateOf(false) }
    var pendingAccelerationEnabled by rememberSaveable { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        viewModel.bind(activity)
    }

    LaunchedEffect(
        uiState.steamCloudRefreshTokenConfigured,
        uiState.busy,
        uiState.quickStartSteamFailed,
        imported,
        downloadStarted,
    ) {
        if (uiState.steamCloudRefreshTokenConfigured &&
            !uiState.busy &&
            !uiState.quickStartSteamFailed &&
            !downloadStarted &&
            !imported
        ) {
            downloadStarted = true
            viewModel.onStartQuickStartSteamImport(activity) { success ->
                if (success) {
                    imported = true
                } else {
                    downloadStarted = false
                }
            }
        }
    }

    QuickStartSteamImportProgressContent(
        modifier = modifier,
        uiState = uiState,
        imported = imported,
        titleResId = R.string.quick_start_steam_download_page_title,
        subtitleResId = R.string.quick_start_steam_download_page_subtitle,
        showAccelerationSwitch = true,
        onImportSuccess = onImportSuccess,
        onPause = { viewModel.onQuickStartSteamPauseChanged(true) },
        onResume = { viewModel.onQuickStartSteamPauseChanged(false) },
        onRetry = {
            downloadStarted = true
            viewModel.onRetryQuickStartSteamImport(activity)
        },
        showExitAction = false,
        onExit = {},
        showSteamLoginFallback = false,
        onOpenSteamLogin = {},
        onRequestAccelerationChange = { enabled ->
            pendingAccelerationEnabled = enabled
        },
    )

    QuickStartSteamAccelerationConfirmDialog(
        pendingAccelerationEnabled = pendingAccelerationEnabled,
        onDismiss = { pendingAccelerationEnabled = null },
        onConfirm = { enabled ->
            pendingAccelerationEnabled = null
            viewModel.onQuickStartSteamAccelerationChanged(activity, enabled)
        },
    )
}

@Composable
private fun QuickStartSteamAccelerationConfirmDialog(
    pendingAccelerationEnabled: Boolean?,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    pendingAccelerationEnabled?.let { enabled ->
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(text = stringResource(R.string.quick_start_steam_acceleration_confirm_title))
            },
            text = {
                Text(
                    text = stringResource(
                        if (enabled) {
                            R.string.quick_start_steam_acceleration_confirm_enable_message
                        } else {
                            R.string.quick_start_steam_acceleration_confirm_disable_message
                        }
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = { onConfirm(enabled) }
                ) {
                    Text(text = stringResource(R.string.quick_start_steam_acceleration_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun QuickStartAutomaticImportExitConfirmDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) {
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.quick_start_auto_import_exit_confirm_title))
        },
        text = {
            Text(text = stringResource(R.string.quick_start_auto_import_exit_confirm_message))
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(text = stringResource(R.string.quick_start_auto_import_exit_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun QuickStartSteamImportProgressContent(
    modifier: Modifier,
    uiState: SettingsScreenViewModel.UiState,
    imported: Boolean,
    titleResId: Int,
    subtitleResId: Int,
    showAccelerationSwitch: Boolean,
    onImportSuccess: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    showExitAction: Boolean,
    onExit: () -> Unit,
    showSteamLoginFallback: Boolean,
    onOpenSteamLogin: () -> Unit,
    onRequestAccelerationChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
                    .animateContentSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(
                        if (imported) R.string.quick_start_title_done else titleResId
                    ),
                    style = if (imported) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )
                if (!imported) {
                    Text(
                        text = stringResource(subtitleResId),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    if (showAccelerationSwitch) {
                        SteamAccelerationSwitchCard(
                            checked = uiState.workshopWattAccelerationEnabled,
                            enabled = uiState.quickStartSteamAccelerationSwitchEnabled && !uiState.quickStartSteamPaused,
                            onCheckedChange = onRequestAccelerationChange
                        )
                    }
                    QuickStartSteamDownloadProgressCard(
                        uiState = uiState,
                        onPause = onPause,
                        onResume = onResume,
                    )
                    QuickStartSteamDownloadRetryAction(
                        uiState = uiState,
                        onRetry = onRetry,
                        showExitAction = showExitAction,
                        onExit = onExit,
                        showSteamLoginFallback = showSteamLoginFallback,
                        onOpenSteamLogin = onOpenSteamLogin,
                    )
                }
                AnimatedVisibility(visible = imported, label = "quickstart-steam-continue-button") {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(onClick = onImportSuccess),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "→",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SteamAccelerationSwitchCard(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.quick_start_steam_acceleration_enabled_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.quick_start_steam_acceleration_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun QuickStartSteamDownloadProgressCard(
    uiState: SettingsScreenViewModel.UiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val progress = uiState.busyProgressPercent
                    ?.coerceIn(0, 100)
                    ?.div(100f)
                QuickStartAnimatedLinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.weight(1f),
                )
                QuickStartSteamDownloadPauseButton(
                    uiState = uiState,
                    onPause = onPause,
                    onResume = onResume,
                )
            }
            uiState.busyMessage?.let { busyMessage ->
                Text(
                    text = busyMessage.resolve(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Start
                )
            }
            uiState.quickStartSteamFailureMessage?.let { failureMessage ->
                Text(
                    text = failureMessage.resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Start
                )
            }
            val totalBytes = uiState.quickStartSteamTotalBytes
            if (totalBytes != null && totalBytes > 0L) {
                HorizontalDivider()
                Text(
                    text = stringResource(
                        R.string.quick_start_steam_download_detail,
                        formatQuickStartBytes(uiState.quickStartSteamDownloadedBytes),
                        formatQuickStartBytes(totalBytes),
                        uiState.busyProgressPercent ?: 0
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (uiState.quickStartSteamDownloadPhase == SteamStsJarDownloadPhase.CONNECTING ||
                uiState.quickStartSteamDownloadPhase == SteamStsJarDownloadPhase.RESOLVING
            ) {
                Text(
                    text = stringResource(R.string.quick_start_steam_download_waiting_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun QuickStartAnimatedLinearProgressIndicator(
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    if (progress == null) {
        LinearProgressIndicator(modifier = modifier)
        return
    }

    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(
            durationMillis = 450,
            easing = FastOutSlowInEasing,
        ),
        label = "quick-start-progress",
    )
    LinearProgressIndicator(
        progress = { animatedProgress },
        modifier = modifier,
    )
}

@Composable
private fun QuickStartSteamDownloadPauseButton(
    uiState: SettingsScreenViewModel.UiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    val canControl = uiState.busy && uiState.quickStartSteamDownloadPhase != null
    val paused = uiState.quickStartSteamPaused
    IconButton(
        enabled = canControl,
        onClick = if (paused) onResume else onPause,
    ) {
        Icon(
            imageVector = if (paused) Icons.PlayArrow else Icons.Pause,
            contentDescription = stringResource(
                if (paused) R.string.quick_start_steam_download_resume else R.string.quick_start_steam_download_pause
            ),
            tint = if (canControl) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QuickStartSteamDownloadRetryAction(
    uiState: SettingsScreenViewModel.UiState,
    onRetry: () -> Unit,
    showExitAction: Boolean,
    onExit: () -> Unit,
    showSteamLoginFallback: Boolean,
    onOpenSteamLogin: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (uiState.quickStartSteamFailed) {
            Button(onClick = onRetry) {
                Text(text = stringResource(R.string.quick_start_steam_download_retry))
            }
            if (showSteamLoginFallback) {
                TextButton(onClick = onOpenSteamLogin) {
                    Text(text = stringResource(R.string.quick_start_auto_import_use_steam_login))
                }
            }
        }
        if (showExitAction) {
            OutlinedButton(
                onClick = onExit,
                enabled = true,
            ) {
                Text(text = stringResource(R.string.quick_start_auto_import_choose_other_mode))
            }
        }
    }
}

private fun formatQuickStartBytes(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.coerceAtLeast(0L).toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return if (unitIndex == 0) {
        "${value.toLong()} ${units[unitIndex]}"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }
}

@Composable
private fun QuickStartChoiceCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.46f)
                }
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.46f)
                }
            )
        }
    }
}
