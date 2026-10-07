package io.stamethyst.tools.steamcloud

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamSessionFileStoreTest {
    @Test
    fun writesAtomicallyAndParsesValuesContainingEquals() {
        val directory = Files.createTempDirectory("steam-session-test-")
        val file = directory.resolve("session.env")
        try {
            SteamSessionFileStore.write(file, "# secret\nSTEAM_ACCOUNT_NAME=user\nSTEAM_REFRESH_TOKEN=a=b=c\n")
            assertEquals("user", SteamSessionFileStore.read(file)["STEAM_ACCOUNT_NAME"])
            assertEquals("a=b=c", SteamSessionFileStore.read(file)["STEAM_REFRESH_TOKEN"])
            if (runCatching { Files.getPosixFilePermissions(file) }.isSuccess) {
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file),
                )
            }
            assertTrue(Files.list(directory).noneMatch { it.fileName.toString().contains(".tmp-") })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
