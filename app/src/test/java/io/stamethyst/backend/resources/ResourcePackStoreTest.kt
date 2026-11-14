package io.stamethyst.backend.resources

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
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
import org.junit.Assert.assertArrayEquals
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
    fun recoverAndInspect_returnsTheHealthyValidationWithoutAnotherSweep() {
        withTestContext { context, root ->
            val archive = File(root, "resources.zip")
            writeResourcePackArchive(archive)
            val installed = ResourcePackStore.installArchive(context, archive, null, "test")
            val validated = ArrayList<File>()

            val inspection = recoverRecordingValidations(context, validated)

            assertTrue(inspection.ready)
            assertEquals(installed, inspection.manifest)
            assertEquals(listOf(inspection.generationDir), validated)
        }
    }

    @Test
    fun recoverAndInspect_reusesValidationWhenRebuildingMissingPointer() {
        withTestContext { context, root ->
            val archive = File(root, "resources.zip")
            writeResourcePackArchive(archive)
            val installed = ResourcePackStore.installArchive(context, archive, null, "test")
            RuntimePaths.externalResourcesActivePointerFile(context).delete()
            val validated = ArrayList<File>()

            val inspection = recoverRecordingValidations(context, validated)

            assertTrue(inspection.ready)
            assertEquals(installed, inspection.manifest)
            assertEquals("recovered_generation", inspection.state)
            assertEquals(listOf(inspection.generationDir), validated)
        }
    }

    @Test
    fun recoverAndInspect_rejectsSameSizeTamperingAndReusesVerifiedFallback() {
        withTestContext { context, root ->
            val archive = File(root, "resources.zip")
            writeResourcePackArchive(archive)
            val fallback = ResourcePackStore.installArchive(context, archive, null, "first")
            writeResourcePackArchive(archive, byteArrayOf(4, 5, 6))
            ResourcePackStore.installArchive(context, archive, null, "second")
            val corrupted = checkNotNull(ResourcePackStore.activeGenerationDir(context))
            tamperSameSize(File(corrupted, "assets/${ResourcePackContract.requiredAssetFiles.first()}"))
            val validated = ArrayList<File>()

            val inspection = recoverRecordingValidations(context, validated)

            assertTrue(inspection.ready)
            assertEquals(fallback, inspection.manifest)
            assertEquals(fallback.packId, ResourcePackStore.activePackId(context))
            assertEquals(listOf(corrupted, inspection.generationDir), validated)
        }
    }

    @Test
    fun recoverAndInspect_doesNotReuseVerificationAcrossOperations() {
        withTestContext { context, root ->
            val archive = File(root, "resources.zip")
            writeResourcePackArchive(archive)
            ResourcePackStore.installArchive(context, archive, null, "test")
            val first = ResourcePackStore.recoverAndInspect(context)
            assertTrue(first.ready)
            tamperSameSize(File(first.generationDir, "lib/${ResourcePackContract.ABI}/${ResourcePackContract.nativeLibraries.first()}"))

            val second = ResourcePackStore.recoverAndInspect(context)

            assertFalse(second.ready)
            assertEquals("needs_install", second.state)
            assertTrue(second.issues.any { it.contains("hash mismatch") })
        }
    }

    @Test
    fun recoverAndInspect_rejectsMissingNativeSource() {
        withTestContext { context, root ->
            val archive = File(root, "resources.zip")
            writeResourcePackArchive(archive)
            ResourcePackStore.installArchive(context, archive, null, "test")
            val first = ResourcePackStore.recoverAndInspect(context)
            val nativePath = "lib/${ResourcePackContract.ABI}/${ResourcePackContract.nativeLibraries.first()}"
            assertTrue(File(first.generationDir, nativePath).delete())

            val inspection = ResourcePackStore.recoverAndInspect(context)

            assertFalse(inspection.ready)
            assertTrue(inspection.issues.any { it.contains(nativePath) })
        }
    }

    @Test
    fun recoverAndInspect_verifiesReplacementGenerationOnNextOperation() {
        withTestContext { context, root ->
            val archive = File(root, "resources.zip")
            writeResourcePackArchive(archive)
            ResourcePackStore.installArchive(context, archive, null, "first")
            val first = ResourcePackStore.recoverAndInspect(context)
            writeResourcePackArchive(archive, byteArrayOf(4, 5, 6))
            val replacement = ResourcePackStore.installArchive(context, archive, null, "replacement")
            val validated = ArrayList<File>()

            val second = recoverRecordingValidations(context, validated)

            assertTrue(second.ready)
            assertFalse(first.packId == second.packId)
            assertEquals(replacement, second.manifest)
            assertEquals(listOf(second.generationDir), validated)
        }
    }

    @Test
    fun ensureAvailable_repairsMissingNativeTargets() {
        withTestContext { context, root ->
            val archive = File(root, "resources.zip")
            writeResourcePackArchive(archive)
            ResourcePackStore.installArchive(context, archive, null, "test")
            ExternalResourcePackService.ensureAvailable(context)
            val nativeDir = RuntimePaths.externalNativeLibDir(context)
            assertTrue(File(nativeDir, ResourcePackContract.nativeLibraries.first()).delete())

            ExternalResourcePackService.ensureAvailable(context)

            assertNativeContent(context, byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun ensureAvailable_repairsSameSizeTamperingWithUnchangedNativeTimestamp() {
        withTestContext { context, root ->
            val archive = File(root, "resources.zip")
            writeResourcePackArchive(archive)
            ResourcePackStore.installArchive(context, archive, null, "test")
            ExternalResourcePackService.ensureAvailable(context)
            val nativeDir = RuntimePaths.externalNativeLibDir(context)
            tamperSameSize(File(nativeDir, ResourcePackContract.nativeLibraries.first()))

            ExternalResourcePackService.ensureAvailable(context)

            assertNativeContent(context, byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun ensureAvailable_reusesHealthyNativeTargets() {
        withTestContext { context, root ->
            val archive = File(root, "resources.zip")
            writeResourcePackArchive(archive)
            ResourcePackStore.installArchive(context, archive, null, "test")
            ExternalResourcePackService.ensureAvailable(context)
            val target = File(RuntimePaths.externalNativeLibDir(context), ResourcePackContract.nativeLibraries.first())
            assertTrue(target.setLastModified(1_000_000L))

            ExternalResourcePackService.ensureAvailable(context)

            assertEquals(1_000_000L, target.lastModified())
            assertNativeContent(context, byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun ensureAvailableForLaunch_installsNativesOnlyAfterComponentReplacement() {
        withTestContext { context, root ->
            val archive = File(root, "resources.zip")
            writeResourcePackArchive(archive)
            ResourcePackStore.installArchive(context, archive, null, "test")
            val nativeDir = RuntimePaths.externalNativeLibDir(context)
            var componentInstalls = 0

            ExternalResourcePackService.ensureAvailableForLaunch(context, null) {
                componentInstalls++
                assertFalse(RuntimePaths.externalNativeLibMarkerFile(context).exists())
                assertFalse(File(nativeDir, ResourcePackContract.nativeLibraries.first()).exists())
                RuntimePaths.gdxPatchDir(context).deleteRecursively()
                writeFile(File(nativeDir, "bundled-only.so"))
            }

            assertEquals(1, componentInstalls)
            assertNativeContent(context, byteArrayOf(1, 2, 3))
            assertArrayEquals(byteArrayOf(1, 2, 3), File(nativeDir, "bundled-only.so").readBytes())
        }
    }

    @Test
    fun ensureAvailable_repairsDerivedTargetsAfterSameIdReinstall() {
        withTestContext { context, root ->
            val archive = File(root, "resources.zip")
            writeResourcePackArchive(archive)
            val first = ResourcePackStore.installArchive(context, archive, null, "first")
            ExternalResourcePackService.ensureAvailable(context)

            val reinstalled = ResourcePackStore.installArchive(context, archive, null, "reinstall")
            assertEquals(first.packId, reinstalled.packId)
            assertFalse(RuntimePaths.externalNativeLibMarkerFile(context).exists())
            ExternalResourcePackService.ensureAvailable(context)

            assertNativeContent(context, byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun reinstall_doesNotShortCircuitAHealthyGeneration() {
        // A non-IO sentinel stops the embedded presence probe before any network fallback.
        val freshArchiveRequested = UnsupportedOperationException("fresh archive requested")
        withTestContext(assets = { throw freshArchiveRequested }) { context, root ->
            val archive = File(root, "resources.zip")
            writeResourcePackArchive(archive)
            ResourcePackStore.installArchive(context, archive, null, "test")
            ExternalResourcePackService.ensureAvailable(context)

            val error = runCatching { ExternalResourcePackService.reinstall(context) }.exceptionOrNull()

            assertTrue(error is UnsupportedOperationException)
            assertEquals("fresh archive requested", error?.message)
            assertTrue(ResourcePackStore.inspect(context).ready)
        }
    }

    @Test
    fun ensureAvailableForLaunch_rejectsSnapshotWhenComponentsChangeGeneration() {
        withTestContext { context, root ->
            val archive = File(root, "resources.zip")
            writeResourcePackArchive(archive)
            ResourcePackStore.installArchive(context, archive, null, "first")
            writeResourcePackArchive(archive, byteArrayOf(4, 5, 6))

            val error = runCatching {
                ExternalResourcePackService.ensureAvailableForLaunch(context, null) {
                    ResourcePackStore.installArchive(context, archive, null, "replacement")
                }
            }.exceptionOrNull()

            assertTrue(error is IOException)
            assertTrue(error?.message.orEmpty().contains("generation changed"))
            ExternalResourcePackService.ensureAvailable(context)
            assertNativeContent(context, byteArrayOf(4, 5, 6))
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

    private fun recoverRecordingValidations(context: Context, validated: MutableList<File>): ResourcePackInspection =
        ResourcePackStore.recoverAndInspect(context) { generation ->
            validated += generation
            ResourcePackArchive.validateGeneration(generation, BuildConfig.RESOURCE_PACK_VERSION.trim())
        }

    private fun tamperSameSize(file: File) {
        val modified = file.lastModified()
        file.writeBytes(ByteArray(file.length().toInt()) { 9 })
        assertTrue(file.setLastModified(modified))
    }

    private fun assertNativeContent(context: Context, expected: ByteArray) {
        val nativeDir = RuntimePaths.externalNativeLibDir(context)
        ResourcePackContract.nativeLibraries.forEach { name ->
            assertArrayEquals(name, expected, File(nativeDir, name).readBytes())
        }
        assertTrue(RuntimePaths.externalNativeLibMarkerFile(context).isFile)
    }

    private fun writeResourcePackArchive(archive: File, content: ByteArray = byteArrayOf(1, 2, 3)) {
        ZipOutputStream(archive.outputStream()).use { zip ->
            val paths = buildList {
                addAll(ResourcePackContract.requiredAssetFiles)
                add(ResourcePackContract.runtimeArchiveAlternatives.first())
                addAll(ResourcePackContract.nativeLibraries.map { name -> "../lib/${ResourcePackContract.ABI}/$name" })
            }
            paths.distinct().forEach { path ->
                val normalized = if (path.startsWith("../")) path.removePrefix("../") else "assets/$path"
                zip.putNextEntry(ZipEntry(normalized))
                zip.write(content)
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

    private fun withTestContext(
        assets: () -> AssetManager = { throw UnsupportedOperationException("Unexpected asset access") },
        block: (Context, File) -> Unit
    ) {
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
                override fun getAssets(): AssetManager = assets()
            }
            block(context, root)
        } finally {
            root.deleteRecursively()
        }
    }
}
