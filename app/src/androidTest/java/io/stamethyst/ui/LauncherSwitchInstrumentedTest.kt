package io.stamethyst.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LauncherSwitchInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun decoratedSwitchRetainsSwitchSemanticsAndTogglesBothWays() {
        compose.setContent {
            MaterialTheme {
                var checked by remember { mutableStateOf(false) }
                LauncherSwitch(checked, { checked = it }, Modifier.testTag("power-switch"))
            }
        }
        val control = compose.onNodeWithTag("power-switch")
        control.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        control.assertIsOff().performClick().assertIsOn().performClick().assertIsOff()
    }

    @Test
    fun disabledDecoratedSwitchKeepsItsValueWithoutInvokingCallback() {
        var changes = 0
        compose.setContent {
            MaterialTheme {
                LauncherSwitch(true, { changes++ }, Modifier.testTag("disabled-switch"), enabled = false)
            }
        }
        compose.onNodeWithTag("disabled-switch").assertIsOn().assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, changes) }
    }
}
