package io.stamethyst.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import io.stamethyst.config.BackBehavior
import io.stamethyst.navigation.Route
import io.stamethyst.ui.settings.core.SettingsScreenViewModel
import io.stamethyst.ui.settings.core.settingsSearchActions
import io.stamethyst.ui.settings.sections.LauncherSettingsScreenContent
import io.stamethyst.ui.settings.sections.SettingsSearchActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI-only callbacks: never change real preferences, import files, or reset user data. */
@RunWith(AndroidJUnit4::class)
class SettingsSearchInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val navigations = mutableListOf<Route>()
    private var previousSearchHistory: String? = null

    @Before
    fun saveSearchHistory() {
        previousSearchHistory = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)
            .getString("settings", null)
    }

    @After
    fun restoreSearchHistory() {
        val editor = context.getSharedPreferences("search_history", Context.MODE_PRIVATE).edit()
        previousSearchHistory?.let { editor.putString("settings", it) } ?: editor.remove("settings")
        editor.commit()
    }

    @Test
    fun choiceResultOpensDialogAndSavesWithoutNavigating() {
        var selected: BackBehavior? = null
        showSettings { actions ->
            actions.copy(input = actions.input.copy(onBackBehaviorChanged = { selected = it }))
        }
        search(R.string.settings_back_behavior_title)
        clickResult(R.string.settings_back_behavior_title)

        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_back_behavior_desc)).assertIsDisplayed()
        compose.runOnIdle { assertTrue(navigations.isEmpty()) }
        // Selecting the already selected option must still call the real control's save callback.
        compose.onNode(
            hasText(context.getString(R.string.settings_back_behavior_exit)) and
                hasAnyAncestor(isDialog())
        ).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(BackBehavior.EXIT_TO_LAUNCHER, selected) }
    }

    @Test
    fun playerNameCanBeEditedFromSearchAndCancelledWithoutSaving() {
        var saves = 0
        showSettings { actions -> actions.copy(onPlayerNameChanged = { saves++; true }) }
        search(R.string.settings_player_name_title)
        clickResult(R.string.settings_player_name_dialog_title)

        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("new player")
        compose.onNodeWithText(context.getString(R.string.main_folder_dialog_cancel)).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, saves)
            assertTrue(navigations.isEmpty())
        }
    }

    @Test
    fun playerNameValidationAndSaveArePreservedInSearch() {
        var savedName: String? = null
        showSettings { actions ->
            actions.copy(onPlayerNameChanged = { name ->
                if (name.isBlank()) false else {
                    savedName = name
                    true
                }
            })
        }
        search(R.string.settings_player_name_title)
        clickResult(R.string.settings_player_name_dialog_title)
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("")
        compose.onNodeWithText(context.getString(R.string.main_folder_dialog_confirm)).performClick()
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, savedName) }

        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("new player")
        compose.onNodeWithText(context.getString(R.string.main_folder_dialog_confirm)).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("new player", savedName)
            assertTrue(navigations.isEmpty())
        }
    }

    @Test
    fun destructiveResultStillRequiresConfirmation() {
        var applied = 0
        showSettings { actions -> actions.copy(onApplyModFileNameAliases = { applied++ }) }
        search(R.string.settings_mod_alias_apply_file_names_action)
        clickResult(R.string.settings_mod_alias_apply_file_names_action)
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, applied) }
        compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, applied)
            assertTrue(navigations.isEmpty())
        }
    }

    @Test
    fun resourceOperationResultOpensItsOwnDialog() {
        showSettings()
        search(R.string.settings_save_operations)
        clickResult(R.string.settings_save_operations)

        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_import_saves)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_export_saves)).assertIsDisplayed()
        compose.runOnIdle { assertTrue(navigations.isEmpty()) }
    }

    @Test
    fun busyStateDisablesDialogResult() {
        showSettings(SettingsScreenViewModel.UiState(busy = true))
        search(R.string.settings_back_behavior_title)
        compose.onNodeWithText(context.getString(R.string.settings_back_behavior_title))
            .performScrollTo().assertIsNotEnabled()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertTrue(navigations.isEmpty()) }
    }

    @Test
    fun nonDialogResultStillNavigatesToCategory() {
        showSettings()
        search(R.string.settings_gameplay_font_scale_title)
        clickResult(R.string.settings_gameplay_font_scale_title)
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(Route.SettingsGame), navigations) }
    }

    @Test
    fun changingSearchDoesNotCarryOpenDialogToAnotherResult() {
        showSettings()
        search(R.string.settings_theme_mode_title)
        clickResult(R.string.settings_theme_mode_title)
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.main_folder_dialog_confirm)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(context.getString(R.string.settings_app_icon_title))
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNode(isDialog()).assertDoesNotExist()
        clickResult(R.string.settings_app_icon_title)
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.runOnIdle { assertTrue(navigations.isEmpty()) }
    }

    private fun showSettings(
        uiState: SettingsScreenViewModel.UiState = SettingsScreenViewModel.UiState(playerName = "player"),
        configure: (SettingsSearchActions) -> SettingsSearchActions = { it },
    ) {
        compose.setContent {
            MaterialTheme {
                SettingsContent(uiState, configure)
            }
        }
    }

    @Composable
    private fun SettingsContent(
        uiState: SettingsScreenViewModel.UiState,
        configure: (SettingsSearchActions) -> SettingsSearchActions,
    ) {
        val viewModel = remember { SettingsScreenViewModel() }
        val actions = settingsSearchActions(viewModel, requireNotNull(LocalActivity.current))
        LauncherSettingsScreenContent(
            uiState = uiState,
            onOpenSettingsRoute = navigations::add,
            searchActions = configure(actions),
        )
    }

    private fun search(@StringRes title: Int) {
        compose.onNode(hasSetTextAction()).performTextInput(context.getString(title))
        compose.onNode(hasSetTextAction()).performImeAction()
    }

    private fun clickResult(@StringRes title: Int) {
        compose.onNodeWithText(context.getString(title)).performScrollTo().performClick()
    }
}
