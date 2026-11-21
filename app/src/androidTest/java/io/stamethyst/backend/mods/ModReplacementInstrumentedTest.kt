package io.stamethyst.backend.mods

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.backend.workshop.WorkshopAutoImportResult
import io.stamethyst.backend.workshop.WorkshopAutoImporter
import io.stamethyst.backend.workshop.WorkshopItemDetails
import io.stamethyst.backend.workshop.WorkshopItemSummary
import io.stamethyst.config.RuntimePaths
import io.stamethyst.model.ModItemUi
import io.stamethyst.ui.main.MainFolderStateStore
import io.stamethyst.ui.main.MainScreenViewModel
import io.stamethyst.ui.main.ModAssociationStore
import io.stamethyst.ui.main.ModDisplayOrder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModReplacementInstrumentedTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun workshopReplacementKeepsFolderDragOrderRemarkAndAssociation() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val files = temp.newFolder("isolated-files")
        val usedPreferences = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getExternalFilesDir(type: String?): File? = null
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("replacement-${temp.root.name}-$name".also(usedPreferences::add), mode)
        }
        try {
            val library = RuntimePaths.optionalModsLibraryDir(context).apply { mkdirs() }
            val oldJar = File(library, "mod.jar").apply { writeMod("replacement-test", "Original title", "1.0") }
            val partnerJar = File(library, "partner.jar").apply { writeMod("partner-test", "Partner", "1.0") }
            val order = ModDisplayOrder(context).apply { save(listOf(oldJar.absolutePath, partnerJar.absolutePath)) }
            val associationStore = ModAssociationStore()
            assertTrue(associationStore.associate(context, card(oldJar), card(partnerJar)))
            val originalBadge = associationStore.snapshot().badgeFor(card(oldJar))
            MainFolderStateStore().apply {
                ensureLoaded(context)
                folders.add(MainScreenViewModel.ModFolder("group", "My mods"))
                assignments[oldJar.absolutePath] = "group"
                assignments[partnerJar.absolutePath] = "group"
                persist(context, synchronous = true)
            }
            ModManifestNameRewriter.rewriteNameInPlace(oldJar, "我的备注")
            val update = temp.newFile("update.jar").apply { writeMod("replacement-test", "Published update", "2.0") }

            val details = WorkshopItemDetails(WorkshopItemSummary(1uL, 646570u, "Published update", "", ""))
            val result = WorkshopAutoImporter.importDownloadedJar(context, details, update)
            check(result is WorkshopAutoImportResult.Imported) { result.toString() }
            val installed = File(result.storagePath)
            assertNotEquals(oldJar.absolutePath, installed.absolutePath)
            assertFalse(oldJar.exists())
            val manifest = ModJarManifestParser.readModManifest(installed)
            assertEquals("我的备注", manifest.name)
            assertEquals("2.0", manifest.version)

            val updatedCard = card(installed)
            val partnerCard = card(partnerJar)
            val replacements = ModSelectionReplacementStore(context).load()
            order.rebindInstalledPaths(setOf(installed.absolutePath, partnerJar.absolutePath), replacements)
            assertEquals(listOf(installed.absolutePath, partnerJar.absolutePath), order.read())
            associationStore.reload(context)
            associationStore.sanitize(context, listOf(updatedCard, partnerCard), replacements)
            assertEquals(originalBadge, associationStore.snapshot().badgeFor(updatedCard))
            assertEquals(listOf(partnerCard), associationStore.snapshot().associatedModsFor(updatedCard, listOf(updatedCard, partnerCard)))
            val folders = MainFolderStateStore().apply { reload(context) }
            assertEquals("group", folders.assignments[installed.absolutePath])
        } finally {
            usedPreferences.forEach(base::deleteSharedPreferences)
        }
    }

    private fun File.writeMod(id: String, name: String, version: String) {
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("ModTheSpire.json"))
            zip.write("""{"modid":"$id","name":"$name","version":"$version"}""".toByteArray())
            zip.closeEntry()
        }
    }

    private fun card(jar: File): ModItemUi {
        val manifest = ModJarManifestParser.readModManifest(jar)
        return ModItemUi(
            manifest.normalizedModId, manifest.modId, jar.absolutePath, manifest.name, manifest.version,
            description = "", dependencies = emptyList(), required = false, installed = true,
            enabled = false, explicitPriority = null, effectivePriority = null,
        )
    }
}
