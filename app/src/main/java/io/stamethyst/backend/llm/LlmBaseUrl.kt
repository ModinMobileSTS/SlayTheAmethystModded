package io.stamethyst.backend.llm

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder

/** OkHttp accepts spaces/quotes in paths by encoding them, including accidentally pasted curl. */
internal fun isValidLlmBaseUrl(value: String): Boolean {
    val raw = value.trim()
    if (raw.any { it.isWhitespace() || it.isISOControl() || it in "\"'{}\\`<>" }) return false
    val url = raw.toHttpUrlOrNull() ?: return false
    return url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
        validApiPath(url)
}

internal fun requireLlmBaseUrl(value: String): String {
    require(isValidLlmBaseUrl(value)) {
        "Invalid API Base URL. Enter only the HTTP(S) API address, not a curl command, headers or JSON."
    }
    return value.trim().trimEnd('/')
}

private fun invalidApiPathCharacter(char: Char): Boolean =
    char.isWhitespace() || char.isISOControl() || char in "\"'{}[]\\`<>"

private fun validApiPath(url: HttpUrl): Boolean = url.pathSegments.all { segment ->
    // Check one further percent-decoding layer too: pasted URL-encoded command text is still not
    // an API path. Preserve literal '+' rather than interpreting it as form-data whitespace.
    val decoded = runCatching { URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8") }.getOrDefault(segment)
    segment.none(::invalidApiPathCharacter) && decoded.none(::invalidApiPathCharacter) &&
        !decoded.contains("authorization:", ignoreCase = true)
}

/** Never include command text/credentials hidden inside an abnormal path in shared diagnostics. */
internal fun llmDiagnosticUrl(url: HttpUrl): String {
    val safe = url.newBuilder().username("").password("").query(null).fragment(null).build()
    return if (validApiPath(safe)) safe.toString() else {
        safe.newBuilder().encodedPath("/").build().toString() + "<invalid API path omitted>"
    }
}
