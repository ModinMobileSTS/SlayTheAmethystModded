package io.stamethyst.ui

import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextInputService
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The caller's window is unfocused while a modal owns cursor/selection/IME interactions. */
class LauncherDialogInputInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun hostedDialogUsesItsOwnWindowInputServicesAndKeepsCallerLocals() {
        checkWindowInputServices(hosted = true)
    }

    @Test fun standaloneDialogUsesItsOwnWindowInputServicesAndKeepsCallerLocals() {
        checkWindowInputServices(hosted = false)
    }

    @Test fun nestedDialogDoesNotReuseItsParentsInputServices() {
        val showChild = mutableStateOf(true)
        var parent: InputEnvironment? = null
        var child: InputEnvironment? = null
        compose.setContent {
            MaterialTheme {
                LauncherDialogHost {
                    LauncherDialog(onDismissRequest = {}) {
                        val environment = inputEnvironment()
                        SideEffect { parent = environment }
                        Text("Parent")
                        if (showChild.value) LauncherAlertDialog(
                            onDismissRequest = { showChild.value = false },
                            text = {
                                val childEnvironment = inputEnvironment()
                                SideEffect { child = childEnvironment }
                                Text("Child")
                            },
                            confirmButton = {
                                TextButton(onClick = { showChild.value = false }) { Text("Close child") }
                            },
                        )
                    }
                }
            }
        }
        compose.waitUntil { child?.windowInfo?.isWindowFocused == true }
        compose.runOnIdle { assertSeparateWindows(requireNotNull(parent), requireNotNull(child)) }
        compose.onNodeWithText("Close child").performClick()
        compose.waitUntil { parent?.windowInfo?.isWindowFocused == true }
        compose.onNodeWithText("Parent").assertExists()
    }

    private fun checkWindowInputServices(hosted: Boolean) {
        var caller: InputEnvironment? = null
        var dialog: InputEnvironment? = null
        var inheritedLabel: String? = null
        val unfocusedWindow = object : WindowInfo {
            override val isWindowFocused = false
        }
        compose.setContent {
            MaterialTheme {
                val content: @Composable () -> Unit = {
                    CompositionLocalProvider(
                        LocalWindowInfo provides unfocusedWindow,
                        LocalCallerLabel provides "Patch rename",
                    ) {
                        val callerEnvironment = inputEnvironment()
                        SideEffect { caller = callerEnvironment }
                        LauncherAlertDialog(
                            onDismissRequest = {},
                            text = {
                                val dialogEnvironment = inputEnvironment()
                                val label = LocalCallerLabel.current
                                SideEffect {
                                    dialog = dialogEnvironment
                                    inheritedLabel = label
                                }
                                Text(label)
                            },
                            confirmButton = {},
                        )
                    }
                }
                if (hosted) LauncherDialogHost(content) else content()
            }
        }
        compose.onNodeWithText("Patch rename").assertExists()
        compose.runOnIdle {
            assertSeparateWindows(requireNotNull(caller), requireNotNull(dialog))
            assertEquals("Patch rename", inheritedLabel)
        }
        compose.waitUntil { dialog?.windowInfo?.isWindowFocused == true }
        compose.runOnIdle { assertTrue(requireNotNull(dialog).view.hasWindowFocus()) }
    }

    private fun assertSeparateWindows(parent: InputEnvironment, child: InputEnvironment) {
        assertNotSame("Dialog View", parent.view, child.view)
        assertNotSame("Cursor/selection window focus", parent.windowInfo, child.windowInfo)
        assertNotSame("Focus manager", parent.focusManager, child.focusManager)
        assertNotSame("Input mode manager", parent.inputModeManager, child.inputModeManager)
        assertNotSame("IME input service", parent.textInputService, child.textInputService)
        assertNotSame("Keyboard controller", parent.keyboardController, child.keyboardController)
        assertNotSame("Selection toolbar", parent.textToolbar, child.textToolbar)
    }

    private data class InputEnvironment(
        val view: View,
        val windowInfo: WindowInfo,
        val focusManager: Any,
        val inputModeManager: Any,
        val textInputService: Any?,
        val keyboardController: Any?,
        val textToolbar: Any,
    )

    @Suppress("DEPRECATION")
    @Composable
    private fun inputEnvironment() = InputEnvironment(
        view = LocalView.current,
        windowInfo = LocalWindowInfo.current,
        focusManager = LocalFocusManager.current,
        inputModeManager = LocalInputModeManager.current,
        textInputService = LocalTextInputService.current,
        keyboardController = LocalSoftwareKeyboardController.current,
        textToolbar = LocalTextToolbar.current,
    )
}

private val LocalCallerLabel = staticCompositionLocalOf { "Missing caller label" }
