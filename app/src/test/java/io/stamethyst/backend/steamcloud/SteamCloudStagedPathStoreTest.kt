package io.stamethyst.backend.steamcloud

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCloudStagedPathStoreTest {
    @Test
    fun rollbackRestoresAllTargetsInReverseOrder() {
        val root = Files.createTempDirectory("steam-cloud-staged-path").toFile()
        try {
            val staging = File(root, "staging")
            val live = File(root, "live")
            val rollback = File(root, "rollback")
            write(staging, "preferences/player", "new-player")
            write(staging, "saves/run", "new-run")
            write(live, "preferences/player", "old-player")
            write(live, "saves/run", "old-run")

            val transaction = SteamCloudStagedPathStore.apply(
                replacements = listOf(
                    replacement(staging, live, "preferences"),
                    replacement(staging, live, "saves"),
                ),
                rollbackRoot = rollback,
            )

            assertEquals("new-player", read(live, "preferences/player"))
            assertEquals("new-run", read(live, "saves/run"))
            assertTrue(transaction.rollback().failures.isEmpty())
            assertEquals("old-player", read(live, "preferences/player"))
            assertEquals("old-run", read(live, "saves/run"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rollbackRemovesTargetThatDidNotPreviouslyExist() {
        val root = Files.createTempDirectory("steam-cloud-staged-path-new").toFile()
        try {
            val staging = File(root, "staging")
            val live = File(root, "live")
            val rollback = File(root, "rollback")
            write(staging, "saves/run", "new-run")

            val transaction = SteamCloudStagedPathStore.apply(
                listOf(replacement(staging, live, "saves")),
                rollback,
            )

            assertTrue(File(live, "saves").exists())
            assertTrue(transaction.rollback().failures.isEmpty())
            assertFalse(File(live, "saves").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun replacement(staging: File, target: File, name: String) =
        SteamCloudStagedPathReplacement(File(staging, name), File(target, name))

    private fun write(root: File, path: String, text: String) {
        File(root, path).apply {
            parentFile?.mkdirs()
            writeText(text)
        }
    }

    private fun read(root: File, path: String): String = File(root, path).readText()
}
