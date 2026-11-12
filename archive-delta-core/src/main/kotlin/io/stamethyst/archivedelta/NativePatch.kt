package io.stamethyst.archivedelta

import com.google.archivepatcher.applier.PartiallyCompressingOutputStream
import com.google.archivepatcher.applier.bsdiff.BsPatch
import com.google.archivepatcher.shared.DeltaFriendlyFile
import com.google.archivepatcher.shared.JreDeflateParameters
import com.google.archivepatcher.shared.TypedRange
import java.io.DataInputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

/** Strict bounded GFbFv1_0 profile. Legacy Java applier entry points bypass this validation. */
object NativePatch {
    private data class Plan(
        val oldSize: Long,
        val newSize: Long,
        val oldRanges: List<TypedRange<Void>>,
        val newRanges: List<TypedRange<JreDeflateParameters>>,
        val deltaLength: Long,
    )

    fun apply(base: Path, patch: Path, output: Path, work: Path, limits: ArchiveLimits, control: ArchiveControl) {
        ArchiveIO.requireValid(Files.size(patch) <= limits.maxPatchBytes, "Patch exceeds limit", ArchiveFailure.LIMIT)
        DataInputStream(Files.newInputStream(patch).buffered()).use { input ->
            val plan = read(input, Files.size(base), limits)
            val old = work.resolve("apply-old-friendly")
            Files.newOutputStream(old).use { raw ->
                DeltaFriendlyFile.generateDeltaFriendlyFile(plan.oldRanges, base.toFile(),
                    ArchiveIO.bounded(raw, plan.oldSize, control), false, 32768)
            }
            ArchiveIO.requireValid(Files.size(old) == plan.oldSize, "Old friendly length mismatch")
            val delta = work.resolve("apply-bsdiff")
            Files.newOutputStream(delta).use { out ->
                val buffer = ByteArray(65536)
                var left = plan.deltaLength
                while (left > 0) {
                    control.check()
                    val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                    ArchiveIO.requireValid(n > 0, "Truncated native delta")
                    out.write(buffer, 0, n); left -= n
                }
                ArchiveIO.requireValid(input.read() == -1, "Trailing native patch bytes")
            }
            Files.newOutputStream(output).use { raw ->
                val recompress = PartiallyCompressingOutputStream(plan.newRanges,
                    ArchiveIO.bounded(raw, limits.maxFileBytes, control), 32768)
                try {
                    RandomAccessFile(old.toFile(), "r").use { oldData ->
                        Files.newInputStream(delta).use { bytes ->
                            BsPatch.applyPatch(oldData, ArchiveIO.bounded(recompress, plan.newSize, control), bytes, plan.newSize)
                        }
                    }
                    recompress.finishAndValidate(plan.newSize)
                } finally { recompress.release() }
            }
        }
    }

    private fun read(input: DataInputStream, baseSize: Long, limits: ArchiveLimits): Plan {
        fun positive(max: Long): Long = input.readLong().also {
            ArchiveIO.requireValid(it in 0..max, "Native length/offset exceeds bounds", ArchiveFailure.LIMIT)
        }
        fun count(): Int = input.readInt().also {
            ArchiveIO.requireValid(it in 0..limits.maxEntries, "Native plan count exceeds limit", ArchiveFailure.LIMIT)
        }
        val magic = ByteArray(8); input.readFully(magic)
        ArchiveIO.requireValid(magic.contentEquals("GFbFv1_0".toByteArray(Charsets.US_ASCII)), "Bad native magic")
        ArchiveIO.requireValid(input.readInt() == 0, "Unsupported native flags", ArchiveFailure.UNSUPPORTED)
        val oldSize = positive(limits.maxExpandedBytes)
        var end = 0L
        val old = List(count()) {
            val offset = positive(baseSize); val length = positive(baseSize)
            ArchiveIO.requireValid(offset >= end && length <= baseSize - offset, "Invalid old range")
            end = offset + length
            TypedRange<Void>(offset, length, null)
        }
        end = 0
        val newer = List(count()) {
            val offset = positive(limits.maxExpandedBytes); val length = positive(limits.maxExpandedBytes)
            ArchiveIO.requireValid(offset >= end && length <= limits.maxExpandedBytes - offset, "Invalid new range")
            end = offset + length
            val window = input.readUnsignedByte(); val level = input.readUnsignedByte()
            val strategy = input.readUnsignedByte(); val nowrap = input.readUnsignedByte()
            ArchiveIO.requireValid(window == 0 && level in 1..9 && strategy in 0..2 && nowrap in 0..1, "Unsupported compression parameters")
            TypedRange(offset, length, JreDeflateParameters.of(level, strategy, nowrap == 1))
        }
        ArchiveIO.requireValid(input.readInt() == 1 && input.readUnsignedByte() == 0, "Unsupported delta type")
        ArchiveIO.requireValid(positive(0) == 0L && positive(limits.maxExpandedBytes) == oldSize, "Old work length mismatch")
        positive(0)
        val newSize = positive(limits.maxExpandedBytes)
        ArchiveIO.requireValid(end <= newSize, "Recompression beyond new size")
        return Plan(oldSize, newSize, old, newer, positive(limits.maxPatchBytes))
    }
}
