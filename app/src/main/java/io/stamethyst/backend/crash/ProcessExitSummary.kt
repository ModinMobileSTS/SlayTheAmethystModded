package io.stamethyst.backend.crash

data class ProcessExitSummary(
    val pid: Int,
    val reason: Int,
    val reasonName: String,
    val status: Int,
    val timestamp: Long,
    val description: String
)
