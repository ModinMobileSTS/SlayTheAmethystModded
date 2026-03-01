package io.stamethyst.ui

import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.stamethyst.model.ModItemUi
import io.stamethyst.navigation.LocalNavigator
import io.stamethyst.navigation.Route
import io.stamethyst.navigation.rememberAppNavigator
import io.stamethyst.ui.compatibility.LauncherCompatibilityScreen
import io.stamethyst.ui.main.LauncherMainScreen
import io.stamethyst.ui.main.MainScreenViewModel
import io.stamethyst.ui.settings.LauncherSettingsScreen
import io.stamethyst.ui.settings.SettingsScreenViewModel

@Composable
fun LauncherContent(
    mainViewModel: MainScreenViewModel,
    settingsViewModel: SettingsScreenViewModel,
) {
    val navigator = rememberAppNavigator(Route.Main)

    CompositionLocalProvider(
        LocalNavigator provides navigator,
    ) {
        NavDisplay(
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            onBack = { navigator.goBack() },
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
                    )
                }

                entry<Route.Compatibility> {
                    LauncherCompatibilityScreen(
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        )
    }
}
