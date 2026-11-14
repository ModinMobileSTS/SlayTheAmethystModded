package io.stamethyst.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.stamethyst.R
import io.stamethyst.backend.mods.AgentPatchModManager
import io.stamethyst.model.AgentPatchModUi
import io.stamethyst.model.ModItemUi
import io.stamethyst.config.RuntimePaths
import io.stamethyst.ui.LauncherDialogHost
import io.stamethyst.ui.UiText
import io.stamethyst.ui.UiBusyOperation
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Even a failed patch write must not dispose the mod list through a full refresh. */
class AgentPatchToggleRefreshInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun sharedPatchUpdatesBothPathsAndSnapshotsWhileKeepingDetailsOpen() = withFixture { fixture ->
        val context = compose.activity
        val controller = fixture.controller
        val original = controller.snapshot() // Populate the derived optional-mod cache before toggling.
        val published = mutableStateOf(original)
        fixture.onPublish = { published.value = controller.snapshot() }
        val parents = original.optionalMods.filter { it.modId == fixture.parentId }
        assertEquals(2, parents.size)
        assertEquals(2, parents.map { it.storagePath }.toSet().size)
        assertEquals(setOf("Patch-Toggle-Regression", fixture.parentId), parents.map { it.manifestModId }.toSet())

        compose.setContent {
            MaterialTheme {
                LauncherDialogHost {
                    Column {
                        published.value.optionalMods.filter { it.modId == fixture.parentId }.forEach { parent ->
                            ModCard(
                                mod = parent, isDraggedInOverlay = false, showModFileName = false,
                                selectionEnabled = true, fileActionsEnabled = true,
                                dragEnabled = false, showDragHandle = false,
                                callbacks = ModCardCallbacks(onSetAgentPatchEnabled = { mod, patch, enabled ->
                                    controller.onSetAgentPatchEnabled(context, mod, patch, enabled)
                                }),
                            )
                        }
                    }
                }
            }
        }

        // A full filesystem refresh would remove these cards and dispose their detail dialogs.
        fixture.parentJars.forEach { assertTrue(it.delete()) }
        repeat(3) {
            for ((parent, enabled) in listOf(parents[0] to true, parents[1] to false)) {
                compose.onNodeWithText(parent.name).performClick()
                compose.onNodeWithTag("mod_manager_tab_2", useUnmergedTree = true).performClick()
                val toggle = compose.onNodeWithContentDescription(
                    context.getString(R.string.mod_details_toggle, "Shared toggle fixture"), useUnmergedTree = true,
                ).performScrollTo()
                if (enabled) toggle.assertIsOff() else toggle.assertIsOn()
                toggle.performClick()
                if (enabled) toggle.assertIsOn() else toggle.assertIsOff()
                compose.onNodeWithTag("mod_details_dialog", useUnmergedTree = true).assertIsDisplayed()
                compose.runOnIdle { fixture.assertState(original, sharedEnabled = enabled, requiredEnabled = false) }
                compose.onNodeWithText(context.getString(R.string.mod_details_done), useUnmergedTree = true).performClick()
            }
        }

        // The same local update must also reach requiredModsSnapshot, with the optional parents isolated.
        val requiredParent = original.requiredMods.first { it.modId == "basemod" }
        val requiredPatch = requiredParent.agentPatchMods.first { it.patchModId == fixture.requiredPatchModId }
        for (enabled in listOf(true, false)) {
            compose.runOnIdle {
                controller.onSetAgentPatchEnabled(context, requiredParent, requiredPatch, enabled)
                fixture.assertState(original, sharedEnabled = false, requiredEnabled = enabled)
            }
        }
        assertEquals(8, fixture.publications)
        assertTrue(fixture.effects.isEmpty())
    }

    @Test fun failedPersistenceLeavesBothSnapshotsUnchanged() = withFixture { fixture ->
        val original = fixture.controller.snapshot()
        val parent = original.optionalMods.first { it.modId == fixture.parentId }
        val patch = parent.agentPatchMods.first { it.patchModId == fixture.sharedPatchModId }
        // Force a real write error after descriptor lookup, rather than merely using a missing patch.
        assertTrue(fixture.enabledFile.delete())
        assertTrue(fixture.enabledFile.mkdir())
        compose.runOnIdle {
            fixture.controller.onSetAgentPatchEnabled(compose.activity, parent, patch, true)
            assertEquals(original, fixture.controller.snapshot())
        }
        assertEquals(1, fixture.publications)
        assertEquals(1, fixture.effects.size)
        assertTrue(fixture.effects.single() is MainScreenViewModel.Effect.ShowSnackbar)
        assertTrue(fixture.enabledFile.isDirectory)
    }

    @Test fun missingPatchDoesNotStartFullRefresh() {
        val viewModel = MainScreenViewModel()
        val mod = ModItemUi(
            modId = "missing-toggle-regression", manifestModId = "missing-toggle-regression",
            storagePath = "/missing-toggle-regression.jar", name = "Regression", version = "1",
            description = "", dependencies = emptyList(), required = false,
            installed = true, enabled = true, explicitPriority = null, effectivePriority = null,
        )
        val patch = AgentPatchModUi("missing-patch", "missing-patch", "Missing", "1", "", false)
        compose.runOnIdle {
            viewModel.replaceUiStateForBenchmark(MainScreenViewModel.UiState(initializing = false, controlsEnabled = true))
            viewModel.onSetAgentPatchEnabled(compose.activity, mod, patch, true)
            assertFalse("Patch toggles must not switch the list to loading content", viewModel.uiState.initializing)
        }
    }

    private fun withFixture(test: (ToggleFixture) -> Unit) {
        val fixture = ToggleFixture()
        check(!fixture.enabledFile.exists() || fixture.enabledFile.isFile)
        val previousEnabled = fixture.enabledFile.takeIf { it.isFile }?.readBytes()
        try {
            fixture.prepare()
            test(fixture)
        } finally {
            fixture.controller.shutdown()
            fixture.createdFiles.forEach { it.delete() }
            fixture.enabledFile.delete() // Also removes the empty directory used for the write-failure test.
            if (previousEnabled != null) fixture.enabledFile.writeBytes(previousEnabled)
        }
    }

    private inner class ToggleFixture {
        val parentId = "patch-toggle-regression"
        val sharedPatchModId = "$parentId-fixture"
        val requiredPatchModId = "basemod-toggle-regression-fixture"
        private val otherParentId = "$parentId-other"
        private val patchId = "toggle-fixture"
        private val context get() = compose.activity
        val enabledFile = RuntimePaths.enabledAgentPatchModsConfig(context)
        val createdFiles = mutableListOf<File>()
        val parentJars = mutableListOf<File>()
        var publications = 0
        var onPublish: () -> Unit = {}
        val effects = mutableListOf<MainScreenViewModel.Effect>()
        val controller = MainModManagementController(object : MainModManagementController.Host {
            override fun canEditMainScreenState() = true
            override fun isBusy() = false
            override fun setBusy(busy: Boolean, message: UiText?, operation: UiBusyOperation, progressPercent: Int?) = Unit
            override fun republish(host: android.app.Activity) { publications++; onPublish() }
            override fun emitEffect(effect: MainScreenViewModel.Effect) { effects.add(effect) }
        })

        fun prepare() {
            for ((suffix, manifestId) in listOf("A" to "Patch-Toggle-Regression", "B" to parentId, "Other" to otherParentId)) {
                val file = File(RuntimePaths.optionalModsLibraryDir(context), "$parentId-$suffix.jar")
                jar(file, "ModTheSpire.json", """{"modid":"$manifestId","name":"Toggle $suffix","version":"1.0"}""")
                parentJars.add(file)
            }
            patch(parentId, patchId, sharedPatchModId, "Shared toggle fixture", enabled = false)
            patch(parentId, "toggle-sibling", "$parentId-sibling", "Other revision", enabled = true)
            patch(otherParentId, patchId, "$otherParentId-fixture", "Other parent patch", enabled = true)
            patch("basemod", patchId, requiredPatchModId, "Required toggle fixture", enabled = false)
            controller.refresh(context, storageAccessible = true)
        }

        fun assertState(original: MainModManagementController.Snapshot, sharedEnabled: Boolean, requiredEnabled: Boolean) {
            val updated = controller.snapshot()
            for ((before, after) in listOf(original.requiredMods to updated.requiredMods, original.optionalMods to updated.optionalMods)) {
                assertEquals(before.map { it.storagePath }, after.map { it.storagePath })
                before.zip(after).forEach { (oldMod, newMod) ->
                    assertEquals(oldMod, newMod.copy(agentPatchMods = oldMod.agentPatchMods))
                    assertEquals(oldMod.agentPatchMods.size, newMod.agentPatchMods.size)
                    oldMod.agentPatchMods.zip(newMod.agentPatchMods).forEach { (oldPatch, newPatch) ->
                        val expectedEnabled = when (oldPatch.patchModId) {
                            sharedPatchModId -> sharedEnabled
                            requiredPatchModId -> requiredEnabled
                            else -> oldPatch.enabled
                        }
                        assertEquals(oldPatch.copy(enabled = expectedEnabled), newPatch)
                    }
                }
            }
            assertEquals(sharedEnabled, enabledFile.readLines().contains(sharedPatchModId))
            assertEquals(requiredEnabled, enabledFile.readLines().contains(requiredPatchModId))
        }

        private fun patch(parentId: String, patchId: String, patchModId: String, name: String, enabled: Boolean) {
            jar(File(RuntimePaths.agentModsForModRoot(context, parentId), "$patchId.jar"), "agent-patch.json",
                """{"parent_mod_id":"$parentId","patch_id":"$patchId","patch_mod_id":"$patchModId","name":"$name","version":"1.0"}""")
            AgentPatchModManager.setEnabled(context, parentId, patchId, enabled)
        }

        private fun jar(file: File, entry: String, json: String) {
            check(!file.exists()) { "Refusing to overwrite fixture path: $file" }
            file.parentFile!!.mkdirs()
            createdFiles.add(file)
            ZipOutputStream(file.outputStream()).use {
                it.putNextEntry(ZipEntry(entry))
                it.write(json.toByteArray())
                it.closeEntry()
            }
        }
    }
}
