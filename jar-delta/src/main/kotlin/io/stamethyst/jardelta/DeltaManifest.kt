package io.stamethyst.jardelta

import kotlinx.serialization.Serializable
import java.nio.file.Path

/** Data only: payloads are complete ZIP entries, never executable transformations. */
@Serializable
data class DeltaManifest(
    val formatVersion: Int,
    val baseSha256: String,
    val resultEntriesSha256: String,
    val operations: List<DeltaOperation>,
)

@Serializable
data class DeltaOperation(
    val type: String,
    val entry: String,
    val payload: String? = null,
    val sha256: String? = null,
)

data class DeltaLimits(
    val maxEntries: Int = 100_000,
    val maxEntryBytes: Long = 256L * 1024 * 1024,
    val maxTotalBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxManifestBytes: Long = 1024 * 1024,
) {
    init {
        require(maxEntries > 0 && maxEntryBytes > 0 && maxTotalBytes > 0 && maxManifestBytes > 0)
    }
}

/** The actual pre-patch bytes, not necessarily an upstream original. */
data class HistoryReference(val path: Path, val sha256: String)

data class DeltaMetrics(
    val phaseNanos: Map<String, Long>,
    val deltaBytes: Long,
    val resultBytes: Long,
    /** Logical extra file bytes at peak; excludes the input files and filesystem allocation overhead. */
    val peakExtraBytes: Long,
)

data class DeltaResult(val history: HistoryReference, val sha256: String, val metrics: DeltaMetrics)

class DeltaException(message: String) : java.io.IOException(message)

internal fun checkDelta(condition: Boolean, message: String) {
    if (!condition) throw DeltaException(message)
}
