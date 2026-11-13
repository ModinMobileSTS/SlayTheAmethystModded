package io.stamethyst.ui.main

import io.stamethyst.model.ModItemUi
import io.stamethyst.model.WorkshopModState
import io.stamethyst.model.WorkshopModUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModDetailsUiHelpersTest {
    private val mod = ModItemUi(
        modId = "test", manifestModId = "test", storagePath = "/test.jar", name = "Test",
        version = "1.0", description = "Description", dependencies = emptyList(), required = false,
        installed = true, enabled = true, explicitPriority = null, effectivePriority = null,
    )

    @Test fun localInstalledModsHaveEnableControl() {
        assertTrue(mod.showsEnableControl())
        assertFalse(mod.copy(installed = false).showsEnableControl())
    }

    @Test fun workshopEnableControlMatchesOnlyRunnableStates() {
        WorkshopModState.entries.forEach { state ->
            val item = mod.copy(workshop = WorkshopModUi(646570u, 1uL, state))
            assertEquals(state.name,
                state in setOf(WorkshopModState.ImportedPatched, WorkshopModState.UpdateAvailable, WorkshopModState.TexturePackInstalled),
                item.showsEnableControl())
        }
        assertTrue(mod.copy(installed = false,
            workshop = WorkshopModUi(646570u, 1uL, WorkshopModState.TexturePackInstalled)).showsEnableControl())
    }

    @Test fun unknownSizesStayUnknownAndSizesUseBinaryUnits() {
        assertNull(formatModFileSize(0))
        assertNull(formatModFileSize(-1))
        assertEquals("512 B", formatModFileSize(512))
        assertEquals("1.0 KB", formatModFileSize(1024))
        assertEquals("1.5 MB", formatModFileSize(1572864))
    }

    @Test fun successfulInstallSummariesAreHidden() {
        listOf(WorkshopModState.ImportedPatched, WorkshopModState.TexturePackInstalled).forEach { state ->
            assertNull(mod.copy(workshop = WorkshopModUi(646570u, 1uL, state,
                statusText = "已安装 Test")).detailsStatusText())
        }
        assertNull(mod.detailsStatusText())
    }

    @Test fun actionableWorkshopStatusIsPreserved() {
        listOf(WorkshopModState.DownloadFailed, WorkshopModState.FileMissing,
            WorkshopModState.NonStandardDownloaded, WorkshopModState.ImportedUnpatched).forEach { state ->
            assertEquals("Action required", mod.copy(workshop = WorkshopModUi(646570u, 1uL, state,
                statusText = " Action required ")).detailsStatusText())
        }
        assertNull(mod.copy(workshop = WorkshopModUi(646570u, 1uL, WorkshopModState.Downloading,
            statusText = "  ")).detailsStatusText())
    }

    @Test fun technicalDependenciesAreTrimmedUniqueAndInManifestOrder() {
        assertEquals(listOf("basemod", "stslib"), mod.copy(
            dependencies = listOf(" basemod ", "", "stslib", "basemod", "  "),
        ).detailsDependencies())
        assertTrue(mod.copy(dependencies = listOf(" ", "")).detailsDependencies().isEmpty())
        assertTrue(mod.detailsDependencies().isEmpty())
    }

    @Test fun technicalPathUsesLocalStorageBeforeWorkshopFallback() {
        val workshop = WorkshopModUi(646570u, 1uL, WorkshopModState.ImportedPatched, localJarPath = "/workshop/mod.jar")
        assertEquals("/test.jar", mod.copy(workshop = workshop).detailsStoragePath())
        assertEquals("/workshop/mod.jar", mod.copy(storagePath = " ", workshop = workshop).detailsStoragePath())
        assertNull(mod.copy(storagePath = "").detailsStoragePath())
        assertNull(mod.copy(storagePath = "", workshop = workshop.copy(localJarPath = " ")).detailsStoragePath())
    }
}
