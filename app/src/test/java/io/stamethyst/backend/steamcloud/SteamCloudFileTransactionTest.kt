package io.stamethyst.backend.steamcloud

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SteamCloudFileTransactionTest {
    @get:Rule val temp = TemporaryFolder()
    private val root get() = temp.root
    private val transactions get() = File(root, "transactions")
    private fun file(path: String, content: String): File = File(root, path).also { it.parentFile.mkdirs(); it.writeText(content) }
    private fun prepare(vararg replacements: SteamCloudPathReplacement) =
        SteamCloudFileTransaction.prepare(transactions, root, replacements.toList())
    private fun recover() = SteamCloudFileTransaction.recoverAll(transactions, root)

    @Test fun commitReplacesFilesAndMetadataTogether() {
        val save = file("live/save", "old"); val mode = file("mode", "cloud")
        val tx = prepare(SteamCloudPathReplacement(file("stage/save", "new"), save),
            SteamCloudPathReplacement(file("stage/mode", "local"), mode))
        tx.apply(); tx.commit(); recover()
        assertEquals("new", save.readText()); assertEquals("local", mode.readText())
    }
    @Test fun prepareDoesNotTouchLiveFiles() {
        val save = file("live/save", "old")
        prepare(SteamCloudPathReplacement(file("stage/save", "new"), save))
        assertEquals("old", save.readText()); recover(); assertEquals("old", save.readText())
    }
    @Test fun processDeathAfterApplyRestoresAllOriginals() {
        val save = file("live/save", "old"); val mode = file("mode", "cloud")
        prepare(SteamCloudPathReplacement(file("stage/save", "new"), save),
            SteamCloudPathReplacement(file("stage/mode", "local"), mode)).apply()
        recover(); recover()
        assertEquals("old", save.readText()); assertEquals("cloud", mode.readText())
    }
    @Test fun modeRecoveryPreservesLaterDisableToggle() {
        val control = file("control.json", """{"mode":"independent","disabled":false}""")
        prepare(SteamCloudPathReplacement(file("stage/control", """{"mode":"cloud","disabled":false}"""),
            control, rollbackJsonKeys = setOf("mode"))).apply()
        control.writeText("""{"mode":"cloud","disabled":true}""")
        recover(); recover()
        assertEquals("""{"disabled":true,"mode":"independent"}""", control.readText())
    }
    @Test fun interruptedValidationStillRollsBackDurably() {
        val target = file("live/save", "old")
        try {
            SteamCloudFileTransaction.execute(transactions, root,
                listOf(SteamCloudPathReplacement(file("stage/save", "new"), target))) {
                Thread.currentThread().interrupt()
                throw java.util.concurrent.CancellationException("cancel")
            }
            fail()
        } catch (_: java.util.concurrent.CancellationException) {
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        assertEquals("old", target.readText())
    }
    @Test fun processDeathHalfwayThroughApplyIsRecoverable() {
        val a = file("live/a", "old-a"); val b = file("live/b", "old-b")
        val tx = prepare(SteamCloudPathReplacement(file("stage/a", "new-a"), a), SteamCloudPathReplacement(null, b))
        try { tx.apply { throw AssertionError("simulated process death") }; fail() } catch (_: AssertionError) { }
        assertEquals("new-a", a.readText()); recover()
        assertEquals("old-a", a.readText()); assertEquals("old-b", b.readText())
    }
    @Test fun rollbackRemovesNewFilesAndRestoresDeletions() {
        val old = file("live/old", "old"); val new = File(root, "live/new")
        val tx = prepare(SteamCloudPathReplacement(null, old), SteamCloudPathReplacement(file("stage/new", "new"), new))
        tx.apply(); tx.rollback()
        assertEquals("old", old.readText()); assertFalse(new.exists())
    }
    @Test fun directoryReplacementRollsBackRecursively() {
        file("live/saves/nested/a", "old"); file("stage/saves/b", "new")
        val tx = prepare(SteamCloudPathReplacement(File(root, "stage/saves"), File(root, "live/saves")))
        tx.apply(); recover()
        assertEquals("old", File(root, "live/saves/nested/a").readText()); assertFalse(File(root, "live/saves/b").exists())
    }
    @Test fun missingBackupBlocksRecoveryAndPreservesJournal() {
        val target = file("live/save", "old")
        prepare(SteamCloudPathReplacement(file("stage/save", "new"), target)).apply()
        val directory = transactions.listFiles()!!.single()
        File(directory, "old/0").delete()
        try { recover(); fail() } catch (_: SteamCloudRecoveryException) { }
        assertTrue(File(directory, "journal.json").exists()); assertEquals("new", target.readText())
    }
    @Test fun missingStagedFileNeverBecomesDeletion() {
        val target = file("live/save", "old")
        val tx = prepare(SteamCloudPathReplacement(file("stage/save", "new"), target))
        File(transactions.listFiles()!!.single(), "new/0").delete()
        try { tx.apply(); fail() } catch (_: IOException) { }
        recover(); assertEquals("old", target.readText())
    }
    @Test(expected = IllegalArgumentException::class) fun overlappingTargetsAreRejected() {
        prepare(SteamCloudPathReplacement(null, File(root, "live")), SteamCloudPathReplacement(null, File(root, "live/save")))
    }
    @Test(expected = IllegalArgumentException::class) fun escapingTargetsAreRejected() {
        prepare(SteamCloudPathReplacement(null, File(root, "../escape")))
    }
    @Test(expected = IllegalArgumentException::class) fun symlinkTargetsAreRejected() {
        val directory = temp.newFolder("real")
        Files.createSymbolicLink(File(root, "link").toPath(), directory.toPath())
        prepare(SteamCloudPathReplacement(null, File(root, "link/save")))
    }
    @Test fun failedValidationRollsBackBeforeReturning() {
        val target = file("live/save", "old")
        try { SteamCloudFileTransaction.execute(transactions, root,
            listOf(SteamCloudPathReplacement(file("stage/save", "new"), target))) { throw IOException("validation") }; fail()
        } catch (_: IOException) { }
        assertEquals("old", target.readText())
    }
}
