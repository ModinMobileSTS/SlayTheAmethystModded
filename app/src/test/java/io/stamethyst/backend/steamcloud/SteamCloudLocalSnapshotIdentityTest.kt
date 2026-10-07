package io.stamethyst.backend.steamcloud

import org.junit.Assert.*
import org.junit.Test

class SteamCloudLocalSnapshotIdentityTest {
    private fun entry(path: String, hash: String = "hash", size: Long = 4) =
        SteamCloudLocalFileSnapshotEntry(path, SteamCloudRootKind.SAVES, size, 1, hash)

    @Test fun orderingAndTimestampDoNotChangeContentIdentity() {
        val a = entry("saves/a"); val b = entry("saves/b")
        assertEquals(SteamCloudLocalSnapshotIdentity.compute(listOf(a, b)),
            SteamCloudLocalSnapshotIdentity.compute(listOf(b.copy(lastModifiedMs = 999), a)))
    }

    @Test fun anyContentChangeInvalidatesTheConfirmation() {
        val original = entry("saves/a")
        val identity = SteamCloudLocalSnapshotIdentity.compute(listOf(original))
        assertNotEquals(identity, SteamCloudLocalSnapshotIdentity.compute(listOf(original.copy(sha256 = "new"))))
        assertNotEquals(identity, SteamCloudLocalSnapshotIdentity.compute(listOf(original.copy(fileSize = 5))))
    }

    @Test fun deletionAdditionAndRenameInvalidateTheConfirmation() {
        val original = entry("saves/a")
        val identity = SteamCloudLocalSnapshotIdentity.compute(listOf(original))
        assertNotEquals(identity, SteamCloudLocalSnapshotIdentity.compute(emptyList()))
        assertNotEquals(identity, SteamCloudLocalSnapshotIdentity.compute(listOf(original, entry("saves/b"))))
        assertNotEquals(identity, SteamCloudLocalSnapshotIdentity.compute(listOf(original.copy(localRelativePath = "saves/c"))))
    }
}
