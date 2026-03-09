package io.stamethyst.ui

import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.stamethyst.navigation.LocalNavigator
import io.stamethyst.navigation.Route
import io.stamethyst.navigation.rememberAppNavigator
import io.stamethyst.ui.compatibility.LauncherCompatibilityScreen
import io.stamethyst.ui.feedback.LauncherFeedbackScreen
import io.stamethyst.ui.feedback.FeedbackSubmissionNotice
import io.stamethyst.ui.main.LauncherMainScreen
import io.stamethyst.ui.main.MainScreenViewModel
import io.stamethyst.ui.quickstart.QuickStartScreen
import io.stamethyst.ui.settings.LauncherSettingsScreen
import io.stamethyst.ui.settings.SettingsScreenViewModel

@Composable
fun LauncherContent(
    initialRoute: Route = Route.Main,
    mainViewModel: MainScreenViewModel,
    settingsViewModel: SettingsScreenViewModel,
) {
    val navigator = rememberAppNavigator(initialRoute)
    var pendingFeedbackNotice by remember {
        mutableStateOf<FeedbackSubmissionNotice?>(null)
    }
    val mainUiState = mainViewModel.uiState
    val settingsUiState = settingsViewModel.uiState
    val isModImportInteractionLocked =
        mainUiState.busyOperation == UiBusyOperation.MOD_IMPORT ||
            settingsUiState.busyOperation == UiBusyOperation.MOD_IMPORT
    val modImportBusyMessage = when {
        mainUiState.busyOperation == UiBusyOperation.MOD_IMPORT -> mainUiState.busyMessage
        settingsUiState.busyOperation == UiBusyOperation.MOD_IMPORT -> settingsUiState.busyMessage
        else -> null
    }

    CompositionLocalProvider(
        LocalNavigator provides navigator,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            NavDisplay(
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                onBack = {
                    if (!isModImportInteractionLocked) {
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

                    entry<Route.Feedback> {
                        LauncherFeedbackScreen(
                            modifier = Modifier.fillMaxSize(),
                            onSubmissionCompleted = { notice ->
                                pendingFeedbackNotice = notice
                                navigator.goBack()
                            }
                        )
                    }
                }
            )
            if (isModImportInteractionLocked) {
                ModImportInteractionBlocker(
                    message = modImportBusyMessage ?: "Importing selected mod jars..."
                )
            }
        }
    }
}

@Composable
private fun ModImportInteractionBlocker(message: String) {
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
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
