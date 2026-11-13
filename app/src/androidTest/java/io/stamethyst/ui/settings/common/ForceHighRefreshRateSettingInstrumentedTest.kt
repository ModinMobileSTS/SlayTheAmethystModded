package io.stamethyst.ui.settings.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import io.stamethyst.backend.render.ForceHighRefreshRateController
import io.stamethyst.config.LauncherConfig
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Compile offline; running these UI tests requires separate non-destructive device authorization. */
@RunWith(AndroidJUnit4::class)
class ForceHighRefreshRateSettingInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var original = false

    @Before fun prepare() {
        original = LauncherConfig.isForceHighRefreshRateEnabled(context)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ForceHighRefreshRateController.setEnabled(context, false)
        }
    }

    @After fun restore() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ForceHighRefreshRateController.setEnabled(context, original)
        }
    }

    @Test fun bothInstancesSharePersistentSettingAndExperimentalLabel() {
        assertFalse(LauncherConfig.DEFAULT_FORCE_HIGH_REFRESH_RATE)
        compose.setContent {
            MaterialTheme {
                Column {
                    ForceHighRefreshRateSetting()
                    ForceHighRefreshRateSetting()
                }
            }
        }
        val switches = compose.onAllNodes(isToggleable())
        switches[0].assertIsOff().performClick()
        switches[0].assertIsOn()
        switches[1].assertIsOn()
        compose.runOnIdle { assertTrue(LauncherConfig.isForceHighRefreshRateEnabled(context)) }
        switches[1].performClick()
        switches[0].assertIsOff()
        switches[1].assertIsOff()
        compose.runOnIdle { assertFalse(LauncherConfig.isForceHighRefreshRateEnabled(context)) }
        compose.onAllNodesWithText(context.getString(R.string.settings_ram_saver_experimental_chip))
            .assertCountEquals(2)
    }

    @Test fun newInstanceReadsSavedValueAndDisabledParentCannotBeBypassed() {
        val controlsEnabled = mutableStateOf(false)
        compose.runOnIdle { ForceHighRefreshRateController.setEnabled(context, true) }
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSettingsControlsEnabled provides controlsEnabled.value) {
                    ForceHighRefreshRateSetting()
                }
            }
        }
        val toggle = compose.onNode(isToggleable())
        toggle.assertIsOn().assertIsNotEnabled()
        toggle.performClick()
        compose.runOnIdle {
            assertTrue(LauncherConfig.isForceHighRefreshRateEnabled(context))
            controlsEnabled.value = true
        }
        toggle.performClick().assertIsOff()
        compose.runOnIdle { assertFalse(LauncherConfig.isForceHighRefreshRateEnabled(context)) }
    }
}
