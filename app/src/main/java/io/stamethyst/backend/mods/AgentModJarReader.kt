package io.stamethyst.backend.mods

import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Bounded, on-demand access to a mod archive.
 *
 * The normal path uses the platform ZipFile for random access. Android's ZipFile rejects archives
 * with duplicate entry names, so every operation has a sequential Commons Compress fallback. The
 * fallback deliberately returns the first matching entry, matching the class-only overlay used by
 * the compiler and avoiding a full extracted workspace.
 */
object AgentModJarReader {
    private const val MAX_CLASS_BYTES = 64L * 1024L * 1024L
    private const val BUFFER_SIZE = 32 * 1024

    data class EntryRange(
        val totalBytes: Long,
        val offset: Long,
        val bytes: ByteArray,
        val truncated: Boolean,
    )

    fun sha256(file: File): String {
        require(file.isFile) { "Source JAR is missing: ${file.absolutePath}" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    }

    fun classEntryName(binaryName: String): String? = classEntryCandidates(binaryName).firstOrNull()

    fun classEntryName(jar: File, binaryName: String): String? {
        val candidates = classEntryCandidates(binaryName)
        if (candidates.isEmpty()) return null
        val platform = runCatching {
            ZipFile(jar).use { zip -> candidates.firstOrNull { zip.getEntry(it) != null } }
        }.getOrNull()
        return platform ?: findFirstMatchingEntry(jar, candidates.toSet())
    }

    private fun classEntryCandidates(binaryName: String): List<String> {
        val normalized = binaryName.trim()
            .removePrefix("L")
            .removeSuffix(";")
            .replace('/', '.')
        if (normalized.isEmpty() || normalized.contains("..")) return emptyList()
        val parts = normalized.split('.').filter(String::isNotEmpty)
        if (parts.isEmpty()) return emptyList()
        return (parts.size - 1 downTo 0).map { split ->
            val packageParts = parts.subList(0, split)
            val classParts = parts.subList(split, parts.size)
            val className = classParts.joinToString("$")
            if (className.any { it == '/' || it == '\\' || it == ':' }) return@map ""
            val packageName = packageParts.joinToString("/")
            (if (packageName.isEmpty()) className else "$packageName/$className") + ".class"
        }.filter(String::isNotEmpty)
    }

    fun readClassBytes(jar: File, binaryName: String): ByteArray? {
        val candidates = classEntryCandidates(binaryName)
        if (candidates.isEmpty()) return null
        val platform = runCatching {
            ZipFile(jar).use { zip ->
                candidates.firstNotNullOfOrNull { name ->
                    val entry = zip.getEntry(name) ?: return@firstNotNullOfOrNull null
                    zip.getInputStream(entry).use { readAllBounded(it, MAX_CLASS_BYTES) }
                }
            }
        }.getOrNull()
        if (platform != null) return platform
        val names = candidates.toHashSet()
        return runCatching {
            openArchiveStream(jar).use { input ->
                while (true) {
                    val entry = input.nextZipEntry ?: break
                    if (entry.name in names) return@use readAllBounded(input, MAX_CLASS_BYTES)
                }
                null
            }
        }.getOrNull()
    }

    fun readClassEntryBytes(jar: File, internalName: String): ByteArray? {
        val normalized = internalName.removeSuffix(".class").replace('.', '/') + ".class"
        return readEntryBytes(jar, normalized, MAX_CLASS_BYTES)
            ?: readClassBytes(jar, internalName.removeSuffix(".class").replace('/', '.'))
    }

    fun hasClassEntry(jar: File, internalName: String): Boolean {
        val candidates = classEntryCandidates(internalName)
        if (candidates.isEmpty()) return false
        val platform = runCatching {
            ZipFile(jar).use { zip -> candidates.any { zip.getEntry(it) != null } }
        }.getOrNull()
        return platform ?: (findFirstMatchingEntry(jar, candidates.toSet()) != null)
    }

    fun classEntries(jar: File): List<String> = listEntries(jar)
        .asSequence()
        .filter { it.endsWith(".class", ignoreCase = true) && !it.startsWith("META-INF/") }
        .map { it.substring(0, it.length - ".class".length) }
        .toList()

    fun listEntries(jar: File): List<String> {
        require(jar.isFile) { "JAR is missing: ${jar.absolutePath}" }
        val platform = runCatching {
            ZipFile(jar).use { zip ->
                zip.entries().asSequence().map { it.name }.toList()
            }
        }.getOrNull()
        if (platform != null) return platform

        val names = ArrayList<String>()
        openArchiveStream(jar).use { input ->
            while (true) {
                val entry = input.nextZipEntry ?: break
                names += entry.name
            }
        }
        return names
    }

    fun readEntryBytes(jar: File, entryName: String, maxBytes: Long = MAX_CLASS_BYTES): ByteArray? {
        val normalized = normalizeEntry(entryName) ?: return null
        val platform = runCatching {
            ZipFile(jar).use { zip ->
                val entry = zip.getEntry(normalized) ?: return@use null
                zip.getInputStream(entry).use { input -> readAllBounded(input, maxBytes) }
            }
        }.getOrNull()
        if (platform != null) return platform

        return runCatching {
            openArchiveStream(jar).use { input ->
                while (true) {
                    val entry = input.nextZipEntry ?: break
                    if (entry.name == normalized) return@use readAllBounded(input, maxBytes)
                }
                null
            }
        }.getOrNull()
    }

    fun readEntryRange(
        jar: File,
        entryName: String,
        offset: Long,
        limit: Int,
    ): EntryRange? {
        require(offset >= 0L) { "offset must be non-negative" }
        require(offset <= MAX_RESOURCE_BYTES) { "offset exceeds the read limit" }
        require(limit > 0) { "limit must be positive" }
        val normalized = normalizeEntry(entryName) ?: return null
        val platform = runCatching {
            ZipFile(jar).use { zip ->
                val entry = zip.getEntry(normalized) ?: return@use null
                zip.getInputStream(entry).use { input ->
                    rangeFromStream(input, entry.size.takeIf { it >= 0L }, offset, limit)
                }
            }
        }.getOrNull()
        if (platform != null) return platform

        return runCatching {
            openArchiveStream(jar).use { input ->
                while (true) {
                    val entry = input.nextZipEntry ?: break
                    if (entry.name == normalized) {
                        return@use rangeFromStream(
                            input,
                            entry.size.takeIf { it >= 0L },
                            offset,
                            limit,
                        )
                    }
                }
                null
            }
        }.getOrNull()
    }

    fun hasEntry(jar: File, entryName: String): Boolean {
        val normalized = normalizeEntry(entryName) ?: return false
        val platform = runCatching { ZipFile(jar).use { it.getEntry(normalized) != null } }.getOrNull()
        if (platform != null) return platform
        return runCatching { containsEntrySequentially(jar, normalized) }.getOrDefault(false)
    }

    private fun normalizeEntry(raw: String): String? {
        val value = raw.replace('\\', '/').trim()
        if (value.isEmpty() || value.startsWith('/') || value.contains(':')) return null
        val parts = value.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun readAllBounded(input: InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IOException("Archive entry exceeds the read limit.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun rangeFromStream(
        input: InputStream,
        knownSize: Long?,
        offset: Long,
        limit: Int,
    ): EntryRange {
        var skipped = 0L
        while (skipped < offset) {
            val count = input.skip(offset - skipped)
            if (count > 0L) {
                skipped += count
                continue
            }
            if (input.read() < 0) {
                val total = knownSize ?: skipped
                return EntryRange(total, offset, ByteArray(0), truncated = false)
            }
            skipped++
        }
        val bytes = ByteArray(limit)
        var read = 0
        while (read < bytes.size) {
            val count = input.read(bytes, read, bytes.size - read)
            if (count < 0) break
            read += count
        }
        val result = if (read == bytes.size) bytes else bytes.copyOf(read)
        var total = knownSize
        if (total == null) {
            total = offset + read
            val drain = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(drain)
                if (count < 0) break
                total += count
                if (total > MAX_RESOURCE_BYTES) {
                    throw IOException("Archive resource exceeds the read limit.")
                }
            }
        }
        return EntryRange(total, offset, result, truncated = offset + read < total)
    }

    private fun openArchiveStream(jar: File): ZipArchiveInputStream =
        ZipArchiveInputStream(BufferedInputStream(FileInputStream(jar), BUFFER_SIZE))

    private fun findFirstMatchingEntry(jar: File, candidates: Set<String>): String? = runCatching {
        openArchiveStream(jar).use { input ->
            while (true) {
                val entry = input.nextZipEntry ?: break
                if (entry.name in candidates) return@use entry.name
            }
            null
        }
    }.getOrNull()

    private fun containsEntrySequentially(jar: File, entryName: String): Boolean {
        openArchiveStream(jar).use { input ->
            while (true) {
                val entry = input.nextZipEntry ?: break
                if (entry.name == entryName) return true
            }
        }
        return false
    }

    private const val MAX_RESOURCE_BYTES = 128L * 1024L * 1024L
}
