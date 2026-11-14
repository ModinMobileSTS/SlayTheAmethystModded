package io.stamethyst.ui.main

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Compile offline. Execution needs a separately authorized, non-destructive UI test setup. */
@RunWith(AndroidJUnit4::class)
class RefreshRateSettingsDialogInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun shortDialogMeasuresAndScrollsWithoutNestedScrollerCrash() {
        val showing = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (showing.value) {
                    RefreshRateSettingsDialog(
                        modifier = Modifier.heightIn(max = 280.dp),
                        onDismissRequest = { showing.value = false },
                        onOpenDisplaySettings = {},
                    )
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.common_action_confirm)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_force_high_refresh_rate_desc))
            .performScrollTo().assertIsDisplayed()
        compose.onNode(isToggleable()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_ram_saver_experimental_chip))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.common_action_confirm)).performClick()
        compose.onNode(isToggleable()).assertDoesNotExist()
    }

    @Test fun largeFontKeepsSettingsActionPinnedAndBusySwitchDisabled() {
        var settingsRequests = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.6f)) {
                MaterialTheme {
                    RefreshRateSettingsDialog(
                        modifier = Modifier.heightIn(max = 360.dp),
                        enabled = false,
                        onDismissRequest = {},
                        onOpenDisplaySettings = { settingsRequests++ },
                    )
                }
            }
        }
        compose.onNode(isToggleable()).performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.common_action_confirm)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.main_refresh_rate_open_settings))
            .assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, settingsRequests) }
    }
}
