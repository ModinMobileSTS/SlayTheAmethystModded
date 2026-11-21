package io.stamethyst.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModListRefreshInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun backgroundRefreshKeepsEvenAnEmptyListMounted() {
        val state = mutableStateOf(MainScreenViewModel.UiState())
        compose.setContent {
            MaterialTheme { LauncherModsScreenContent(uiState = state.value) }
        }
        compose.onNodeWithTag(MODS_CONTENT_PREPARING_TAG).assertIsDisplayed()

        compose.runOnIdle {
            state.value = state.value.copy(initializing = false, hasLoadedModSnapshot = true)
        }
        compose.onNodeWithTag(MODS_CONTENT_READY_TAG).assertIsDisplayed()

        compose.runOnIdle {
            state.value = state.value.copy(initializing = true, controlsEnabled = false)
        }
        compose.onNodeWithTag(MODS_CONTENT_READY_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MODS_CONTENT_PREPARING_TAG).assertDoesNotExist()
    }
}
