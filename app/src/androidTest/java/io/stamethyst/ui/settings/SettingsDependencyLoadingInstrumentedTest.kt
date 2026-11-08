package io.stamethyst.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.stamethyst.R
import io.stamethyst.config.SteamCloudSaveMode
import io.stamethyst.ui.settings.common.HapticTextButton
import io.stamethyst.ui.settings.common.LocalSettingsLoadRetry
import io.stamethyst.ui.settings.common.SettingsActionListItem
import io.stamethyst.ui.settings.common.SettingsDependencyContent
import io.stamethyst.ui.settings.common.SettingsDropdownField
import io.stamethyst.ui.settings.common.SettingsRadioOptionRow
import io.stamethyst.ui.settings.common.SettingsRouteScaffold
import io.stamethyst.ui.settings.common.SettingsSwitchItem
import io.stamethyst.ui.settings.common.SettingsSwitchSpec
import io.stamethyst.ui.settings.common.settingsControlEnabled
import io.stamethyst.ui.settings.core.SettingsLoadDomain
import io.stamethyst.ui.settings.core.SettingsLoadState
import io.stamethyst.ui.settings.core.SettingsScreenViewModel
import io.stamethyst.ui.settings.steamcloud.SteamCloudSaveSettingsCards
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsDependencyLoadingInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun slowSteamStateKeepsControlsVisibleButDisabledWithoutLoadingText() {
        var accountClicks = 0
        val state = SettingsScreenViewModel.UiState(loadStates = mapOf(
            SettingsLoadDomain.CONFIGURATION to SettingsLoadState.READY,
            SettingsLoadDomain.STEAM_ACCOUNT to SettingsLoadState.LOADING,
        ))
        compose.setContent {
            MaterialTheme {
                Column {
                    SettingsDependencyContent(state, SettingsLoadDomain.CONFIGURATION) {
                        SettingsActionListItem("custom FPS: 120", enabled = true, onClick = {})
                    }
                    SettingsDependencyContent(state, SettingsLoadDomain.STEAM_ACCOUNT) {
                        SettingsActionListItem("Steam login", enabled = true, onClick = { accountClicks++ })
                    }
                }
            }
        }
        compose.onNodeWithText("custom FPS: 120").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Steam login").assertIsDisplayed().assertIsNotEnabled()
            .performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, accountClicks) }
        assertNoLoadingText()
    }

    @Test
    fun failedResourceCheckKeepsDisabledControlAndRetry() {
        var retried: SettingsLoadDomain? = null
        val state = SettingsScreenViewModel.UiState(loadStates = mapOf(
            SettingsLoadDomain.RESOURCES to SettingsLoadState.ERROR,
        ))
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSettingsLoadRetry provides { retried = it }) {
                    Column {
                        SettingsDependencyContent(state, SettingsLoadDomain.RESOURCES) {
                            SettingsActionListItem("Resource pack", enabled = true, onClick = {})
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Resource pack").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.settings_value_retry)).performClick()
        compose.runOnIdle { assertEquals(SettingsLoadDomain.RESOURCES, retried) }
    }

    @Test
    fun configurationEnablesAsSoonAsReadyWhileResourcesStayDisabled() {
        var configurationState by mutableStateOf(SettingsLoadState.LOADING)
        compose.setContent {
            MaterialTheme {
                val state = SettingsScreenViewModel.UiState(loadStates = mapOf(
                    SettingsLoadDomain.CONFIGURATION to configurationState,
                    SettingsLoadDomain.RESOURCES to SettingsLoadState.LOADING,
                ))
                Column {
                    SettingsDependencyContent(state, SettingsLoadDomain.CONFIGURATION) {
                        SettingsActionListItem("saved FPS: 120", enabled = true, onClick = {})
                    }
                    SettingsDependencyContent(state, SettingsLoadDomain.RESOURCES) {
                        SettingsActionListItem("Resource pack", enabled = true, onClick = {})
                    }
                }
            }
        }
        compose.onNodeWithText("saved FPS: 120").assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle { configurationState = SettingsLoadState.READY }
        compose.onNodeWithText("saved FPS: 120").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Resource pack").assertIsDisplayed().assertIsNotEnabled()
        assertNoLoadingText()
    }

    @Test
    fun readyChildCannotReenableControlsUnderLoadingConfiguration() {
        val state = SettingsScreenViewModel.UiState(loadStates = mapOf(
            SettingsLoadDomain.CONFIGURATION to SettingsLoadState.LOADING,
            SettingsLoadDomain.STEAM_ACCOUNT to SettingsLoadState.READY,
        ))
        compose.setContent {
            MaterialTheme {
                SettingsDependencyContent(state, SettingsLoadDomain.CONFIGURATION) {
                    SettingsDependencyContent(state, SettingsLoadDomain.STEAM_ACCOUNT) {
                        HapticTextButton(onClick = {}) { Text("Steam login") }
                    }
                }
            }
        }
        compose.onNodeWithText("Steam login").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun sharedSwitchRadioDropdownAndButtonAreDisabledAndIgnoreTouches() {
        var changes = 0
        val state = SettingsScreenViewModel.UiState(loadStates = mapOf(
            SettingsLoadDomain.CONFIGURATION to SettingsLoadState.LOADING,
        ))
        compose.setContent {
            MaterialTheme {
                Column {
                    SettingsDependencyContent(state, SettingsLoadDomain.CONFIGURATION) {
                        SettingsSwitchItem(SettingsSwitchSpec(
                            checked = true, enabled = true, title = "Switch", onCheckedChange = { changes++ },
                        ))
                        SettingsRadioOptionRow(false, true, "Radio", onSelect = { changes++ })
                        SettingsDropdownField(
                            label = "Dropdown", valueText = "Not yet read", enabled = true,
                            options = listOf("Option"), optionLabel = { it }, onOptionSelected = { changes++ },
                        )
                        HapticTextButton(onClick = { changes++ }) { Text("Apply") }
                    }
                }
            }
        }
        compose.onAllNodes(isToggleable())[0].assertIsNotEnabled().performTouchInput { click() }
        listOf("Radio", "Dropdown", "Apply").forEach {
            compose.onNodeWithText(it).assertIsDisplayed().assertIsNotEnabled().performTouchInput { click() }
        }
        compose.onNodeWithText("Not yet read").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, changes) }
        assertNoLoadingText()
    }

    @Test
    fun directMaterialControlsFollowTheSameDependencyGate() {
        var changes = 0
        val state = SettingsScreenViewModel.UiState(loadStates = mapOf(
            SettingsLoadDomain.CONFIGURATION to SettingsLoadState.LOADING,
        ))
        compose.setContent {
            MaterialTheme {
                Column {
                    SettingsDependencyContent(state, SettingsLoadDomain.CONFIGURATION) {
                        Button(enabled = settingsControlEnabled(), onClick = { changes++ }) { Text("Save") }
                        OutlinedTextField(
                            "", onValueChange = { changes++ }, enabled = settingsControlEnabled(),
                            modifier = Modifier.testTag("field"),
                        )
                        Slider(
                            0.5f, onValueChange = { changes++ }, enabled = settingsControlEnabled(),
                            modifier = Modifier.testTag("slider"),
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Save").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag("field").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag("slider").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, changes) }
    }

    @Test
    fun scaffoldKeepsLazyContentAndBackNavigationWhileLoadingConfiguration() {
        var backClicks = 0
        val state = SettingsScreenViewModel.UiState(loadStates = mapOf(
            SettingsLoadDomain.CONFIGURATION to SettingsLoadState.LOADING,
        ))
        compose.setContent {
            MaterialTheme {
                SettingsRouteScaffold(
                    uiState = state, title = "Settings", subtitle = "Preferences",
                    iconResId = R.drawable.ic_account_circle,
                    onGoBack = { backClicks++ },
                ) {
                    item { SettingsActionListItem("Preference", enabled = true, onClick = {}) }
                }
            }
        }
        compose.onNodeWithText("Preference").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_content_desc_back))
            .assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, backClicks) }
        assertNoLoadingText()
    }

    @Test
    fun saveModeRowsStayVisibleAndCannotChangeUntilSaveCheckCompletes() {
        var saveState by mutableStateOf(SettingsLoadState.LOADING)
        var changes = 0
        compose.setContent {
            MaterialTheme {
                Column {
                    SteamCloudSaveSettingsCards(
                        uiState = SettingsScreenViewModel.UiState(
                            steamCloudRefreshTokenConfigured = true,
                            steamCloudSaveMode = SteamCloudSaveMode.INDEPENDENT,
                            loadStates = mapOf(SettingsLoadDomain.STEAM_SAVES to saveState),
                        ),
                        onSteamCloudSaveModeChanged = { changes++ },
                        onOpenSteamCloudSyncBlacklistSettings = {},
                        onForceIndependentSaveOverwriteCloud = {},
                    )
                }
            }
        }
        val independent = compose.activity.getString(R.string.settings_steam_cloud_save_mode_independent_title)
        val cloud = compose.activity.getString(R.string.settings_steam_cloud_save_mode_cloud_title)
        compose.onNodeWithText(independent).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(cloud).assertIsDisplayed().assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, changes) }
        compose.runOnIdle { saveState = SettingsLoadState.READY }
        compose.onNodeWithText(cloud).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, changes) }
        assertNoLoadingText()
    }

    private fun assertNoLoadingText() {
        compose.onNodeWithText(compose.activity.getString(R.string.settings_value_loading)).assertDoesNotExist()
    }
}
