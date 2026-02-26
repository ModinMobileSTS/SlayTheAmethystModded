package io.stamethyst.backend

import java.util.Locale

internal data class StsBootBridgeEvent(
    val type: String,
    val percent: Int,
    val message: String
)

internal fun parseBootBridgeEventLine(line: String): StsBootBridgeEvent? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) {
        return null
    }
    val parts = trimmed.split("\t", limit = 3)
    val type = if (parts.isNotEmpty()) parts[0].trim().uppercase(Locale.ROOT) else ""
    val percent = if (parts.size > 1) parts[1].trim().toIntOrNull() ?: -1 else -1
    val message = if (parts.size > 2) parts[2].trim() else ""
    return StsBootBridgeEvent(type = type, percent = percent, message = message)
}
