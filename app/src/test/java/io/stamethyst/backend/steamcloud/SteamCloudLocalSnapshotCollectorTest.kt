package io.stamethyst.backend.steamcloud

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SteamCloudLocalSnapshotCollectorTest {
    @Test
    fun collectFile_matchesFullSnapshotWithoutEnumeratingSiblingFiles() {
        val tempRoot = Files.createTempDirectory("steam-cloud-single-snapshot-test").toFile()
        try {
            writeFile(tempRoot, "saves/nested/WATCHER.autosave", "autosave")
            val expected = SteamCloudLocalSnapshotCollector.collect(tempRoot).single()
            // A full traversal would reject this unrelated sibling. Single-file verification
            // neither traverses nor hashes files downloaded in previous iterations.
            Files.createSymbolicLink(File(tempRoot, "saves/unrelated").toPath(), tempRoot.toPath())
            assertEquals(expected, SteamCloudLocalSnapshotCollector.collectFile(tempRoot, expected.localRelativePath))
        } finally {
            Files.deleteIfExists(File(tempRoot, "saves/unrelated").toPath())
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun collectFile_rejectsUnsafePathsMissingFilesAndSymlinkParents() {
        val tempRoot = Files.createTempDirectory("steam-cloud-single-path-test").toFile()
        try {
            writeFile(tempRoot, "preferences/STSPlayer", "pref")
            listOf("../preferences/STSPlayer", "saves/../preferences/STSPlayer", "runs/ignored", "saves/missing").forEach { path ->
                assertThrows(java.io.IOException::class.java) { SteamCloudLocalSnapshotCollector.collectFile(tempRoot, path) }
            }
            File(tempRoot, "saves").mkdirs()
            Files.createSymbolicLink(File(tempRoot, "saves/link").toPath(), File(tempRoot, "preferences").toPath())
            assertThrows(java.io.IOException::class.java) { SteamCloudLocalSnapshotCollector.collectFile(tempRoot, "saves/link/STSPlayer") }
            Files.createSymbolicLink(File(tempRoot, "saves/file").toPath(), File(tempRoot, "preferences/STSPlayer").toPath())
            assertThrows(java.io.IOException::class.java) { SteamCloudLocalSnapshotCollector.collectFile(tempRoot, "saves/file") }
        } finally {
            Files.deleteIfExists(File(tempRoot, "saves/link").toPath())
            Files.deleteIfExists(File(tempRoot, "saves/file").toPath())
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun collect_onlyIncludesPreferencesAndSavesWithExactRelativeNames() {
        val tempRoot = Files.createTempDirectory("steam-cloud-local-snapshot-test").toFile()
        try {
            writeFile(tempRoot, "preferences/1_Tuner_CLASS.backUp", "backup")
            writeFile(tempRoot, "saves/WATCHER.autosave", "autosave")
            writeFile(tempRoot, "runs/ignored.run", "ignored")

            val snapshot = SteamCloudLocalSnapshotCollector.collect(tempRoot)

            assertEquals(2, snapshot.size)
            assertEquals("preferences/1_Tuner_CLASS.backUp", snapshot[0].localRelativePath)
            assertEquals("saves/WATCHER.autosave", snapshot[1].localRelativePath)
            assertTrue(snapshot.all { it.sha256.isNotBlank() })
            assertTrue(snapshot.all { it.sha1.isNotBlank() })
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun collect_rejectsManagedRootThatIsNotDirectory() {
        val tempRoot = Files.createTempDirectory("steam-cloud-local-root-test").toFile()
        try {
            File(tempRoot, "preferences").writeText("not-a-directory")

            assertThrows(java.io.IOException::class.java) {
                SteamCloudLocalSnapshotCollector.collect(tempRoot)
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun writeFile(root: File, relativePath: String, content: String) {
        val target = File(root, relativePath.replace('/', File.separatorChar))
        val parent = target.parentFile
        if (parent != null && !parent.isDirectory) {
            parent.mkdirs()
        }
        target.writeText(content, Charsets.UTF_8)
    }
}
