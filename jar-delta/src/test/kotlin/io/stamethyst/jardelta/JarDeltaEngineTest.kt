package io.stamethyst.jardelta

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

class JarDeltaEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val original = linkedMapOf(
        "empty/" to byteArrayOf(), "keep.bin" to ByteArray(1024) { it.toByte() },
        "replace.txt" to "old".toByteArray(), "delete.txt" to "gone".toByteArray(),
    )
    private val updated = original.minus("delete.txt") + mapOf(
        "replace.txt" to "new".toByteArray(), "added.bin" to byteArrayOf(0, -1, 2),
    )
    private val operations = listOf(
        DeltaOperation("replace", "replace.txt", "payload/a", sha(updated.getValue("replace.txt"))),
        DeltaOperation("delete", "delete.txt"),
        DeltaOperation("add", "added.bin", "payload/b", sha(updated.getValue("added.bin"))),
    )
    private val payloads get() = mapOf("payload/a" to updated.getValue("replace.txt"), "payload/b" to updated.getValue("added.bin"))
    private fun file(name: String) = temporary.root.toPath().resolve(name)
    private fun base() = writeZip(file("current.jar"), original)
    private fun delta(base: Path, transform: (DeltaManifest) -> DeltaManifest = { it }) =
        patch(file("delta.zip"), base, updated, operations, payloads, transform)
    private fun history() = file("history")

    private fun fails(block: () -> Unit): Exception {
        try { block() } catch (e: Exception) { return e }
        throw AssertionError("Expected rejection")
    }

    private fun noTemps() {
        Files.walk(temporary.root.toPath()).use { paths ->
            assertFalse(paths.anyMatch { it.fileName.toString().startsWith(".jar-delta-") })
        }
    }

    @Test fun applyRestoreAndReuseHistoryWithoutOverwrite() {
        val base = base()
        val initial = Files.readAllBytes(base)
        val delta = delta(base)
        val engine = JarDeltaEngine()
        val result = engine.apply(base, delta, history())
        assertContents(updated, base)
        assertArrayEquals(initial, Files.readAllBytes(result.history.path))
        assertEquals(contentHash(updated), JarDigests.entriesSha256(base))
        assertEquals(Files.size(delta), result.metrics.deltaBytes)
        assertEquals(initial.size.toLong() + Files.size(base), result.metrics.peakExtraBytes)
        val stamp = Files.getLastModifiedTime(result.history.path)
        val patched = Files.readAllBytes(base)
        assertTrue(fails { engine.apply(base, delta, history()) }.message!!.contains("Base digest"))
        assertArrayEquals(patched, Files.readAllBytes(base))
        engine.restore(base, result.history)
        assertArrayEquals(initial, Files.readAllBytes(base))
        engine.apply(base, delta, history())
        assertEquals(stamp, Files.getLastModifiedTime(result.history.path))
        assertArrayEquals(initial, Files.readAllBytes(result.history.path))
        noTemps()
    }

    @Test fun wrongBaseIsRejectedBeforeBackup() {
        val base = base()
        val delta = delta(base)
        writeZip(base, original + ("other" to byteArrayOf(7)))
        val before = Files.readAllBytes(base)
        fails { JarDeltaEngine().apply(base, delta, history()) }
        assertArrayEquals(before, Files.readAllBytes(base))
        assertFalse(Files.exists(history()))
        noTemps()
    }

    @Test fun corruptHistoryIsNeverOverwritten() {
        val base = base()
        val before = Files.readAllBytes(base)
        val delta = delta(base)
        Files.createDirectories(history())
        val backup = history().resolve("${sha(before)}.jar")
        Files.write(backup, byteArrayOf(1, 2, 3))
        fails { JarDeltaEngine().apply(base, delta, history()) }
        assertArrayEquals(before, Files.readAllBytes(base))
        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(backup))
        noTemps()
    }

    @Test fun malformedManifestPayloadAndOperationsAreRejected() {
        val base = base()
        val before = Files.readAllBytes(base)
        val variants: List<(DeltaManifest) -> DeltaManifest> = listOf(
            { it.copy(formatVersion = 99) },
            { it.copy(resultEntriesSha256 = "0".repeat(64)) },
            { it.copy(operations = it.operations + it.operations.first()) },
            { it.copy(operations = it.operations.map { op -> op.copy(type = "execute") }) },
            { it.copy(operations = listOf(DeltaOperation("delete", "absent"))) },
            { it.copy(operations = listOf(DeltaOperation("delete", "../escape"))) },
            { it.copy(operations = it.operations.map { op -> if (op.type == "replace") op.copy(sha256 = "0".repeat(64)) else op }) },
            { it.copy(operations = it.operations.map { op -> if (op.type == "replace") op.copy(type = "add") else op }) },
            { it.copy(operations = it.operations.map { op -> if (op.type == "add") op.copy(type = "replace") else op }) },
        )
        variants.forEach { transform ->
            fails { JarDeltaEngine().apply(base, delta(base, transform), history()) }
            assertArrayEquals(before, Files.readAllBytes(base))
            assertFalse(Files.exists(history()))
        }
        val corrupt = delta(base)
        Files.write(corrupt, byteArrayOf(1, 2, 3))
        fails { JarDeltaEngine().apply(base, corrupt, history()) }
        assertArrayEquals(before, Files.readAllBytes(base))
        noTemps()
    }

    @Test fun missingAndUndeclaredPayloadsAreRejected() {
        val base = base()
        val before = Files.readAllBytes(base)
        listOf(payloads - "payload/a", payloads + ("extra" to byteArrayOf())).forEach { bodies ->
            val delta = patch(file("delta.zip"), base, updated, operations, bodies)
            fails { JarDeltaEngine().apply(base, delta, history()) }
            assertArrayEquals(before, Files.readAllBytes(base))
        }
        noTemps()
    }

    @Test fun limitsApplyToManifestArchiveAndResult() {
        val base = base()
        val before = Files.readAllBytes(base)
        val delta = delta(base)
        listOf(
            DeltaLimits(maxManifestBytes = 1), DeltaLimits(maxEntries = 2),
            DeltaLimits(maxEntryBytes = 32), DeltaLimits(maxTotalBytes = 500),
        ).forEach { limits ->
            fails { JarDeltaEngine().apply(base, delta, history(), limits) }
            assertArrayEquals(before, Files.readAllBytes(base))
        }
        noTemps()
    }

    @Test fun backupOutputAndAtomicCommitFailuresPreserveCurrent() {
        val base = base()
        val before = Files.readAllBytes(base)
        val delta = delta(base)
        listOf("history", "candidate", "commit").forEach { stage ->
            val access = object : FileAccess {
                override fun output(path: Path): OutputStream {
                    val isHistory = path.fileName.toString().startsWith(".jar-delta-history-")
                    if ((stage == "history" && isHistory) || (stage == "candidate" && !isHistory)) {
                        val real = SystemFileAccess.output(path)
                        return object : java.io.FilterOutputStream(real) {
                            override fun write(b: ByteArray, off: Int, len: Int) {
                                out.write(b, off, minOf(3, len))
                                throw IOException("Injected disk-full write failure")
                            }
                            override fun write(b: Int) { throw IOException("Injected write failure") }
                        }
                    }
                    return SystemFileAccess.output(path)
                }
                override fun commit(candidate: Path, current: Path) {
                    throw java.nio.file.AtomicMoveNotSupportedException(candidate.toString(), current.toString(), "Injected")
                }
            }
            fails { JarDeltaEngine(access).apply(base, delta, history()) }
            assertArrayEquals(before, Files.readAllBytes(base))
            if (stage == "history") assertFalse(Files.exists(history().resolve("${sha(before)}.jar")))
            noTemps()
        }
    }

    @Test fun invalidGeneratedBytesAreNotCommitted() {
        val base = base()
        val before = Files.readAllBytes(base)
        val delta = delta(base)
        val access = object : FileAccess {
            override fun output(path: Path): OutputStream {
                val stream = SystemFileAccess.output(path)
                if (path.fileName.toString().startsWith(".jar-delta-history-")) return stream
                return object : java.io.FilterOutputStream(stream) {
                    override fun close() {
                        super.close()
                        Files.write(path, byteArrayOf(0, 1, 2))
                    }
                }
            }
            override fun commit(candidate: Path, current: Path) { throw AssertionError("Must not commit") }
        }
        fails { JarDeltaEngine(access).apply(base, delta, history()) }
        assertArrayEquals(before, Files.readAllBytes(base))
        noTemps()
    }

    @Test fun restoreRejectsCorruptionAndCommitFailureWithoutChangingCurrent() {
        val base = base()
        val delta = delta(base)
        val result = JarDeltaEngine().apply(base, delta, history())
        val patched = Files.readAllBytes(base)
        val historyBytes = Files.readAllBytes(result.history.path)
        val access = object : FileAccess by SystemFileAccess {
            override fun commit(candidate: Path, current: Path) { throw IOException("Injected commit failure") }
        }
        fails { JarDeltaEngine(access).restore(base, result.history) }
        assertArrayEquals(patched, Files.readAllBytes(base))
        Files.write(result.history.path, byteArrayOf(42))
        fails { JarDeltaEngine().restore(base, result.history) }
        assertArrayEquals(patched, Files.readAllBytes(base))
        Files.write(result.history.path, historyBytes)
        JarDeltaEngine().restore(base, result.history)
        assertArrayEquals(historyBytes, Files.readAllBytes(base))
        noTemps()
    }

    @Test fun duplicateAndUnsafeZipEntriesAndSignaturesAreRejected() {
        val unsafe = listOf("../x", "/x", "a\\b", "a//b", "a/./b", "META-INF/TEST.SF")
        unsafe.forEach { name ->
            val base = writeZip(file("current.jar"), mapOf(name to byteArrayOf(1)))
            val delta = patch(file("delta.zip"), base, emptyMap(), emptyList(), emptyMap())
            val before = Files.readAllBytes(base)
            fails { JarDeltaEngine().apply(base, delta, history()) }
            assertArrayEquals(before, Files.readAllBytes(base))
        }
        val base = writeZip(file("current.jar"), mapOf("aa" to byteArrayOf(1), "bb" to byteArrayOf(2)))
        val bytes = Files.readAllBytes(base)
        // Rename both local/central entry records without relying on ZipOutputStream accepting duplicates.
        for (i in 0 until bytes.size - 1) if (bytes[i] == 'b'.code.toByte() && bytes[i + 1] == 'b'.code.toByte()) {
            bytes[i] = 'a'.code.toByte(); bytes[i + 1] = 'a'.code.toByte()
        }
        Files.write(base, bytes)
        val delta = patch(file("delta.zip"), base, emptyMap(), emptyList(), emptyMap())
        assertTrue(fails { JarDeltaEngine().apply(base, delta, history()) }.message!!.contains("Duplicate"))
        assertArrayEquals(bytes, Files.readAllBytes(base))
        noTemps()
    }

    @Test fun baseAndHistorySymlinksAreRejected() {
        val base = base()
        val delta = delta(base)
        val link = file("link.jar")
        Files.createSymbolicLink(link, base)
        fails { JarDeltaEngine().apply(link, delta, history()) }
        Files.createDirectories(history())
        Files.createSymbolicLink(history().resolve("${sha(Files.readAllBytes(base))}.jar"), base)
        fails { JarDeltaEngine().apply(base, delta, history()) }
        noTemps()
    }

    @Test fun largeBinaryEntriesAndNonAsciiNamesArePreserved() {
        val contents = mapOf("素材/数据.bin" to ByteArray(4 * 1024 * 1024).also { java.util.Random(7).nextBytes(it) })
        val base = writeZip(file("large.jar"), contents)
        val result = contents + ("new" to byteArrayOf(5))
        val delta = patch(file("delta.zip"), base, result,
            listOf(DeltaOperation("add", "new", "payload/a", sha(byteArrayOf(5)))), mapOf("payload/a" to byteArrayOf(5)))
        JarDeltaEngine().apply(base, delta, history())
        assertContents(result, base)
        noTemps()
    }

    @Test fun backupCopyMustMatchItsClaimedDigestBeforePublication() {
        val base = base()
        val initial = Files.readAllBytes(base)
        val delta = delta(base)
        val access = object : FileAccess by SystemFileAccess {
            override fun output(path: Path): OutputStream {
                val real = SystemFileAccess.output(path)
                return object : java.io.FilterOutputStream(real) {
                    override fun close() {
                        super.close()
                        Files.write(path, byteArrayOf(9))
                    }
                }
            }
        }
        assertTrue(fails { JarDeltaEngine(access).apply(base, delta, history()) }.message!!.contains("History copy"))
        assertArrayEquals(initial, Files.readAllBytes(base))
        assertFalse(Files.exists(history().resolve("${sha(initial)}.jar")))
        noTemps()
    }

    @Test fun restorePartialWriteDoesNotDamageCurrentOrHistory() {
        val base = base()
        val initial = Files.readAllBytes(base)
        val delta = delta(base)
        val result = JarDeltaEngine().apply(base, delta, history())
        val patched = Files.readAllBytes(base)
        val access = object : FileAccess by SystemFileAccess {
            override fun output(path: Path): OutputStream {
                val real = SystemFileAccess.output(path)
                return object : java.io.FilterOutputStream(real) {
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        out.write(b, off, 1)
                        throw IOException("Injected restore disk-full")
                    }
                }
            }
        }
        fails { JarDeltaEngine(access).restore(base, result.history) }
        assertArrayEquals(patched, Files.readAllBytes(base))
        assertArrayEquals(initial, Files.readAllBytes(result.history.path))
        noTemps()
    }

    @Test fun changesToCurrentDetectedBeforeCommitAreNotOverwritten() {
        val base = base()
        val initial = Files.readAllBytes(base)
        val delta = delta(base)
        val external = "external writer".toByteArray()
        val access = object : FileAccess by SystemFileAccess {
            override fun output(path: Path): OutputStream {
                val real = SystemFileAccess.output(path)
                if (path.fileName.toString().startsWith(".jar-delta-history-")) return real
                return object : java.io.FilterOutputStream(real) {
                    override fun close() {
                        super.close()
                        Files.write(base, external)
                    }
                }
            }
            override fun commit(candidate: Path, current: Path) { throw AssertionError("Must not commit") }
        }
        assertTrue(fails { JarDeltaEngine(access).apply(base, delta, history()) }.message!!.contains("Current JAR changed"))
        assertArrayEquals(external, Files.readAllBytes(base))
        assertArrayEquals(initial, Files.readAllBytes(history().resolve("${sha(initial)}.jar")))
        noTemps()
    }

    @Test fun existenceRulesAreCheckedForOtherwiseValidPackages() {
        val base = base()
        val initial = Files.readAllBytes(base)
        val cases = listOf(
            listOf(DeltaOperation("add", "keep.bin", "payload/a", sha(byteArrayOf(1)))),
            listOf(DeltaOperation("replace", "missing", "payload/a", sha(byteArrayOf(1)))),
            listOf(DeltaOperation("delete", "missing")),
        )
        cases.forEach { ops ->
            val payloads = if (ops[0].type == "delete") emptyMap() else mapOf("payload/a" to byteArrayOf(1))
            val delta = patch(file("delta.zip"), base, original, ops, payloads)
            assertTrue(fails { JarDeltaEngine().apply(base, delta, history()) }.message!!.contains("existence mismatch"))
            assertArrayEquals(initial, Files.readAllBytes(base))
        }
        noTemps()
    }

    @Test fun outputLimitsAreCheckedEvenIfInputsIndividuallyFit() {
        val base = writeZip(file("base.jar"), mapOf("old" to ByteArray(600)))
        val result = mapOf("old" to ByteArray(600), "new" to ByteArray(600))
        val delta = patch(file("delta.zip"), base, result,
            listOf(DeltaOperation("add", "new", "payload/a", sha(ByteArray(600)))), mapOf("payload/a" to ByteArray(600)))
        val initial = Files.readAllBytes(base)
        val failure = fails { JarDeltaEngine().apply(base, delta, history(), DeltaLimits(maxTotalBytes = 1100)) }
        assertTrue(failure.message!!.contains("Result exceeds size limit"))
        assertArrayEquals(initial, Files.readAllBytes(base))
        noTemps()
    }

    @Test fun crcMismatchIsRejectedWithoutBackup() {
        val base = base()
        val bytes = Files.readAllBytes(base)
        // Corrupt CRC in the first central-directory header (offset 16), not compressed contents.
        val central = bytes.indices.first { i -> i + 3 < bytes.size &&
            bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() &&
            bytes[i + 2] == 0x01.toByte() && bytes[i + 3] == 0x02.toByte() }
        bytes[central + 16] = (bytes[central + 16].toInt() xor 1).toByte()
        Files.write(base, bytes)
        val delta = patch(file("delta.zip"), base, original, emptyList(), emptyMap())
        assertTrue(fails { JarDeltaEngine().apply(base, delta, history()) }.message!!.contains("CRC mismatch"))
        assertArrayEquals(bytes, Files.readAllBytes(base))
        assertFalse(Files.exists(history()))
        noTemps()
    }

    @Test fun seededArbitraryArchivesFollowTheSameEntryRules() {
        repeat(24) { seed ->
            val random = java.util.Random(seed.toLong())
            val contents = (0 until 12).associate { index ->
                "group-$seed/entry-$index.bin" to ByteArray(random.nextInt(8192)).also(random::nextBytes)
            }
            val names = contents.keys.toList()
            val replacement = ByteArray(random.nextInt(8192)).also(random::nextBytes)
            val addition = ByteArray(random.nextInt(8192)).also(random::nextBytes)
            val addedName = "new-$seed.bin"
            val expected = contents.minus(names[1]) + mapOf(names[0] to replacement, addedName to addition)
            val base = writeZip(file("seed-$seed.jar"), contents)
            val originalBytes = Files.readAllBytes(base)
            val delta = patch(file("seed-$seed.zip"), base, expected, listOf(
                DeltaOperation("replace", names[0], "payload/a", sha(replacement)),
                DeltaOperation("delete", names[1]),
                DeltaOperation("add", addedName, "payload/b", sha(addition)),
            ), mapOf("payload/a" to replacement, "payload/b" to addition))
            val engine = JarDeltaEngine()
            val applied = engine.apply(base, delta, history())
            assertContents(expected, base)
            assertEquals(sha(originalBytes), applied.history.sha256)
            engine.restore(base, applied.history)
            assertArrayEquals(originalBytes, Files.readAllBytes(base))
        }
        noTemps()
    }
}
