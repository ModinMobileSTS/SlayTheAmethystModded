package io.stamethyst.archivedelta

import com.google.archivepatcher.applier.LimitedInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files

class NativePatchTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun rejects(bytes: ByteArray) {
        val root = temporary.root.toPath()
        val base = Files.write(root.resolve("base"), byteArrayOf(1))
        val patch = Files.write(root.resolve("patch"), bytes)
        try {
            NativePatch.apply(base, patch, root.resolve("out"), root, ArchiveLimits(maxEntries = 10), ArchiveControl())
        } catch (_: java.io.IOException) { return }
        throw AssertionError("Expected rejection")
    }
    private fun native(oldCount: Int = 0, offset: Long = 0, length: Long = 0, payload: ByteArray = byteArrayOf()): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeBytes("GFbFv1_0"); out.writeInt(0); out.writeLong(1); out.writeInt(oldCount)
            if (oldCount == 1) { out.writeLong(offset); out.writeLong(length) }
            out.writeInt(0); out.writeInt(1); out.writeByte(0)
            out.writeLong(0); out.writeLong(1); out.writeLong(0); out.writeLong(1)
            out.writeLong(payload.size.toLong()); out.write(payload)
        }
        return bytes.toByteArray()
    }
    private fun integer(value: Long) = ByteArray(8) { (value ushr (8 * it)).toByte() }
    private fun bsdiff(body: ByteArray) = "ENDSLEY/BSDIFF43".toByteArray() + integer(1) + body

    @Test fun unsignedSingleByteReads() {
        val stream = LimitedInputStream(ByteArrayInputStream(byteArrayOf(-1, -128)), 2)
        assertEquals(255, stream.read()); assertEquals(128, stream.read()); assertEquals(-1, stream.read())
    }
    @Test fun badHeaderCountsAndOverflowRejected() {
        rejects(byteArrayOf())
        rejects(native(oldCount = Int.MAX_VALUE))
        rejects(native(oldCount = 1, offset = Long.MAX_VALUE, length = 1))
        rejects(native(oldCount = 1, offset = 1, length = 1))
    }
    @Test fun everyTruncatedBsdiffIntegerRejected() {
        repeat(8) { n -> rejects(native(payload = "ENDSLEY/BSDIFF43".toByteArray() + integer(1).copyOf(n))) }
    }
    @Test fun zeroProgressAndTrailingBytesRejected() {
        rejects(native(payload = bsdiff(integer(0) + integer(0) + integer(0))))
        rejects(native(payload = bsdiff(integer(0) + integer(1) + integer(0) + byteArrayOf(3, 4))))
    }
    @Test fun simpleIndependentLiteralPatchApplies() {
        val root = temporary.root.toPath()
        val base = Files.write(root.resolve("base"), byteArrayOf(1))
        val patch = Files.write(root.resolve("patch"), native(payload = bsdiff(integer(0) + integer(1) + integer(0) + byteArrayOf(9))))
        NativePatch.apply(base, patch, root.resolve("out"), root, ArchiveLimits(), ArchiveControl())
        assertArrayEquals(byteArrayOf(9), Files.readAllBytes(root.resolve("out")))
    }

    @Test fun shortReadsAreSupportedByBsdiff() {
        val root = temporary.root.toPath()
        val base = Files.write(root.resolve("base"), byteArrayOf(1))
        val bytes = bsdiff(integer(0) + integer(1) + integer(0) + byteArrayOf(7))
        val input = object : java.io.FilterInputStream(ByteArrayInputStream(bytes)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
        }
        val output = ByteArrayOutputStream()
        java.io.RandomAccessFile(base.toFile(), "r").use { old ->
            com.google.archivepatcher.applier.bsdiff.BsPatch.applyPatch(old, output, input, 1L)
        }
        assertArrayEquals(byteArrayOf(7), output.toByteArray())
    }
}
