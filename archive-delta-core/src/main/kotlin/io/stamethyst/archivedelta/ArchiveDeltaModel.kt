package io.stamethyst.archivedelta

import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.file.Path

@Serializable
data class FileIdentity(val size: Long, val sha256: String)

/** Separate namespace from the experimental jar-delta manifest. */
@Serializable
data class ArchivePackageManifest(
    val format: String = "stamethyst-archive-delta",
    val version: Int = 1,
    val codec: String = "archive-patcher-file-by-file-v1",
    val compression: String = "deflate-raw",
    val base: FileIdentity,
    val target: FileIdentity,
    val payload: FileIdentity,
)

data class ArchiveLimits(
    val maxFileBytes: Long = 32L * 1024 * 1024,
    val maxExpandedBytes: Long = 64L * 1024 * 1024,
    val maxEntryBytes: Long = 32L * 1024 * 1024,
    val maxEntries: Int = 10_000,
    val maxPatchBytes: Long = 128L * 1024 * 1024,
    val maxPackageBytes: Long = 64L * 1024 * 1024,
    val maxBsdiffHeapBytes: Long = 256L * 1024 * 1024,
) {
    init {
        require(maxFileBytes > 0 && maxExpandedBytes in 1..(Int.MAX_VALUE.toLong() - 8) &&
            maxEntryBytes > 0 && maxEntries > 0 && maxPatchBytes > 0 &&
            maxPackageBytes > 0 && maxBsdiffHeapBytes > 0)
    }
}

enum class ArchivePhase { VALIDATE, PLAN, TRANSFORM, DIFF, PACKAGE, VERIFY, PUBLISH }

/** Cooperative checkpoints, not a CPU deadline or untrusted-service sandbox. */
data class ArchiveControl(
    val cancelled: () -> Boolean = { false },
    val progress: (ArchivePhase) -> Unit = {},
) {
    fun check() {
        if (Thread.currentThread().isInterrupted || cancelled()) throw ArchiveDeltaException(ArchiveFailure.CANCELLED, "Cancelled")
    }
    fun phase(value: ArchivePhase) { check(); progress(value); check() }
}

enum class ArchiveFailure { INVALID_INPUT, UNSUPPORTED, LIMIT, CANCELLED, VERIFICATION, IO }

class ArchiveDeltaException(val failure: ArchiveFailure, message: String, cause: Throwable? = null) : IOException(message, cause)

data class ArchiveGenerationResult(
    val path: Path,
    val manifest: ArchivePackageManifest,
    val phaseNanos: Map<ArchivePhase, Long>,
)
