package io.stamethyst.backend.llm

import dev.langchain4j.http.client.HttpClient
import dev.langchain4j.http.client.HttpClientBuilder
import dev.langchain4j.http.client.HttpRequest
import dev.langchain4j.http.client.SuccessfulHttpResponse
import dev.langchain4j.http.client.okhttp.OkHttpClientBuilder
import dev.langchain4j.http.client.sse.DefaultServerSentEventParsingHandle
import dev.langchain4j.http.client.sse.ServerSentEvent
import dev.langchain4j.http.client.sse.ServerSentEventContext
import dev.langchain4j.http.client.sse.ServerSentEventListener
import dev.langchain4j.http.client.sse.ServerSentEventListenerUtils
import dev.langchain4j.http.client.sse.ServerSentEventParser
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.EventListener

/**
 * LangChain4j's default parser calls StringBuilder.isEmpty(), which is not an Android API.
 * Keep the workaround at the HTTP boundary so normal and streaming requests use the same client.
 */
internal class AndroidCompatibleSseHttpClientBuilder(
    private val delegate: OkHttpClientBuilder,
    private val connectTimeoutLimit: Duration? = null,
    private val connectionPool: okhttp3.ConnectionPool? = null,
) : HttpClientBuilder {
    // langchain4j's builder getters are nullable: an unset timeout returns null and callers such as
    // DefaultOpenAiClient tolerate that. Declaring a non-null Duration here would make Kotlin insert
    // a null check and crash with "connectTimeout(...) must not be null" on every model build.
    override fun connectTimeout(): Duration? = delegate.connectTimeout()

    override fun connectTimeout(timeout: Duration): HttpClientBuilder = apply {
        // OpenAI's model.timeout sets both timeouts; keep connection attempts bounded without
        // shortening the configured read timeout for a slow, non-streaming summary.
        delegate.connectTimeout(connectTimeoutLimit?.let { minOf(timeout, it) } ?: timeout)
    }

    override fun readTimeout(): Duration? = delegate.readTimeout()

    override fun readTimeout(timeout: Duration): HttpClientBuilder = apply {
        delegate.readTimeout(timeout)
    }

    override fun build(): HttpClient {
        // Public SDK accessors only. Copy the builder so rebuilding never stacks capture listeners.
        val base = (delegate.okHttpClientBuilder() ?: okhttp3.OkHttpClient.Builder()).build()
        val originalFactory = base.eventListenerFactory
        val capturingBuilder = base.newBuilder().eventListenerFactory { call ->
            val original = originalFactory.create(call)
            val scope = AgentRequestScope.current()
            if (scope == null) original else original + object : EventListener() {
                // The factory runs in newCall on the caller thread, before async dispatch. Register
                // at callStart, once RealCall is fully initialized, and before DNS/connect/headers.
                override fun callStart(call: Call) = scope.register(call)
                override fun callEnd(call: Call) = scope.unregister(call)
                override fun callFailed(call: Call, ioe: IOException) = scope.unregister(call)
            }
        }
        val capturingDelegate = OkHttpClientBuilder().okHttpClientBuilder(capturingBuilder)
        delegate.connectTimeout()?.let { capturingDelegate.connectTimeout(it) }
        delegate.readTimeout()?.let { capturingDelegate.readTimeout(it) }
        delegate.streamingBufferSize()?.let { capturingDelegate.streamingBufferSize(it) }
        return AndroidCompatibleSseHttpClient(capturingDelegate.build(), connectionPool)
    }
}

private class AndroidCompatibleSseHttpClient(
    private val delegate: HttpClient,
    /** Shared pool so a dead connection can be discarded before a retry reuses it. */
    private val connectionPool: okhttp3.ConnectionPool? = null,
) : HttpClient {
    override fun execute(request: HttpRequest): SuccessfulHttpResponse {
        val scope = AgentRequestScope.current()
        scope?.throwIfStopped()
        try {
            return delegate.execute(request).also { scope?.throwIfStopped() }
        } catch (error: Exception) {
            throw scope?.stopReason ?: error
        }
    }

    override fun executeAsync(request: HttpRequest): CompletableFuture<SuccessfulHttpResponse> {
        val scope = AgentRequestScope.current() ?: return delegate.executeAsync(request)
        scope.throwIfStopped()
        val upstream = delegate.executeAsync(request)
        val result = CompletableFuture<SuccessfulHttpResponse>()
        upstream.whenComplete { response, error ->
            val failure = scope.stopReason ?: error
            if (failure != null) result.completeExceptionally(failure) else result.complete(response)
        }
        result.whenComplete { _, _ -> if (result.isCancelled) upstream.cancel(true) }
        return result
    }

    override fun execute(
        request: HttpRequest,
        parser: ServerSentEventParser,
        listener: ServerSentEventListener,
    ) {
        val scope = AgentRequestScope.current()
        scope?.throwIfStopped()
        val terminal = AtomicBoolean()
        delegate.execute(request, AndroidCompatibleSseParser(), object : ServerSentEventListener {
            override fun onOpen(response: SuccessfulHttpResponse) {
                if (scope?.stopReason == null) listener.onOpen(response)
            }

            override fun onEvent(event: ServerSentEvent, context: ServerSentEventContext) {
                if (!terminal.get() && scope?.stopReason == null) listener.onEvent(event, context)
            }

            override fun onError(error: Throwable) {
                if (terminal.compareAndSet(false, true)) listener.onError(scope?.stopReason ?: error)
            }

            override fun onClose() {
                if (!terminal.compareAndSet(false, true)) return
                val failure = scope?.stopReason
                if (failure != null) listener.onError(failure) else listener.onClose()
            }
        })
    }

    /**
     * Drops every pooled connection.
     *
     * After the screen turns off the platform can tear down established sockets while the Wi-Fi
     * network still reports as connected and validated. OkHttp keeps those dead connections in its
     * pool, so the next request reuses one and fails immediately with
     * `SocketException: Software caused connection abort` again — which is why retrying alone never
     * recovered. Evicting the pool forces the retry onto a fresh TCP connection.
     */
    internal fun evictConnectionPool() {
        runCatching { connectionPool?.evictAll() }
    }
}

private class AndroidCompatibleSseParser : ServerSentEventParser {
    override fun parse(input: InputStream, listener: ServerSentEventListener) {
        val parsingHandle = DefaultServerSentEventParsingHandle(input)
        val context = ServerSentEventContext(parsingHandle)
        try {
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                var event: String? = null
                val data = StringBuilder()
                while (!parsingHandle.isCancelled()) {
                    val currentLine = reader.readLine() ?: break
                    if (currentLine.isEmpty()) {
                        if (data.length > 0) {
                            emit(listener, ServerSentEvent(event, data.toString()), context)
                            event = null
                            data.setLength(0)
                        }
                        continue
                    }
                    if (currentLine.startsWith("event:")) {
                        event = currentLine.substring("event:".length).trim()
                    } else if (currentLine.startsWith("data:")) {
                        if (data.length > 0) data.append('\n')
                        data.append(dataFieldValue(currentLine))
                    }
                }
                if (!parsingHandle.isCancelled() && data.length > 0) {
                    emit(listener, ServerSentEvent(event, data.toString()), context)
                }
            }
        } catch (error: IOException) {
            ServerSentEventListenerUtils.ignoringExceptions { listener.onError(error) }
        }
    }

    private fun emit(
        listener: ServerSentEventListener,
        event: ServerSentEvent,
        context: ServerSentEventContext,
    ) {
        ServerSentEventListenerUtils.ignoringExceptions { listener.onEvent(event, context) }
    }

    private fun dataFieldValue(line: String): String {
        val content = line.substring("data:".length)
        return if (content.startsWith(" ")) content.substring(1) else content
    }
}
