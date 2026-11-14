package io.stamethyst.backend.launch

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import io.stamethyst.backend.mods.BASEMOD_RESOURCE_SENTINEL
import io.stamethyst.backend.mods.REQUIRED_GDX_CLASSES
import io.stamethyst.backend.mods.STS_RESOURCE_SENTINEL
import io.stamethyst.config.RuntimePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MtsClasspathPreparationReuseTest {
    @Test
    fun unchangedVerifiedInputsSkipSecondDeepCheckButStillRequireClasspathOutputs() = withFixture { fixture ->
        var deepChecks = 0
        val proof = GameBodyPatchVerification.verify(fixture.desktop, fixture.patch) { _, _ ->
            deepChecks++
            true
        }
        assertNotNull(proof)
        fixture.writeMarker()

        assertTrue(fixture.isCurrent(proof) { _, _ ->
            deepChecks++
            false
        })
        assertEquals(1, deepChecks)
        RuntimePaths.mtsBaseModResourcesJar(fixture.context).delete()
        assertFalse(fixture.isCurrent(proof) { _, _ -> error("unchanged proof must be reused") })
    }

    @Test
    fun failedDeepCheckCannotProduceReusableProof() = withFixture { fixture ->
        var checks = 0
        val proof = GameBodyPatchVerification.verify(fixture.desktop, fixture.patch) { _, _ ->
            checks++
            false
        }
        assertNull(proof)
        fixture.writeMarker()
        assertFalse(fixture.isCurrent(proof) { _, _ ->
            checks++
            false
        })
        assertEquals(2, checks)
    }

    @Test
    fun sameSizeAndMtimeDesktopMutationInvalidatesProof() = withFixture { fixture ->
        assertMutationInvalidatesProof(fixture, fixture.desktop)
    }

    @Test
    fun sameSizeAndMtimePatchMutationInvalidatesProof() = withFixture { fixture ->
        assertMutationInvalidatesProof(fixture, fixture.patch)
    }

    @Test
    fun mutationDuringVerificationCannotCertifyNewBytes() = withFixture { fixture ->
        val proof = GameBodyPatchVerification.verify(fixture.desktop, fixture.patch) { _, _ ->
            rewritePreservingMetadata(fixture.desktop)
            true
        }
        fixture.writeMarker()
        var checks = 0
        assertFalse(fixture.isCurrent(proof) { _, _ ->
            checks++
            false
        })
        assertEquals(1, checks)
    }

    @Test
    fun standaloneCurrentCheckDoesNotInheritEarlierVerification() = withFixture { fixture ->
        val proof = GameBodyPatchVerification.verify(fixture.desktop, fixture.patch) { _, _ -> true }
        fixture.writeMarker()
        assertTrue(fixture.isCurrent(proof) { _, _ -> error("proof should be used") })

        // Fixture jars are readable archives but contain no real patch classes. The
        // public entrypoint must independently reject them, even after a proven call.
        assertFalse(MtsClasspathWarmupCoordinator.isCacheCurrent(fixture.context))
    }

    @Test
    fun unreadableOrMissingInputsNeverProduceProof() = withFixture { fixture ->
        fixture.desktop.writeText("not a zip")
        assertNull(GameBodyPatchVerification.verify(fixture.desktop, fixture.patch) { _, _ -> true })
        fixture.desktop.delete()
        assertNull(GameBodyPatchVerification.verify(fixture.desktop, fixture.patch) { _, _ -> true })
    }

    @Test
    fun cacheMissWritesPostRebuildMarkerIncludingSameMetadataChanges() = withFixture { fixture ->
        val before = MtsClasspathWarmupCoordinator.buildCacheMarkerValue(fixture.context)
        assertFalse(fixture.isCurrent(null) { _, _ -> error("missing marker needs no deep check") })

        MtsClasspathWarmupCoordinator.rebuildCache(fixture.context) {
            rewritePreservingMetadata(fixture.desktop)
        }

        val after = MtsClasspathWarmupCoordinator.buildCacheMarkerValue(fixture.context)
        assertNotEquals(before, after)
        assertEquals(after, RuntimePaths.mtsClasspathCacheMarker(fixture.context).readText())
        assertTrue(after.endsWith('\n'))
        assertTrue(fixture.isCurrent(null) { _, _ -> true })
    }

    @Test
    fun failedRebuildDoesNotPublishNewMarker() = withFixture { fixture ->
        fixture.writeMarker()
        val markerFile = RuntimePaths.mtsClasspathCacheMarker(fixture.context)
        val before = markerFile.readText()

        assertThrows(IOException::class.java) {
            MtsClasspathWarmupCoordinator.rebuildCache(fixture.context) {
                rewritePreservingMetadata(fixture.desktop)
                throw IOException("failed rebuild")
            }
        }

        assertEquals(before, markerFile.readText())
        assertFalse(fixture.isCurrent(null) { _, _ -> error("old marker must miss") })
    }

    private fun assertMutationInvalidatesProof(fixture: Fixture, changed: File) {
        val proof = GameBodyPatchVerification.verify(fixture.desktop, fixture.patch) { _, _ -> true }
        assertNotNull(proof)
        rewritePreservingMetadata(changed)
        // Refresh the marker to isolate proof invalidation from marker mismatch.
        fixture.writeMarker()
        var checks = 0
        assertFalse(fixture.isCurrent(proof) { _, _ ->
            checks++
            false
        })
        assertEquals(1, checks)
    }

    private fun rewritePreservingMetadata(file: File) {
        val length = file.length()
        val mtime = file.lastModified()
        writeJar(file, "payload" to "BBBB")
        assertTrue(file.setLastModified(mtime))
        assertEquals(length, file.length())
        assertEquals(mtime, file.lastModified())
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("mts-preparation-reuse-").toFile()
        try {
            block(Fixture(root))
        } finally {
            root.deleteRecursively()
        }
    }

    private class Fixture(root: File) {
        val context: Context = object : ContextWrapper(Application()) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?): File = File(root, "external").apply { mkdirs() }
            override fun getPackageName(): String = "io.stamethyst.test"
        }
        val desktop = RuntimePaths.importedStsJar(context)
        val patch = RuntimePaths.gdxPatchJar(context)

        init {
            listOf(
                desktop, patch, RuntimePaths.importedMtsJar(context),
                RuntimePaths.importedBaseModJar(context), RuntimePaths.importedStsLibJar(context)
            ).forEach { writeJar(it, "payload" to "AAAA") }
            writeJar(RuntimePaths.mtsGdxApiJar(context), *REQUIRED_GDX_CLASSES.map { it to "api" }.toTypedArray())
            writeJar(RuntimePaths.mtsStsResourcesJar(context), STS_RESOURCE_SENTINEL to "resource")
            writeJar(RuntimePaths.mtsBaseModResourcesJar(context), BASEMOD_RESOURCE_SENTINEL to "resource")
        }

        fun writeMarker() {
            val marker = RuntimePaths.mtsClasspathCacheMarker(context)
            marker.parentFile?.mkdirs()
            marker.writeText(MtsClasspathWarmupCoordinator.buildCacheMarkerValue(context))
        }

        fun isCurrent(proof: GameBodyPatchVerification?, checker: (File, File) -> Boolean): Boolean {
            val fingerprints = MtsClasspathWarmupCoordinator.captureFingerprints(context)
            return MtsClasspathWarmupCoordinator.isCacheCurrent(
                context, MtsClasspathWarmupCoordinator.buildCacheMarkerValue(context, fingerprints),
                proof, fingerprints, checker
            )
        }
    }

    companion object {
        private fun writeJar(file: File, vararg entries: Pair<String, String>) {
            file.parentFile?.mkdirs()
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name).apply { time = 1_700_000_000_000L })
                    zip.write(text.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
    }
}
