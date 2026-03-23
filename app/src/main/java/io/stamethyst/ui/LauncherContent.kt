package io.stamethyst.ui

import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import io.stamethyst.R
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.stamethyst.navigation.LocalNavigator
import io.stamethyst.navigation.Route
import io.stamethyst.navigation.rememberAppNavigator
import io.stamethyst.backend.feedback.FeedbackInboxCoordinator
import io.stamethyst.ui.compatibility.LauncherCompatibilityScreen
import io.stamethyst.ui.feedback.LauncherFeedbackScreen
import io.stamethyst.ui.feedback.LauncherFeedbackConversationScreen
import io.stamethyst.ui.feedback.LauncherFeedbackIssueBrowserScreen
import io.stamethyst.ui.feedback.LauncherFeedbackSubscriptionsScreen
import io.stamethyst.ui.feedback.FeedbackSubmissionNotice
import io.stamethyst.ui.main.LauncherMainScreen
import io.stamethyst.ui.main.MainScreenViewModel
import io.stamethyst.ui.quickstart.QuickStartScreen
import io.stamethyst.ui.settings.LauncherMobileGluesSettingsScreen
import io.stamethyst.ui.settings.LauncherSettingsScreen
import io.stamethyst.ui.settings.SettingsScreenViewModel

@Composable
fun LauncherContent(
    initialRoute: Route = Route.Main,
    mainViewModel: MainScreenViewModel,
    settingsViewModel: SettingsScreenViewModel,
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = rememberAppNavigator(initialRoute)
    val uriHandler = LocalUriHandler.current
    var pendingFeedbackNotice by remember {
        mutableStateOf<FeedbackSubmissionNotice?>(null)
    }
    val feedbackInboxState by FeedbackInboxCoordinator.uiState.collectAsState()
    val mainUiState = mainViewModel.uiState
    val settingsUiState = settingsViewModel.uiState
    val currentRoute = navigator.backStack.lastOrNull() as? Route
    val isBlockingImportInteractionLocked =
        mainUiState.busyOperation == UiBusyOperation.MOD_IMPORT ||
            settingsUiState.busyOperation == UiBusyOperation.MOD_IMPORT
    val shouldShowBlockingImportWindow =
        isBlockingImportInteractionLocked && currentRoute != Route.QuickStart
    val blockingImportBusyMessage = when {
        mainUiState.busyOperation == UiBusyOperation.MOD_IMPORT -> mainUiState.busyMessage
        settingsUiState.busyOperation == UiBusyOperation.MOD_IMPORT -> settingsUiState.busyMessage
        else -> null
    }
    val blockingImportBusyProgressPercent = when {
        mainUiState.busyOperation == UiBusyOperation.MOD_IMPORT ->
            mainUiState.busyProgressPercent
        settingsUiState.busyOperation == UiBusyOperation.MOD_IMPORT ->
            settingsUiState.busyProgressPercent
        else -> null
    }

    CompositionLocalProvider(
        LocalNavigator provides navigator,
    ) {
        androidx.compose.runtime.LaunchedEffect(activity) {
            FeedbackInboxCoordinator.bind(activity.applicationContext)
            FeedbackInboxCoordinator.startPolling(activity.applicationContext)
        }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                NavDisplay(
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                    onBack = {
                        if (!isBlockingImportInteractionLocked) {
                            navigator.goBack()
                        }
                    },
                    backStack = navigator.backStack,
                    transitionSpec = {
                        slideInHorizontally(
                            animationSpec = tween(durationMillis = 420),
                            initialOffsetX = { fullWidth -> fullWidth }
                        ).togetherWith(
                            slideOutHorizontally(
                                animationSpec = tween(durationMillis = 420),
                                targetOffsetX = { fullWidth -> -fullWidth }
                            )
                        )
                    },
                    popTransitionSpec = {
                        slideInHorizontally(
                            animationSpec = tween(durationMillis = 420),
                            initialOffsetX = { fullWidth -> -fullWidth }
                        ).togetherWith(
                            slideOutHorizontally(
                                animationSpec = tween(durationMillis = 420),
                                targetOffsetX = { fullWidth -> fullWidth }
                            )
                        )
                    },
                    entryProvider = entryProvider {
                        entry<Route.QuickStart> {
                            QuickStartScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                                onImportSuccess = { navigator.resetRoot(Route.Main) }
                            )
                        }

                        entry<Route.Main> {
                            LauncherMainScreen(
                                viewModel = mainViewModel,
                                modifier = Modifier.fillMaxSize(),
                                onOpenSettings = { navigator.push(Route.Settings) },
                                feedbackUnreadCount = feedbackInboxState.unreadIssueCount,
                                onOpenFeedbackUpdates = {
                                    val unreadIssues = feedbackInboxState.subscriptions
                                        .filter { it.unread }
                                    when {
                                        unreadIssues.size == 1 -> {
                                            navigator.push(
                                                Route.FeedbackConversation(unreadIssues.first().issueNumber)
                                            )
                                        }

                                        else -> {
                                            navigator.push(Route.FeedbackSubscriptions)
                                        }
                                    }
                                }
                            )
                        }

                        entry<Route.Settings> {
                            LauncherSettingsScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                                feedbackSubmissionNotice = pendingFeedbackNotice,
                                onDismissFeedbackSubmissionNotice = {
                                    pendingFeedbackNotice = null
                                }
                            )
                        }

                        entry<Route.Compatibility> {
                            LauncherCompatibilityScreen(
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.MobileGluesSettings> {
                            LauncherMobileGluesSettingsScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.Feedback> {
                            LauncherFeedbackScreen(
                                modifier = Modifier.fillMaxSize(),
                                onSubmissionCompleted = { notice ->
                                    pendingFeedbackNotice = notice
                                    navigator.goBack()
                                },
                            )
                        }

                        entry<Route.FeedbackSubscriptions> {
                            LauncherFeedbackSubscriptionsScreen(
                                modifier = Modifier.fillMaxSize(),
                                onOpenConversation = { issueNumber ->
                                    navigator.push(Route.FeedbackConversation(issueNumber))
                                }
                            )
                        }

                        entry<Route.FeedbackIssueBrowser> {
                            LauncherFeedbackIssueBrowserScreen(
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        entry<Route.FeedbackConversation> { route ->
                            LauncherFeedbackConversationScreen(
                                modifier = Modifier.fillMaxSize(),
                                issueNumber = route.issueNumber
                            )
                        }
                    }
                )
                if (shouldShowBlockingImportWindow) {
                    BlockingImportInteractionBlocker(
                        message = blockingImportBusyMessage?.resolve()
                            ?: stringResource(R.string.mod_import_busy_message),
                        progressPercent = blockingImportBusyProgressPercent
                    )
                }
                settingsUiState.updatePromptState?.let { promptState ->
                    AlertDialog(
                        onDismissRequest = settingsViewModel::dismissUpdatePrompt,
                        title = { Text(stringResource(R.string.update_dialog_title)) },
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 360.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.update_dialog_current_version,
                                        promptState.currentVersion
                                    ),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = stringResource(
                                        R.string.update_dialog_latest_version,
                                        promptState.latestVersion
                                    ),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = stringResource(
                                        R.string.update_dialog_published_at,
                                        promptState.publishedAtText
                                    ),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = stringResource(
                                        R.string.update_dialog_download_source,
                                        promptState.downloadSourceDisplayName
                                    ),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = stringResource(R.string.update_dialog_notes_title),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = promptState.notesText,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    settingsViewModel.dismissUpdatePrompt()
                                    uriHandler.openUri(promptState.downloadUrl)
                                }
                            ) {
                                Text(stringResource(R.string.update_dialog_action_download))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = settingsViewModel::dismissUpdatePrompt) {
                                Text(stringResource(R.string.update_dialog_action_later))
                            }
                        }
                    )
            }
            feedbackInboxState.pendingNotice?.let { notice ->
                AlertDialog(
                    onDismissRequest = FeedbackInboxCoordinator::dismissUnreadNotice,
                    title = { Text(stringResource(R.string.main_feedback_notice_title)) },
                    text = {
                        Text(
                            if (notice.unreadIssueCount == 1) {
                                stringResource(R.string.main_feedback_notice_single)
                            } else {
                                stringResource(
                                    R.string.main_feedback_notice_multiple,
                                    notice.unreadIssueCount
                                )
                            }
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                FeedbackInboxCoordinator.dismissUnreadNotice()
                                val unreadIssues = feedbackInboxState.subscriptions.filter { it.unread }
                                when {
                                    unreadIssues.size == 1 -> {
                                        navigator.push(
                                            Route.FeedbackConversation(unreadIssues.first().issueNumber)
                                        )
                                    }

                                    else -> {
                                        navigator.push(Route.FeedbackSubscriptions)
                                    }
                                }
                            }
                        ) {
                            Text(stringResource(R.string.main_feedback_notice_open))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = FeedbackInboxCoordinator::dismissUnreadNotice) {
                            Text(stringResource(R.string.main_feedback_notice_later))
                        }
                    }
                )
            }
        }
    }
}
}

@Composable
private fun BlockingImportInteractionBlocker(
    message: String,
    progressPercent: Int? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.24f))
            .pointerInteropFilter { true },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val progressFraction = progressPercent
                    ?.coerceIn(0, 100)
                    ?.div(100f)
                if (progressFraction != null) {
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
