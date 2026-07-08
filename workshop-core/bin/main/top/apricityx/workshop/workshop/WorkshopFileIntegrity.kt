package top.apricityx.workshop.workshop

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

internal sealed interface AssembledFileValidation {
    data object Verified : AssembledFileValidation

    data class ChunkVerifiedHashMismatch(
        val expectedShaHex: String,
        val actualShaHex: String,
    ) : AssembledFileValidation

    data class Invalid(
        val expectedShaHex: String,
        val actualShaHex: String,
        val exactChunkCoverage: Boolean,
        val chunkChecksumsValid: Boolean,
    ) : AssembledFileValidation
}

internal object WorkshopFileIntegrityVerifier {
    fun assess(file: File, manifestFile: ManifestFile): AssembledFileValidation {
        if (manifestFile.shaContent.isEmpty()) {
            return AssembledFileValidation.Verified
        }

        val actualSha = sha1(file)
        if (actualSha.contentEquals(manifestFile.shaContent)) {
            return AssembledFileValidation.Verified
        }

        val exactChunkCoverage = hasExactChunkCoverage(manifestFile)
        val chunkChecksumsValid = exactChunkCoverage && validateChunksAtOffsets(file, manifestFile.chunks)
        return if (chunkChecksumsValid) {
            AssembledFileValidation.ChunkVerifiedHashMismatch(
                expectedShaHex = manifestFile.shaContent.toHexString(),
                actualShaHex = actualSha.toHexString(),
            )
        } else {
            AssembledFileValidation.Invalid(
                expectedShaHex = manifestFile.shaContent.toHexString(),
                actualShaHex = actualSha.toHexString(),
                exactChunkCoverage = exactChunkCoverage,
                chunkChecksumsValid = chunkChecksumsValid,
            )
        }
    }

    private fun sha1(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private fun hasExactChunkCoverage(manifestFile: ManifestFile): Boolean {
        var nextOffset = 0L
        manifestFile.chunks
            .sortedBy(ManifestChunk::offset)
            .forEach { chunk ->
                if (chunk.offset != nextOffset) {
                    return false
                }
                nextOffset += chunk.uncompressedLength.toLong()
            }
        return nextOffset == manifestFile.size
    }

    private fun validateChunksAtOffsets(
        file: File,
        chunks: List<ManifestChunk>,
    ): Boolean {
        RandomAccessFile(file, "r").use { input ->
            chunks.sortedBy(ManifestChunk::offset).forEach { chunk ->
                if (chunk.offset < 0 || chunk.uncompressedLength < 0) {
                    return false
                }
                val buffer = ByteArray(chunk.uncompressedLength)
                input.seek(chunk.offset)
                runCatching { input.readFully(buffer) }.getOrElse { return false }
                if (steamAdler32(buffer) != chunk.checksum) {
                    return false
                }
            }
        }
        return true
    }
}

private fun ByteArray.toHexString(): String = joinToString(separator = "") { "%02x".format(it) }
