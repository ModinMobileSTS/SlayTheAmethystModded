package io.stamethyst.jardelta

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Single-writer, offline engine. No mod identity, ASM, downloads, or runtime integration. */
class JarDeltaEngine internal constructor(private val files: FileAccess) {
    constructor() : this(SystemFileAccess)

    fun apply(
        currentJar: Path,
        deltaPackage: Path,
        historyDirectory: Path,
        limits: DeltaLimits = DeltaLimits(),
    ): DeltaResult {
        val start = System.nanoTime()
        val phases = linkedMapOf<String, Long>()
        val current = regular(currentJar)
        val delta = regular(deltaPackage)
        checkDelta(!Files.isSameFile(current, delta), "Delta is the current JAR")
        val initialHash = JarDigests.fileSha256(current)
        val deltaBytes = Files.size(delta)
        ZipFile(delta.toFile()).use { patch ->
            val manifest = DeltaPackageReader.read(patch, limits)
            checkDelta(initialHash == manifest.baseSha256, "Base digest mismatch (wrong base or already applied)")
            val expected = ZipFile(current.toFile()).use { base ->
                val entries = JarDigests.scan(base, limits)
                checkDelta(entries.keys.none(::isSignature), "Signed JARs are not supported")
                validateOperations(entries, manifest, patch, limits)
            }
            checkDelta(JarDigests.fingerprint(expected) == manifest.resultEntriesSha256, "Declared result mismatch")
            phases["preflight"] = System.nanoTime() - start
            val backupStart = System.nanoTime()
            val saved = saveHistory(current, historyDirectory, initialHash)
            val history = saved.reference
            val newHistoryBytes = if (saved.created) Files.size(history.path) else 0L
            phases["backup"] = System.nanoTime() - backupStart
            val candidate = Files.createTempFile(current.parent, ".jar-delta-", ".jar")
            try {
                val generateStart = System.nanoTime()
                ZipFile(history.path.toFile()).use { base ->
                    files.output(candidate).use { raw ->
                        ZipOutputStream(raw).use { output ->
                            val operations = manifest.operations.associateBy { it.entry }
                            expected.keys.sorted().forEach { name ->
                                val operation = operations[name]
                                val source = if (operation == null) base else patch
                                val entry = source.getEntry(operation?.payload ?: name)
                                    ?: throw DeltaException("Entry disappeared: $name")
                                output.putNextEntry(ZipEntry(name).apply { time = 0L })
                                source.getInputStream(entry).use { input ->
                                    copyBounded(input, output, expected.getValue(name).size)
                                }
                                output.closeEntry()
                            }
                        }
                    }
                }
                phases["generate"] = System.nanoTime() - generateStart
                val validateStart = System.nanoTime()
                checkDelta(JarDigests.entriesSha256(candidate, limits) == manifest.resultEntriesSha256,
                    "Generated JAR digest mismatch")
                val resultHash = JarDigests.fileSha256(candidate)
                val resultBytes = Files.size(candidate)
                checkDelta(JarDigests.fileSha256(history.path) == initialHash, "History changed")
                phases["validate"] = System.nanoTime() - validateStart
                val commitStart = System.nanoTime()
                checkDelta(JarDigests.fileSha256(regular(current)) == initialHash, "Current JAR changed")
                files.commit(candidate, current)
                phases["commit"] = System.nanoTime() - commitStart
                phases["total"] = System.nanoTime() - start
                return DeltaResult(history, resultHash,
                    DeltaMetrics(phases, deltaBytes, resultBytes, newHistoryBytes + resultBytes))
            } finally {
                Files.deleteIfExists(candidate)
            }
        }
    }

    fun restore(currentJar: Path, history: HistoryReference): DeltaResult {
        val start = System.nanoTime()
        val current = regular(currentJar)
        val source = regular(history.path)
        validateHash(history.sha256)
        checkDelta(!Files.isSameFile(current, source), "History is the current file")
        checkDelta(JarDigests.fileSha256(source) == history.sha256, "History digest mismatch")
        val before = JarDigests.fileSha256(current)
        val candidate = Files.createTempFile(current.parent, ".jar-delta-restore-", ".jar")
        try {
            files.output(candidate).use { output ->
                Files.newInputStream(source).use { copyBounded(it, output, Files.size(source)) }
            }
            checkDelta(JarDigests.fileSha256(candidate) == history.sha256, "Restored digest mismatch")
            val bytes = Files.size(candidate)
            checkDelta(JarDigests.fileSha256(regular(current)) == before, "Current JAR changed")
            files.commit(candidate, current)
            return DeltaResult(history, history.sha256,
                DeltaMetrics(mapOf("total" to System.nanoTime() - start), 0, bytes, bytes))
        } finally {
            Files.deleteIfExists(candidate)
        }
    }

    private data class SavedHistory(val reference: HistoryReference, val created: Boolean)

    private fun saveHistory(current: Path, directory: Path, hash: String): SavedHistory {
        Files.createDirectories(directory)
        val destination = directory.toAbsolutePath().normalize().resolve("$hash.jar")
        checkDelta(destination != current, "History path is the current file")
        if (Files.exists(destination, NOFOLLOW_LINKS)) {
            checkDelta(JarDigests.fileSha256(regular(destination)) == hash, "Existing history is corrupt")
            return SavedHistory(HistoryReference(destination, hash), false)
        }
        val temporary = Files.createTempFile(destination.parent, ".jar-delta-history-", ".jar")
        try {
            files.output(temporary).use { output ->
                Files.newInputStream(current).use { copyBounded(it, output, Files.size(current)) }
            }
            checkDelta(JarDigests.fileSha256(temporary) == hash, "History copy digest mismatch")
            // Atomic no-clobber publication. Unlike ATOMIC_MOVE, this never replaces an existing path.
            Files.createLink(destination, temporary)
            return SavedHistory(HistoryReference(destination, hash), true)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun validateOperations(
        base: Map<String, EntryDigest>, manifest: DeltaManifest, patch: ZipFile, limits: DeltaLimits,
    ): Map<String, EntryDigest> {
        val result = base.toMutableMap()
        manifest.operations.forEach { operation ->
            checkDelta(!isSignature(operation.entry), "Signature operations are not supported")
            checkDelta((operation.entry in base) == (operation.type != "add"), "Operation existence mismatch: ${operation.entry}")
            if (operation.type == "delete") result.remove(operation.entry)
            else result[operation.entry] = patch.getInputStream(patch.getEntry(operation.payload)).use {
                digestStream(it, limits.maxEntryBytes)
            }
        }
        checkDelta(result.size <= limits.maxEntries, "Result has too many entries")
        var total = 0L
        result.values.forEach {
            checkDelta(it.size <= limits.maxTotalBytes - total, "Result exceeds size limit")
            total += it.size
        }
        return result
    }

    private fun isSignature(name: String): Boolean {
        val upper = name.uppercase(java.util.Locale.ROOT)
        return upper.startsWith("META-INF/") && '/' !in upper.removePrefix("META-INF/") &&
            (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") ||
                upper.endsWith(".EC") || upper.removePrefix("META-INF/").startsWith("SIG-"))
    }

    private fun regular(path: Path): Path = path.toAbsolutePath().normalize().also {
        checkDelta(Files.isRegularFile(it, NOFOLLOW_LINKS), "Not a regular non-symlink file: $it")
    }
}

internal fun copyBounded(input: java.io.InputStream, output: OutputStream, expected: Long) {
    var copied = 0L
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        checkDelta(n.toLong() <= expected - copied, "Source grew while copying")
        output.write(buffer, 0, n)
        copied += n
    }
    checkDelta(copied == expected, "Source shrank while copying")
}

/** Narrow filesystem seam for deterministic write/commit failure tests, not a plugin API. */
internal interface FileAccess {
    fun output(path: Path): OutputStream
    fun commit(candidate: Path, current: Path)
}

internal object SystemFileAccess : FileAccess {
    override fun output(path: Path): OutputStream = Files.newOutputStream(path)
    override fun commit(candidate: Path, current: Path) {
        Files.move(candidate, current, ATOMIC_MOVE, REPLACE_EXISTING)
    }
}
