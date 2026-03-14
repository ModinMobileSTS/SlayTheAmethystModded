package io.stamethyst.backend.launch

internal data class JvmHeapPressureNotice(
    val peakHeapUsedBytes: Long,
    val peakHeapMaxBytes: Long,
    val peakUsageRatio: Double,
    val currentHeapMaxMb: Int,
    val suggestedHeapMaxMb: Int
)
