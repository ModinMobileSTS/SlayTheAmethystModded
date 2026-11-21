package io.stamethyst.backend.steamcloud

import java.io.IOException
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SteamCloudRemoteCommitStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun pending() = SteamCloudRemoteCommitStore.Pending(steamId64 = "1", localEntries = emptyList(),
        priorBaseline = null, expectedRemoteEntries = emptyList(), deletedPaths = emptySet())

    @Test fun failedCleanupFsyncIsNotReportedAsACleanCancellation() {
        val failure = IOException("directory fsync failed")
        val cancelled = CancellationException("before begin")
        val store = SteamCloudRemoteCommitStore(temp.root, "1") {
            assertFalse("fsync must be possible on the cancelled worker", Thread.currentThread().isInterrupted)
            throw failure
        }
        try {
            val error = assertThrows(IOException::class.java) {
                store.prepareBeforeBegin(pending(), null) {
                    Thread.currentThread().interrupt()
                    throw cancelled
                }
            }
            assertSame(failure, error.cause)
            assertSame(cancelled, error.suppressed.single())
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(SteamCloudSyncProcessService.RESULT_FAILURE, SteamCloudSyncProcessService.failureEvent(
                error, SteamCloudSyncMode.MERGE, cancelled = true).first)
        } finally { Thread.interrupted() }
    }

    @Test fun preBeginCleanupWillNotUnlinkAJournalWithAnotherOperationId() {
        val store = SteamCloudRemoteCommitStore(temp.root, "1")
        val other = pending()
        assertThrows(SteamCloudJournalPersistenceException::class.java) {
            store.prepareBeforeBegin(pending(), null) {
                // Simulate unexpected replacement despite the production operation lock.
                store.file.writeText(Json.encodeToString(other))
                throw CancellationException("before begin")
            }
        }
        assertEquals(other, store.read())
    }

    private val legacy get() = File(temp.root, SteamCloudRemoteCommitStore.LEGACY_FILE_NAME)
    // Previous v2 format, including the persisted operation UUID and with no requiresLocalInstall field.
    private val oldText = """{"operationId":"00000000-0000-0000-0000-000000000001","steamId64":"1","localEntries":[],"priorBaseline":null,"expectedRemoteEntries":[],"deletedPaths":[]}"""
    @Test fun migrationIsBytePreservingOwnerOnlyAndRestartable() {
        val a = SteamCloudRemoteCommitStore(temp.root, "1")
        val b = SteamCloudRemoteCommitStore(temp.root, "2")
        legacy.writeText(oldText)
        assertTrue(a.hasPending()); assertFalse(b.hasPending()); assertNull(b.read())
        assertEquals(oldText, legacy.readText()); assertFalse(a.file.exists())
        // Rename completed, reply from the durability barrier lost. A fresh store must
        // still see the old operation, whether the filesystem retained the old or new name.
        val failure = SteamCloudRemoteCommitStore(temp.root, "1") { throw IOException("fsync") }
        assertThrows(SteamCloudJournalPersistenceException::class.java) { failure.read() }
        assertFalse(legacy.exists()); assertEquals(oldText, a.file.readText())
        assertEquals("1", a.read()!!.steamId64); assertNull(b.read())
    }
    @Test fun unreadableOrCorruptLegacyNeverBecomesNoPending() {
        val store = SteamCloudRemoteCommitStore(temp.root, "2")
        for (bad in listOf("{", oldText.replace("\"1\"", "\"../1\""), oldText.replace("operationId", "futureId"),
            oldText.replace("00000000-", "invalid-"))) {
            legacy.writeText(bad)
            assertThrows(SteamCloudJournalPersistenceException::class.java) { store.read() }
            assertThrows(SteamCloudJournalPersistenceException::class.java) { store.hasPending() }
            assertEquals(bad, legacy.readText()); assertFalse(store.file.exists())
        }
        assertTrue(legacy.delete()); assertTrue(legacy.mkdir())
        assertThrows(SteamCloudJournalPersistenceException::class.java) { store.read() }
        assertTrue(legacy.isDirectory)
    }
    @Test fun collidingOrMisfiledJournalIsPreservedEvenForAnExplicitNewIntent() {
        val a = SteamCloudRemoteCommitStore(temp.root, "1")
        val b = SteamCloudRemoteCommitStore(temp.root, "2")
        val other = Json.encodeToString(pending())
        legacy.writeText(oldText); a.file.writeText(other)
        assertThrows(SteamCloudJournalPersistenceException::class.java) { a.prepareBeforeBegin(pending(), null) {} }
        assertNull(b.read()); assertEquals(oldText, legacy.readText()); assertEquals(other, a.file.readText())
        b.file.writeText(other)
        assertThrows(SteamCloudJournalPersistenceException::class.java) { b.read() }
        assertEquals(other, b.file.readText())
    }
    @Test fun accountPathAcceptsOnlyCanonicalPositiveDecimalSteamId64() {
        for (bad in listOf("", "0", "+1", "01", "-1", "２", "1 ", "../1", "1/2", "18446744073709551616")) {
            assertThrows(IllegalArgumentException::class.java) { SteamCloudRemoteCommitStore(temp.root, bad) }
        }
        assertNotEquals(SteamCloudRemoteCommitStore(temp.root, "1").file,
            SteamCloudRemoteCommitStore(temp.root, "18446744073709551615").file)
    }
}
