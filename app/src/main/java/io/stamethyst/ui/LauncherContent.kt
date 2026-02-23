package io.stamethyst.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
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
            backStack = navigator.backStack,
            entryProvider = entryProvider {
                entry<Route.Main> {
                    LauncherMainScreen(
                        viewModel = mainViewModel,
                        modifier = Modifier.fillMaxSize(),
                        onOpenSettings = { navigator.push(Route.Settings) }
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
