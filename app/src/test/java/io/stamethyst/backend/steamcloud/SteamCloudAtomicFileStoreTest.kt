package io.stamethyst.backend.steamcloud

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SteamCloudAtomicFileStoreTest {
    @Test
    fun replaceFile_replacesTargetAndConsumesSource() {
        val directory = Files.createTempDirectory("steam-cloud-atomic-test").toFile()
        val target = directory.resolve("target.txt")
        val source = directory.resolve("source.tmp")
        try {
            target.writeText("old")
            source.writeText("new")

            SteamCloudAtomicFileStore.replaceFile(source, target)

            assertEquals("new", target.readText())
            assertFalse(source.exists())
        } finally {
            source.delete()
            target.delete()
            directory.delete()
        }
    }

    @Test
    fun writeText_keepsPreviousTargetAsBackup() {
        val directory = Files.createTempDirectory("steam-cloud-atomic-test").toFile()
        val target = directory.resolve("manifest.json")
        val backup = SteamCloudAtomicFileStore.backupFile(target)
        try {
            target.writeText("old")

            SteamCloudAtomicFileStore.writeText(target, "new")

            assertEquals("new", target.readText())
            assertEquals("old", backup.readText())
        } finally {
            backup.delete()
            target.delete()
            directory.delete()
        }
    }

    @Test
    fun writeTextWithoutBackup_removesPreviousBackupAndReplacesTarget() {
        val directory = Files.createTempDirectory("steam-cloud-sensitive-atomic-test").toFile()
        val target = directory.resolve("auth-state")
        val backup = SteamCloudAtomicFileStore.backupFile(target)
        try {
            target.writeText("credentials")
            backup.writeText("older-credentials")

            SteamCloudAtomicFileStore.writeTextWithoutBackup(target, "tombstone")

            assertEquals("tombstone", target.readText())
            assertFalse(backup.exists())
        } finally {
            backup.delete()
            target.delete()
            directory.delete()
        }
    }
}
