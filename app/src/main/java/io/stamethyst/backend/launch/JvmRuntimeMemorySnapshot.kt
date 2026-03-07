package io.stamethyst.backend.launch

internal data class JvmRuntimeMemorySnapshot(
    val heapUsedBytes: Long,
    val heapMaxBytes: Long
)
