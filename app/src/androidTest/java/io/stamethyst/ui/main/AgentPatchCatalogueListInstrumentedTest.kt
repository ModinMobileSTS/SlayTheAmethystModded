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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import io.stamethyst.backend.mods.RemoteAgentPatch
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

    private fun patch(name: String, version: String, hash: String) = RemoteAgentPatch(
        name = name, description = "Translation", authorName = "24", version = version,
        patchModId = "amethyst.ai.patch.chronomuncher.patch-1793399884157-536381f1",
        workshopId = "1609846039", targetVersions = listOf("2.0"), jarName = "patch.jar",
        downloadUrl = "https://raw.githubusercontent.com/example/patch.jar", size = 1, sha256 = hash,
    )
}
