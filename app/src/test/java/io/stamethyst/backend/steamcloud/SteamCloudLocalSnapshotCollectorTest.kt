package io.stamethyst.backend.steamcloud

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SteamCloudLocalSnapshotCollectorTest {
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
