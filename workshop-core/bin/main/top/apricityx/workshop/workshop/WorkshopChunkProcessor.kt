package top.apricityx.workshop.workshop

import org.tukaani.xz.LZMAInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object ChunkProcessor {
    fun process(rawChunk: ByteArray, manifestChunk: ManifestChunk, depotKey: ByteArray? = null): ByteArray {
        val decompressed = when {
            depotKey != null -> decryptThenDecompress(rawChunk, depotKey)
            rawChunk.hasPrefix("VSZa".encodeToByteArray()) -> decompressVzstd(rawChunk)
            rawChunk.hasPrefix("VZa".encodeToByteArray()) -> decompressVzip(rawChunk)
            rawChunk.hasPrefix(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)) -> unzipSingleEntry(rawChunk)
            rawChunk.size == manifestChunk.uncompressedLength -> rawChunk
            else -> throw WorkshopDownloadException("Chunk ${manifestChunk.idHex} appears encrypted and no depot key is available")
        }

        if (decompressed.size != manifestChunk.uncompressedLength) {
            throw WorkshopDownloadException("Chunk ${manifestChunk.idHex} length mismatch")
        }
        val checksum = steamAdler32(decompressed)
        if (checksum != manifestChunk.checksum) {
            throw WorkshopDownloadException("Chunk ${manifestChunk.idHex} Adler32 mismatch")
        }
        return decompressed
    }

    private fun decryptThenDecompress(rawChunk: ByteArray, depotKey: ByteArray): ByteArray {
        require(depotKey.size == 32) { "Depot key must be 32 bytes" }
        val ivCipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(depotKey, "AES"))
        }
        val iv = ivCipher.doFinal(rawChunk.copyOfRange(0, 16))
        val cbc = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(depotKey, "AES"), IvParameterSpec(iv))
        }
        val decrypted = cbc.doFinal(rawChunk.copyOfRange(16, rawChunk.size))
        return when {
            decrypted.hasPrefix("VSZa".encodeToByteArray()) -> decompressVzstd(decrypted)
            decrypted.hasPrefix("VZa".encodeToByteArray()) -> decompressVzip(decrypted)
            decrypted.hasPrefix(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)) -> unzipSingleEntry(decrypted)
            else -> throw WorkshopDownloadException("Unsupported decrypted chunk compression")
        }
    }

    private fun decompressVzstd(rawChunk: ByteArray): ByteArray {
        val expectedSize = ByteBuffer.wrap(rawChunk, rawChunk.size - 11, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val compressed = rawChunk.copyOfRange(8, rawChunk.size - 15)
        val destination = ByteArray(expectedSize)
        val written = AndroidZstdBridgeInvoker.decompress(destination, compressed)
        if (written != expectedSize) {
            throw WorkshopDownloadException("VZstd decompression failed")
        }
        return destination
    }

    private fun decompressVzip(rawChunk: ByteArray): ByteArray {
        val footerOffset = rawChunk.size - VZIP_FOOTER_LENGTH
        if (rawChunk.size < VZIP_HEADER_LENGTH + VZIP_FOOTER_LENGTH) {
            throw WorkshopDownloadException("Invalid VZip payload length")
        }

        val header = ByteBuffer.wrap(rawChunk, 0, VZIP_HEADER_LENGTH + VZIP_PROPERTIES_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        if (header.short.toInt() and 0xFFFF != VZIP_HEADER_MAGIC || header.get().toInt() != 'a'.code) {
            throw WorkshopDownloadException("Invalid VZip header")
        }

        // This field is a timestamp for some VZip files and a CRC32 for depot chunks.
        header.int
        val propertyBits = header.get()
        val dictionarySize = header.int
        val compressedOffset = header.position()

        val footer = ByteBuffer.wrap(rawChunk, footerOffset, VZIP_FOOTER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        footer.int // output CRC, not needed because Steam depot chunks are validated with Adler32 afterwards.
        val uncompressedSize = footer.int
        if (footer.short.toInt() and 0xFFFF != VZIP_FOOTER_MAGIC) {
            throw WorkshopDownloadException("Invalid VZip footer")
        }

        val compressed = rawChunk.copyOfRange(compressedOffset, footerOffset)
        return LZMAInputStream(
            ByteArrayInputStream(compressed),
            uncompressedSize.toLong(),
            propertyBits.toByte(),
            dictionarySize,
        ).use { input ->
            val output = ByteArray(uncompressedSize)
            var offset = 0
            while (offset < output.size) {
                val read = input.read(output, offset, output.size - offset)
                if (read == -1) {
                    break
                }
                offset += read
            }

            if (offset != output.size) {
                throw WorkshopDownloadException("VZip decompression truncated")
            }
            output
        }
    }

    private fun unzipSingleEntry(bytes: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val entry = zip.nextEntry ?: throw WorkshopDownloadException("Zip payload was empty")
            val output = ByteArrayOutputStream()
            zip.copyTo(output)
            zip.closeEntry()
            return output.toByteArray()
        }
    }
}

private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean =
    size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

private const val VZIP_HEADER_MAGIC = 0x5A56
private const val VZIP_FOOTER_MAGIC = 0x767A
private const val VZIP_HEADER_LENGTH = 7
private const val VZIP_PROPERTIES_LENGTH = 5
private const val VZIP_FOOTER_LENGTH = 10

private object AndroidZstdBridgeInvoker {
    private val decompressMethod by lazy {
        Class.forName("io.stamethyst.backend.workshop.AndroidZstdBridge")
            .getMethod("decompress", ByteArray::class.java, ByteArray::class.java)
    }

    fun decompress(destination: ByteArray, compressed: ByteArray): Int =
        (decompressMethod.invoke(null, destination, compressed) as Number).toInt()
}
