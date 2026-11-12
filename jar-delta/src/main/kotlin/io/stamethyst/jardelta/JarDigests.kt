package io.stamethyst.jardelta

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

object JarDigests {
    fun fileSha256(path: Path): String = Files.newInputStream(path).use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().hex()
    }

    /** v1: SHA-256 over UTF-8-name-sorted (name length u32 BE, name, size u64 BE, raw SHA-256). */
    fun entriesSha256(path: Path, limits: DeltaLimits = DeltaLimits()): String =
        ZipFile(path.toFile()).use { fingerprint(scan(it, limits)) }

    internal fun fingerprint(entries: Map<String, EntryDigest>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.toSortedMap().forEach { (name, entry) ->
            val bytes = name.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
            digest.update(ByteBuffer.allocate(8).putLong(entry.size).array())
            digest.update(entry.sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        }
        return digest.digest().hex()
    }

    internal fun scan(zip: ZipFile, limits: DeltaLimits): Map<String, EntryDigest> {
        val result = linkedMapOf<String, EntryDigest>()
        var total = 0L
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            validateName(entry.name)
            checkDelta(result.size < limits.maxEntries, "Too many ZIP entries")
            checkDelta(!result.containsKey(entry.name), "Duplicate ZIP entry: ${entry.name}")
            val content = zip.getInputStream(entry).use {
                digestStream(it, minOf(limits.maxEntryBytes, limits.maxTotalBytes - total))
            }
            checkDelta(entry.size == content.size && entry.crc == content.crc, "ZIP size/CRC mismatch: ${entry.name}")
            checkDelta(!entry.isDirectory || content.size == 0L, "Nonempty directory entry")
            total += content.size
            result[entry.name] = content
        }
        return result
    }
}

internal data class EntryDigest(val size: Long, val sha256: String, val crc: Long)

internal fun digestStream(stream: InputStream, limit: Long): EntryDigest {
    val digest = MessageDigest.getInstance("SHA-256")
    val crc = CRC32()
    var size = 0L
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        checkDelta(count.toLong() <= limit - size, "Uncompressed data exceeds limit")
        size += count
        digest.update(buffer, 0, count)
        crc.update(buffer, 0, count)
    }
    return EntryDigest(size, digest.digest().hex(), crc.value)
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun validateHash(value: String) = checkDelta(value.matches(Regex("[0-9a-f]{64}")), "Invalid SHA-256")

internal fun validateName(name: String) {
    checkDelta(name.isNotEmpty() && name.length <= 4096 && !name.startsWith('/') &&
        '\\' !in name && ':' !in name && name.none { it.code < 32 || it.code == 127 } &&
        name.removeSuffix("/").split('/').none { it.isEmpty() || it == "." || it == ".." },
        "Unsafe ZIP entry name: $name")
    checkDelta(name.none { Character.isSurrogate(it) }, "Non-BMP ZIP names are not supported in format v1")
}
