package io.stamethyst.backend.resources

import android.content.Context
import android.content.ContextWrapper
import io.stamethyst.BuildConfig
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourcePackStoreTest {
    @Test
    fun installArchive_createsValidatedActiveGeneration() {
        withTestContext { context, root ->
            val archive = File(root, "resources.zip")
            writeResourcePackArchive(archive)

            ResourcePackStore.installArchive(context, archive, null, "test")

            val inspection = ResourcePackStore.inspect(context)
            assertTrue(inspection.ready)
            assertNotNull(inspection.packId)
            assertTrue(RuntimePaths.externalResourcesActivePointerFile(context).isFile)
            assertTrue(inspection.generationDir?.isDirectory == true)
        }
    }

    @Test
    fun inspect_reusesTheSameGenerationAfterASecondInstallOfIdenticalContent() {
        withTestContext { context, root ->
            val archive = File(root, "resources.zip")
            writeResourcePackArchive(archive)
            ResourcePackStore.installArchive(context, archive, null, "first")
            val originalId = ResourcePackStore.activePackId(context)

            ResourcePackStore.installArchive(context, archive, null, "second")

            assertTrue(ResourcePackStore.inspect(context).ready)
            assertEquals(originalId, ResourcePackStore.activePackId(context))
        }
    }

    @Test
    fun isQuickStartReady_acceptsAHealthyActiveGeneration() {
        withTestContext { context, root ->
            val archive = File(root, "resources.zip")
            writeResourcePackArchive(archive)

            ResourcePackStore.installArchive(context, archive, null, "test")

            assertTrue(ResourcePackStore.isQuickStartReady(context))
        }
    }

    @Test
    fun isQuickStartReady_rejectsMissingRequiredContent() {
        withTestContext { context, root ->
            val archive = File(root, "resources.zip")
            writeResourcePackArchive(archive)
            ResourcePackStore.installArchive(context, archive, null, "test")
            val generation = ResourcePackStore.activeGenerationDir(context)
                ?: error("active generation was not installed")
            File(generation, "assets/${ResourcePackContract.requiredAssetFiles.first()}").delete()

            assertFalse(ResourcePackStore.isQuickStartReady(context))
        }
    }

    @Test
    fun nativeLibraries_areReinstalledWhenTheDerivedTargetIsMissing() {
        withTestContext { context, root ->
            val archive = File(root, "resources.zip")
            writeResourcePackArchive(archive)
            ResourcePackStore.installArchive(context, archive, null, "test")

            ExternalResourcePackService.installNativeLibraries(context)
            val nativeDir = RuntimePaths.externalNativeLibDir(context)
            assertTrue(ResourcePackContract.nativeLibraries.all { name ->
                File(nativeDir, name).isFile
            })
            assertTrue(RuntimePaths.externalNativeLibMarkerFile(context).isFile)

            ResourcePackContract.nativeLibraries.forEach { name ->
                File(nativeDir, name).delete()
            }
            RuntimePaths.externalNativeLibMarkerFile(context).delete()

            ExternalResourcePackService.installNativeLibraries(context)

            assertTrue(ResourcePackContract.nativeLibraries.all { name ->
                File(nativeDir, name).isFile
            })
            assertTrue(RuntimePaths.externalNativeLibMarkerFile(context).isFile)
        }
    }

    @Test
    fun inspect_detectsModifiedActiveContent() {
        withTestContext { context, root ->
            val archive = File(root, "resources.zip")
            writeResourcePackArchive(archive)
            ResourcePackStore.installArchive(context, archive, null, "test")

            val generation = ResourcePackStore.activeGenerationDir(context)
                ?: error("active generation was not installed")
            File(generation, "assets/${ResourcePackContract.requiredAssetFiles.first()}")
                .writeBytes(byteArrayOf(9, 8, 7))

            val inspection = ResourcePackStore.inspect(context)

            assertFalse(inspection.ready)
            assertTrue(inspection.issues.any { issue -> issue.contains("hash mismatch") })
        }
    }

    @Test
    fun inspectQuick_skipsContentHashesWhileInspectRemainsAuthoritative() {
        withTestContext { context, root ->
            val archive = File(root, "resources.zip")
            writeResourcePackArchive(archive)
            ResourcePackStore.installArchive(context, archive, null, "test")

            val generation = ResourcePackStore.activeGenerationDir(context)
                ?: error("active generation was not installed")
            val content = File(generation, "assets/${ResourcePackContract.requiredAssetFiles.first()}")
            val originalSize = content.length().toInt()
            content.writeBytes(ByteArray(originalSize) { 9 })

            assertTrue(ResourcePackStore.inspectQuick(context).ready)
            assertFalse(ResourcePackStore.inspect(context).ready)
        }
    }

    @Test
    fun activeGenerationDir_doesNotHashContentOnTheHotPath() {
        withTestContext { context, root ->
            val archive = File(root, "resources.zip")
            writeResourcePackArchive(archive)
            ResourcePackStore.installArchive(context, archive, null, "test")

            val generation = ResourcePackStore.activeGenerationDir(context)
                ?: error("active generation was not installed")
            val content = File(generation, "assets/${ResourcePackContract.requiredAssetFiles.first()}")
            val originalSize = content.length().toInt()
            content.writeBytes(ByteArray(originalSize) { 9 })

            assertNotNull(ResourcePackStore.activeGenerationDir(context))
            assertFalse(ResourcePackStore.inspect(context).ready)
        }
    }

    @Test
    fun recover_importsLegacyCurrentIntoGenerationStore() {
        withTestContext { context, root ->
            val legacyCurrent = File(RuntimePaths.legacyInternalExternalResourcesRoot(context), "current")
            writeExtractedResourcePack(legacyCurrent)
            File(legacyCurrent, ResourcePackContract.INSTALL_MARKER_FILE_NAME).writeText(
                "version=${BuildConfig.RESOURCE_PACK_VERSION}\n"
            )

            ResourcePackStore.recover(context)

            assertTrue(ResourcePackStore.inspect(context).ready)
            assertFalse(legacyCurrent.exists())
            assertTrue(RuntimePaths.externalResourcesQuarantineRoot(context).listFiles().orEmpty().isNotEmpty())
        }
    }

    @Test
    fun failedArchiveInstall_preservesTheActiveGeneration() {
        withTestContext { context, root ->
            val valid = File(root, "valid.zip")
            writeResourcePackArchive(valid)
            ResourcePackStore.installArchive(context, valid, null, "test")
            val originalId = ResourcePackStore.activePackId(context)

            val invalid = File(root, "invalid.zip")
            ZipOutputStream(invalid.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("assets/components/jre/version"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
            val error = runCatching {
                ResourcePackStore.installArchive(context, invalid, null, "test")
            }.exceptionOrNull()

            assertTrue(error is IOException)
            assertTrue(ResourcePackStore.inspect(context).ready)
            assertTrue(originalId == ResourcePackStore.activePackId(context))
        }
    }

    @Test
    fun installArchive_rejectsPathTraversal() {
        withTestContext { context, root ->
            val archive = File(root, "unsafe.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("../outside.txt"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }

            val error = runCatching {
                ResourcePackStore.installArchive(context, archive, null, "test")
            }.exceptionOrNull()

            assertTrue(error is IOException)
            assertFalse(File(root, "outside.txt").exists())
        }
    }

    @Test
    fun recover_rebuildsActivePointerFromAValidGeneration() {
        withTestContext { context, root ->
            val archive = File(root, "resources.zip")
            writeResourcePackArchive(archive)
            ResourcePackStore.installArchive(context, archive, null, "test")
            val originalId = ResourcePackStore.activePackId(context)
            RuntimePaths.externalResourcesActivePointerFile(context).delete()

            assertFalse(ResourcePackStore.inspect(context).ready)
            ResourcePackStore.recover(context)

            assertTrue(ResourcePackStore.inspect(context).ready)
            assertTrue(originalId == ResourcePackStore.activePackId(context))
        }
    }

    @Test
    fun recover_importsLegacyFilesStoredDirectlyUnderRepositoryRoot() {
        withTestContext { context, _ ->
            val legacyRoot = RuntimePaths.externalResourcesRoot(context)
            writeExtractedResourcePack(legacyRoot)
            File(legacyRoot, ResourcePackContract.INSTALL_MARKER_FILE_NAME).writeText(
                "version=${BuildConfig.RESOURCE_PACK_VERSION}\n"
            )

            ResourcePackStore.recover(context)

            assertTrue(ResourcePackStore.inspect(context).ready)
            assertFalse(File(legacyRoot, "assets").exists())
        }
    }

    @Test
    fun tryCleanupTransient_removesStagingWithoutTouchingTheActiveGeneration() {
        withTestContext { context, _ ->
            val archive = File(RuntimePaths.externalResourcesRoot(context).parentFile, "resources.zip")
            writeResourcePackArchive(archive)
            ResourcePackStore.installArchive(context, archive, null, "test")
            val originalId = ResourcePackStore.activePackId(context)

            val leftover = File(RuntimePaths.externalResourcesStagingRoot(context), "leftover.bin")
            leftover.parentFile?.mkdirs()
            leftover.writeBytes(byteArrayOf(1, 2, 3))

            assertTrue(ResourcePackStore.tryCleanupTransient(context))
            assertFalse(leftover.exists())
            assertTrue(ResourcePackStore.inspect(context).ready)
            assertEquals(originalId, ResourcePackStore.activePackId(context))
        }
    }

    @Test
    fun requireArchiveBytes_rejectsOversizedArchives() {
        val error = runCatching {
            ResourcePackContract.requireArchiveBytes(ResourcePackContract.MAX_ARCHIVE_BYTES + 1)
        }.exceptionOrNull()
        assertTrue(error is IOException)
    }

    private fun writeResourcePackArchive(archive: File) {
        ZipOutputStream(archive.outputStream()).use { zip ->
            val paths = buildList {
                addAll(ResourcePackContract.requiredAssetFiles)
                add(ResourcePackContract.runtimeArchiveAlternatives.first())
                addAll(ResourcePackContract.nativeLibraries.map { name -> "../lib/${ResourcePackContract.ABI}/$name" })
            }
            paths.distinct().forEach { path ->
                val normalized = if (path.startsWith("../")) path.removePrefix("../") else "assets/$path"
                zip.putNextEntry(ZipEntry(normalized))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
    }

    private fun writeExtractedResourcePack(root: File) {
        ResourcePackContract.requiredAssetFiles.forEach { path ->
            writeFile(File(root, "assets/$path"))
        }
        writeFile(File(root, "assets/${ResourcePackContract.runtimeArchiveAlternatives.first()}"))
        ResourcePackContract.nativeLibraries.forEach { name ->
            writeFile(File(root, "lib/${ResourcePackContract.ABI}/$name"))
        }
    }

    private fun writeFile(file: File) {
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
    }

    private fun withTestContext(block: (Context, File) -> Unit) {
        val root = Files.createTempDirectory("resource-pack-store-test-").toFile()
        val internal = File(root, "internal")
        val external = File(root, "external")
        internal.mkdirs()
        external.mkdirs()
        try {
            val context = object : ContextWrapper(null) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = internal
                override fun getExternalFilesDir(type: String?): File = external
            }
            block(context, root)
        } finally {
            root.deleteRecursively()
        }
    }
}
