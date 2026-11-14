package io.stamethyst.backend.launch

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/** A central-directory snapshot, never a process-wide size/mtime cache. */
internal data class LaunchJarFingerprint(val markerValue: String, val readableZip: Boolean)

internal class LaunchJarFingerprints private constructor(
    private val values: Map<String, LaunchJarFingerprint>
) {
    fun get(file: File): LaunchJarFingerprint = values.getValue(identity(file))

    // Keep the caller's path and label, even when two labels refer to the same jar.
    fun line(label: String, file: File): String =
        "$label|${file.absolutePath}|${get(file).markerValue}"

    companion object {
        private const val POOL_CAP = 4
        private val SEPARATOR_BYTE = byteArrayOf('|'.code.toByte())

        /**
         * Read each distinct jar once during a read-only operation. A new operation, or
         * any operation that can rewrite jars, must capture a fresh snapshot. In
         * particular, these values are not transferred from main to the game process.
         */
        fun capture(
            files: List<File>,
            readFingerprint: (File) -> LaunchJarFingerprint = ::read
        ): LaunchJarFingerprints {
            val distinctFiles = files.associateBy(::identity)
            val threadCount = minOf(Runtime.getRuntime().availableProcessors(), POOL_CAP, distinctFiles.size)
            if (threadCount <= 1) {
                return LaunchJarFingerprints(distinctFiles.mapValues { (_, file) -> readFingerprint(file) })
            }
            val pool = Executors.newFixedThreadPool(threadCount)
            try {
                val pending = distinctFiles.mapValues { (_, file) ->
                    pool.submit(Callable { readFingerprint(file) })
                }
                return LaunchJarFingerprints(pending.mapValues { (_, future) ->
                    try {
                        future.get()
                    } catch (error: ExecutionException) {
                        throw error.cause ?: error
                    }
                })
            } finally {
                pool.shutdownNow()
            }
        }

        /**
         * Entry names, sizes and CRCs detect in-place jar rebuilds even with preserved
         * length and mtime. Retain the existing marker's non-zip fallback and ordering.
         */
        fun read(file: File): LaunchJarFingerprint {
            if (!file.isFile) {
                return LaunchJarFingerprint("-1|-1", readableZip = false)
            }
            val entryDigest = try {
                ZipFile(file).use { zip ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    for (entry in zip.entries()) {
                        digest.update(entry.name.toByteArray(StandardCharsets.UTF_8))
                        digest.update(SEPARATOR_BYTE)
                        digest.update(entry.size.toString().toByteArray(StandardCharsets.UTF_8))
                        digest.update(SEPARATOR_BYTE)
                        digest.update(entry.crc.toString().toByteArray(StandardCharsets.UTF_8))
                        digest.update(SEPARATOR_BYTE)
                    }
                    digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
                }
            } catch (_: Throwable) {
                null
            }
            return if (entryDigest == null) {
                LaunchJarFingerprint("${file.length()}|${file.lastModified()}|nozip", readableZip = false)
            } else {
                LaunchJarFingerprint("${file.length()}|$entryDigest", readableZip = true)
            }
        }

        private fun identity(file: File): String = try {
            file.canonicalPath
        } catch (_: Exception) {
            file.absolutePath
        }
    }
}
