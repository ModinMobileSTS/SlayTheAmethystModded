package io.stamethyst.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import io.stamethyst.backend.update.UpdateReleaseInfo
import io.stamethyst.ui.settings.core.SettingsScreenViewModel
import io.stamethyst.ui.settings.sections.SettingsUpdateSection
import io.stamethyst.ui.settings.sections.SlingBreakDownloadConfirmation
import io.stamethyst.ui.settings.sections.UpdateSettingsActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI-only: no downloads, no preference resets, no deletion of installed games or saves. */
@RunWith(AndroidJUnit4::class)
class SlingBreakSettingsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun decliningDependenciesDoesNotConfirmSelectionOrStartDownload() {
        var confirmations = 0
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                SlingBreakDownloadConfirmation(onConfirm = { confirmations++ }, onDismiss = { dismissals++ })
            }
        }
        compose.onNodeWithText(context.getString(R.string.godot_download_confirmation)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_sling_break_download_no)).performClick()
        compose.runOnIdle {
            assertEquals(0, confirmations)
            assertEquals(1, dismissals)
        }
    }

    @Test fun installedGithubTagIsIndependentAndUpdateRequiresConfirmation() {
        var checks = 0
        var installs = 0
        val release = UpdateReleaseInfo("v0.0.2", "0.0.2", null, "", "Game release notes",
            assetName = "game.pck", assetDownloadUrl = "https://github.com/Apricityx/slingbreak/releases/download/v0.0.2/game.pck")
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsUpdateSection(
                        uiState = SettingsScreenViewModel.UiState(
                            currentVersionText = "launcher-99", slingBreakInstalledVersion = "v0.0.1",
                            slingBreakUpdateRelease = release,
                        ),
                        actions = UpdateSettingsActions({}, {}, {}, {}, {},
                            onCheckSlingBreakUpdates = { checks++ }, onInstallSlingBreakUpdate = { installs++ }),
                    )
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.update_current_version, "v0.0.1")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.godot_check_updates)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, checks); assertEquals(0, installs) }
        compose.onNodeWithText(context.getString(R.string.godot_install_version, "v0.0.2")).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.godot_update_confirmation)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, installs) }
        compose.onNodeWithText(context.getString(R.string.settings_sling_break_download_yes)).performClick()
        compose.runOnIdle { assertEquals(1, installs) }
    }
}
