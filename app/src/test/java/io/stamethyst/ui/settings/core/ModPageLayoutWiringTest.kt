package io.stamethyst.ui.settings.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural guards supplement the UI tests; no device/runtime behavior is inferred here. */
class ModPageLayoutWiringTest {
    @Test fun configurationLoadsAndResetsLayoutWithoutRescanningMods() {
        val repository = source("ui/settings/core/SettingsRepository.kt")
        assertTrue(repository.contains("modPageLayout = LauncherPreferences.readModPageLayout(context)"))
        assertTrue(repository.contains("LauncherPreferences.saveModPageLayout(context, LauncherPreferences.DEFAULT_MOD_PAGE_LAYOUT)"))
        val viewModel = source("ui/settings/core/SettingsScreenViewModel.kt")
        assertTrue(viewModel.contains("modPageLayout = snapshot.modPageLayout"))
        val handler = viewModel.substringAfter("fun onModPageLayoutChanged(").substringBefore("\n    fun ")
        assertTrue(handler.contains("uiState.busy || uiState.modPageLayout == layout"))
        assertTrue(handler.contains("LauncherPreferences.saveModPageLayout(host, layout)"))
        assertTrue(handler.contains("refreshConfiguration(host)"))
        assertFalse(handler.contains("refreshStatus("))
        assertTrue(source("ui/LauncherContent.kt").contains("LocalModPageLayout provides settingsUiState.modPageLayout"))
    }

    @Test fun searchAndLauncherSettingsWireTheSameCallback() {
        listOf("ui/settings/core/SettingsScreen.kt", "ui/settings/core/SettingsSearchActions.kt").forEach {
            assertTrue(source(it).contains("onModPageLayoutChanged = { viewModel.onModPageLayoutChanged(activity, it) }"))
        }
        assertTrue(source("ui/settings/common/SettingsSearchIndex.kt").contains("R.string.settings_mod_page_layout_title"))
        assertTrue(source("ui/settings/sections/SettingsSearchResultItem.kt").contains("R.string.settings_mod_page_layout_title"))
    }

    @Test fun secondaryDialogsNeverSelectALegacyImplementation() {
        listOf("ModDetailsDialog.kt", "ModCardDialogs.kt", "ModSortDialog.kt",
            "ModImportPatchDialog.kt", "WorkshopModUpdateDialog.kt", "AgentPatchCatalogueDialog.kt").forEach {
            val dialog = source("ui/main/$it")
            assertFalse("$it must remain shared", dialog.contains("LocalModPageLayout"))
            assertFalse("$it must remain shared", dialog.contains("classicLayout"))
        }
        val card = source("ui/main/ModCardItem.kt")
        assertTrue(card.contains("compact = !classicLayout"))
        assertTrue(card.split("ModDetailsDialog(").size == 2)
    }

    @Test fun layoutSettingAndSearchDoNotShowTheRemovedHint() {
        val appearance = source("ui/settings/sections/SettingsAppearanceSection.kt")
        val layoutSetting = appearance.substringAfter("title = stringResource(R.string.settings_mod_page_layout_title),")
            .substringBefore("if (settingTitleResId == null || settingTitleResId == R.string.settings_theme_mode_title)")
        assertFalse(layoutSetting.contains("description ="))
        assertFalse(layoutSetting.contains("dialogDescription ="))
        assertTrue(layoutSetting.contains("valueText = modPageLayoutDisplayName(uiState.modPageLayout)"))
        val searchEntry = source("ui/settings/common/SettingsSearchIndex.kt")
            .substringAfter("titleResId = R.string.settings_mod_page_layout_title,")
            .substringBefore("),")
        assertFalse(searchEntry.contains("subtitleResId"))
    }

    @Test fun longPressToolbarAndItsButtonsAlwaysUseTheNewLayout() {
        val folder = source("ui/main/ModFolderSection.kt")
        val toolbar = folder.substringAfter("internal fun BatchEditToolbar(")
            .substringBefore("internal const val MOD_BATCH_EDIT_TOOLBAR_TAG")
        assertTrue(toolbar.contains("FrostedGlassChrome("))
        assertTrue(toolbar.contains("actionsMenu()"))
        assertFalse(toolbar.contains("LocalModPageLayout"))
        assertFalse(toolbar.contains("classicLayout"))
        assertFalse(folder.contains("ClassicBatchEditToolbar"))
    }

    private fun source(path: String): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main/java").isDirectory }
        return File(root, "app/src/main/java/io/stamethyst/$path").readText()
    }
}
