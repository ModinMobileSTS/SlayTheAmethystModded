package io.stamethyst.backend.llm

import dev.langchain4j.exception.HttpException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Collections
import java.util.IdentityHashMap

private const val MAX_SETTINGS_ERROR_CHARS = 4_096
private val settingsErrorCredential = Regex(
    "(?i)((?:authorization[\"']?\\s*[:=]\\s*[\"']?\\s*(?:bearer\\s+)?|bearer\\s+)|(?:api[_-]?key|access[_-]?token)[\"']?\\s*[:=]\\s*[\"']?)[^\\s,;\"'}]+",
)
private val settingsErrorApiKey = Regex("\\bsk-[A-Za-z0-9_-]{8,}")
private val settingsErrorUrl = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)

/** Keep provider diagnostics visible without exposing the configured key or dumping a stack trace. */
internal fun llmSettingsErrorDetail(error: Throwable, apiKey: String): String {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val chain = generateSequence(error) { it.cause }.takeWhile { seen.add(it) }.take(8).toList()
    // LangChain4j wraps HTTP failures in InvalidRequestException, AuthenticationException, etc.
    // Put the actual status first so it survives even a very long provider error page.
    val http = chain.filterIsInstance<HttpException>().firstOrNull()
    val heading = error.javaClass.simpleName + (http?.let { " (HTTP ${it.statusCode()})" } ?: "")
    val messages = chain.mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }.distinct()
    val diagnostic = chain.flatMap { it.suppressed.filterIsInstance<LlmSettingsHttpDiagnostic>() }
        .lastOrNull()?.detail
    val emptyBody = if (http != null && http.message.isNullOrBlank()) listOf("Response body: <empty>") else emptyList()
    val detail = redactLlmSettingsSecrets(
        (listOfNotNull(heading, diagnostic) + emptyBody + messages).joinToString("\n"), apiKey,
    )
    return if (detail.length > MAX_SETTINGS_ERROR_CHARS) {
        detail.take(MAX_SETTINGS_ERROR_CHARS - 1) + "…"
    } else detail
}

internal fun redactLlmSettingsSecrets(value: String, apiKey: String): String {
    var detail = value
    detail = settingsErrorUrl.replace(detail) { match ->
        match.value.toHttpUrlOrNull()?.let(::llmDiagnosticUrl) ?: "[invalid URL omitted]"
    }
    // Redact before bounding: truncating first can expose part of a key at the cut point.
    apiKey.trim().takeIf(String::isNotEmpty)?.let { detail = detail.replace(it, "[redacted]") }
    detail = settingsErrorCredential.replace(detail) { "${it.groupValues[1]}[redacted]" }
    detail = settingsErrorApiKey.replace(detail, "[redacted]")
    return detail
}
