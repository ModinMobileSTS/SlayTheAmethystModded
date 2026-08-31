package io.stamethyst.backend.workshop

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Resources
import io.stamethyst.backend.mods.ImportedModPatchRegistry
import io.stamethyst.backend.mods.importing.ModImportPlanner
import io.stamethyst.backend.mods.importing.ModImportPlanningOptions
import io.stamethyst.backend.mods.importing.patches.ImportPatchRegistry
import io.stamethyst.backend.mods.importing.patches.mods.downfall.DownfallImportPatchModule
import io.stamethyst.backend.mods.importing.patches.texture.AtlasFilterPatchModule
import io.stamethyst.backend.mods.importing.patches.texture.AtlasOfflineDownscalePatchModule
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class WorkshopAutoImporterTest {
    @Test
    fun importDownloadedJarReturnsFailedWhenPlanningFails() {
        val roots = TestRoots.create("workshop-auto-import")
        val details = WorkshopItemDetails(
            summary = WorkshopItemSummary(
                publishedFileId = 123456u,
                appId = 646570u,
                title = "Broken Jar",
                previewUrl = "",
                description = "",
            )
        )
        val missingJar = File(roots.rootDir, "missing.jar")

        val result = WorkshopAutoImporter.importDownloadedJar(roots.context, details, missingJar)

        assertTrue(result is WorkshopAutoImportResult.Failed)
    }

    @Test
    fun importPlanningUsesExternalWorkshopWorkspaceForLargeJarInspection() {
        val roots = TestRoots.create("workshop-external-import-cache")
        val sourceJar = File(roots.rootDir, "Mod.jar")
        writeSimpleModJar(sourceJar, modId = "externalcachemod", name = "External Cache Mod")

        val plan = ModImportPlanner.planLocalFiles(
            context = roots.context,
            files = listOf(sourceJar),
        )

        try {
            assertTrue(plan.session.sessionDir.absolutePath.startsWith(RuntimePaths.workshopImportSessionsRoot(roots.context).absolutePath))
        } finally {
            ModImportPlanner.cleanup(plan.session)
        }
    }

    @Test
    fun downloadedDownfallJarAutoImportsWithoutInteractiveDownscalePlanning() {
        val roots = TestRoots.create("workshop-downfall-auto-import")
        val sourceJar = File(roots.rootDir, "Downfall.jar")
        writeDownfallLikeJar(sourceJar)
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body(Buffer().write(sourceJar.readBytes()))
                    .build()
            )
            val details = downfallDetails(fileUrl = server.url("/Downfall.jar").toString())
            val outputDir = RuntimePaths.workshopItemDir(
                roots.context,
                details.summary.appId,
                details.summary.publishedFileId,
            )
            val events = mutableListOf<WorkshopDownloadEvent>()

            runBlocking {
                WorkshopService(roots.context, OkHttpClient())
                    .download(WorkshopDownloadRequest(details, outputDir))
                    .collect { events += it }
            }

            val completed = events.filterIsInstance<WorkshopDownloadEvent.Completed>().single()
            val downloadedJar = File(outputDir, completed.files.single().relativePath)
            assertTrue(downloadedJar.isFile)
            // The patch manager defaults Atlas downscaling to disabled. This test exercises the
            // deferred inspection path, so opt into that module explicitly.
            ImportPatchRegistry.setEnabled(
                roots.context,
                AtlasOfflineDownscalePatchModule.id,
                true
            )
            val plan = ModImportPlanner.planLocalFiles(
                context = roots.context,
                files = listOf(downloadedJar),
                options = ModImportPlanningOptions(
                    includeUserConfigurablePatches = true,
                    deferUserConfigurablePatchInspection = true,
                )
            )
            try {
                val moduleIds = plan.importableItems.single().patchPlans.map { it.moduleId }.toSet()
                assertTrue(moduleIds.contains(DownfallImportPatchModule.id))
                assertTrue(moduleIds.contains(AtlasFilterPatchModule.id))
                assertTrue(moduleIds.contains(AtlasOfflineDownscalePatchModule.id))
            } finally {
                ModImportPlanner.cleanup(plan.session)
            }

            val autoImportProgress = mutableListOf<WorkshopAutoImportProgress>()
            val result = WorkshopAutoImporter.importDownloadedJar(roots.context, details, downloadedJar) { progress ->
                autoImportProgress += progress
            }

            assertTrue("Expected imported result, got $result", result is WorkshopAutoImportResult.Imported)
            val imported = result as WorkshopAutoImportResult.Imported
            assertEquals("Downfall Expansion Mod", imported.modName)
            assertTrue(autoImportProgress.isNotEmpty())
            assertEquals(0, autoImportProgress.first().percent)
            assertTrue(autoImportProgress.any { it.percent in 1..29 })
            assertEquals(100, autoImportProgress.last().percent)
            assertTrue(autoImportProgress.any { it.totalSteps > 0 && it.currentStep < it.totalSteps })
            assertTrue(File(imported.storagePath).isFile)
            val patchInfo = ImportedModPatchRegistry.readAll(roots.context)[imported.storagePath]
            assertNotNull(patchInfo)
            assertEquals(1, patchInfo?.patchedAtlasEntries)
            assertEquals(1, patchInfo?.patchedFilterLines)
            assertEquals(1, patchInfo?.patchedDownfallClassEntries)
            assertEquals(1, patchInfo?.patchedDownfallMerchantClassEntries)
            assertEquals(0, patchInfo?.downscaledAtlasPageEntries)
            val importedAtlasText = readZipEntryText(File(imported.storagePath), "downfall/spine/boss.atlas")
            assertTrue(importedAtlasText.contains("filter: Linear,Linear"))
            assertFalse(importedAtlasText.contains("MipMapLinearLinear"))
            val autoImportPatchLogs = WorkshopAutoImportPatchLogStore.listLogFiles(roots.context)
            assertEquals(1, autoImportPatchLogs.size)
            val logText = autoImportPatchLogs.single().readText(Charsets.UTF_8)
            assertTrue(logText.contains("自动导入修补开始"))
            assertTrue(logText.contains("修补开始"))
            assertTrue(logText.contains(DownfallImportPatchModule.id))
            assertTrue(logText.contains("修补完成"))
            assertTrue(logText.contains("patchedClassEntries=1"))
            assertTrue(logText.contains("自动导入成功"))
        } finally {
            server.close()
        }
    }

    @Test
    fun importDownloadedJarsImportsEveryJarInBatch() {
        val roots = TestRoots.create("workshop-multi-jar-auto-import")
        val firstJar = File(roots.rootDir, "First.jar")
        val secondJar = File(roots.rootDir, "Second.jar")
        writeSimpleModJar(firstJar, modId = "firstmod", name = "First Mod")
        writeSimpleModJar(secondJar, modId = "secondmod", name = "Second Mod")
        val details = WorkshopItemDetails(
            summary = WorkshopItemSummary(
                publishedFileId = 223344uL,
                appId = 646570u,
                title = "Multi Jar Workshop",
                previewUrl = "",
                description = "",
            )
        )

        val result = WorkshopAutoImporter.importDownloadedJars(
            context = roots.context,
            details = details,
            jarFiles = listOf(firstJar, secondJar),
        )

        assertTrue("Expected imported result, got $result", result is WorkshopAutoImportResult.Imported)
        val imported = result as WorkshopAutoImportResult.Imported
        assertEquals(listOf("First Mod", "Second Mod"), imported.modNames)
        assertEquals(2, imported.storagePaths.size)
        imported.storagePaths.forEach { path -> assertTrue(File(path).isFile) }
    }

    @Test
    fun autoImportPatchLogStorePrunesOldestSlotAfterTenLogs() {
        val roots = TestRoots.create("workshop-auto-import-log-slots")
        val created = (0 until WorkshopAutoImportPatchLogStore.MAX_LOG_SLOTS + 1).map { index ->
            WorkshopAutoImportPatchLogStore.createLogFile(roots.context).also { logFile ->
                WorkshopAutoImportPatchLogStore.appendLine(logFile, "log-$index")
            }
        }

        val remaining = WorkshopAutoImportPatchLogStore.listLogFiles(roots.context)

        assertEquals(WorkshopAutoImportPatchLogStore.MAX_LOG_SLOTS, remaining.size)
        assertFalse(remaining.any { it.name == created.first().name })
        assertTrue(remaining.any { it.name == created.last().name })
    }

    private fun downfallDetails(fileUrl: String): WorkshopItemDetails = WorkshopItemDetails(
        summary = WorkshopItemSummary(
            publishedFileId = 1610056683uL,
            appId = 646570u,
            title = "Downfall Expansion Mod",
            previewUrl = "",
            description = "",
        ),
        fileUrl = fileUrl,
    )

    private fun writeDownfallLikeJar(jarFile: File) {
        ZipOutputStream(jarFile.outputStream()).use { zipOut ->
            writeEntry(
                zipOut,
                "ModTheSpire.json",
                """
                {
                  "modid": "downfall",
                  "name": "Downfall Expansion Mod",
                  "version": "1.0.0",
                  "dependencies": ["basemod", "stslib"]
                }
                """.trimIndent().toByteArray(Charsets.UTF_8)
            )
            writeEntry(
                zipOut,
                "charbosses/bosses/Merchant/CharBossMerchant.class",
                buildMerchantClassBytes("charbosses/bosses/Merchant/CharBossMerchant")
            )
            writeEntry(
                zipOut,
                "downfall/spine/boss.atlas",
                """
                boss.png
                size: 4096, 4096
                format: RGBA8888
                filter: MipMapLinearLinear, Linear
                repeat: none
                body
                  rotate: false
                  xy: 0, 0
                  size: 4096, 4096
                  orig: 4096, 4096
                  offset: 0, 0
                  index: -1
                """.trimIndent().toByteArray(Charsets.UTF_8)
            )
            writeEntry(zipOut, "downfall/spine/boss.json", "{}".toByteArray(Charsets.UTF_8))
            writeEntry(zipOut, "downfall/spine/boss.png", ByteArray(32) { it.toByte() })
        }
    }

    private fun writeSimpleModJar(jarFile: File, modId: String, name: String) {
        ZipOutputStream(jarFile.outputStream()).use { zipOut ->
            writeEntry(
                zipOut,
                "ModTheSpire.json",
                """
                {
                  "modid": "$modId",
                  "name": "$name",
                  "version": "1.0.0",
                  "dependencies": []
                }
                """.trimIndent().toByteArray(Charsets.UTF_8)
            )
        }
    }

    private fun writeEntry(zipOut: ZipOutputStream, name: String, bytes: ByteArray) {
        zipOut.putNextEntry(ZipEntry(name))
        zipOut.write(bytes)
        zipOut.closeEntry()
    }

    private fun readZipEntryText(jarFile: File, name: String): String {
        ZipFile(jarFile).use { zipFile ->
            val entry = zipFile.getEntry(name) ?: error("Missing zip entry: $name")
            return zipFile.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    private fun buildMerchantClassBytes(className: String): ByteArray {
        val classWriter = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        classWriter.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null)
        classWriter.visitField(Opcodes.ACC_PUBLIC, "drawX", "F", null, null).visitEnd()

        val constructor = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        constructor.visitCode()
        constructor.visitVarInsn(Opcodes.ALOAD, 0)
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        constructor.visitVarInsn(Opcodes.ALOAD, 0)
        constructor.visitLdcInsn(1260.0f)
        constructor.visitFieldInsn(
            Opcodes.GETSTATIC,
            "com/megacrit/cardcrawl/core/Settings",
            "scale",
            "F"
        )
        constructor.visitInsn(Opcodes.FMUL)
        constructor.visitFieldInsn(Opcodes.PUTFIELD, className, "drawX", "F")
        constructor.visitInsn(Opcodes.RETURN)
        constructor.visitMaxs(0, 0)
        constructor.visitEnd()

        classWriter.visitEnd()
        return classWriter.toByteArray()
    }

    private class TestRoots private constructor(
        val rootDir: File,
        val context: Context,
    ) {
        companion object {
            fun create(prefix: String): TestRoots {
                val rootDir = Files.createTempDirectory(prefix).toFile()
                val filesDir = File(rootDir, "internal-files").apply { mkdirs() }
                val cacheDir = File(rootDir, "cache").apply { mkdirs() }
                val externalCacheDir = File(rootDir, "external-cache").apply { mkdirs() }
                val externalFilesDir = File(rootDir, "external-files").apply { mkdirs() }
                val prefs = LinkedHashMap<String, InMemorySharedPreferences>()
                val resources = TestResources()
                return TestRoots(
                    rootDir = rootDir,
                    context = object : ContextWrapper(Application()) {
                        override fun getFilesDir(): File = filesDir

                        override fun getCacheDir(): File = cacheDir

                        override fun getExternalCacheDir(): File = externalCacheDir

                        override fun getExternalFilesDir(type: String?): File = externalFilesDir

                        override fun getApplicationContext(): Context = this

                        override fun getPackageName(): String = "io.stamethyst.test"

                        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                            prefs.getOrPut(name) { InMemorySharedPreferences() }

                        override fun getResources(): Resources = resources
                    }
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private class TestResources : Resources(null, null, null) {
        override fun getString(id: Int): String = "res-$id"

        override fun getString(id: Int, vararg formatArgs: Any?): String =
            "res-$id ${formatArgs.joinToString()}"
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val values = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = synchronized(values) { LinkedHashMap(values) }

        override fun getString(key: String, defValue: String?): String? =
            synchronized(values) { values[key] as? String ?: defValue }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            synchronized(values) { (values[key] as? Set<String>)?.toMutableSet() ?: defValues }

        override fun getInt(key: String, defValue: Int): Int =
            synchronized(values) { values[key] as? Int ?: defValue }

        override fun getLong(key: String, defValue: Long): Long =
            synchronized(values) { values[key] as? Long ?: defValue }

        override fun getFloat(key: String, defValue: Float): Float =
            synchronized(values) { values[key] as? Float ?: defValue }

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            synchronized(values) { values[key] as? Boolean ?: defValue }

        override fun contains(key: String): Boolean = synchronized(values) { values.containsKey(key) }

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private val removals = LinkedHashSet<String>()
            private var clear = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { pending[key] = value }

            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
                apply { pending[key] = values?.toMutableSet() }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { pending[key] = value }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { pending[key] = value }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { pending[key] = value }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { pending[key] = value }

            override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }

            override fun clear(): SharedPreferences.Editor = apply { clear = true }

            override fun commit(): Boolean {
                synchronized(values) {
                    if (clear) values.clear()
                    removals.forEach(values::remove)
                    pending.forEach { (key, value) ->
                        if (value == null) values.remove(key) else values[key] = value
                    }
                }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
