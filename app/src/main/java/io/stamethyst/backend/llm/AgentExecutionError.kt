package io.stamethyst.backend.llm

import java.util.UUID

/** Point-in-time diagnostics, not another billable request or a second copy of action duration. */
internal fun agentErrorStep(
    source: String,
    detail: String,
    relatedStepId: String? = null,
    status: AgentStepStatus = AgentStepStatus.FAILED,
    id: String = relatedStepId?.let { "$it:error" } ?: UUID.randomUUID().toString(),
): AgentExecutionStep = AgentExecutionStep(
    id = id,
    kind = AgentStepKind.ERROR,
    name = source.take(120),
    detail = agentErrorDetail(detail),
    startedAtMs = System.currentTimeMillis(),
    elapsedMs = 0,
    status = status,
    inputTokens = 0,
    outputTokens = 0,
    relatedStepId = relatedStepId,
)

/** Bounded descriptions, without stacks, with common credential shapes redacted. */
internal fun agentErrorDetail(error: Throwable): String = agentErrorDetail(
    generateSequence(error) { it.cause }.take(4).joinToString("; caused by ") {
        val http = if (it is dev.langchain4j.exception.HttpException) " (HTTP ${it.statusCode()})" else ""
        "${it.javaClass.simpleName}$http: ${it.message.orEmpty().take(480)}"
    },
)

private val errorCredential = Regex(
    "(?i)((?:authorization[\"']?\\s*[:=]\\s*[\"']?\\s*(?:bearer\\s+)?|bearer\\s+)|(?:api[_-]?key|access[_-]?token)[\"']?\\s*[:=]\\s*[\"']?)[^\\s,;\"'}]+",
)
private val errorApiKey = Regex("\\bsk-[A-Za-z0-9_-]{8,}")

internal fun agentErrorDetail(detail: String): String = errorApiKey.replace(
    errorCredential.replace(detail.take(2_048)) { "${it.groupValues[1]}[redacted]" },
    "[redacted]",
).take(480)
