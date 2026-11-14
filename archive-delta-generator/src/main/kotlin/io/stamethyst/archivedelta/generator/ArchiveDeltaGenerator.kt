package io.stamethyst.archivedelta.generator

import com.google.archivepatcher.generator.*
import com.google.archivepatcher.generator.bsdiff.BsDiffPatchWriter
import com.google.archivepatcher.generator.bsdiff.RandomAccessObject.RandomAccessByteArrayObject
import com.google.archivepatcher.generator.bsdiff.RandomAccessObjectFactory.RandomAccessByteArrayObjectFactory
import com.google.archivepatcher.shared.DefaultDeflateCompatibilityWindow
import com.google.archivepatcher.shared.DeltaFriendlyFile
import io.stamethyst.archivedelta.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipFile

/** Offline generation API. Inputs must remain single-writer owned throughout the call. */
class ArchiveDeltaGenerator {
    fun generate(
        base: Path, target: Path, destination: Path, temporaryDirectory: Path,
        limits: ArchiveLimits = ArchiveLimits(), control: ArchiveControl = ArchiveControl(),
    ): ArchiveGenerationResult = ArchivePackages.guarded {
        val phases = linkedMapOf<ArchivePhase, Long>()
        fun <T> phase(name: ArchivePhase, block: () -> T): T {
            control.phase(name); val start = System.nanoTime()
            return block().also { phases[name] = System.nanoTime() - start }
        }
        ArchiveIO.requireValid(!Files.exists(destination, NOFOLLOW_LINKS), "Destination exists")
        ArchiveIO.workspace(temporaryDirectory) { work ->
            val identities = phase(ArchivePhase.VALIDATE) {
                ArchiveIO.requireValid(DefaultDeflateCompatibilityWindow().isCompatible, "Deflate runtime incompatible", ArchiveFailure.UNSUPPORTED)
                validateZip(base, limits, control); validateZip(target, limits, control)
                ArchiveIO.identity(base, limits.maxFileBytes, control) to ArchiveIO.identity(target, limits.maxFileBytes, control)
            }
            val oldFriendly = work.resolve("old-friendly")
            val newFriendly = work.resolve("new-friendly")
            val plan = phase(ArchivePhase.PLAN) {
                PreDiffExecutor.Builder().readingOriginalFiles(base.toFile(), target.toFile()).build().prepareForDiffing()
            }
            val inverse = phase(ArchivePhase.TRANSFORM) {
                Files.newOutputStream(oldFriendly).use { raw ->
                    DeltaFriendlyFile.generateDeltaFriendlyFile(plan.oldFileUncompressionPlan, base.toFile(),
                        ArchiveIO.bounded(raw, limits.maxExpandedBytes, control), false, 32768)
                }
                Files.newOutputStream(newFriendly).use { raw ->
                    DeltaFriendlyFile.generateDeltaFriendlyFile(plan.newFileUncompressionPlan, target.toFile(),
                        ArchiveIO.bounded(raw, limits.maxExpandedBytes, control), true, 32768)
                }
            }
            val delta = work.resolve("bsdiff")
            phase(ArchivePhase.DIFF) {
                val oldSize = Files.size(oldFriendly); val newSize = Files.size(newFriendly)
                val heap = oldSize + newSize + 4L * (oldSize + 1)
                ArchiveIO.requireValid(heap <= limits.maxBsdiffHeapBytes && 4L * (oldSize + 1) < Int.MAX_VALUE,
                    "BSDiff working arrays exceed budget", ArchiveFailure.LIMIT)
                // Deliberate bounded heap backend: no process-global tmpdir mutation or hidden mmap files.
                RandomAccessByteArrayObject(Files.readAllBytes(oldFriendly)).use { old ->
                    RandomAccessByteArrayObject(Files.readAllBytes(newFriendly)).use { newer ->
                        Files.newOutputStream(delta).use { raw ->
                            BsDiffPatchWriter.generatePatch(old, newer, ArchiveIO.bounded(raw, limits.maxPatchBytes, control),
                                RandomAccessByteArrayObjectFactory(), 16)
                        }
                    }
                }
            }
            val native = work.resolve("native.patch")
            val payload = work.resolve("payload.deflate")
            val manifest = phase(ArchivePhase.PACKAGE) {
                val finalPlan = PreDiffPlan(plan.qualifiedRecommendations, plan.oldFileUncompressionPlan,
                    plan.newFileUncompressionPlan, inverse)
                Files.newOutputStream(native).use { raw ->
                    PatchWriter(finalPlan, Files.size(oldFriendly), Files.size(newFriendly), delta.toFile())
                        .writeV1Patch(ArchiveIO.bounded(raw, limits.maxPatchBytes, control))
                }
                val deflater = Deflater(9, true)
                try {
                    Files.newOutputStream(payload).use { raw ->
                        DeflaterOutputStream(ArchiveIO.bounded(raw, limits.maxPackageBytes, control), deflater).use { compressed ->
                            Files.newInputStream(native).use { ArchiveIO.copy(it, compressed, limits.maxPatchBytes, control) }
                        }
                    }
                } finally { deflater.end() }
                ArchivePackageManifest(base = identities.first, target = identities.second,
                    payload = ArchiveIO.identity(payload, limits.maxPackageBytes, control))
            }
            ArchiveIO.workspace(destination.toAbsolutePath().parent) { outputWork ->
                val candidatePackage = outputWork.resolve("package.zip")
                ArchivePackages.write(candidatePackage, manifest, payload)
                phase(ArchivePhase.VERIFY) {
                    ArchivePackages.buildCandidate(base, candidatePackage, work.resolve("replay.jar"), work, limits, control)
                    ArchiveIO.requireValid(ArchiveIO.identity(target, limits.maxFileBytes, control) == identities.second,
                        "Target changed", ArchiveFailure.VERIFICATION)
                }
                phase(ArchivePhase.PUBLISH) { ArchiveIO.publish(candidatePackage, destination) }
            }
            ArchiveGenerationResult(destination.toAbsolutePath().normalize(), manifest, phases.toMap())
        }
    }

    private fun validateZip(path: Path, limits: ArchiveLimits, control: ArchiveControl) {
        ArchiveIO.requireValid(Files.size(ArchiveIO.regular(path)) <= limits.maxFileBytes, "Archive exceeds size limit", ArchiveFailure.LIMIT)
        ZipFile(path.toFile()).use { zip ->
            val names = HashSet<String>(); var total = 0L
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                control.check()
                val entry = entries.nextElement()
                ArchiveIO.requireValid(names.add(entry.name), "Duplicate ZIP name")
                ArchiveIO.requireValid(names.size <= limits.maxEntries, "Too many entries", ArchiveFailure.LIMIT)
                ArchiveIO.requireValid(entry.method == 0 || entry.method == 8, "Unsupported ZIP compression", ArchiveFailure.UNSUPPORTED)
                val crc = CRC32(); var size = 0L
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        control.check(); val n = input.read(buffer); if (n < 0) break
                        ArchiveIO.requireValid(n.toLong() <= minOf(limits.maxEntryBytes - size, limits.maxExpandedBytes - total),
                            "ZIP expanded content exceeds limit", ArchiveFailure.LIMIT)
                        size += n; total += n; crc.update(buffer, 0, n)
                    }
                }
                ArchiveIO.requireValid(entry.size == size && entry.crc == crc.value, "ZIP size/CRC mismatch")
            }
        }
        // Reject unsupported layouts before expensive parameter inference (including legacy ZIP64/EOCD limitations).
        MinimalZipArchive.listEntries(path.toFile())
    }
}
