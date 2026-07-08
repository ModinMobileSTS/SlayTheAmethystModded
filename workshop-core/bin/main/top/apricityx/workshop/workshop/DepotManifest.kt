package top.apricityx.workshop.workshop

import top.apricityx.workshop.steam.proto.ContentManifestMetadata
import top.apricityx.workshop.steam.proto.ContentManifestPayload
import top.apricityx.workshop.steam.protocol.readExactlyCompat
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class DepotManifest(
    val depotId: UInt,
    val manifestId: ULong,
    val createdAt: Instant,
    val encryptedCrc: UInt,
    val filenamesEncrypted: Boolean,
    val files: List<ManifestFile>,
) {
    fun uniqueChunks(): List<ManifestChunk> {
        val unique = LinkedHashMap<String, ManifestChunk>()
        files.forEach { file ->
            file.chunks.forEach { chunk ->
                unique.putIfAbsent(chunk.idHex, chunk)
            }
        }
        return unique.values.toList()
    }

    fun decryptFilenames(depotKey: ByteArray): DepotManifest {
        if (!filenamesEncrypted) {
            return this
        }
        require(depotKey.size == 32) { "Depot key must be 32 bytes" }

        val ecb = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(depotKey, "AES"))
        }
        val cbc = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val decryptedFiles = files
            .map { file ->
                file.copy(
                    path = decryptManifestName(file.path, depotKey, ecb, cbc),
                    linkTarget = file.linkTarget?.let { decryptManifestName(it, depotKey, ecb, cbc) },
                )
            }
            .sortedWith { left, right -> left.path.compareTo(right.path, ignoreCase = true) }
        return copy(
            filenamesEncrypted = false,
            files = decryptedFiles,
        )
    }

    private fun decryptManifestName(
        encoded: String,
        depotKey: ByteArray,
        ecb: Cipher,
        cbc: Cipher,
    ): String {
        val sanitizedEncoded = encoded.normalizeEncryptedManifestName()
        val encrypted = runCatching { Base64.getDecoder().decode(sanitizedEncoded) }
            .getOrElse { throw WorkshopDownloadException("Failed to base64 decode encrypted manifest name", it) }
        if (encrypted.size <= 16) {
            throw WorkshopDownloadException("Encrypted manifest name payload was too short")
        }

        val iv = ecb.doFinal(encrypted.copyOfRange(0, 16))
        cbc.init(Cipher.DECRYPT_MODE, SecretKeySpec(depotKey, "AES"), IvParameterSpec(iv))
        val decrypted = runCatching { cbc.doFinal(encrypted.copyOfRange(16, encrypted.size)) }
            .getOrElse { throw WorkshopDownloadException("Failed to decrypt manifest name", it) }
        val trimmed = decrypted.dropLastWhile { it == 0.toByte() }.toByteArray()
        return trimmed.decodeToString().replace('\\', '/')
    }
}

data class ManifestFile(
    val path: String,
    val size: Long,
    val flags: UInt,
    val shaContent: ByteArray,
    val linkTarget: String?,
    val chunks: List<ManifestChunk>,
)

data class ManifestChunk(
    val id: ByteArray,
    val checksum: UInt,
    val offset: Long,
    val compressedLength: Int,
    val uncompressedLength: Int,
) {
    val idHex: String = id.joinToString(separator = "") { byte -> "%02X".format(byte) }
}

object DepotManifestParser {
    private const val PROTOBUF_PAYLOAD_MAGIC = 0x71F617D0u
    private const val PROTOBUF_METADATA_MAGIC = 0x1F4812BEu
    private const val PROTOBUF_SIGNATURE_MAGIC = 0x1B81B817u
    private const val PROTOBUF_END_MAGIC = 0x32C415ABu

    fun parse(bytes: ByteArray): DepotManifest {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        var payload: ContentManifestPayload? = null
        var metadata: ContentManifestMetadata? = null

        while (true) {
            val magic = try {
                readUInt32Le(input)
            } catch (_: EOFException) {
                break
            }
            when (magic) {
                PROTOBUF_PAYLOAD_MAGIC -> {
                    val length = readUInt32Le(input).toInt()
                    payload = ContentManifestPayload.parseFrom(input.readExactlyCompat(length))
                }

                PROTOBUF_METADATA_MAGIC -> {
                    val length = readUInt32Le(input).toInt()
                    metadata = ContentManifestMetadata.parseFrom(input.readExactlyCompat(length))
                }

                PROTOBUF_SIGNATURE_MAGIC -> {
                    val length = readUInt32Le(input).toInt()
                    input.skipBytes(length)
                }

                PROTOBUF_END_MAGIC -> break
                else -> throw WorkshopDownloadException("Unknown depot manifest section magic: 0x${magic.toString(16)}")
            }
        }

        val parsedPayload = payload ?: throw WorkshopDownloadException("Depot manifest payload is missing")
        val parsedMetadata = metadata ?: throw WorkshopDownloadException("Depot manifest metadata is missing")

        return DepotManifest(
            depotId = parsedMetadata.depotId.toUInt(),
            manifestId = parsedMetadata.gidManifest.toULong(),
            createdAt = Instant.ofEpochSecond(parsedMetadata.creationTime.toLong()),
            encryptedCrc = parsedMetadata.crcEncrypted.toUInt(),
            filenamesEncrypted = parsedMetadata.filenamesEncrypted,
            files = parsedPayload.mappingsList.map { mapping ->
                val path = mapping.filename.sanitizeManifestString()
                val linkTarget = mapping.linktarget
                    .sanitizeManifestString()
                    .takeIf(String::isNotBlank)
                ManifestFile(
                    path = if (parsedMetadata.filenamesEncrypted) path else path.replace('\\', '/'),
                    size = mapping.size,
                    flags = mapping.flags.toUInt(),
                    shaContent = mapping.shaContent.toByteArray(),
                    linkTarget = when {
                        linkTarget == null -> null
                        parsedMetadata.filenamesEncrypted -> linkTarget
                        else -> linkTarget.replace('\\', '/')
                    },
                    chunks = mapping.chunksList.map { chunk ->
                        ManifestChunk(
                            id = chunk.sha.toByteArray(),
                            checksum = chunk.crc.toUInt(),
                            offset = chunk.offset.toLong(),
                            compressedLength = chunk.cbCompressed,
                            uncompressedLength = chunk.cbOriginal,
                        )
                    },
                )
            },
        )
    }

    private fun readUInt32Le(input: DataInputStream): UInt {
        val bytes = input.readExactlyCompat(4)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int.toUInt()
    }
}

private fun String.sanitizeManifestString(): String = trim { it <= ' ' || it == '\u0000' }

private fun String.normalizeEncryptedManifestName(): String =
    buildString(length) {
        this@normalizeEncryptedManifestName.forEach { character ->
            if (character > ' ') {
                append(character)
            }
        }
    }
