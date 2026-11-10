package io.stamethyst.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.stamethyst.model.AgentPatchModUi
import io.stamethyst.model.ModItemUi
import io.stamethyst.config.RuntimePaths
import io.stamethyst.ui.UiText
import io.stamethyst.ui.UiBusyOperation
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Even a failed patch write must not dispose the mod list through a full refresh. */
class AgentPatchToggleRefreshInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun successfulToggleUpdatesCachedSnapshotWithoutReloadingMods() {
        val context = compose.activity
        val parentId = "patch-toggle-regression"
        val patchId = "toggle-fixture"
        val patchModId = "patch-toggle-regression-fixture"
        val parentJar = File(RuntimePaths.optionalModsLibraryDir(context), "$parentId.jar")
        val patchJar = File(RuntimePaths.agentModsForModRoot(context, parentId), "$patchId.jar")
        val enabledFile = RuntimePaths.enabledAgentPatchModsConfig(context)
        val previousEnabled = enabledFile.takeIf { it.isFile }?.readBytes()
        fun jar(file: File, entry: String, json: String) {
            check(!file.exists()) { "Refusing to overwrite fixture path: $file" }
            file.parentFile!!.mkdirs()
            ZipOutputStream(file.outputStream()).use {
                it.putNextEntry(ZipEntry(entry))
                it.write(json.toByteArray())
                it.closeEntry()
            }
        }
        var publications = 0
        val controller = MainModManagementController(object : MainModManagementController.Host {
            override fun canEditMainScreenState() = true
            override fun isBusy() = false
            override fun setBusy(busy: Boolean, message: UiText?, operation: UiBusyOperation, progressPercent: Int?) = Unit
            override fun republish(host: android.app.Activity) { publications++ }
            override fun emitEffect(effect: MainScreenViewModel.Effect) = Unit
        })
        try {
            jar(parentJar, "ModTheSpire.json", """{"modid":"$parentId","name":"Toggle regression","version":"1.0"}""")
            jar(patchJar, "agent-patch.json", """{"parent_mod_id":"$parentId","patch_id":"$patchId","patch_mod_id":"$patchModId","name":"Toggle fixture","version":"1.0"}""")
            controller.refresh(context, storageAccessible = true)
            val parent = controller.snapshot().optionalMods.first { it.modId == parentId }
            val patch = parent.agentPatchMods.single()
            // Populate the derived cache before writing, as the real launcher does.
            repeat(3) {
                for (enabled in listOf(true, false)) {
                    controller.onSetAgentPatchEnabled(context, parent, patch, enabled)
                    val updated = controller.snapshot().optionalMods.first { it.modId == parentId }
                    assertEquals(enabled, updated.agentPatchMods.single().enabled)
                    assertEquals(enabled, enabledFile.readLines().contains(patchModId))
                    assertEquals(parent.storagePath, updated.storagePath)
                    assertEquals(parent.enabled, updated.enabled)
                }
            }
            assertEquals(6, publications)
        } finally {
            controller.shutdown()
            parentJar.delete()
            patchJar.delete()
            if (previousEnabled == null) enabledFile.delete() else enabledFile.writeBytes(previousEnabled)
        }
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
}
