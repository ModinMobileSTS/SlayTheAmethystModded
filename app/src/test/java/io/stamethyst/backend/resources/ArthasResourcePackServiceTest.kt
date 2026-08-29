package io.stamethyst.backend.resources

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArthasResourcePackServiceTest {
    @Test
    fun installArchive_validPack_installsValidatedVersion() {
        withTestContext { context, root ->
            val archive = File(root, "valid.zip")
            writePack(archive, version = "test-1")

            val state = ArthasResourcePackService.installArchive(context, archive)

            assertTrue(state.installed)
            assertTrue(state.valid)
            assertEquals("test-1", state.version)
            assertEquals(4, File(root, "arthas_resources/current").listFiles()?.size)
        }
    }

    @Test
    fun installArchive_checksumMismatch_preservesCurrentVersion() {
        withTestContext { context, root ->
            val valid = File(root, "valid.zip")
            writePack(valid, version = "test-1")
            ArthasResourcePackService.installArchive(context, valid)
            val invalid = File(root, "invalid.zip")
            writePack(invalid, version = "test-2", corruptCoreHash = true)

            val error = runCatching {
                ArthasResourcePackService.installArchive(context, invalid)
            }.exceptionOrNull()

            assertTrue(error is IOException)
            val state = ArthasResourcePackService.state(context)
            assertTrue(state.valid)
            assertEquals("test-1", state.version)
        }
    }

    @Test
    fun installArchive_extraEntry_isRejected() {
        withTestContext { context, root ->
            val archive = File(root, "extra.zip")
            writePack(archive, version = "test-1", extraEntry = true)

            val error = runCatching {
                ArthasResourcePackService.installArchive(context, archive)
            }.exceptionOrNull()

            assertTrue(error is IOException)
            assertFalse(ArthasResourcePackService.state(context).installed)
        }
    }

    private fun writePack(
        archive: File,
        version: String,
        corruptCoreHash: Boolean = false,
        extraEntry: Boolean = false,
    ) {
        val files = linkedMapOf(
            "arthas-core.jar" to "core-$version".toByteArray(),
            "arthas-spy.jar" to "spy-$version".toByteArray(),
            "arthas-bridge.jar" to "bridge-$version".toByteArray(),
        )
        val manifest = buildString {
            append("schemaVersion=1\n")
            append("packageVersion=$version\n")
            files.forEach { (name, bytes) ->
                val hash = sha256(bytes)
                append("$name.size=${bytes.size}\n")
                append("$name.sha256=")
                append(if (corruptCoreHash && name == "arthas-core.jar") "0".repeat(64) else hash)
                append('\n')
            }
        }.toByteArray()
        ZipOutputStream(archive.outputStream()).use { zip ->
            (files + (ArthasResourcePackService.MANIFEST_NAME to manifest)).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            if (extraEntry) {
                zip.putNextEntry(ZipEntry("unexpected.txt"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun withTestContext(block: (Context, File) -> Unit) {
        val root = Files.createTempDirectory("arthas-resource-test-").toFile()
        try {
            val context = object : ContextWrapper(null) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = root
            }
            block(context, root)
        } finally {
            root.deleteRecursively()
        }
    }
}
