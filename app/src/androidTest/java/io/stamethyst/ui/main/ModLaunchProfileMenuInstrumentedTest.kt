package io.stamethyst.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import io.stamethyst.ui.LauncherBannerHost
import io.stamethyst.ui.LauncherBannerPage
import io.stamethyst.ui.LauncherBannerState
import io.stamethyst.ui.LocalLauncherBannerState
import dev.chrisbanes.haze.rememberHazeState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModLaunchProfileMenuInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bannerProfilesUnlockAfterScanWithoutModSelectionChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = mutableStateOf(MainScreenViewModel.UiState(controlsEnabled = false))
        val selected = ArrayList<String>()
        compose.setContent {
            MaterialTheme {
                val banner = remember { LauncherBannerState() }
                val haze = rememberHazeState()
                val pager = rememberPagerState { 1 }
                CompositionLocalProvider(LocalLauncherBannerState provides banner) {
                    Box(Modifier.fillMaxSize()) {
                        LauncherBannerPage("mods") {
                            LauncherModsScreenContent(
                                uiState = state.value,
                                actions = MainScreenActions(
                                    isHostAvailable = true,
                                    onSelectModLaunchProfile = { id, _ -> selected.add(id) },
                                ),
                            )
                        }
                        LauncherBannerHost(
                            state = banner,
                            hazeState = haze,
                            activeKey = "mods",
                            dockKeys = listOf("mods"),
                            pagerState = pager,
                        )
                    }
                }
            }
        }
        compose.onNodeWithText(context.getString(
            R.string.main_mod_launch_profile_button,
            context.getString(R.string.main_mod_launch_profile_default),
        )).assertIsNotEnabled()
        val button = context.getString(R.string.main_mod_launch_profile_button, "Saved")
        compose.runOnIdle {
            state.value = state.value.copy(
                modLaunchProfiles = listOf(
                    ModLaunchProfile("default", "Default", emptySet()),
                    ModLaunchProfile("saved", "Saved", emptySet()),
                ),
                activeModLaunchProfileId = "saved",
            )
        }
        compose.onNodeWithText(button).assertIsEnabled().performClick()
        val saved = context.getString(R.string.main_mod_launch_profile_selected_item, "Saved")
        compose.onNodeWithText(saved).assertIsNotEnabled()
        // Only loading flags change: no mod toggle or optionalMods replacement.
        compose.runOnIdle {
            state.value = state.value.copy(initializing = false, controlsEnabled = true)
        }
        compose.onNodeWithText(saved).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("saved"), selected) }
    }

    @Test fun savedProfilesAreBrowsableButReadOnlyUntilScanCompletes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scanComplete = mutableStateOf(false)
        val selected = ArrayList<String>()
        compose.setContent {
            MaterialTheme {
                ModLaunchProfileMenu(
                    profiles = listOf(
                        ModLaunchProfile("default", "Default", emptySet()),
                        ModLaunchProfile("saved", "Saved", setOf("not-scanned-yet.jar")),
                    ),
                    activeProfileId = "saved",
                    enabled = scanComplete.value,
                    menuEnabled = true,
                    onSelectProfile = { id, _ -> selected.add(id) },
                    onAddProfile = { error("Unexpected add") },
                    onRenameProfile = { _, _ -> error("Unexpected rename") },
                    onDeleteProfile = { _, _ -> error("Unexpected delete") },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.main_mod_launch_profile_button, "Saved"))
            .assertIsEnabled().performClick()
        val saved = context.getString(R.string.main_mod_launch_profile_selected_item, "Saved")
        compose.onNodeWithText(saved).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.main_mod_launch_profile_auto_save))
            .assertIsOn().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.main_mod_launch_profile_add)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.main_mod_launch_profile_rename, "Saved"))
            .assertIsNotEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.main_mod_launch_profile_delete, "Saved"))
            .assertIsNotEnabled()
        compose.runOnIdle { scanComplete.value = true }
        compose.onNodeWithText(saved).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("saved"), selected) }
    }

    @Test fun manualActionsAppearOnlyWhenDirtyAndAutosaveCannotDiscardEdits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val autoSave = mutableStateOf(true)
        val dirty = mutableStateOf(false)
        val actions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                ModLaunchProfileMenu(
                    profiles = listOf(ModLaunchProfile("saved", "Saved", emptySet())),
                    activeProfileId = "saved",
                    enabled = true,
                    autoSave = autoSave.value,
                    selectionDirty = dirty.value,
                    onSelectProfile = { _, _ -> error("Unexpected select") },
                    onAddProfile = { error("Unexpected add") },
                    onRenameProfile = { _, _ -> error("Unexpected rename") },
                    onDeleteProfile = { _, _ -> error("Unexpected delete") },
                    onSetAutoSave = { autoSave.value = it },
                    onSaveSelection = { actions.add("save"); dirty.value = false },
                    onRestoreSelection = { actions.add("restore"); dirty.value = false },
                )
            }
        }
        val menu = compose.onNodeWithText(context.getString(R.string.main_mod_launch_profile_button, "Saved"))
        val toggle = compose.onNodeWithText(context.getString(R.string.main_mod_launch_profile_auto_save))
        val save = compose.onNodeWithText(context.getString(R.string.main_mod_launch_profile_save_selection))
        val restore = compose.onNodeWithText(context.getString(R.string.main_mod_launch_profile_restore_selection))
        menu.performClick()
        toggle.assertIsOn().performClick()
        toggle.assertIsOff()
        save.assertDoesNotExist()
        restore.assertDoesNotExist()

        compose.runOnIdle { dirty.value = true }
        save.assertIsDisplayed()
        restore.assertIsDisplayed()
        toggle.assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.main_mod_launch_profile_auto_save_guard)).assertIsDisplayed()
        save.performClick()
        menu.performClick()
        save.assertDoesNotExist()
        compose.runOnIdle { dirty.value = true }
        restore.performClick()
        menu.performClick()
        restore.assertDoesNotExist()
        toggle.performClick()
        toggle.assertIsOn()
        compose.runOnIdle { assertEquals(listOf("save", "restore"), actions) }
    }

    @Test fun dirtyProfileSwitchRequiresExplicitDiscard() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val selected = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            MaterialTheme {
                ModLaunchProfileMenu(
                    profiles = listOf(
                        ModLaunchProfile("saved", "Saved", emptySet()),
                        ModLaunchProfile("other", "Other", emptySet()),
                    ),
                    activeProfileId = "saved",
                    enabled = true,
                    autoSave = false,
                    selectionDirty = true,
                    onSelectProfile = { id, discard -> selected.add(id to discard) },
                    onAddProfile = { error("Unexpected add") },
                    onRenameProfile = { _, _ -> error("Unexpected rename") },
                    onDeleteProfile = { _, _ -> error("Unexpected delete") },
                )
            }
        }
        val menu = compose.onNodeWithText(context.getString(R.string.main_mod_launch_profile_button, "Saved"))
        menu.performClick()
        compose.onNodeWithText("Other").performClick()
        compose.onNodeWithText(context.getString(R.string.main_mod_launch_profile_switch_guard)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.main_folder_dialog_cancel)).performClick()
        compose.runOnIdle { assertEquals(emptyList<Pair<String, Boolean>>(), selected) }
        menu.performClick()
        compose.onNodeWithText("Other").performClick()
        compose.onNodeWithText(context.getString(R.string.main_mod_launch_profile_discard_and_switch)).performClick()
        compose.runOnIdle { assertEquals(listOf("other" to true), selected) }
    }
}
