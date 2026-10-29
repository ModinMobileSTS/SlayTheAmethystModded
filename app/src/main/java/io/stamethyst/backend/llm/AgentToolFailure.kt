package io.stamethyst.backend.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Only explicit failure signals count; a successful file read containing error text does not. */
internal fun agentToolFailure(result: JsonObject): String? {
    fun text(key: String) = (result[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val error = text("error")
    val status = text("status")
    if ("error" !in result && status !in setOf("failed", "error", "cancelled", "timeout", "timed_out") &&
        (result["success"] as? JsonPrimitive)?.booleanOrNull != false &&
        (result["passed"] as? JsonPrimitive)?.booleanOrNull != false) return null
    return agentErrorDetail(buildList {
        add(error ?: status ?: "tool_execution_failed")
        listOf("message", "reason", "diagnostics").forEach { key -> text(key)?.let { add(it.take(480)) } }
        (result["issues"] as? JsonArray)?.take(3)?.forEach { issue ->
            (issue as? JsonPrimitive)?.contentOrNull?.let { add(it.take(480)) }
        }
    }.joinToString(": "))
}

internal fun agentToolFailure(result: String): String? =
    runCatching { (Json.parseToJsonElement(result) as? JsonObject)?.let(::agentToolFailure) }.getOrNull()
