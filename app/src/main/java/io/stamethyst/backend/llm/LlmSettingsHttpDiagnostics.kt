package io.stamethyst.backend.llm

import android.os.Build
import io.stamethyst.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import java.time.Instant

/** Settings probes only: no global client mutation, extra requests, protocol fallback or body reads. */
internal object LlmSettingsHttpDiagnostics {
    private val active = ThreadLocal<Capture>()

    fun interceptor(): Interceptor? = active.get()?.interceptor

    // The block performs synchronous SDK/OkHttp calls on the caller's IO thread. Nothing suspends
    // inside it; the interceptor closes over this capture rather than looking up a worker ThreadLocal.
    fun <T> capture(baseUrl: String, apiKey: String, block: () -> T): T {
        val previous = active.get()
        val capture = Capture(baseUrl, apiKey)
        active.set(capture)
        try {
            return block()
        } catch (error: Exception) {
            if (error !is java.util.concurrent.CancellationException) {
                error.addSuppressed(LlmSettingsHttpDiagnostic(capture.detail()))
            }
            throw error
        } finally {
            if (previous == null) active.remove() else active.set(previous)
        }
    }

    private class Capture(baseUrl: String, private val apiKey: String) {
        private val configuredUrl = runCatching { llmDiagnosticUrl(baseUrl.trim().toHttpUrl()) }
            .getOrDefault("<invalid URL>")
        private val key = apiKey.trim()
        @Volatile private var lastExchange = "HTTP exchange: not observed"
        private var attempts = 0

        val interceptor = Interceptor { chain ->
            val request = chain.request()
            val connection = chain.connection()
            val route = connection?.route()
            val address = route?.socketAddress
            val peer = address?.address?.hostAddress?.let { "$it:${address.port}" } ?: "<unknown>"
            val auth = request.header("Authorization")
            val attempt = ++attempts
            val requestDetail = listOf(
                "Time: ${Instant.now()}",
                "Request: ${request.method} ${llmDiagnosticUrl(request.url)}",
                "Attempt: $attempt",
                "Peer: $peer; proxy=${route?.proxy?.type()?.name ?: "unknown"}",
                "Transport: ${connection?.protocol() ?: "unknown"}; TLS=${connection?.handshake()?.tlsVersion?.javaName ?: "none"}",
                "Credential header: present=${auth != null}; chars=${auth?.length ?: 0}",
            ).joinToString("\n")
            lastExchange = "$requestDetail\nHTTP response: not received"
            val response = chain.proceed(request)
            val headers = RESPONSE_HEADERS.mapNotNull { name ->
                response.header(name)?.let {
                    val safe = redactLlmSettingsSecrets(it, apiKey).replace(Regex("[\\r\\n\\t]+"), " ")
                    "$name: ${safe.take(180)}"
                }
            }
            val bodyLength = response.body?.contentLength()?.takeIf { it >= 0 }?.toString() ?: "unknown"
            lastExchange = (listOf(requestDetail, "Response: HTTP ${response.code} (${response.protocol})",
                "Response body bytes (declared): $bodyLength") + headers)
                .joinToString("\n")
            response
        }

        fun detail(): String = redactLlmSettingsSecrets(listOf(
            "LLM HTTP diagnostics v1",
            "App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}); Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Configured URL: $configuredUrl",
            "Configured key: chars=${key.length}; whitespace=${key.count(Char::isWhitespace)}; nonAscii=${key.count { it.code > 127 }}; bearerPrefix=${key.startsWith("Bearer ", ignoreCase = true)}",
            lastExchange,
        ).joinToString("\n"), apiKey)
    }

    private val RESPONSE_HEADERS = listOf(
        "Server", "Via", "Content-Type", "Content-Length", "Date",
        "X-Request-Id", "X-Oneapi-Request-Id", "X-New-Api-Version", "CF-Ray",
    )
}

/** Attached only after failure, without changing the SDK exception type/cause or retaining keys. */
internal class LlmSettingsHttpDiagnostic(val detail: String) : RuntimeException(null, null, false, false)
