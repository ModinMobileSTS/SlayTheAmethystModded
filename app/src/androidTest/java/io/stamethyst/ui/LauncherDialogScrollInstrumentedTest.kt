package io.stamethyst.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.stamethyst.config.BootOverlayStyle
import io.stamethyst.ui.settings.common.SettingsChoiceDialogItem
import io.stamethyst.ui.settings.common.SettingsChoiceSpec
import io.stamethyst.ui.settings.common.SettingsDropdownField
import io.stamethyst.ui.settings.sections.BootOverlayStylePreviewGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Constrain the dialog itself: constraining the activity's content would not constrain its window. */
class LauncherDialogScrollInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ordinaryContentScrollsToEndWhileActionsStayVisible() {
        var confirmed = false
        compose.setContent {
            MaterialTheme {
                LauncherDialogHost {
                    LauncherAlertDialog(
                        modifier = Modifier.heightIn(max = 240.dp),
                        onDismissRequest = {},
                        title = { Text("Landscape dialog") },
                        text = { Column { repeat(60) { Text("Row $it", Modifier.height(40.dp)) } } },
                        confirmButton = { TextButton(onClick = { confirmed = true }) { Text("Confirm") } },
                        dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
                    )
                }
            }
        }
        compose.onNodeWithText("Confirm").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("Row 59").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Confirm").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(confirmed) }
    }

    @Test fun longHeadingAtLargeFontDoesNotConsumeTheEntireBodyViewport() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.6f)) {
                MaterialTheme {
                    LauncherAlertDialog(
                        modifier = Modifier.heightIn(max = 240.dp),
                        onDismissRequest = {},
                        title = { Text(List(16) { "Long heading $it" }.joinToString("\n")) },
                        text = { Text("End of content") },
                        confirmButton = { TextButton(onClick = {}) { Text("Close") } },
                    )
                }
            }
        }
        compose.onNodeWithText("Close").assertIsDisplayed()
        compose.onNodeWithText("End of content").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed()
    }

    @Test fun callerOwnedLazyListKeepsFiniteConstraintsAndPinnedActions() {
        compose.setContent {
            MaterialTheme {
                LauncherAlertDialog(
                    modifier = Modifier.heightIn(max = 240.dp),
                    scrollableText = false,
                    onDismissRequest = {},
                    title = { Text("Lazy list") },
                    text = {
                        LazyColumn(Modifier.heightIn(max = 520.dp).testTag("lazy-list")) {
                            items(80) { Text("Lazy row $it", Modifier.padding(vertical = 12.dp)) }
                        }
                    },
                    confirmButton = { TextButton(onClick = {}) { Text("Save") } },
                )
            }
        }
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithTag("lazy-list").performScrollToIndex(79)
        compose.onNodeWithText("Lazy row 79").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test fun callerOwnedColumnCanStillScrollWithoutAnOuterScroller() {
        compose.setContent {
            MaterialTheme {
                LauncherAlertDialog(
                    modifier = Modifier.heightIn(max = 240.dp),
                    scrollableText = false,
                    onDismissRequest = {},
                    title = { Text("Existing scroller") },
                    text = {
                        Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                            repeat(60) { Text("Existing row $it", Modifier.height(40.dp)) }
                        }
                    },
                    confirmButton = { TextButton(onClick = {}) { Text("Done") } },
                )
            }
        }
        compose.onNodeWithText("Existing row 59").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test fun inputAndSupportingTextRemainReachableInKeyboardSizedViewport() {
        val input = mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                LauncherAlertDialog(
                    modifier = Modifier.heightIn(max = 200.dp),
                    onDismissRequest = {},
                    title = { Text("Input") },
                    text = {
                        Column {
                            Text(List(12) { "Instructions $it" }.joinToString("\n"))
                            OutlinedTextField(
                                value = input.value,
                                onValueChange = { input.value = it },
                                singleLine = true,
                                modifier = Modifier.testTag("input"),
                            )
                            Text("Last instruction")
                        }
                    },
                    confirmButton = { TextButton(onClick = {}) { Text("Submit") } },
                )
            }
        }
        compose.onNodeWithTag("input").performScrollTo().performClick().performTextInput("draft")
        compose.runOnIdle { assertEquals("draft", input.value) }
        compose.onNodeWithText("Last instruction").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Submit").assertIsDisplayed()
    }

    @Test fun settingsChoiceCanSelectTheLastOptionWithDescriptions() {
        var selected = -1
        compose.setContent {
            MaterialTheme {
                SettingsChoiceDialogItem(SettingsChoiceSpec(
                    title = "Choice settings",
                    valueText = "Current choice",
                    enabled = true,
                    selectedValue = 0,
                    options = (0..39).toList(),
                    optionLabel = { "Choice $it" },
                    optionDescription = { "Detailed explanation for choice $it" },
                    onOptionSelected = { selected = it },
                ))
            }
        }
        compose.onNodeWithText("Choice settings").performClick()
        compose.onNodeWithText("Choice 39").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(39, selected) }
    }

    @Test fun settingsDropdownCanSelectTheLastOptionWithDescriptions() {
        var selected = -1
        compose.setContent {
            MaterialTheme {
                SettingsDropdownField(
                    label = "Dropdown settings",
                    valueText = "Current option",
                    enabled = true,
                    options = (0..39).toList(),
                    optionLabel = { "Option $it" },
                    optionDescription = { "Detailed explanation for option $it" },
                    onOptionSelected = { selected = it },
                )
            }
        }
        compose.onNodeWithText("Dropdown settings").performClick()
        compose.onNodeWithText("Option 39").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(39, selected) }
    }

    @Test fun bootStylePreviewGridCanScrollToItsLastRowInLandscapeHeight() {
        compose.setContent {
            MaterialTheme {
                LauncherAlertDialog(
                    modifier = Modifier.heightIn(max = 240.dp),
                    onDismissRequest = {},
                    title = { Text("Boot styles") },
                    text = {
                        Column {
                            BootOverlayStylePreviewGrid(BootOverlayStyle.MODERN, true, onSelect = {})
                            Text("End of boot styles")
                        }
                    },
                    confirmButton = { TextButton(onClick = {}) { Text("Close styles") } },
                )
            }
        }
        compose.onNodeWithText("End of boot styles").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close styles").assertIsDisplayed()
    }
}
