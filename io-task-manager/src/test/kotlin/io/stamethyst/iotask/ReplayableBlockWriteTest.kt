package io.stamethyst.iotask

import io.stamethyst.iotask.durable.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class ReplayableBlockWriteTest {
    private fun block(offset: Long, bytes: ByteArray) = WriteBlock(offset, bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })

    @Test fun emptyCandidateIsValid() {
        val root = Files.createTempDirectory("empty-blocks").toFile()
        try {
            ReplayableBlockWrite.open(root, "empty", 0, emptyList()).use {
                assertTrue(it.verified())
                assertEquals(0L, it.candidate.length())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun partialAndInvalidWritesReplayWithoutTouchingOtherBlocks() {
        val root = Files.createTempDirectory("blocks").toFile()
        val first = "first".toByteArray()
        val second = "second".toByteArray()
        val layout = listOf(block(0, first), block(first.size.toLong(), second))
        try {
            ReplayableBlockWrite.open(root, "v1", 11, layout).use {
                it.write(0, first.inputStream())
                try { it.write(1, "xx".byteInputStream()); fail("Short input accepted") } catch (_: IllegalStateException) { }
                assertFalse(it.isComplete(1))
            }
            ReplayableBlockWrite.open(root, "v1", 11, layout).use {
                assertTrue(it.isComplete(0))
                try { it.write(1, "xxxxxx".byteInputStream()); fail("Wrong digest accepted") } catch (_: IllegalStateException) { }
                it.write(1, second.inputStream())
                assertTrue(it.verified())
                assertEquals("firstsecond", it.candidate.readText())
                it.revert(0)
                it.revert(0)
                assertFalse(it.isComplete(0))
                assertTrue(it.isComplete(1))
                assertEquals("firstsecond", it.candidate.readText())
            }
            ReplayableBlockWrite.open(root, "v1", 11, layout).use {
                assertFalse(it.isComplete(0))
                assertTrue(it.isComplete(1))
                it.write(0, first.inputStream())
            }
            File(root, "candidate").writeText("Xirstsecond")
            ReplayableBlockWrite.open(root, "v1", 11, layout).use {
                assertFalse(it.isComplete(0))
                assertTrue(it.isComplete(1))
                it.write(0, first.inputStream())
                assertTrue(it.verified())
            }
            try { ReplayableBlockWrite.open(root, "v2", 11, layout).close(); fail("Changed generation") } catch (_: IllegalArgumentException) { }
        } finally { root.deleteRecursively() }
    }

    @Test fun refusesUnownedCandidateAndInvalidLayout() {
        val root = Files.createTempDirectory("blocks").toFile()
        try {
            File(root, "candidate").writeText("user data")
            try { ReplayableBlockWrite.open(root, "v1", 1, listOf(block(0, byteArrayOf(1)))).close(); fail("Overwrite") } catch (_: IllegalStateException) { }
            assertEquals("user data", File(root, "candidate").readText())
            try { ReplayableBlockWrite.open(root, "v1", 1, listOf(block(1, byteArrayOf(1)))).close(); fail("Gap") } catch (_: IllegalArgumentException) { }
        } finally { root.deleteRecursively() }
    }
}
