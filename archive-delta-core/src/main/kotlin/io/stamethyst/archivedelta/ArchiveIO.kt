package io.stamethyst.archivedelta

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

/** Infrastructure shared with the generator; not a codec extension API. */
object ArchiveIO {
    fun requireValid(value: Boolean, message: String, failure: ArchiveFailure = ArchiveFailure.INVALID_INPUT) {
        if (!value) throw ArchiveDeltaException(failure, message)
    }

    fun regular(path: Path): Path = path.toAbsolutePath().normalize().also {
        requireValid(Files.isRegularFile(it, NOFOLLOW_LINKS), "Not a regular non-symlink file: $it")
    }

    fun identity(path: Path, limit: Long, control: ArchiveControl = ArchiveControl()): FileIdentity {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        Files.newInputStream(regular(path)).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                control.check()
                val n = input.read(buffer)
                if (n < 0) break
                requireValid(n.toLong() <= limit - total, "File exceeds limit", ArchiveFailure.LIMIT)
                total += n
                digest.update(buffer, 0, n)
            }
        }
        return FileIdentity(total, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    fun bounded(out: OutputStream, limit: Long, control: ArchiveControl): OutputStream = object : OutputStream() {
        var written = 0L
        override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            control.check()
            requireValid(length.toLong() <= limit - written, "Output exceeds limit", ArchiveFailure.LIMIT)
            out.write(bytes, offset, length)
            written += length
        }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    fun copy(input: InputStream, output: OutputStream, limit: Long, control: ArchiveControl) {
        val bounded = bounded(output, limit, control)
        val buffer = ByteArray(65536)
        while (true) {
            control.check()
            val n = input.read(buffer)
            if (n < 0) break
            requireValid(n > 0, "Non-progressing stream")
            bounded.write(buffer, 0, n)
        }
    }

    /** Atomic no-clobber publication. No destructive fallback on unsupported filesystems. */
    fun publish(candidate: Path, destination: Path) {
        Files.createLink(destination.toAbsolutePath().normalize(), candidate)
    }

    fun <T> workspace(parent: Path, block: (Path) -> T): T {
        Files.createDirectories(parent)
        val directory = Files.createTempDirectory(parent, "archive-delta-")
        var primary: Throwable? = null
        try { return block(directory) } catch (error: Throwable) { primary = error; throw error }
        finally {
            try {
                Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            } catch (cleanup: Exception) {
                if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
            }
        }
    }
}
