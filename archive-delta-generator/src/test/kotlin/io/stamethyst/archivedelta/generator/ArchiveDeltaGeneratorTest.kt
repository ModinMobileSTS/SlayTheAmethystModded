package io.stamethyst.archivedelta.generator

import io.stamethyst.archivedelta.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.*

internal fun zip(path: Path, contents: Map<String, ByteArray>, stored: Boolean = false, level: Int = 6): Path {
    ZipOutputStream(Files.newOutputStream(path)).use { zip ->
        zip.setLevel(level); zip.setComment("independent target metadata $level")
        contents.forEach { (name, data) ->
            zip.putNextEntry(ZipEntry(name).apply {
                time = 1700000000000L + level * 2000
                if (stored) { method = ZipEntry.STORED; size = data.size.toLong(); crc = CRC32().apply { update(data) }.value }
            })
            zip.write(data); zip.closeEntry()
        }
    }
    return path
}

class ArchiveDeltaGeneratorTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun path(name: String) = temporary.root.toPath().resolve(name)
    private fun failure(block: () -> Unit): ArchiveDeltaException {
        try { block() } catch (error: ArchiveDeltaException) { return error }
        throw AssertionError("Expected rejection")
    }
    private fun inputs(stored: Boolean = false): Pair<Path, Path> {
        val random = ByteArray(16384).also { java.util.Random(882).nextBytes(it) }
        val contents = linkedMapOf("unchanged.bin" to random, "text.txt" to "abcd\n".repeat(1000).toByteArray(),
            "delete" to byteArrayOf(4), "empty/" to byteArrayOf())
        val target = contents - "delete" + mapOf("text.txt" to "abXd\n".repeat(1000).toByteArray(), "新增.txt" to byteArrayOf(7))
        return zip(path("base.jar"), contents, stored) to zip(path("target.jar"), target, stored, 9)
    }
    private fun generate(base: Path, target: Path, options: ArchiveLimits = ArchiveLimits(), control: ArchiveControl = ArchiveControl()) =
        ArchiveDeltaGenerator().generate(base, target, path("delta.zip"), path("work"), options, control)

    private fun assertWorkClean() {
        if (Files.exists(path("work"))) Files.list(path("work")).use { assertEquals(0L, it.count()) }
        Files.list(temporary.root.toPath()).use { assertFalse(it.anyMatch { p -> p.fileName.toString().startsWith("archive-delta-") }) }
    }

    @Test fun exactRoundtripForStoredAndDeflatedArchives() {
        for (stored in listOf(false, true)) {
            val (base, target) = inputs(stored)
            val before = Files.readAllBytes(base); val expected = Files.readAllBytes(target)
            val result = generate(base, target)
            assertEquals(result.manifest, ArchivePackages.inspect(result.path))
            assertEquals(ArchivePhase.entries.toSet(), result.phaseNanos.keys)
            ArchivePackages.buildCandidate(base, result.path, path("result.jar"), path("work"))
            assertArrayEquals(expected, Files.readAllBytes(path("result.jar")))
            assertArrayEquals(before, Files.readAllBytes(base)); assertArrayEquals(expected, Files.readAllBytes(target))
            assertTrue(result.manifest.payload.size < expected.size)
            Files.delete(path("delta.zip")); Files.delete(path("result.jar")); assertWorkClean()
        }
    }

    @Test fun identicalFilesAndEmptyDeflatedEntriesRoundtrip() {
        val base = zip(path("same.jar"), mapOf("empty" to byteArrayOf(), "empty/" to byteArrayOf()))
        val result = generate(base, base)
        ArchivePackages.buildCandidate(base, result.path, path("result.jar"), path("work"))
        assertEquals(-1L, Files.mismatch(base, path("result.jar")))
        assertWorkClean()
    }

    @Test fun seededInputsUseIndependentByteOracle() {
        repeat(8) { seed ->
            val random = java.util.Random(seed.toLong())
            val contents = (0..4).associate { "entry-$it" to ByteArray(random.nextInt(2000)).also(random::nextBytes) }
            val base = zip(path("base-$seed.jar"), contents)
            val target = zip(path("target-$seed.jar"), contents - "entry-1" + ("new" to byteArrayOf(seed.toByte())), level = 9)
            val result = generate(base, target)
            ArchivePackages.buildCandidate(base, result.path, path("result-$seed.jar"), path("work"))
            assertEquals(-1L, Files.mismatch(target, path("result-$seed.jar")))
            Files.delete(result.path)
        }
        assertWorkClean()
    }

    @Test fun refusesOverwriteAndWrongBaseWithoutPublishing() {
        val (base, target) = inputs(); val result = generate(base, target)
        val existing = Files.readAllBytes(result.path)
        failure { generate(base, target) }
        assertArrayEquals(existing, Files.readAllBytes(result.path))
        val wrong = zip(path("wrong.jar"), mapOf("wrong" to byteArrayOf(9)))
        assertEquals(ArchiveFailure.VERIFICATION, failure {
            ArchivePackages.buildCandidate(wrong, result.path, path("candidate.jar"), path("work"))
        }.failure)
        assertFalse(Files.exists(path("candidate.jar")))
        Files.write(path("existing.jar"), byteArrayOf(42))
        failure { ArchivePackages.buildCandidate(base, result.path, path("existing.jar"), path("work")) }
        assertArrayEquals(byteArrayOf(42), Files.readAllBytes(path("existing.jar")))
        assertWorkClean()
    }

    @Test fun packageAndPayloadCorruptionRejected() {
        val (base, target) = inputs(); val result = generate(base, target)
        val entries = ZipFile(result.path.toFile()).use { z -> z.entries().asSequence().associate { e -> e.name to z.getInputStream(e).use { it.readBytes() } } }
        val mutations = listOf(
            entries + ("unexpected" to byteArrayOf()),
            entries + ("payload.deflate" to entries.getValue("payload.deflate").copyOf(4)),
            entries + ("manifest.json" to "{}".toByteArray()),
            entries + ("manifest.json" to entries.getValue("manifest.json").toString(Charsets.UTF_8).replace("deflate-raw", "unknown").toByteArray()),
        )
        mutations.forEachIndexed { i, contents ->
            val bad = zip(path("bad-$i.zip"), contents)
            failure { ArchivePackages.buildCandidate(base, bad, path("bad-result.jar"), path("work")) }
            assertFalse(Files.exists(path("bad-result.jar")))
        }
        assertWorkClean()
    }

    @Test fun limitsRejectBeforePublication() {
        val (base, target) = inputs()
        val options = listOf(ArchiveLimits(maxFileBytes = 10), ArchiveLimits(maxEntryBytes = 5),
            ArchiveLimits(maxExpandedBytes = 20), ArchiveLimits(maxEntries = 1),
            ArchiveLimits(maxBsdiffHeapBytes = 8), ArchiveLimits(maxPatchBytes = 20), ArchiveLimits(maxPackageBytes = 20))
        options.forEach {
            assertEquals(ArchiveFailure.LIMIT, failure { generate(base, target, it) }.failure)
            assertFalse(Files.exists(path("delta.zip"))); assertWorkClean()
        }
    }

    @Test fun cooperativeCancellationAtEveryPhasePreservesInputs() {
        val (base, target) = inputs(); val before = Files.readAllBytes(base)
        ArchivePhase.entries.forEach { stop ->
            var cancel = false
            val control = ArchiveControl(cancelled = { cancel }, progress = { if (it == stop) cancel = true })
            assertEquals(ArchiveFailure.CANCELLED, failure { generate(base, target, control = control) }.failure)
            assertFalse(Files.exists(path("delta.zip")))
            assertArrayEquals(before, Files.readAllBytes(base)); assertWorkClean()
        }
    }

    @Test fun targetChangedDuringGenerationIsNotPublished() {
        val (base, target) = inputs()
        val control = ArchiveControl(progress = { if (it == ArchivePhase.VERIFY) Files.write(target, byteArrayOf(5)) })
        assertEquals(ArchiveFailure.VERIFICATION, failure { generate(base, target, control = control) }.failure)
        assertFalse(Files.exists(path("delta.zip"))); assertWorkClean()
    }

    @Test fun invalidZipAndSymlinkRejected() {
        val (base, target) = inputs()
        Files.write(base, byteArrayOf(1, 2, 3))
        failure { generate(base, target) }
        val link = path("link.jar"); Files.createSymbolicLink(link, target)
        failure { generate(link, target) }
        assertFalse(Files.exists(path("delta.zip"))); assertWorkClean()
    }

    @Test fun preexistingOutputCreatedAtPublicationIsNeverOverwritten() {
        val (base, target) = inputs()
        val control = ArchiveControl(progress = { if (it == ArchivePhase.PUBLISH) Files.write(path("delta.zip"), byteArrayOf(42)) })
        failure { generate(base, target, control = control) }
        assertArrayEquals(byteArrayOf(42), Files.readAllBytes(path("delta.zip"))); assertWorkClean()
    }

    @Test fun targetHashTamperingAndPayloadWithValidHashStillCannotPublishWrongOutput() {
        val (base, target) = inputs(); val result = generate(base, target)
        val entries = ZipFile(result.path.toFile()).use { z -> z.entries().asSequence().associate { e -> e.name to z.getInputStream(e).use { it.readBytes() } } }
        val manifestText = entries.getValue("manifest.json").toString(Charsets.UTF_8)
        val changed = manifestText.replace(result.manifest.target.sha256, "0".repeat(64))
        val badTarget = zip(path("target-hash.zip"), entries + ("manifest.json" to changed.toByteArray()))
        assertEquals(ArchiveFailure.VERIFICATION, failure {
            ArchivePackages.buildCandidate(base, badTarget, path("candidate.jar"), path("work"))
        }.failure)
        assertFalse(Files.exists(path("candidate.jar")))

        // Authored invalid payload with internally consistent transport hash; checks must go beyond hash verification.
        val payload = Files.write(path("invalid.deflate"), entries.getValue("payload.deflate") + byteArrayOf(1))
        val badManifest = result.manifest.copy(payload = ArchiveIO.identity(payload, Long.MAX_VALUE))
        ArchivePackages.write(path("trailing.zip"), badManifest, payload)
        failure { ArchivePackages.buildCandidate(base, path("trailing.zip"), path("candidate.jar"), path("work")) }
        assertFalse(Files.exists(path("candidate.jar"))); assertWorkClean()
    }

    @Test fun duplicateNamesAndCorruptCrcRejected() {
        val (base, target) = inputs()
        zip(base, mapOf("aa" to byteArrayOf(1), "bb" to byteArrayOf(2)))
        val duplicate = Files.readAllBytes(base)
        for (i in 0 until duplicate.size - 1) {
            if (duplicate[i] == 'b'.code.toByte() && duplicate[i + 1] == 'b'.code.toByte()) {
                duplicate[i] = 'a'.code.toByte(); duplicate[i + 1] = 'a'.code.toByte()
            }
        }
        Files.write(base, duplicate)
        assertEquals(ArchiveFailure.INVALID_INPUT, failure { generate(base, target) }.failure)
        zip(base, mapOf("aa" to byteArrayOf(1)))
        val badCrc = Files.readAllBytes(base)
        val central = badCrc.indices.first { i -> i + 3 < badCrc.size && badCrc[i] == 0x50.toByte() &&
            badCrc[i + 1] == 0x4b.toByte() && badCrc[i + 2] == 1.toByte() && badCrc[i + 3] == 2.toByte() }
        badCrc[central + 16] = (badCrc[central + 16].toInt() xor 1).toByte()
        Files.write(base, badCrc)
        assertEquals(ArchiveFailure.INVALID_INPUT, failure { generate(base, target) }.failure)
        assertFalse(Files.exists(path("delta.zip"))); assertWorkClean()
    }
}
