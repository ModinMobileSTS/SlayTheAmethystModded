package io.stamethyst.backend.nativelib

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLibraryMarketServiceTest {
    @Test
    fun parseCatalog_supportsCurrentFormat() {
        val parsed = NativeLibraryMarketService.parseCatalog(
            """
            [
              {
                "name": "Libgdx Video",
                "description": "Video playback bridge",
                "files": [
                  {
                    "file_name": "libgdx-video-desktoparm64.so"
                  }
                ]
              }
            ]
            """.trimIndent()
        )

        assertEquals(1, parsed.size)
        assertEquals("libgdx-video-desktoparm64", parsed.first().id)
        assertEquals("Libgdx Video", parsed.first().displayName)
        assertEquals("Video playback bridge", parsed.first().description)
        assertEquals(1, parsed.first().files.size)
        assertEquals(
            "libgdx-video-desktoparm64.so",
            parsed.first().files.first().fileName
        )
        assertTrue(
            parsed.first().files.first().downloadUrl.endsWith("libgdx-video-desktoparm64.so")
        )
    }

    @Test
    fun parseCatalog_supportsLegacyAliasesAndExplicitIds() {
        val parsed = NativeLibraryMarketService.parseCatalog(
            """
            [
              {
                "id": "tensorflow",
                "card_name": "Tensorflow",
                "description": "Legacy fields",
                "card_files": [
                  {
                    "file_name": "libjnitensorflow.so",
                    "download_url": "https://example.com/libjnitensorflow.so"
                  }
                ]
              }
            ]
            """.trimIndent()
        )

        assertEquals(1, parsed.size)
        assertEquals("tensorflow", parsed.first().id)
        assertEquals("Tensorflow", parsed.first().displayName)
        assertEquals(
            "https://example.com/libjnitensorflow.so",
            parsed.first().files.first().downloadUrl
        )
    }

    @Test
    fun computeInstallProgressPercent_mapsCurrentFileIntoWholeInstall() {
        assertEquals(
            0,
            NativeLibraryMarketService.computeInstallProgressPercent(1, 2, 0L, 100L)
        )
        assertEquals(
            25,
            NativeLibraryMarketService.computeInstallProgressPercent(1, 2, 50L, 100L)
        )
        assertEquals(
            50,
            NativeLibraryMarketService.computeInstallProgressPercent(1, 2, 100L, 100L)
        )
        assertEquals(
            75,
            NativeLibraryMarketService.computeInstallProgressPercent(2, 2, 50L, 100L)
        )
        assertEquals(
            100,
            NativeLibraryMarketService.computeInstallProgressPercent(2, 2, 100L, 100L)
        )
        assertNull(
            NativeLibraryMarketService.computeInstallProgressPercent(1, 2, 50L, null)
        )
    }

    @Test
    fun formatTransferBytes_formatsHumanReadableUnits() {
        assertEquals("0 B", NativeLibraryMarketService.formatTransferBytes(0L))
        assertEquals("1.5 KB", NativeLibraryMarketService.formatTransferBytes(1536L))
        assertEquals("5.0 MB", NativeLibraryMarketService.formatTransferBytes(5L * 1024L * 1024L))
    }

    @Test
    fun extractSharedLibrariesFromZip_extractsRequestedLibraryByBasename() {
        withTempDir { tempDir ->
            val archiveFile = File(tempDir, "natives.zip")
            writeZip(
                archiveFile,
                mapOf(
                    "arm64-v8a/libfoo.so" to "foo-binary",
                    "README.txt" to "ignored"
                )
            )
            val outputDir = File(tempDir, "output").apply(File::mkdirs)

            val extracted = NativeLibraryMarketService.extractSharedLibrariesFromZip(
                archiveFile = archiveFile,
                requestedFileName = "libfoo.so",
                targetDir = outputDir
            )

            assertEquals(listOf("libfoo.so"), extracted.map(File::getName))
            assertEquals("foo-binary", File(outputDir, "libfoo.so").readText())
            assertTrue(NativeLibraryMarketService.isZipArchive(archiveFile))
        }
    }

    @Test
    fun extractSharedLibrariesFromZip_extractsAllLibrariesWhenRequestIsArchiveName() {
        withTempDir { tempDir ->
            val archiveFile = File(tempDir, "tensorflow.zip")
            writeZip(
                archiveFile,
                mapOf(
                    "libjnitensorflow.so" to "jni",
                    "nested/libtensorflow_framework.so.2" to "framework",
                    "docs/README.txt" to "ignored"
                )
            )
            val outputDir = File(tempDir, "output").apply(File::mkdirs)

            val extracted = NativeLibraryMarketService.extractSharedLibrariesFromZip(
                archiveFile = archiveFile,
                requestedFileName = "tensorflow.zip",
                targetDir = outputDir
            )

            assertEquals(
                listOf("libjnitensorflow.so", "libtensorflow_framework.so.2"),
                extracted.map(File::getName).sorted()
            )
            assertEquals("jni", File(outputDir, "libjnitensorflow.so").readText())
            assertEquals("framework", File(outputDir, "libtensorflow_framework.so.2").readText())
        }
    }

    private fun withTempDir(block: (File) -> Unit) {
        val tempDir = Files.createTempDirectory("native-library-market-test").toFile()
        try {
            block(tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun writeZip(zipFile: File, entries: Map<String, String>) {
        ZipOutputStream(zipFile.outputStream()).use { zipOutput ->
            entries.forEach { (entryName, content) ->
                zipOutput.putNextEntry(ZipEntry(entryName))
                zipOutput.write(content.toByteArray())
                zipOutput.closeEntry()
            }
        }
    }
}
