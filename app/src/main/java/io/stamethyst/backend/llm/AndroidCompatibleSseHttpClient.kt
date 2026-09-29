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

/**
 * LangChain4j's default parser calls StringBuilder.isEmpty(), which is not an Android API.
 * Keep the workaround at the HTTP boundary so normal and streaming requests use the same client.
 */
internal class AndroidCompatibleSseHttpClientBuilder(
    private val delegate: OkHttpClientBuilder,
) : HttpClientBuilder {
    // langchain4j's builder getters are nullable: an unset timeout returns null and callers such as
    // DefaultOpenAiClient tolerate that. Declaring a non-null Duration here would make Kotlin insert
    // a null check and crash with "connectTimeout(...) must not be null" on every model build.
    override fun connectTimeout(): Duration? = delegate.connectTimeout()

    override fun connectTimeout(timeout: Duration): HttpClientBuilder = apply {
        delegate.connectTimeout(timeout)
    }

    override fun readTimeout(): Duration? = delegate.readTimeout()

    override fun readTimeout(timeout: Duration): HttpClientBuilder = apply {
        delegate.readTimeout(timeout)
    }

    override fun build(): HttpClient = AndroidCompatibleSseHttpClient(delegate.build())
}

private class AndroidCompatibleSseHttpClient(
    private val delegate: HttpClient,
) : HttpClient {
    override fun execute(request: HttpRequest): SuccessfulHttpResponse = delegate.execute(request)

    override fun executeAsync(request: HttpRequest): CompletableFuture<SuccessfulHttpResponse> =
        delegate.executeAsync(request)

    override fun execute(
        request: HttpRequest,
        parser: ServerSentEventParser,
        listener: ServerSentEventListener,
    ) {
        delegate.execute(request, AndroidCompatibleSseParser(), listener)
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
