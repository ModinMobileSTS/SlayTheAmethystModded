package io.stamethyst.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import io.stamethyst.backend.mods.RemoteAgentPatch
import io.stamethyst.model.AgentPatchModUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI-only regression: no catalogue requests, patch installs or app data changes. */
@RunWith(AndroidJUnit4::class)
class AgentPatchCatalogueListInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sharedModIdVersionsRenderAndKeepTheirOwnActionsAfterReordering() {
        val first = patch("信徒中文补丁", "3.7.0", "a".repeat(64))
        val second = patch("时序·信徒中文补丁", "3.7.1", "b".repeat(64))
        var patches by mutableStateOf(listOf(first, second))
        var details: RemoteAgentPatch? = null
        var installed: RemoteAgentPatch? = null
        val installLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.main_mod_agent_patch_install)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).height(420.dp)) {
                    RemoteAgentPatchList(
                        patches = patches.map { it to null },
                        parentVersion = "2.0",
                        workingPatchModId = null,
                        onDetails = { details = it },
                        onInstall = { installed = it },
                        onSetEnabled = { _, _ -> error("Uninstalled patches cannot be toggled") },
                    )
                }
            }
        }

        compose.onNodeWithText(first.name).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(first, details) }
        compose.onNodeWithText(second.name).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(second, details); details = null }
        compose.onAllNodesWithText(installLabel)[1].performClick()
        compose.runOnIdle {
            assertEquals(second, installed)
            assertNull(details) // Installing must not also open the row's details.
            patches = patches.reversed()
        }
        compose.onAllNodesWithText(installLabel)[1].performClick()
        compose.runOnIdle { assertEquals(first, installed); assertNull(details) }
        compose.onNodeWithText(first.name).performClick()
        compose.runOnIdle { assertEquals(first, details) }
    }

    @Test fun installedPatchSwitchTogglesWithoutOpeningDetailsOrInstalling() {
        val remote = patch("Installed patch", "1.0", "c".repeat(64))
        var local by mutableStateOf(localPatch(remote, enabled = true))
        val toggles = mutableListOf<Pair<AgentPatchModUi, Boolean>>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).height(420.dp)) {
                    RemoteAgentPatchList(
                        patches = listOf(remote to local),
                        parentVersion = "2.0",
                        workingPatchModId = null,
                        onDetails = { error("Toggle must not open details") },
                        onInstall = { error("Toggle must not install") },
                        onSetEnabled = { patch, enabled ->
                            toggles.add(patch to enabled)
                            local = patch.copy(enabled = enabled)
                        },
                    )
                }
            }
        }

        compose.onNodeWithText(context.getString(R.string.main_mod_agent_patch_installed)).assertDoesNotExist()
        val toggle = compose.onNodeWithContentDescription(context.getString(R.string.mod_details_toggle, remote.name))
        toggle.assertIsOn().performClick().assertIsOff()
        toggle.performClick().assertIsOn()
        compose.runOnIdle {
            assertEquals(listOf(local.copy(enabled = true) to false, local.copy(enabled = false) to true), toggles)
        }
    }

    @Test fun installedPatchWithNewerVersionKeepsUpdateAction() {
        val remote = patch("Update patch", "2.0", "d".repeat(64))
        val local = localPatch(remote, enabled = false).copy(version = "1.0")
        var update: RemoteAgentPatch? = null
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).height(420.dp)) {
                    RemoteAgentPatchList(
                        patches = listOf(remote to local),
                        parentVersion = "2.0",
                        workingPatchModId = null,
                        onDetails = { error("Update must not open details") },
                        onInstall = { update = it },
                        onSetEnabled = { _, _ -> error("Update must not toggle") },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.mod_details_toggle, remote.name)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.main_mod_agent_patch_update)).performClick()
        compose.runOnIdle { assertEquals(remote, update) }
    }

    @Test fun installedPatchSwitchRespectsBusyAndDisabledControls() {
        val remote = patch("Busy patch", "1.0", "e".repeat(64))
        val local = localPatch(remote, enabled = false)
        var workingId by mutableStateOf<String?>(remote.patchModId)
        var controlsEnabled by mutableStateOf(true)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).height(420.dp)) {
                    RemoteAgentPatchList(
                        patches = listOf(remote to local),
                        parentVersion = "2.0",
                        workingPatchModId = workingId,
                        controlsEnabled = controlsEnabled,
                        onDetails = {},
                        onInstall = { error("Disabled patch must not install") },
                        onSetEnabled = { _, _ -> error("Disabled switch must not toggle") },
                    )
                }
            }
        }
        val toggle = compose.onNodeWithContentDescription(context.getString(R.string.mod_details_toggle, remote.name))
        toggle.assertIsOff().assertIsNotEnabled().performClick()
        compose.runOnIdle { workingId = null; controlsEnabled = false }
        toggle.assertIsOff().assertIsNotEnabled().performClick()
    }

    private fun localPatch(remote: RemoteAgentPatch, enabled: Boolean) = AgentPatchModUi(
        patchId = "local-patch",
        patchModId = remote.patchModId,
        name = remote.name,
        version = remote.version,
        description = remote.description,
        enabled = enabled,
    )

    private fun patch(name: String, version: String, hash: String) = RemoteAgentPatch(
        name = name, description = "Translation", authorName = "24", version = version,
        patchModId = "amethyst.ai.patch.chronomuncher.patch-1793399884157-536381f1",
        workshopId = "1609846039", targetVersions = listOf("2.0"), jarName = "patch.jar",
        downloadUrl = "https://raw.githubusercontent.com/example/patch.jar", size = 1, sha256 = hash,
    )
}
