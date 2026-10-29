package io.stamethyst.backend.llm

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import android.content.Context
import android.util.Log
import io.stamethyst.backend.mods.AgentApiSourceCache
import io.stamethyst.backend.mods.AgentPatchClassDecompiler
import io.stamethyst.backend.mods.AgentApiIndex
import io.stamethyst.backend.mods.JavaSourceMembers
import io.stamethyst.backend.mods.AgentModJarReader
import io.stamethyst.backend.mods.AgentModInspectionManager
import io.stamethyst.backend.mods.AgentModSourceSnapshot
import io.stamethyst.backend.mods.AgentPatchModManager
import io.stamethyst.backend.mods.AgentPatchPreflight
import io.stamethyst.backend.mods.AgentPatchSourceCompiler
import io.stamethyst.backend.mods.AgentPatchTargetInspector
import io.stamethyst.backend.mods.AgentPatchWorkspace
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiResponsesChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.chat.request.json.JsonArraySchema
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import dev.langchain4j.model.chat.request.json.JsonStringSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val TAG = "AgentGateway"
private const val DEFAULT_MAX_LIST_ENTRIES = 1200
private const val DEFAULT_MAX_LIST_CHARS = 20_000

/** Text reads default to this many lines, matching the agent host's file-read tooling. */
private const val DEFAULT_MAX_READ_LINES = 2000

/** A single line longer than this is truncated when read as text. */
private const val MAX_READ_LINE_CHARS = 2000

/** Upper bound for one binary (base64) read; keeps the encoded result under the tool-result cap. */
private const val DEFAULT_MAX_READ_BYTES = 16L * 1024L

/** Refuse to decode an enormous image before it is downscaled for the model. */
private const val MAX_IMAGE_INPUT_BYTES = 24L * 1024L * 1024L

/** Text reads above this size are refused rather than streamed into an unbounded line buffer. */
private const val MAX_TEXT_READ_BYTES = 16L * 1024L * 1024L

/** Tool results are capped at this many lines and bytes before they become model context. */
private const val MAX_TOOL_RESULT_LINES = 2000
private const val MAX_TOOL_RESULT_BYTES = 51_200

/** Cap for one decompiled game/API class returned inline to the model. */
private const val MAX_API_SOURCE_CHARS = 32_000

/** Workspace subdirectory where oversized tool results are persisted for later reading. */
private const val TOOL_OUTPUT_DIR = "tool_output"

/** Upper bound on files returned by one workspace glob. */
private const val DEFAULT_MAX_GLOB_RESULTS = 300

/** Upper bound on matching lines returned by one workspace grep, and the preview width of each. */
private const val DEFAULT_MAX_GREP_MATCHES = 200
private const val MAX_GREP_LINE_CHARS = 300

/** Files larger than this are skipped by grep instead of being loaded into a line buffer. */
private const val MAX_GREP_FILE_BYTES = 2L * 1024L * 1024L

/** How many extra times an empty model turn is retried before it is reported as a failure. */
private const val MAX_EMPTY_RETRIES = 1
private const val MAX_REQUEST_RETRIES = 2

/** A dropped socket gets three retries (5 s, 15 s, 30 s) so the radio has time to come back. */
private const val MAX_ABORT_RETRIES = 3

/** How long a retry may wait for the radio to come back before trying anyway. */
private const val MAX_NETWORK_WAIT_MS = 60_000L
private const val NETWORK_POLL_INTERVAL_MS = 500L
private const val CANCELLABLE_SLEEP_SLICE_MS = 200L

data class OpenAiCompatibleModelConfig(
    val baseUrl: String,
    val apiKey: String,
    val modelName: String,
    val endpoint: LlmEndpoint = LlmEndpoint.CHAT_COMPLETIONS,
    val requestTimeoutSeconds: Int = DEFAULT_LLM_REQUEST_TIMEOUT_SECONDS,
    val reasoningEffort: LlmReasoningEffort = LlmReasoningEffort.OFF,
    /** Stable, opaque conversation affinity; never contains a path, credential or user prompt. */
    val cacheAffinity: String? = null,
)

enum class LlmEndpoint {
    CHAT_COMPLETIONS,
    RESPONSES,
}

object AgentChatModelFactory {
    /**
     * Pool shared by every model built from the same configuration.
     *
     * Kept here rather than per client so the gateway can drop dead connections before a retry;
     * a screen lock tears sockets down while the network still reports as connected.
     */
    private val connectionPools = java.util.concurrent.ConcurrentHashMap<String, okhttp3.ConnectionPool>()

    internal fun connectionPoolFor(config: OpenAiCompatibleModelConfig): okhttp3.ConnectionPool =
        connectionPools.computeIfAbsent(config.baseUrl + "|" + config.apiKey.hashCode()) {
            okhttp3.ConnectionPool()
        }

    /** Drops every pooled connection so the next request opens a fresh socket. */
    fun evictConnectionPool(config: OpenAiCompatibleModelConfig) {
        runCatching { connectionPoolFor(config).evictAll() }
    }

    private const val CONNECT_TIMEOUT_SECONDS = 30L

    private fun affinityHeaders(config: OpenAiCompatibleModelConfig): Map<String, String> =
        config.cacheAffinity?.let { mapOf("x-session-affinity" to it, "X-Session-Id" to it) } ?: emptyMap()

    private fun requestTimeout(config: OpenAiCompatibleModelConfig): java.time.Duration =
        java.time.Duration.ofSeconds(config.requestTimeoutSeconds
            .coerceIn(MIN_LLM_REQUEST_TIMEOUT_SECONDS, MAX_LLM_REQUEST_TIMEOUT_SECONDS).toLong())

    private fun chatCustomParameters(config: OpenAiCompatibleModelConfig): Map<String, Any> =
        if (config.reasoningEffort == LlmReasoningEffort.OFF &&
            config.modelName.substringAfterLast('/').startsWith("deepseek-", ignoreCase = true)
        ) {
            // DeepSeek defaults to thinking enabled. Omitting reasoning_effort does not turn it
            // off, and reasoning may exhaust a compaction request's small visible-output budget.
            mapOf("thinking" to mapOf("type" to "disabled"))
        } else {
            emptyMap()
        }

    private fun httpClientBuilder(config: OpenAiCompatibleModelConfig): AndroidCompatibleSseHttpClientBuilder {
        val readTimeout = requestTimeout(config)
        val timeoutSeconds = readTimeout.seconds
        val connectTimeout = java.time.Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)
        val pool = connectionPoolFor(config)
        val okHttpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .connectionPool(pool)
            // OkHttp already retries a connection that fails before any bytes are written; keep it
            // explicit because a pooled connection can be dead when it is taken out of the pool.
            .retryOnConnectionFailure(true)
        // Responses uses these builder values directly. Chat Completions additionally requires
        // model.timeout below: its model layer otherwise overwrites readTimeout with 60 seconds.
        return AndroidCompatibleSseHttpClientBuilder(
            dev.langchain4j.http.client.okhttp.OkHttpClientBuilder()
                .okHttpClientBuilder(okHttpClient)
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout),
            connectTimeoutLimit = connectTimeout,
            connectionPool = pool,
        )
    }

    fun create(config: OpenAiCompatibleModelConfig): ChatModel = when (config.endpoint) {
        LlmEndpoint.CHAT_COMPLETIONS -> OpenAiChatModel.builder()
            .httpClientBuilder(httpClientBuilder(config))
            .baseUrl(config.baseUrl)
            .apiKey(config.apiKey)
            .customHeaders(affinityHeaders(config))
            .modelName(config.modelName)
            .reasoningEffort(config.reasoningEffort.requestValue)
            .customParameters(chatCustomParameters(config))
            .returnThinking(true)
            .sendThinking(true)
            .timeout(requestTimeout(config))
            // The gateway owns retries and reports every attempt to the editor.
            .maxRetries(0)
            .build()
        LlmEndpoint.RESPONSES -> OpenAiResponsesChatModel.builder()
            .httpClientBuilder(httpClientBuilder(config))
            .baseUrl(config.baseUrl)
            .apiKey(config.apiKey)
            .customHeaders(affinityHeaders(config))
            .modelName(config.modelName)
            .reasoningEffort(config.reasoningEffort.requestValue)
            .build()
    }

    fun createStreaming(config: OpenAiCompatibleModelConfig): StreamingChatModel? =
        if (config.endpoint != LlmEndpoint.CHAT_COMPLETIONS) {
            null
        } else {
            OpenAiStreamingChatModel.builder()
                .httpClientBuilder(httpClientBuilder(config))
                .baseUrl(config.baseUrl)
                .apiKey(config.apiKey)
                .customHeaders(affinityHeaders(config))
                .modelName(config.modelName)
                .reasoningEffort(config.reasoningEffort.requestValue)
                .customParameters(chatCustomParameters(config))
                .returnThinking(true)
                .sendThinking(true)
                .timeout(requestTimeout(config))
                .build()
        }

    private val LlmReasoningEffort.requestValue: String?
        get() = takeUnless { this == LlmReasoningEffort.OFF }?.name?.lowercase()
}

enum class AgentToolSafety {
    READ_ONLY,
    MOD_INSPECTION,
    WORKSPACE_WRITE,
    PATCH_CREATE,
    PATCH_COMPILE,
    PATCH_PACKAGE,
    PATCH_ENABLE,
    PATCH_DELETE,
    PATCH_SMOKE_TEST,
    REQUIRES_APPROVAL,
}

/** The text result of a tool plus any images the model should see with it. */
data class AgentToolOutput(
    val text: String,
    val attachments: List<AgentContextAttachment> = emptyList(),
)

interface AgentTool {
    val specification: ToolSpecification
    val safety: AgentToolSafety
    /** New/third-party tools remain serial until their implementation is audited. */
    val concurrency: AgentToolConcurrency get() = AgentToolConcurrency.EXCLUSIVE

    fun execute(arguments: String): String

    /**
     * Some read tools return an image instead of text (OpenCode V2's read tool does the same).
     * Tools that never return media keep this default so callers only handle one entry point.
     */
    fun executeWithMedia(arguments: String): AgentToolOutput = AgentToolOutput(execute(arguments))
}

class AgentToolPolicyGate(
    private val allowedSafety: Set<AgentToolSafety> = setOf(AgentToolSafety.READ_ONLY),
) {
    fun allows(tool: AgentTool): Boolean = tool.safety in allowedSafety
}

data class AgentToolExecutionEvent(
    val id: String,
    val name: String,
    val arguments: String,
    val result: String? = null,
    val failed: Boolean = false,
    val attachments: List<AgentContextAttachment> = emptyList(),
    val startedAtMs: Long? = null,
    val elapsedMs: Long? = null,
)

class AgentToolRegistry(
    tools: List<AgentTool>,
    private val policyGate: AgentToolPolicyGate = AgentToolPolicyGate(),
    private val onToolExecution: (AgentToolExecutionEvent) -> Unit = {},
) {
    private val toolsByName = tools.associateBy { it.specification.name() }
    private val eventLock = Any()

    init {
        require(toolsByName.size == tools.size) { "Agent tool names must be unique." }
    }

    private val specifications: List<ToolSpecification> = toolsByName.values
        .filter(policyGate::allows)
        .map(AgentTool::specification)
        .sortedBy { it.name() }

    fun toolSpecifications(): List<ToolSpecification> = specifications

    fun concurrency(request: ToolExecutionRequest): AgentToolConcurrency = toolsByName[request.name()]
        ?.takeIf(policyGate::allows)?.concurrency ?: AgentToolConcurrency.EXCLUSIVE

    private fun notifyExecution(event: AgentToolExecutionEvent, checkCancelled: () -> Unit) = synchronized(eventLock) {
        checkCancelled()
        onToolExecution(event)
    }

    fun execute(request: ToolExecutionRequest, checkCancelled: () -> Unit = {}): AgentToolOutput {
        checkCancelled()
        val timer = AgentStepTimer(AgentStepKind.TOOL)
        val event = AgentToolExecutionEvent(
            id = java.util.UUID.randomUUID().toString(),
            name = request.name(),
            arguments = request.arguments(),
            startedAtMs = timer.step.startedAtMs,
        )
        notifyExecution(event, checkCancelled)
        checkCancelled()
        val tool = toolsByName[request.name()]
        val output = when {
            tool == null -> failure("unknown_tool")
            !policyGate.allows(tool) -> failure("approval_required")
            else -> try {
                tool.executeWithMedia(request.arguments())
            } catch (error: java.util.concurrent.CancellationException) {
                throw error
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.util.concurrent.CancellationException("Agent tool cancelled").apply { initCause(error) }
            } catch (error: Exception) {
                failure("tool_execution_failed")
            }
        }
        if (Thread.currentThread().isInterrupted) {
            throw java.util.concurrent.CancellationException("Agent tool cancelled")
        }
        checkCancelled()
        Log.i(TAG, "tool=${request.name()} resultChars=${output.text.length} images=${output.attachments.size}")
        val failed = runCatching {
            Json.parseToJsonElement(output.text).jsonObject.containsKey("error")
        }.getOrDefault(false)
        notifyExecution(event.copy(result = output.text, failed = failed, attachments = output.attachments,
            elapsedMs = timer.finish().elapsedMs), checkCancelled)
        return output
    }

    private fun failure(code: String): AgentToolOutput = AgentToolOutput(buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString())
}

class WorkspaceTextFileTool(
    workspaceRoot: File,
    private val maxBytes: Long = 64L * 1024L,
) : AgentTool {
    private val canonicalWorkspaceRoot = workspaceRoot.canonicalFile

    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("read_workspace_file")
        .description("Read a UTF-8 text file from the agent workspace. Paths are relative to the workspace.")
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("path", "Relative path of the workspace file to read.")
                .required("path")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.READ_ONLY
    override val concurrency = AgentToolConcurrency.PARALLEL

    override fun execute(arguments: String): String {
        val relativePath = runCatching {
            Json.parseToJsonElement(arguments)
                .jsonObject["path"]
                ?.jsonPrimitive
                ?.content
        }.getOrNull()?.takeIf(String::isNotBlank) ?: return failure("invalid_arguments")

        val candidate = File(canonicalWorkspaceRoot, relativePath).canonicalFile
        if (!candidate.toPath().startsWith(canonicalWorkspaceRoot.toPath())) {
            return failure("path_outside_workspace")
        }
        if (!candidate.isFile) return failure("file_not_found")
        if (candidate.length() > maxBytes) return failure("file_too_large")

        return buildJsonObject {
            put("path", JsonPrimitive(relativePath))
            put("content", JsonPrimitive(candidate.readText(Charsets.UTF_8)))
        }.toString()
    }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()
}

class AgentWorkspaceListTool(
    private val workspaceRoot: File,
    private val maxEntries: Int = DEFAULT_MAX_LIST_ENTRIES,
    private val maxChars: Int = DEFAULT_MAX_LIST_CHARS,
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("list_agent_workspace")
        .description(
            "List files in the selected mod's isolated agent workspace. The result is bounded, so scope " +
                "it with 'path' (for example source or patch_source/src) when the workspace " +
                "is large; an unbounded listing is truncated.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("path", "Optional workspace-relative directory to scope the listing.")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.READ_ONLY
    override val concurrency = AgentToolConcurrency.PARALLEL

    override fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments")
        val relativePath = json["path"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        val base = if (relativePath == null) {
            workspaceRoot
        } else {
            resolveWorkspaceFile(workspaceRoot, relativePath) ?: return failure("path_outside_workspace")
        }
        if (!base.exists()) return failure("path_not_found")
        if (base.isFile) {
            return buildJsonObject {
                put("root", JsonPrimitive(relativePath ?: base.name))
                put("file_count", JsonPrimitive(1))
                put("truncated", JsonPrimitive(false))
                put("files", kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive(base.relativeTo(workspaceRoot).invariantSeparatorsPath))
                })
            }.toString()
        }

        val selected = ArrayList<String>()
        var chars = 0
        var total = 0
        base.walkTopDown().filter { it.isFile }.forEach { file ->
            total++
            if (selected.size >= maxEntries || chars >= maxChars) return@forEach
            val name = file.relativeTo(workspaceRoot).invariantSeparatorsPath
            selected += name
            chars += name.length + 3
        }
        val truncated = selected.size < total
        return buildJsonObject {
            put("root", JsonPrimitive(relativePath ?: "."))
            put("file_count", JsonPrimitive(total))
            put("returned", JsonPrimitive(selected.size))
            put("truncated", JsonPrimitive(truncated))
            if (truncated) {
                put(
                    "hint",
                    JsonPrimitive("Listing truncated to $maxEntries entries. Pass a narrower path to list a subdirectory."),
                )
            }
            put("files", kotlinx.serialization.json.buildJsonArray {
                selected.forEach { add(JsonPrimitive(it)) }
            })
        }.toString()
    }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()
}

class AgentWorkspaceFileTool(
    private val workspaceRoot: File,
    private val maxLines: Int = DEFAULT_MAX_READ_LINES,
    private val maxLineChars: Int = MAX_READ_LINE_CHARS,
    private val maxBytes: Long = DEFAULT_MAX_READ_BYTES,
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("read_agent_workspace_file")
        .description(
            "Read a file in the selected mod's isolated agent workspace. Text files are read by line " +
                "and returned with line-number prefixes: 'offset' is the 1-indexed first line (default 1) " +
                "and 'limit' the number of lines (default $DEFAULT_MAX_READ_LINES); a line longer than " +
                "$MAX_READ_LINE_CHARS characters is truncated. Continue from 'next_offset' while 'truncated' " +
                "is true. For long lines or tool_output files use encoding=utf8_chars with a zero-based character " +
                "offset and limit; follow next_offset. Use encoding=base64 for binary files, where offsets are bytes. " +
                "PNG, JPEG, WebP and GIF files are returned as images the model can see, with a short JSON summary " +
                "instead of file text.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("path", "Relative path inside the agent workspace.")
                .addStringProperty("encoding", "utf8, utf8_chars (lossless character paging), or base64; defaults to utf8.")
                .addStringProperty(
                    "offset",
                    "1-indexed first line for utf8 (default 1), or byte offset for base64 (default 0).",
                )
                .addStringProperty(
                    "limit",
                    "Maximum lines for utf8 (default $DEFAULT_MAX_READ_LINES), or bytes for base64 (default $DEFAULT_MAX_READ_BYTES).",
                )
                .required("path")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.READ_ONLY
    override val concurrency = AgentToolConcurrency.PARALLEL

    override fun execute(arguments: String): String = executeWithMedia(arguments).text

    override fun executeWithMedia(arguments: String): AgentToolOutput {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failed("invalid_arguments")
        val relativePath = json["path"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: return failed("invalid_arguments")
        val file = resolveWorkspaceFile(workspaceRoot, relativePath) ?: return failed("path_outside_workspace")
        if (!file.isFile) return failed("file_not_found")
        val encoding = json["encoding"]?.jsonPrimitive?.content?.lowercase() ?: "utf8"
        // An explicit base64 request keeps the old copyable-bytes behaviour; the default read path
        // shows an image to the model instead of dumping an unusable base64 string into context.
        if (encoding != "base64") {
            readImage(relativePath, file)?.let { return it }
        }
        val text = when (encoding) {
            "utf8" -> readText(relativePath, file, json)
            "utf8_chars" -> readCharacters(relativePath, file, json)
            "base64" -> readBytes(relativePath, file, json)
            else -> return failed("unsupported_encoding")
        }
        return AgentToolOutput(text)
    }

    /** Returns a viewable image result, or null when the file is not a supported image. */
    private fun readImage(relativePath: String, file: File): AgentToolOutput? {
        // Sniff a small header instead of loading every text read in full just to rule out an image.
        val header = ByteArray(12)
        val headerSize = runCatching { file.inputStream().use { it.read(header) } }
            .getOrElse { return failed("read_failed") }
        if (headerSize <= 0) return null
        AgentImages.detectMime(file.name, header.copyOf(headerSize)) ?: return null
        if (file.length() > MAX_IMAGE_INPUT_BYTES) {
            return failed(
                "image_too_large",
                buildJsonObject { put("total_bytes", JsonPrimitive(file.length())) },
            )
        }
        val bytes = runCatching { file.readBytes() }.getOrElse { return failed("read_failed") }
        // The name or magic bytes said image, but decoding/normalising still failed; returning an
        // error is safer than dumping the binary payload into the text context.
        val payload = AgentImages.prepare(file.name, bytes) ?: return failed("image_unreadable")
        val text = buildJsonObject {
            put("path", JsonPrimitive(relativePath))
            put("type", JsonPrimitive("image"))
            put("mime", JsonPrimitive(payload.mime))
            put("total_bytes", JsonPrimitive(payload.bytes))
            payload.width?.let { put("width", JsonPrimitive(it)) }
            payload.height?.let { put("height", JsonPrimitive(it)) }
            if (payload.downscaled) put("downscaled", JsonPrimitive(true))
            put(
                "note",
                JsonPrimitive(
                    "The image itself is attached to the conversation as viewable content. " +
                        "Describe or reason about what is shown; do not request base64 again.",
                ),
            )
        }.toString()
        return AgentToolOutput(
            text = text,
            attachments = listOf(
                AgentContextAttachment(name = file.name, mime = payload.mime, data = payload.base64),
            ),
        )
    }

    private fun failed(code: String, extra: JsonObject = JsonObject(emptyMap())): AgentToolOutput =
        AgentToolOutput(buildJsonObject {
            put("error", JsonPrimitive(code))
            extra.forEach { (key, value) -> put(key, value) }
        }.toString())

    private fun readCharacters(path: String, file: File, json: JsonObject): String {
        if (file.length() > MAX_TEXT_READ_BYTES) return failure("file_too_large")
        val offset = json.intParam("offset")?.coerceAtLeast(0) ?: 0
        val limit = (json.intParam("limit") ?: 8_000).coerceIn(2, 8_000)
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrElse { return failure("read_failed") }
        if (offset > text.length) return failure("offset_past_end_of_file")
        var end = minOf(text.length.toLong(), offset.toLong() + limit).toInt()
        if (end < text.length && end > offset && Character.isHighSurrogate(text[end - 1])) end--
        return buildJsonObject {
            put("path", JsonPrimitive(path))
            put("encoding", JsonPrimitive("utf8_chars"))
            put("offset", JsonPrimitive(offset))
            put("content", JsonPrimitive(text.substring(offset, end)))
            put("truncated", JsonPrimitive(end < text.length))
            if (end < text.length) put("next_offset", JsonPrimitive(end))
        }.toString()
    }

    /** Reads a window of lines and prefixes each with its 1-indexed line number, like the host's read tool. */
    private fun readText(relativePath: String, file: File, json: JsonObject): String {
        if (file.length() > MAX_TEXT_READ_BYTES) return failure("file_too_large")
        val startLine = json.intParam("offset")?.coerceAtLeast(1) ?: 1
        val lineLimit = (json.intParam("limit") ?: json.intParam("length"))
            ?.coerceAtLeast(1)?.coerceAtMost(maxLines) ?: maxLines
        val content = StringBuilder()
        var linesReturned = 0
        var hasMore = false
        var longLines = false
        try {
            java.io.BufferedReader(
                java.io.InputStreamReader(java.io.FileInputStream(file), Charsets.UTF_8),
            ).use { reader ->
                var skipped = 0
                while (skipped < startLine - 1) {
                    if (reader.readLine() == null) return failure("offset_past_end_of_file")
                    skipped++
                }
                while (linesReturned < lineLimit) {
                    val line = reader.readLine() ?: break
                    if (line.length > maxLineChars) longLines = true
                    content.append(skipped + linesReturned + 1).append(": ")
                    content.append(if (line.length > maxLineChars) line.take(maxLineChars) else line)
                    content.append('\n')
                    linesReturned++
                }
                hasMore = reader.readLine() != null
            }
        } catch (error: java.io.IOException) {
            return failure("read_failed")
        }
        return buildJsonObject {
            put("path", JsonPrimitive(relativePath))
            put("encoding", JsonPrimitive("utf8"))
            put("start_line", JsonPrimitive(startLine))
            put("returned_lines", JsonPrimitive(linesReturned))
            put("truncated", JsonPrimitive(hasMore))
            put("long_lines_truncated", JsonPrimitive(longLines))
            if (longLines) put("hint", JsonPrimitive("Use encoding=utf8_chars to read long lines without losing content."))
            if (hasMore) put("next_offset", JsonPrimitive(startLine + linesReturned))
            put("content", JsonPrimitive(content.toString()))
        }.toString()
    }

    /** Reads a byte range and returns it base64-encoded, so binary resources stay copyable. */
    private fun readBytes(relativePath: String, file: File, json: JsonObject): String {
        val totalBytes = file.length()
        val offset = json.intParam("offset")?.toLong()?.coerceAtLeast(0L) ?: 0L
        if (offset > totalBytes) return failure("offset_past_end_of_file")
        val requested = (json.intParam("limit") ?: json.intParam("length"))?.toLong()?.coerceAtLeast(1L)
            ?: maxBytes
        val readLength = minOf(requested, maxBytes, totalBytes - offset)
        val bytes = runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buffer = ByteArray(readLength.toInt())
                var read = 0
                while (read < buffer.size) {
                    val count = raf.read(buffer, read, buffer.size - read)
                    if (count < 0) break
                    read += count
                }
                if (read == buffer.size) buffer else buffer.copyOf(read)
            }
        }.getOrElse { return failure("read_failed") }
        val nextOffset = offset + bytes.size
        val truncated = nextOffset < totalBytes
        return buildJsonObject {
            put("path", JsonPrimitive(relativePath))
            put("encoding", JsonPrimitive("base64"))
            put("total_bytes", JsonPrimitive(totalBytes))
            put("offset", JsonPrimitive(offset))
            put("returned_bytes", JsonPrimitive(bytes.size))
            put("truncated", JsonPrimitive(truncated))
            if (truncated) put("next_offset", JsonPrimitive(nextOffset))
            put("content", JsonPrimitive(Base64.getEncoder().encodeToString(bytes)))
        }.toString()
    }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()
}

/**
 * Finds files in the agent workspace whose path matches a glob pattern.
 *
 * The companion to [AgentWorkspaceGrepTool]: grep locates files by their contents, glob locates them
 * by their name or extension before the agent reads them. Without it the agent can only page through
 * [AgentWorkspaceListTool], which is capped and gives no way to ask "where are the .java sources".
 */
class AgentWorkspaceGlobTool(
    private val workspaceRoot: File,
    private val maxResults: Int = DEFAULT_MAX_GLOB_RESULTS,
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("glob_agent_workspace")
        .description(
            "Find files in the selected mod's isolated agent workspace by glob pattern, for example " +
                "'**/*.java' or 'source/ThMod/cards/**'. Paths are workspace-relative and always use '/' " +
                "separators. Prefer this over list_agent_workspace when you know the extension or a path " +
                "fragment; the result is capped at $DEFAULT_MAX_GLOB_RESULTS matches.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("pattern", "Glob pattern, for example '**/*.java' or 'source/ThMod/**'.")
                .addStringProperty("path", "Optional workspace-relative directory to scope the search.")
                .required("pattern")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.READ_ONLY
    override val concurrency = AgentToolConcurrency.PARALLEL

    override fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments")
        val rawPattern = json["pattern"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: return failure("invalid_arguments")
        val scopeArg = json["path"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        val base = if (scopeArg == null) {
            workspaceRoot
        } else {
            resolveWorkspaceFile(workspaceRoot, scopeArg) ?: return failure("path_outside_workspace")
        }
        if (!base.exists()) return failure("path_not_found")
        if (!base.isDirectory) return failure("not_a_directory")

        val normalized = rawPattern.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.split('/').any { it == ".." }) {
            return failure("invalid_pattern")
        }
        val matches = compileGlob(normalized) ?: return failure("invalid_pattern")

        val matched = ArrayList<String>()
        var truncated = false
        base.walkTopDown().filter { it.isFile }.forEach { file ->
            if (matched.size >= maxResults) {
                truncated = true
                return@forEach
            }
            val relative = file.relativeTo(workspaceRoot).invariantSeparatorsPath
            if (matches(relative)) matched += relative
        }
        matched.sort()
        val capped = truncated || matched.size > maxResults
        val returned = matched.take(maxResults)
        return buildJsonObject {
            put("pattern", JsonPrimitive(normalized))
            put("root", JsonPrimitive(scopeArg ?: "."))
            put("returned", JsonPrimitive(returned.size))
            put("truncated", JsonPrimitive(capped))
            if (capped) {
                put(
                    "hint",
                    JsonPrimitive("More than $maxResults matches. Add a 'path' scope or a narrower pattern."),
                )
            }
            put("files", buildJsonArray { returned.forEach { add(JsonPrimitive(it)) } })
        }.toString()
    }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()
}

/**
 * Searches the text of the agent workspace for a regular expression.
 *
 * This is the locate step that was missing: the agent can search every source file for a symbol or
 * string instead of reading files one by one. Only whole matching lines come back, with file, line
 * number, and a truncated line preview, and the result stops at [DEFAULT_MAX_GREP_MATCHES] lines.
 */
class AgentWorkspaceGrepTool(
    private val workspaceRoot: File,
    private val maxMatches: Int = DEFAULT_MAX_GREP_MATCHES,
    private val maxLineChars: Int = MAX_GREP_LINE_CHARS,
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("grep_agent_workspace")
        .description(
            "Search the text of files in the selected mod's isolated agent workspace by regular " +
                "expression. Returns matching lines as file:line: text, capped at $DEFAULT_MAX_GREP_MATCHES " +
                "matches. Use 'include' to restrict files (for example '*.java' or '*.json'). Matches that " +
                "contain NUL bytes are treated as binary and skipped.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("pattern", "Java regular expression, for example 'CustomRelic|addCard'.")
                .addStringProperty("path", "Optional workspace-relative directory to scope the search.")
                .addStringProperty("include", "Optional glob applied to the workspace-relative path, for example '*.java'.")
                .required("pattern")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.READ_ONLY
    override val concurrency = AgentToolConcurrency.PARALLEL

    override fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments")
        val patternArg = json["pattern"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: return failure("invalid_arguments")
        val scopeArg = json["path"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        val includeArg = json["include"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        val base = if (scopeArg == null) {
            workspaceRoot
        } else {
            resolveWorkspaceFile(workspaceRoot, scopeArg) ?: return failure("path_outside_workspace")
        }
        if (!base.exists()) return failure("path_not_found")

        val regex = try {
            Regex(patternArg, RegexOption.MULTILINE)
        } catch (error: IllegalArgumentException) {
            return failure("invalid_pattern")
        }
        val includeGlob = includeArg?.let { compileGlob(it.replace('\\', '/')) }
        if (includeArg != null && includeGlob == null) return failure("invalid_include")
        val include = includeGlob

        val files = ArrayList<File>()
        if (base.isFile) {
            files += base
        } else {
            base.walkTopDown().filter { it.isFile }.forEach { files += it }
        }
        files.sortBy { it.relativeTo(workspaceRoot).invariantSeparatorsPath }

        val matches = ArrayList<GrepMatch>()
        var truncated = false
        var searchedFiles = 0
        for (file in files) {
            val relative = file.relativeTo(workspaceRoot).invariantSeparatorsPath
            if (include != null && !include(relative) && !include(file.name)) continue
            if (file.length() > MAX_GREP_FILE_BYTES) continue
            val content = runCatching { file.readText(Charsets.UTF_8) }.getOrElse { continue }
            if (content.indexOf('\u0000') >= 0) continue
            searchedFiles++
            val lines = content.split('\n')
            for (lineIndex in lines.indices) {
                if (matches.size >= maxMatches) {
                    truncated = true
                    break
                }
                val line = lines[lineIndex]
                if (regex.containsMatchIn(line)) {
                    matches += GrepMatch(relative, lineIndex + 1, line)
                }
            }
            if (truncated) break
        }
        return buildJsonObject {
            put("pattern", JsonPrimitive(patternArg))
            if (includeArg != null) put("include", JsonPrimitive(includeArg))
            put("root", JsonPrimitive(scopeArg ?: "."))
            put("searched_files", JsonPrimitive(searchedFiles))
            put("returned", JsonPrimitive(matches.size))
            put("truncated", JsonPrimitive(truncated))
            if (truncated) {
                put(
                    "hint",
                    JsonPrimitive("Match limit ($maxMatches) reached. Scope with 'path' or 'include', or use a narrower pattern."),
                )
            }
            put("matches", buildJsonArray {
                matches.forEach { match ->
                    add(buildJsonObject {
                        put("path", JsonPrimitive(match.path))
                        put("line", JsonPrimitive(match.line))
                        put(
                            "text",
                            JsonPrimitive(match.text.take(maxLineChars)),
                        )
                    })
                }
            })
        }.toString()
    }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()

    private data class GrepMatch(val path: String, val line: Int, val text: String)
}

class AgentWorkspaceWriteTool(
    private val workspaceRoot: File,
    private val maxBytes: Long = 64L * 1024L * 1024L,
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("write_agent_workspace_file")
        .description("Create or replace a file under patch_source/<patch_id>/ in the selected mod's agent workspace. Use the patch_workspace_path returned by create_agent_patch_workspace. The source/ tree is read-only and cannot be modified by the agent.")
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("path", "Workspace-relative path under patch_source/<patch_id>/.")
                .addStringProperty("content", "UTF-8 file content.")
                .addStringProperty("contentBase64", "Binary content encoded as base64.")
                .required("path")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.WORKSPACE_WRITE
    override val concurrency = AgentToolConcurrency.EXCLUSIVE

    override fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments")
        val relativePath = json["path"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: return failure("invalid_arguments")
        val target = resolvePatchSourceFile(workspaceRoot, relativePath) ?: return failure("path_outside_patch_source")
        val hasText = json["content"] != null
        val hasBytes = json["contentBase64"] != null
        if (hasText == hasBytes) return failure("provide_exactly_one_content_field")
        val bytes = runCatching {
            if (hasBytes) Base64.getMimeDecoder().decode(json["contentBase64"]!!.jsonPrimitive.content)
            else json["content"]!!.jsonPrimitive.content.toByteArray(Charsets.UTF_8)
        }.getOrElse { return failure("invalid_content") }
        if (bytes.size > maxBytes) return failure("file_too_large")
        target.parentFile?.mkdirs()
        runCatching { target.writeBytes(bytes) }.getOrElse { return failure("write_failed") }
        return buildJsonObject {
            put("path", JsonPrimitive(relativePath))
            put("bytes", JsonPrimitive(bytes.size))
        }.toString()
    }
}

class AgentWorkspaceDeleteTool(
    private val workspaceRoot: File,
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("delete_agent_workspace_file")
        .description(
            "Delete a file or directory under patch_source/<patch_id>/ in the selected mod's agent workspace. Pass " +
                "recursive=true to delete a directory and everything under it. The source/ tree is read-only.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("path", "Workspace-relative path under patch_source/<patch_id>/.")
                .addBooleanProperty("recursive", "Delete a non-empty directory and everything under it. Defaults to false.")
                .required("path")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.WORKSPACE_WRITE
    override val concurrency = AgentToolConcurrency.EXCLUSIVE

    override fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments")
        val relativePath = json["path"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: return failure("invalid_arguments")
        val recursive = json["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
        val target = resolvePatchSourceFile(workspaceRoot, relativePath) ?: return failure("path_outside_patch_source")
        val targetPath = target.toPath()
        if (targetPath == File(workspaceRoot, "patch_source").canonicalFile.toPath()) {
            return failure("cannot_delete_patch_source_root")
        }
        if (!target.exists()) return failure("file_not_found")

        val wasDirectory = target.isDirectory
        val deletedFiles = if (wasDirectory && recursive) target.walkBottomUp().count { it.isFile } else 0
        val deleted = runCatching {
            if (wasDirectory && !recursive) {
                target.delete()
            } else {
                target.deleteRecursively()
            }
        }.getOrElse { return failure("delete_failed") }
        if (!deleted && target.exists()) return failure("delete_failed")
        return buildJsonObject {
            put("path", JsonPrimitive(relativePath))
            put("type", JsonPrimitive(if (wasDirectory) "directory" else "file"))
            put("files_deleted", JsonPrimitive(deletedFiles))
        }.toString()
    }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()
}

/** Inspects one installed-mod class without creating an extracted source tree. */
class AgentModClassInspectTool(
    private val parentModId: String,
    private val parentJar: File,
    private val readTargets: AgentModReadTargets = AgentModReadTargets(parentModId, parentJar) { emptyList() },
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("inspect_agent_class")
        .description(
            "Inspect one class from the parent or another installed mod without extracting the JAR. Returns the " +
                "exact bytecode origin and declared public/protected API; use decompile_agent_class " +
                "when method behavior or private implementation is needed.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("class_name", "Binary class name, for example com.example.MyCard.")
                .addStringProperty("member_filter", "Optional case-insensitive member-name filter.")
                .addStringProperty("mod_id", "Optional id from list_installed_mods; defaults to the parent mod.")
                .required("class_name")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.READ_ONLY
    override val concurrency = AgentToolConcurrency.PARALLEL

    override fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments")
        val className = json["class_name"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: return failure("invalid_arguments")
        val memberFilter = json["member_filter"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        val target = runCatching { readTargets.resolve(json["mod_id"]?.jsonPrimitive?.content) }
            .getOrElse { return failure(it.message ?: "mod_resolution_failed") }
        val detail = AgentApiIndex.describe(listOf(target.jar), className, memberFilter)
            ?: return failure("class_not_found")
        val entry = AgentModJarReader.classEntryName(target.jar, className)
        return buildJsonObject {
            put("name", JsonPrimitive(detail.summary.binaryName))
            put("origin", JsonPrimitive(if (entry == null) detail.summary.origin else entry))
            put("parent_mod_id", JsonPrimitive(parentModId))
            put("mod_id", JsonPrimitive(target.modId))
            put("constructors", members(detail.constructors))
            put("methods", members(detail.methods))
            put("fields", members(detail.fields))
            put(
                "next_step",
                JsonPrimitive("Call decompile_agent_class for the implementation of this class."),
            )
        }.toString()
    }

    private fun members(values: List<io.stamethyst.backend.mods.ApiMember>) = buildJsonArray {
        values.take(MAX_MEMBERS).forEach { member ->
            add(buildJsonObject {
                put("name", JsonPrimitive(member.name))
                put("declaration", JsonPrimitive(member.declaration))
            })
        }
    }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()

    private companion object {
        const val MAX_MEMBERS = 400
    }
}

/** Decompiles one installed-mod class; only parent classes may enter the workspace source cache. */
class AgentModClassDecompileTool(
    private val context: Context,
    private val parentModId: String,
    private val parentJar: File,
    private val readTargets: AgentModReadTargets = AgentModReadTargets(parentModId, parentJar) { emptyList() },
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("decompile_agent_class")
        .description(
            "Decompile one class from the parent or another installed mod on demand. Use mod_id from " +
                "list_installed_mods and list_agent_mod_entries to discover its classes. Other mods " +
                "are read-only: their source is returned inline, never added to the patch workspace.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("class_name", "Binary class name from search_agent_api or inspect_agent_class.")
                .addBooleanProperty("materialize", "Cache parent source under source/; defaults to true. Always false for other mods.")
                .addStringProperty("mod_id", "Optional id from list_installed_mods; defaults to the parent mod.")
                .required("class_name")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.MOD_INSPECTION
    // Decompilation materializes source and updates the shared inspection manifest/scratch cache.
    override val concurrency = AgentToolConcurrency.EXCLUSIVE

    override fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments")
        val className = json["class_name"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: return failure("invalid_arguments")
        val target = runCatching { readTargets.resolve(json["mod_id"]?.jsonPrimitive?.content) }
            .getOrElse { return failure(it.message ?: "mod_resolution_failed") }
        val isParent = target.jar.canonicalFile == parentJar.canonicalFile
        val materialize = isParent && (json["materialize"]?.jsonPrimitive?.booleanOrNull ?: true)
        val inspection = if (isParent) runCatching {
            AgentModInspectionManager.createInspection(context, parentModId, parentJar)
        }.getOrElse { return failure(it.message ?: "inspection_failed") } else null
        val classpath = runCatching {
            AgentPatchSourceCompiler.buildClasspath(context, target.jar)
        }.getOrElse { return failure(it.message ?: "classpath_unavailable") }
        val result = runCatching {
            AgentPatchClassDecompiler.decompileClass(
                jarFile = target.jar,
                binaryName = className,
                classpath = classpath,
                scratchDir = File(context.cacheDir, "agent-class-decompile"),
            )
        }.getOrElse { return failure(it.message ?: "decompile_failed") }
        val sourceFile = inspection?.let { AgentModSourceSnapshot.sourceFileForEntry(it.sourceRoot, result.classEntry) }
        if (materialize && sourceFile != null && inspection != null) {
            sourceFile.parentFile?.mkdirs()
            sourceFile.writeText(result.source, StandardCharsets.UTF_8)
            AgentModInspectionManager.recordOnDemandClass(inspection, className)
        }
        return buildJsonObject {
            put("status", JsonPrimitive("decompiled"))
            put("class_name", JsonPrimitive(result.requestedClass))
            put("class_entry", JsonPrimitive(result.classEntry))
            put("mod_id", JsonPrimitive(target.modId))
            put("materialized", JsonPrimitive(materialize))
            if (sourceFile != null && inspection != null) {
                put("source_path", JsonPrimitive(sourceFile.relativeTo(inspection.root).invariantSeparatorsPath))
                put("source_sha256", JsonPrimitive(inspection.sourceSha256))
            }
            put("source", JsonPrimitive(result.source))
        }.toString()
    }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()
}

/**
 * Decompiles game/BaseMod/StSLib/dependency classes from the compile classpath.
 *
 * Installed-mod classes still use decompile_agent_class so their source can be cached under the
 * parent workspace. API source is returned inline, cached on device, and never enters that tree.
 * Large classes come back as a member index plus a signature skeleton, or as one requested member.
 */
class AgentApiClassDecompileTool(
    private val context: Context,
    private val parentJar: File,
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("decompile_agent_api_class")
        .description(
            "Decompile game, BaseMod, StSLib, ModTheSpire, or bundled dependency classes from the " +
                "compile classpath to Java source. Pass 'class_names' (or 'class_name' as an array) " +
                "to read several classes in one call. A class above the inline cap returns a member " +
                "index and a signature skeleton; pass 'member' to read one method, constructor, or " +
                "field body. Source is returned inline, cached on device, and never added to the " +
                "patch workspace; for installed mod classes use decompile_agent_class.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty(
                    "class_name",
                    "Binary class name from search_agent_api or describe_agent_api_class. An array of names is also accepted.",
                )
                .addProperty(
                    "class_names",
                    JsonArraySchema.builder()
                        .description("Several binary class names to decompile in one call; at most $MAX_API_CLASSES.")
                        .items(JsonStringSchema.builder().build())
                        .build(),
                )
                .addStringProperty(
                    "member",
                    "Optional method, constructor, or field name. When the full source exceeds the cap, " +
                        "only the matching member body is returned.",
                )
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.MOD_INSPECTION
    // The decompiler keeps one warm CFR driver per thread; cache entries are written atomically.
    override val concurrency = AgentToolConcurrency.PARALLEL

    override fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments")
        val requested = requestedClasses(json)
        if (requested.isEmpty()) return failure("invalid_arguments")
        val member = json["member"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        val resolution = runCatching {
            AgentPatchSourceCompiler.resolveCompileClasspathEntries(context, parentJar)
        }.getOrElse { error ->
            return buildJsonObject {
                put("error", JsonPrimitive("classpath_unavailable"))
                put("message", JsonPrimitive(error.message ?: error.javaClass.simpleName))
            }.toString()
        }
        val classpath = resolution.entries.joinToString(File.pathSeparator) { it.absolutePath }
        val scratchDir = File(context.cacheDir, "agent-api-decompile")
        val cacheRoot = AgentApiSourceCache.root(context)
        // Share one inline budget across the batch so a multi-class result still fits the tool-result
        // cap instead of being truncated by the gateway before the model sees it.
        val budget = (MAX_API_BATCH_CHARS / requested.size)
            .coerceIn(MIN_API_CLASS_CHARS, MAX_API_SOURCE_CHARS)

        val results = requested.map { className ->
            decompileOne(className, resolution.entries, classpath, scratchDir, cacheRoot, member, budget)
        }
        if (results.size == 1) return results.single().toString()
        val failed = results.count { "error" in it }
        return buildJsonObject {
            put(
                "status",
                JsonPrimitive(
                    when {
                        failed == 0 -> "decompiled"
                        failed == results.size -> "failed"
                        else -> "partial"
                    },
                ),
            )
            put("results", buildJsonArray { results.forEach { add(it) } })
            put("next_step", JsonPrimitive(MULTI_NEXT_STEP))
        }.toString()
    }

    private fun decompileOne(
        className: String,
        entries: List<File>,
        classpath: String,
        scratchDir: File,
        cacheRoot: File?,
        member: String?,
        budget: Int,
    ): JsonObject {
        val binaryName = AgentPatchTargetInspector.normalizeClassName(className)
        val jar = entries.asSequence()
            .filter { it.isFile }
            .firstOrNull { AgentModJarReader.classEntryName(it, binaryName) != null }
            ?: return buildJsonObject {
                put("error", JsonPrimitive("class_not_found"))
                put("class_name", JsonPrimitive(binaryName))
                put(
                    "next_step",
                    JsonPrimitive(
                        "Use search_agent_api to find the exact binary name. Game, BaseMod, StSLib, " +
                            "ModTheSpire and bundled dependency classes are on this classpath.",
                    ),
                )
            }

        val cached = cacheRoot?.let { AgentApiSourceCache.read(it, jar, binaryName) }
        val source = if (cached != null) {
            cached
        } else {
            runCatching {
                AgentPatchClassDecompiler.decompileClass(
                    jarFile = jar,
                    binaryName = binaryName,
                    classpath = classpath,
                    scratchDir = scratchDir,
                ).source
            }.getOrElse { error ->
                return buildJsonObject {
                    put("error", JsonPrimitive("decompile_failed"))
                    put("class_name", JsonPrimitive(binaryName))
                    put("message", JsonPrimitive(error.message ?: error.javaClass.simpleName))
                    put(
                        "next_step",
                        JsonPrimitive(
                            "Use inspect_agent_patch_target for the exact method bytecode, or decompile " +
                                "a smaller helper class.",
                        ),
                    )
                }
            }.also { decompiled ->
                if (cacheRoot != null) AgentApiSourceCache.write(cacheRoot, jar, binaryName, decompiled)
            }
        }
        val entry = AgentModJarReader.classEntryName(jar, binaryName) ?: binaryName
        return buildJsonObject {
            put("status", JsonPrimitive("decompiled"))
            put("class_name", JsonPrimitive(binaryName))
            put("class_entry", JsonPrimitive(entry))
            put("origin", JsonPrimitive(jar.name))
            put("source_chars", JsonPrimitive(source.length))
            put("cached", JsonPrimitive(cached != null))
            if (source.length <= budget) {
                put("truncated", JsonPrimitive(false))
                put("source", JsonPrimitive(source))
                put("next_step", JsonPrimitive(FULL_NEXT_STEP))
            } else {
                appendSlicedSource(this, source, member, budget)
            }
        }
    }

    /**
     * Above the inline cap the class is never cut mid-file: the model gets the member index plus
     * either the requested member body or a signature-only skeleton it can page through by name.
     * The index is sized from [budget] so index and text together stay inside it.
     */
    private fun appendSlicedSource(
        target: kotlinx.serialization.json.JsonObjectBuilder,
        source: String,
        member: String?,
        budget: Int,
    ) {
        val members = JavaSourceMembers.members(source)
        val indexLimit = (budget / INDEX_CHARS_PER_BUDGET)
            .coerceIn(MIN_API_MEMBER_INDEX, MAX_API_MEMBER_INDEX)
        val index = memberIndex(members, indexLimit)
        val textBudget = (budget - index.toString().length).coerceAtLeast(budget / 2)
        val chosen = member?.let { JavaSourceMembers.select(members, it, MAX_API_MEMBER_OVERLOADS) }.orEmpty()
        if (chosen.isNotEmpty()) {
            val text = chosen.joinToString("\n\n") { JavaSourceMembers.text(source, it) }
            val returned = text.take(textBudget)
            target.put("truncated", JsonPrimitive(returned.length < text.length))
            target.put("sliced", JsonPrimitive(true))
            target.put("member", JsonPrimitive(member!!))
            target.put("member_index", index)
            target.put("returned_chars", JsonPrimitive(returned.length))
            target.put("source", JsonPrimitive(returned))
            target.put(
                "next_step",
                JsonPrimitive(
                    if (returned.length < text.length) {
                        "This member is larger than the cap; use inspect_agent_patch_target for its bytecode."
                    } else {
                        SLICED_NEXT_STEP
                    },
                ),
            )
            return
        }
        val full = JavaSourceMembers.skeleton(source) ?: source
        val body = full.take(textBudget)
        target.put("truncated", JsonPrimitive(body.length < full.length))
        target.put("sliced", JsonPrimitive(false))
        target.put("member_index", index)
        target.put("returned_chars", JsonPrimitive(body.length))
        target.put("skeleton", JsonPrimitive(body))
        target.put("next_step", JsonPrimitive(SKELETON_NEXT_STEP))
    }

    private fun memberIndex(members: List<JavaSourceMembers.Member>, limit: Int) = buildJsonArray {
        members.take(limit).forEach { member ->
            add(buildJsonObject {
                put("name", JsonPrimitive(member.name))
                put("kind", JsonPrimitive(member.kind))
                put(
                    "declaration",
                    JsonPrimitive(JavaSourceMembers.declaration(member).take(MAX_API_DECLARATION_CHARS)),
                )
            })
        }
    }

    /** Accepts `class_name` as a string or array, plus the explicit `class_names` array. */
    private fun requestedClasses(json: JsonObject): List<String> {
        val names = LinkedHashSet<String>()
        fun collect(element: JsonElement?) {
            when (element) {
                is JsonArray -> element.forEach(::collect)
                is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.let(names::add)
                else -> Unit
            }
        }
        collect(json["class_name"])
        collect(json["class_names"])
        return names.take(MAX_API_CLASSES)
    }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()

    private companion object {
        const val MAX_API_CLASSES = 8
        const val MAX_API_BATCH_CHARS = 30_000
        const val MIN_API_CLASS_CHARS = 3_000
        const val MAX_API_MEMBER_INDEX = 200
        const val MIN_API_MEMBER_INDEX = 20
        const val MAX_API_MEMBER_OVERLOADS = 4
        const val MAX_API_DECLARATION_CHARS = 160
        /** Rough JSON size of one member-index entry, used to share the budget with the text. */
        const val INDEX_CHARS_PER_BUDGET = 128
        const val FULL_NEXT_STEP =
            "Use inspect_agent_patch_target to confirm the exact overload, descriptor and local " +
                "variables before patching."
        const val SLICED_NEXT_STEP =
            "Read other members by name with 'member', or use inspect_agent_patch_target for the exact overload."
        const val SKELETON_NEXT_STEP =
            "The full class exceeds the inline cap. Pass 'member' to read one method, constructor, " +
                "or field body, or use inspect_agent_patch_target for the exact overload."
        const val MULTI_NEXT_STEP =
            "Use inspect_agent_patch_target to confirm the exact overload, descriptor and local " +
                "variables before patching. Pass 'member' to read one member of a large class."
    }
}

/** Reads one resource entry or byte range without extracting the selected archive. */
class AgentModResourceReadTool(
    private val parentJar: File,
    private val readTargets: AgentModReadTargets = AgentModReadTargets("", parentJar) { emptyList() },
    gameJar: File? = null,
) : AgentTool {
    private val resourceTargets = AgentResourceReadTargets(readTargets, gameJar)

    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("read_agent_mod_resource")
        .description(
            "Read a resource entry from the parent mod, another installed mod, or the game desktop-1.0.jar " +
                "without extracting the JAR. Set source=game for original game assets; otherwise source defaults to mod. " +
                "Discover paths with list_agent_mod_entries using the same source and mod_id. Use " +
                "utf8 for JSON/XML/text and base64 for binary assets; reads are bounded and pageable. " +
                "PNG, JPEG, WebP and GIF resources are returned as images the model can see, with a short JSON summary.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("entry", "JAR-relative resource path, for example resources/cards.json.")
                .addStringProperty("source", "mod or game; defaults to mod. game reads the imported desktop-1.0.jar.")
                .addStringProperty("mod_id", "Optional id from list_installed_mods; defaults to the parent mod. Only valid for source=mod.")
                .addStringProperty("encoding", "utf8 or base64; defaults to utf8.")
                .addStringProperty("offset", "Zero-based byte offset; defaults to 0.")
                .addStringProperty("limit", "Maximum bytes to return; defaults to 16384.")
                .required("entry")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.READ_ONLY
    override val concurrency = AgentToolConcurrency.PARALLEL

    override fun execute(arguments: String): String = executeWithMedia(arguments).text

    override fun executeWithMedia(arguments: String): AgentToolOutput {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments")
        val entry = json["entry"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: return failure("invalid_arguments")
        val encoding = json["encoding"]?.jsonPrimitive?.content?.lowercase() ?: "utf8"
        if (encoding != "utf8" && encoding != "base64") return failure("unsupported_encoding")
        val offset = json["offset"]?.jsonPrimitive?.content?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val limit = (json["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: DEFAULT_LIMIT)
            .coerceIn(1, DEFAULT_LIMIT)
        val target = runCatching {
            resourceTargets.resolve(json["source"]?.jsonPrimitive?.content, json["mod_id"]?.jsonPrimitive?.content)
        }
            .getOrElse { return failure(it.message ?: "mod_resolution_failed") }
        if (encoding == "utf8" && offset == 0L) {
            readImage(target, entry)?.let { return it }
        }
        val range = runCatching {
            AgentModJarReader.readEntryRange(target.jar, entry, offset, limit)
        }.getOrElse { return failure(it.message ?: "resource_read_failed") }
            ?: return failure("resource_not_found")
        return AgentToolOutput(buildJsonObject {
            put("entry", JsonPrimitive(entry))
            put("source", JsonPrimitive(target.source))
            put("archive", JsonPrimitive(target.jar.name))
            target.modId?.let { put("mod_id", JsonPrimitive(it)) }
            put("encoding", JsonPrimitive(encoding))
            put("offset", JsonPrimitive(range.offset))
            put("total_bytes", JsonPrimitive(range.totalBytes))
            put("returned_bytes", JsonPrimitive(range.bytes.size))
            put("truncated", JsonPrimitive(range.truncated))
            put(
                "content",
                JsonPrimitive(
                    if (encoding == "base64") {
                        Base64.getEncoder().encodeToString(range.bytes)
                    } else {
                        range.bytes.toString(StandardCharsets.UTF_8)
                    },
                ),
            )
            if (range.truncated) put("next_offset", JsonPrimitive(range.offset + range.bytes.size))
        }.toString())
    }

    /** Returns a viewable image for a whole-entry read, or null when the entry is not an image. */
    private fun readImage(target: AgentResourceReadTarget, entry: String): AgentToolOutput? {
        val jar = target.jar
        val name = entry.substringAfterLast('/')
        // Read only a header first: most resources are text and must not be pulled into memory.
        val header = runCatching { AgentModJarReader.readEntryRange(jar, entry, 0L, 12) }.getOrNull()
            ?: return null
        AgentImages.detectMime(name, header.bytes) ?: return null
        if (header.totalBytes > MAX_IMAGE_INPUT_BYTES) return failure("image_too_large")
        val range = runCatching {
            AgentModJarReader.readEntryRange(jar, entry, 0L, MAX_IMAGE_INPUT_BYTES.toInt())
        }.getOrNull() ?: return failure("resource_read_failed")
        val payload = AgentImages.prepare(name, range.bytes) ?: return failure("image_unreadable")
        val text = buildJsonObject {
            put("entry", JsonPrimitive(entry))
            put("source", JsonPrimitive(target.source))
            put("archive", JsonPrimitive(jar.name))
            target.modId?.let { put("mod_id", JsonPrimitive(it)) }
            put("type", JsonPrimitive("image"))
            put("mime", JsonPrimitive(payload.mime))
            put("total_bytes", JsonPrimitive(payload.bytes))
            payload.width?.let { put("width", JsonPrimitive(it)) }
            payload.height?.let { put("height", JsonPrimitive(it)) }
            if (payload.downscaled) put("downscaled", JsonPrimitive(true))
            put(
                "note",
                JsonPrimitive(
                    "The image itself is attached to the conversation as viewable content. " +
                        "Describe or reason about what is shown; do not request base64 again.",
                ),
            )
        }.toString()
        return AgentToolOutput(
            text = text,
            attachments = listOf(
                AgentContextAttachment(
                    name = entry.substringAfterLast('/'),
                    mime = payload.mime,
                    data = payload.base64,
                ),
            ),
        )
    }

    private fun failure(code: String): AgentToolOutput = AgentToolOutput(buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString())

    private companion object {
        const val DEFAULT_LIMIT = 16 * 1024
        const val MAX_IMAGE_INPUT_BYTES = 24 * 1024 * 1024
    }
}

/** Lists archive paths so resources can be discovered before reading them. */
class AgentModEntriesListTool(
    private val parentJar: File,
    private val readTargets: AgentModReadTargets = AgentModReadTargets("", parentJar) { emptyList() },
    gameJar: File? = null,
) : AgentTool {
    private val resourceTargets = AgentResourceReadTargets(readTargets, gameJar)

    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("list_agent_mod_entries")
        .description(
            "List paths in the parent mod, another installed mod, or the game desktop-1.0.jar without extracting it. " +
                "Set source=game to discover original game assets; otherwise source defaults to mod. Filter with a path prefix " +
                "and choose resources, classes, or all entries; use read_agent_mod_resource to read resources.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("prefix", "Optional case-sensitive JAR path prefix.")
                .addStringProperty("source", "mod or game; defaults to mod. game reads the imported desktop-1.0.jar.")
                .addStringProperty("mod_id", "Optional id from list_installed_mods; defaults to the parent mod. Only valid for source=mod.")
                .addStringProperty("entry_type", "resources, classes, or all; defaults to resources.")
                .addStringProperty("offset", "Zero-based result offset; defaults to 0.")
                .addStringProperty("limit", "Maximum entries to return; defaults to 100.")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.READ_ONLY
    override val concurrency = AgentToolConcurrency.PARALLEL

    override fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments")
        val prefix = json["prefix"]?.jsonPrimitive?.content.orEmpty()
        val type = json["entry_type"]?.jsonPrimitive?.content?.lowercase() ?: "resources"
        if (type !in setOf("resources", "classes", "all")) return failure("invalid_entry_type")
        val offset = (json["offset"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val limit = (json["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: DEFAULT_LIMIT)
            .coerceIn(1, MAX_LIMIT)
        val target = runCatching {
            resourceTargets.resolve(json["source"]?.jsonPrimitive?.content, json["mod_id"]?.jsonPrimitive?.content)
        }
            .getOrElse { return failure(it.message ?: "mod_resolution_failed") }
        val entries = runCatching {
            AgentModJarReader.listEntries(target.jar)
                .asSequence()
                .filterNot { it.endsWith("/") }
                .filter { it.startsWith(prefix) }
                .filter { entry ->
                    when (type) {
                        "classes" -> entry.endsWith(".class", ignoreCase = true)
                        "resources" -> !entry.endsWith(".class", ignoreCase = true)
                        else -> true
                    }
                }
                .sorted()
                .toList()
        }.getOrElse { return failure(it.message ?: "archive_list_failed") }
        return buildJsonObject {
            put("entry_type", JsonPrimitive(type))
            put("source", JsonPrimitive(target.source))
            put("archive", JsonPrimitive(target.jar.name))
            target.modId?.let { put("mod_id", JsonPrimitive(it)) }
            put("prefix", JsonPrimitive(prefix))
            put("total_entries", JsonPrimitive(entries.size))
            put("offset", JsonPrimitive(offset))
            put("entries", buildJsonArray {
                entries.drop(offset).take(limit).forEach { add(JsonPrimitive(it)) }
            })
            if (offset < entries.size && entries.size - offset > limit) {
                put("next_offset", JsonPrimitive(offset + limit))
            }
        }.toString()
    }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()

    private companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 500
    }
}

class AgentPatchWorkspaceCreateTool(
    private val context: Context,
    private val parentModId: String,
    private val sourceJar: File,
    private val onCreated: (AgentPatchWorkspace) -> Unit = {},
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("create_agent_patch_workspace")
        .description(
            "Create a new writable workspace for preparing a patch mod for the selected parent mod. Records an " +
                "immutable parent JAR snapshot and prepares an editable patch_source/<patch_id>/ tree without " +
                "extracting the full original mod. This does " +
                "not compile, package, install, or enable a mod. Call this once only when the user requests a new " +
                "patch and no existing patch workspace should be reused. For changes to an existing patch, reuse its " +
                "patch_source/<patch_id>/ tree and update it instead. Call decompile_agent_mod_source independently " +
                "if you only need to read the parent mod's classes.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty(
                    "name",
                    "Use a concise Chinese display name identifying the main feature or fix. Preserve proper mod " +
                        "names when needed. Avoid generic names such as 'AI补丁', '测试补丁', or '优化补丁', " +
                        "and do not include the version number.",
                )
                .addStringProperty("version", "Semantic version of the patch mod; defaults to 0.1.0.")
                .addStringProperty(
                    "description",
                    "Briefly describe in Chinese the problem or request addressed and the concrete changes. " +
                        "Briefly list distinct changes when applicable. Describe only implemented behavior, not " +
                        "plans or unverified results. Revise this description whenever it no longer matches the actual patch.",
                )
                .required("name")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.PATCH_CREATE
    override val concurrency = AgentToolConcurrency.EXCLUSIVE

    override fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments")
        val name = json["name"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: return failure("invalid_arguments")
        val version = json["version"]?.jsonPrimitive?.content.orEmpty()
        val description = json["description"]?.jsonPrimitive?.content.orEmpty()

        val workspace = runCatching {
            AgentPatchModManager.createWorkspace(
                context = context,
                parentModId = parentModId,
                sourceJar = sourceJar,
                name = name,
                version = version,
                description = description,
            )
        }.getOrElse { return failure(it.message ?: "create_failed") }
        onCreated(workspace)
        return describe(workspace)
    }

    private fun describe(workspace: AgentPatchWorkspace): String =
        buildJsonObject {
            put("status", JsonPrimitive("created"))
            put("parent_mod_id", JsonPrimitive(workspace.parentModId))
            put("patch_id", JsonPrimitive(workspace.patchId))
            put("workspace_root", JsonPrimitive(workspace.root.absolutePath))
            put("source_root", JsonPrimitive(workspace.sourceRoot.absolutePath))
            put("patch_root", JsonPrimitive(workspace.patchRoot.absolutePath))
            put(
                "patch_workspace_path",
                JsonPrimitive(workspace.patchRoot.relativeTo(workspace.root).invariantSeparatorsPath),
            )
            put(
                "source_note",
                JsonPrimitive(
                    "source/ is an on-demand read-only cache for parent classes; use inspect_agent_class, " +
                        "decompile_agent_class, and read_agent_mod_resource to access the JAR. " +
                        "This patch mod's files belong under patch_source/${workspace.patchId}/.",
                ),
            )
        }.toString()

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()
}

class AgentPatchModCompileTool(
    private val context: Context,
    private val parentJar: File,
    private val workspace: () -> AgentPatchWorkspace?,
    private val resolveWorkspace: (String) -> AgentPatchWorkspace? = { null },
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("compile_agent_patch_source")
        .description(
            "Compile Java sources under a patch workspace's src/ into Java 8 .class files at that patch root. " +
                "Run this after writing source and before package_agent_patch_mod or update_agent_patch_mod. " +
                "Each run replaces the previous class tree, so classes from renamed or deleted sources cannot " +
                "survive into the package. Debug info (-g) is always emitted because ModTheSpire reads patch " +
                "parameter names from the LocalVariableTable. " +
                "Defaults to the current session's patch workspace; pass patch_id to compile an existing revision. " +
                "The classpath already includes the game jar, ModTheSpire, the required mods, and the parent mod, " +
                "so imports such as com.megacrit.cardcrawl.*, basemod.* and " +
                "com.evacipated.cardcrawl.modthespire.lib.* resolve.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty(
                    "patch_id",
                    "Patch ID of an existing revision to compile; defaults to the current session's workspace.",
                )
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.PATCH_COMPILE
    override val concurrency = AgentToolConcurrency.EXCLUSIVE

    override fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments")
        val patchId = json["patch_id"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        val activeWorkspace = if (patchId != null) {
            resolveWorkspace(patchId) ?: return failure("patch_workspace_not_found")
        } else {
            workspace() ?: return failure("patch_workspace_not_created")
        }
        val result = runCatching {
            AgentPatchSourceCompiler.compile(context, activeWorkspace, parentJar)
        }.getOrElse { return failure(it.message ?: "compile_failed") }
        return buildJsonObject {
            put("status", JsonPrimitive(if (result.success) "compiled" else "failed"))
            put("patch_id", JsonPrimitive(activeWorkspace.patchId))
            put("compiled_class_count", JsonPrimitive(result.compiledClassCount))
            put("diagnostics", JsonPrimitive(result.diagnostics))
        }.toString()
    }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()
}

class AgentModSourceDecompileTool(
    private val context: Context,
    private val parentModId: String,
    private val parentJar: File,
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("decompile_agent_mod_source")
        .description(
            "Prepare an on-demand source snapshot for the selected parent mod without extracting the " +
                "full JAR. Use decompile_agent_class for a class and read_agent_mod_resource for a resource.",
        )
        .parameters(JsonObjectSchema.builder().additionalProperties(false).build())
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.MOD_INSPECTION
    override val concurrency = AgentToolConcurrency.EXCLUSIVE

    override fun execute(arguments: String): String {
        val inspection = runCatching {
            AgentModInspectionManager.createInspection(
                context = context,
                parentModId = parentModId,
                sourceJar = parentJar,
            )
        }.getOrElse { return failure(it.message ?: "inspection_failed") }
        return buildJsonObject {
            put("status", JsonPrimitive("ready"))
            put("parent_mod_id", JsonPrimitive(inspection.parentModId))
            put("inspection_id", JsonPrimitive(inspection.inspectionId))
            put("workspace_root", JsonPrimitive(inspection.root.absolutePath))
            put("source_root", JsonPrimitive(inspection.sourceRoot.absolutePath))
            put("source_mode", JsonPrimitive("on_demand"))
            put("source_sha256", JsonPrimitive(inspection.sourceSha256))
            put("next_step", JsonPrimitive("Call decompile_agent_class or read_agent_mod_resource."))
        }.toString()
    }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()
}

class AgentPatchTargetInspectTool(
    private val context: Context,
    private val parentJar: File,
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("inspect_agent_patch_target")
        .description(
            "Inspect the exact constructors, methods, and fields of a target class from the installed " +
                "game/mod bytecode. Always call this before writing a patch; use the returned overload_index " +
                "and descriptor instead of guessing signatures. By default it also returns a structured " +
                "local_variables list and a javap-style bytecode listing for each overload, including " +
                "LocalVariableTable names when the class was compiled with debug information; pass " +
                "include_bytecode=false to omit them. Pass overload_index to limit the result to one " +
                "overload when the listing is large.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("target_class", "Binary class name, for example com.example.Target.")
                .addStringProperty("member", "Method name, constructor, <ctor>, or <class>.")
                .addBooleanProperty(
                    "include_bytecode",
                    "Include a javap-style listing for each returned overload. Defaults to true; the listing " +
                        "contains LocalVariableTable names when present.",
                )
                .addStringProperty(
                    "overload_index",
                    "Optional overload index from a previous inspection; limits the result to that overload.",
                )
                .required("target_class", "member")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.READ_ONLY
    override val concurrency = AgentToolConcurrency.PARALLEL

    override fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments")
        val targetClass = json["target_class"]?.jsonPrimitive?.content.orEmpty()
        val member = json["member"]?.jsonPrimitive?.content.orEmpty()
        if (targetClass.isBlank() || member.isBlank()) return failure("invalid_arguments")
        val includeBytecode = json["include_bytecode"]?.jsonPrimitive?.booleanOrNull ?: true
        val overloadIndex = json["overload_index"]?.jsonPrimitive?.content?.toIntOrNull()
        val classpath = runCatching { AgentPatchSourceCompiler.resolveCompileClasspath(context, parentJar) }
            .getOrElse { return failure(it.message ?: "classpath_failed") }
        val inspection = runCatching {
            AgentPatchTargetInspector.inspectTarget(
                targetClassName = targetClass,
                memberName = member,
                classpath = classpath,
                includeBytecode = includeBytecode,
            )
        }.getOrElse { return failure(it.message ?: "target_inspection_failed") }
        if (overloadIndex != null && inspection.overloads.getOrNull(overloadIndex) == null) {
            return failure("overload_index_out_of_range")
        }
        return buildJsonObject {
            put("status", JsonPrimitive("inspected"))
            put("target_class", JsonPrimitive(inspection.targetClassName))
            put("member", JsonPrimitive(inspection.memberName))
            put("class_origin", JsonPrimitive(inspection.classOrigin))
            put("overloads", kotlinx.serialization.json.buildJsonArray {
                inspection.overloads.forEachIndexed { index, signature ->
                    if (overloadIndex != null && index != overloadIndex) return@forEachIndexed
                    add(buildJsonObject {
                        put("overload_index", JsonPrimitive(index))
                        put("name", JsonPrimitive(signature.name))
                        put("display", JsonPrimitive(signature.displayName))
                        put("descriptor", JsonPrimitive(signature.descriptor))
                        put("parameter_types", kotlinx.serialization.json.buildJsonArray {
                            signature.parameterTypes.forEach { add(JsonPrimitive(it)) }
                        })
                        put("return_type", JsonPrimitive(signature.returnType))
                        put("static", JsonPrimitive(signature.isStatic))
                        if (includeBytecode) {
                            put("has_code", JsonPrimitive(signature.hasCode))
                            put("local_variables", buildJsonArray {
                                signature.localVariables.forEach { local ->
                                    add(buildJsonObject {
                                        put("name", JsonPrimitive(local.name))
                                        put("type", JsonPrimitive(local.type))
                                        put("slot", JsonPrimitive(local.slot))
                                    })
                                }
                            })
                            put("bytecode", JsonPrimitive(signature.bytecode.orEmpty()))
                            put("bytecode_truncated", JsonPrimitive(signature.bytecodeTruncated))
                        }
                    })
                }
            })
            put("fields", kotlinx.serialization.json.buildJsonArray {
                inspection.fields.forEach { field ->
                    add(buildJsonObject {
                        put("name", JsonPrimitive(field.name))
                        put("type", JsonPrimitive(field.type))
                        put("static", JsonPrimitive(field.isStatic))
                    })
                }
            })
        }.toString()
    }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()
}

class AgentPatchSkeletonTool(
    private val context: Context,
    private val parentJar: File,
    private val workspace: () -> AgentPatchWorkspace?,
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("generate_agent_patch_skeleton")
        .description(
            "Generate a Java 8 ModTheSpire patch skeleton from an inspected exact target signature. " +
                "The tool writes under the current patch_source/<patch_id>/src/ and includes the selected paramtypez. Call " +
                "inspect_agent_patch_target first; provide its overload_index when the target is overloaded.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("target_class", "Binary target class name returned by inspection.")
                .addStringProperty("member", "Target method name or <ctor>.")
                .addStringProperty("package_name", "Java package for the generated patch class.")
                .addStringProperty("class_name", "Java patch class name.")
                .addStringProperty("patch_kind", "prefix or postfix; defaults to postfix.")
                .addStringProperty("overload_index", "Exact overload_index returned by inspection when needed.")
                .addBooleanProperty("overwrite", "Replace an existing generated file. Defaults to false.")
                .required("target_class", "member", "package_name", "class_name")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.WORKSPACE_WRITE
    override val concurrency = AgentToolConcurrency.EXCLUSIVE

    override fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments")
        val activeWorkspace = workspace() ?: return failure("patch_workspace_not_created")
        val targetClass = json["target_class"]?.jsonPrimitive?.content.orEmpty()
        val member = json["member"]?.jsonPrimitive?.content.orEmpty()
        val packageName = json["package_name"]?.jsonPrimitive?.content.orEmpty()
        val className = json["class_name"]?.jsonPrimitive?.content.orEmpty()
        val kind = json["patch_kind"]?.jsonPrimitive?.content?.ifBlank { "postfix" } ?: "postfix"
        val index = json["overload_index"]?.jsonPrimitive?.content?.toIntOrNull()
        val overwrite = json["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false
        if (targetClass.isBlank() || member.isBlank() || packageName.isBlank() || className.isBlank()) {
            return failure("invalid_arguments")
        }
        val classpath = runCatching { AgentPatchSourceCompiler.resolveCompileClasspath(context, parentJar) }
            .getOrElse { return failure(it.message ?: "classpath_failed") }
        val skeleton = runCatching {
            AgentPatchTargetInspector.generateSkeleton(
                patchRoot = activeWorkspace.patchRoot,
                packageName = packageName,
                patchClassName = className,
                targetClassName = targetClass,
                memberName = member,
                overloadIndex = index,
                patchKind = kind,
                classpath = classpath,
                overwrite = overwrite,
            )
        }.getOrElse { return failure(it.message ?: "skeleton_generation_failed") }
        return buildJsonObject {
            put("status", JsonPrimitive("generated"))
            put("path", JsonPrimitive(skeleton.file.relativeTo(activeWorkspace.patchRoot).invariantSeparatorsPath))
            put("target_class", JsonPrimitive(skeleton.targetClassName))
            put("member", JsonPrimitive(skeleton.memberName))
            put("descriptor", JsonPrimitive(skeleton.descriptor))
            put("source", JsonPrimitive(skeleton.source))
        }.toString()
    }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()
}

class AgentPatchPreflightTool(
    private val context: Context,
    private val parentJar: File,
    private val workspace: () -> AgentPatchWorkspace?,
    private val resolveWorkspace: (String) -> AgentPatchWorkspace? = { null },
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("validate_agent_patch_mod")
        .description(
            "Validate compiled patch bytecode before packaging. Checks every ModTheSpire patch annotation " +
                "against the real target class and rejects missing classes, methods, constructors, and " +
                "ambiguous or mismatched parameter signatures. Defaults to the current session's patch " +
                "workspace; pass patch_id to validate an existing revision.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty(
                    "patch_id",
                    "Patch ID of an existing revision to validate; defaults to the current session's workspace.",
                )
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.PATCH_COMPILE
    override val concurrency = AgentToolConcurrency.EXCLUSIVE

    override fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments")
        val patchId = json["patch_id"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        val activeWorkspace = if (patchId != null) {
            resolveWorkspace(patchId) ?: return failure("patch_workspace_not_found")
        } else {
            workspace() ?: return failure("patch_workspace_not_created")
        }
        return runPreflight(context, parentJar, activeWorkspace)
    }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()
}

private fun runPreflight(
    context: Context,
    parentJar: File,
    workspace: AgentPatchWorkspace,
): String {
    val classpath = runCatching { AgentPatchSourceCompiler.resolveCompileClasspath(context, parentJar) }
        .getOrElse { return buildJsonObject { put("error", JsonPrimitive(it.message ?: "classpath_failed")) }.toString() }
    val result = runCatching { AgentPatchPreflight.validate(workspace.patchRoot, classpath) }
        .getOrElse { return buildJsonObject { put("error", JsonPrimitive(it.message ?: "preflight_failed")) }.toString() }
    return buildJsonObject {
        put("status", JsonPrimitive(if (result.valid) "valid" else "failed"))
        put("patch_class_count", JsonPrimitive(result.patchClassCount))
        put("patch_annotation_count", JsonPrimitive(result.patchAnnotationCount))
        put("patch_initializer_count", JsonPrimitive(result.patchInitializerCount))
        put("issues", kotlinx.serialization.json.buildJsonArray { result.issues.forEach { add(JsonPrimitive(it)) } })
        put("warnings", kotlinx.serialization.json.buildJsonArray { result.warnings.forEach { add(JsonPrimitive(it)) } })
    }.toString()
}

class AgentPatchModPackageTool(
    private val context: Context,
    private val parentJar: File,
    private val workspace: () -> AgentPatchWorkspace?,
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("package_agent_patch_mod")
        .description(
            "Validate and package the files under the current patch_source/<patch_id>/ into a versioned patch mod. Packaging " +
                "always runs the bytecode preflight and refuses to create a JAR when it fails.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty(
                    "name",
                    "Use a concise Chinese display name identifying the main feature or fix. Preserve proper mod " +
                        "names when needed. Avoid generic names such as 'AI补丁', '测试补丁', or '优化补丁', " +
                        "and do not include the version number. Reuse the workspace name unless the patch's purpose changed.",
                )
                .addStringProperty("version", "Patch mod version.")
                .addStringProperty(
                    "description",
                    "Briefly describe in Chinese the problem or request addressed and the concrete implemented changes. " +
                        "Briefly list distinct changes when applicable; do not claim plans or unverified results. " +
                        "Check the actual patch contents before packaging and revise any outdated or inconsistent description.",
                )
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.PATCH_PACKAGE
    override val concurrency = AgentToolConcurrency.EXCLUSIVE

    override fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments")
        val activeWorkspace = workspace() ?: return failure("patch_workspace_not_created")
        val sources = AgentPatchSourceCompiler.collectSources(activeWorkspace.patchRoot)
        val hasSources = sources.isNotEmpty()
        val hasClasses = activeWorkspace.patchRoot.walkTopDown()
            .any { it.isFile && it.extension.equals("class", ignoreCase = true) }
        if (hasSources && !hasClasses) {
            return failure("patch_sources_not_compiled: call compile_agent_patch_source first")
        }
        val preflight = runPreflight(context, parentJar, activeWorkspace)
        val preflightJson = runCatching { Json.parseToJsonElement(preflight).jsonObject }.getOrNull()
        if (preflightJson?.get("status")?.jsonPrimitive?.content != "valid") {
            return failure("patch_preflight_failed: $preflight")
        }
        val info = runCatching {
            AgentPatchModManager.packagePatchMod(
                context = context,
                workspace = activeWorkspace,
                name = json["name"]?.jsonPrimitive?.content.orEmpty(),
                version = json["version"]?.jsonPrimitive?.content.orEmpty(),
                description = json["description"]?.jsonPrimitive?.content.orEmpty(),
            )
        }.getOrElse { return failure(it.message ?: "package_failed") }
        return buildJsonObject {
            put("status", JsonPrimitive("packaged"))
            put("parent_mod_id", JsonPrimitive(activeWorkspace.parentModId))
            put("patch_id", JsonPrimitive(info.patchId))
            put("patch_mod_id", JsonPrimitive(info.patchModId))
            put("jar_path", JsonPrimitive(info.jarFile.absolutePath))
            put("enabled", JsonPrimitive(info.enabled))
            put("next_step", JsonPrimitive("Ask the user whether to enable this packaged patch mod."))
        }.toString()
    }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()
}

/**
 * Repackages an already packaged patch revision in place.
 *
 * The revision keeps its `patch_id`, patch mod id, and enablement, so updating a patch never
 * creates a duplicate or forces the user to re-enable it. When the revision's sources changed,
 * `compile_agent_patch_source` with the same `patch_id` must run first.
 */
class AgentPatchModUpdateTool(
    private val context: Context,
    private val parentModId: String,
    private val parentJar: File,
    private val onUpdated: () -> Unit = {},
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("update_agent_patch_mod")
        .description(
            "Update an already packaged AI patch mod in place. Repackages its patch workspace with a new " +
                "version (and optionally a new name or description) while keeping the same patch_id, modid, " +
                "and enabled state. Compile the patch workspace first when its sources changed. Use " +
                "list_agent_patch_mods to find the patch_id.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("patch_id", "Patch ID from list_agent_patch_mods.")
                .addStringProperty("version", "New semantic version, for example 1.1.0.")
                .addStringProperty(
                    "name",
                    "New concise Chinese display name identifying the main feature or fix; unchanged when omitted. " +
                        "Preserve proper mod names when needed. Avoid generic names such as 'AI补丁', '测试补丁', " +
                        "or '优化补丁' and do not include the version number. Keep the existing name unless the patch's purpose changed.",
                )
                .addStringProperty(
                    "description",
                    "New brief Chinese description of the problem or request addressed and the concrete implemented changes. " +
                        "Briefly list distinct changes when applicable; do not claim plans or unverified results. " +
                        "Unchanged when omitted: if the existing description no longer matches the actual patch, " +
                        "you must supply a corrected description.",
                )
                .required("patch_id", "version")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.PATCH_PACKAGE
    override val concurrency = AgentToolConcurrency.EXCLUSIVE

    override fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments")
        val patchId = json["patch_id"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: return failure("invalid_arguments")
        val version = json["version"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: return failure("invalid_arguments")
        AgentPatchModManager.listPackaged(context, parentModId).firstOrNull { it.patchId == patchId }
            ?: return failure("patch_not_found")
        val workspace = AgentPatchModManager.resolvePatchWorkspace(context, parentModId, patchId, parentJar)
            ?: return failure("patch_workspace_not_found")
        val hasSources = AgentPatchSourceCompiler.collectSources(workspace.patchRoot).isNotEmpty()
        val hasClasses = workspace.patchRoot.walkTopDown()
            .any { it.isFile && it.extension.equals("class", ignoreCase = true) }
        if (hasSources && !hasClasses) {
            return failure("patch_sources_not_compiled: call compile_agent_patch_source with this patch_id first")
        }
        val preflight = runPreflight(context, parentJar, workspace)
        val preflightJson = runCatching { Json.parseToJsonElement(preflight).jsonObject }.getOrNull()
        if (preflightJson?.get("status")?.jsonPrimitive?.content != "valid") {
            return failure("patch_preflight_failed: $preflight")
        }
        val info = runCatching {
            AgentPatchModManager.updatePatchMod(
                context = context,
                parentModId = parentModId,
                patchId = patchId,
                name = json["name"]?.jsonPrimitive?.content.orEmpty(),
                version = version,
                description = json["description"]?.jsonPrimitive?.content.orEmpty(),
                sourceJar = parentJar,
            )
        }.getOrElse { return failure(it.message ?: "update_failed") }
        onUpdated()
        return buildJsonObject {
            put("status", JsonPrimitive("updated"))
            put("parent_mod_id", JsonPrimitive(info.parentModId))
            put("patch_id", JsonPrimitive(info.patchId))
            put("patch_mod_id", JsonPrimitive(info.patchModId))
            put("name", JsonPrimitive(info.name))
            put("version", JsonPrimitive(info.version))
            put("jar_path", JsonPrimitive(info.jarFile.absolutePath))
            put("enabled", JsonPrimitive(info.enabled))
        }.toString()
    }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()
}

class AgentPatchModSetEnabledTool(
    private val context: Context,
    private val parentModId: String,
    private val workspace: () -> AgentPatchWorkspace?,
    private val onChanged: () -> Unit = {},
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("set_agent_patch_mod_enabled")
        .description("Enable or disable a packaged AI patch mod for the selected parent mod. Enabled patch mods are loaded on the next game launch.")
        .parameters(
            JsonObjectSchema.builder()
                .addBooleanProperty("enabled", "True to enable the patch mod, false to disable it.")
                .addStringProperty("patch_id", "Patch ID from list_agent_patch_mods; defaults to the current session's revision.")
                .required("enabled")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.PATCH_ENABLE
    override val concurrency = AgentToolConcurrency.EXCLUSIVE

    override fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments")
        val enabled = json["enabled"]?.jsonPrimitive?.booleanOrNull
            ?: return failure("invalid_arguments")
        val patchId = json["patch_id"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: workspace()?.patchId
            ?: return failure("patch_workspace_not_created")
        return runCatching {
            val info = AgentPatchModManager.listPackaged(context, parentModId)
                .firstOrNull { it.patchId == patchId }
                ?: throw IllegalStateException("AI patch not found: $patchId")
            AgentPatchModManager.setEnabled(context, parentModId, patchId, enabled)
            onChanged()
            buildJsonObject {
                put("status", JsonPrimitive(if (enabled) "enabled" else "disabled"))
                put("patch_mod_id", JsonPrimitive(info.patchModId))
                put("enabled", JsonPrimitive(enabled))
            }.toString()
        }.getOrElse { failure(it.message ?: "set_enabled_failed") }
    }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()
}

class AgentPatchModListTool(
    private val context: Context,
    private val parentModId: String,
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("list_agent_patch_mods")
        .description("List packaged AI patch mods belonging to the selected parent mod and whether each one is enabled.")
        .parameters(JsonObjectSchema.builder().additionalProperties(false).build())
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.READ_ONLY
    override val concurrency = AgentToolConcurrency.PARALLEL

    override fun execute(arguments: String): String = buildJsonObject {
        put("parent_mod_id", JsonPrimitive(parentModId))
        put("patches", kotlinx.serialization.json.buildJsonArray {
            AgentPatchModManager.listPackaged(context, parentModId).forEach { patch ->
                add(buildJsonObject {
                    put("patch_id", JsonPrimitive(patch.patchId))
                    put("patch_mod_id", JsonPrimitive(patch.patchModId))
                    put("name", JsonPrimitive(patch.name))
                    put("version", JsonPrimitive(patch.version))
                    put("enabled", JsonPrimitive(patch.enabled))
                })
            }
        })
    }.toString()
}

class AgentPatchModDeleteTool(
    private val context: Context,
    private val parentModId: String,
    private val onDeleted: () -> Unit = {},
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("delete_agent_patch_mod")
        .description("Delete a packaged AI patch mod for the selected parent mod. The launcher stops loading it immediately.")
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("patch_id", "Patch ID reported by list_agent_patch_mods.")
                .required("patch_id")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.PATCH_DELETE
    override val concurrency = AgentToolConcurrency.EXCLUSIVE

    override fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments")
        val patchId = json["patch_id"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: return failure("invalid_arguments")
        return runCatching {
            val info = AgentPatchModManager.delete(context, parentModId, patchId)
            onDeleted()
            buildJsonObject {
                put("status", JsonPrimitive("deleted"))
                put("patch_id", JsonPrimitive(info.patchId))
                put("patch_mod_id", JsonPrimitive(info.patchModId))
            }.toString()
        }.getOrElse { failure(it.message ?: "delete_failed") }
    }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()
}

private fun resolveWorkspaceFile(root: File, relativePath: String): File? {
    val candidate = File(root, relativePath).canonicalFile
    return candidate.takeIf { it.toPath().startsWith(root.canonicalFile.toPath()) }
}

/** Reads an integer tool argument that arrives as a JSON string. */
private fun JsonObject.intParam(name: String): Int? =
    this[name]?.jsonPrimitive?.content?.toIntOrNull()

private fun resolvePatchSourceFile(root: File, relativePath: String): File? {
    val candidate = resolveWorkspaceFile(root, relativePath) ?: return null
    val patchSourceRoot = File(root, "patch_source").canonicalFile
    val candidatePath = candidate.toPath()
    val patchSourcePath = patchSourceRoot.toPath()
    if (!candidatePath.startsWith(patchSourcePath)) return null
    if (candidatePath == patchSourcePath) return candidate
    val relative = patchSourcePath.relativize(candidatePath)
    return candidate.takeIf {
        relative.nameCount >= 2 && relative.getName(0).toString().startsWith("patch-")
    }
}

private fun failure(code: String): String = buildJsonObject {
    put("error", JsonPrimitive(code))
}.toString()

/**
 * Compiles a double-star-aware glob into a matcher over workspace-relative paths.
 *
 * Regex alone cannot express "recursive" vs "single segment", so the pattern is walked in one pass:
 * a double star followed by a slash spans zero or more path segments, a bare double star spans any
 * characters, `*` and `?` stay within a segment, and everything else is quoted literally. Returns
 * null when the pattern cannot compile.
 */
private fun compileGlob(pattern: String): ((String) -> Boolean)? {
    val regex = StringBuilder("^")
    var index = 0
    while (index < pattern.length) {
        when (val char = pattern[index]) {
            '*' -> {
                val isDoubled = index + 1 < pattern.length && pattern[index + 1] == '*'
                if (isDoubled) {
                    val hasSlash = index + 2 < pattern.length && pattern[index + 2] == '/'
                    if (hasSlash) {
                        regex.append("(?:.*/)?")
                        index += 3
                    } else {
                        regex.append(".*")
                        index += 2
                    }
                    continue
                }
                regex.append("[^/]*")
            }
            '?' -> regex.append("[^/]")
            else -> regex.append(Regex.escape(char.toString()))
        }
        index++
    }
    regex.append('$')
    return runCatching { Regex(regex.toString()) }.getOrNull()?.let { compiled ->
        { candidate: String -> compiled.matches(candidate) }
    }
}

data class AgentReply(
    val text: String,
    val toolRounds: Int,
    val contextTokens: Int? = null,
)

/**
 * The model finished a turn without producing any text or tool call.
 *
 * This used to be returned as a successful empty reply, which surfaced in the UI as a message with
 * only a model name and elapsed time and no content. Treat it as a failure so the user can retry.
 */
class AgentEmptyResponseException(finishReason: String = "unknown") :
    IllegalStateException("The model returned an empty response with no text and no tool call (finish reason: $finishReason).")

class PolicyGatedAgentGateway(
    private val chatModel: ChatModel,
    private val toolRegistry: AgentToolRegistry,
    /**
     * Root of the agent workspace, used to persist oversized tool output so the model can read the
     * full result back with the workspace read tool instead of losing it to truncation.
     */
    private val agentWorkspaceRoot: File? = null,
    private val contextManager: AgentContextManager? = null,
    private val checkCancelled: () -> Unit = {},
    private val onRetry: (retryNumber: Int, delaySeconds: Long, error: Throwable) -> Unit = { _, _, _ -> },
    private val maxParallelTools: Int = 4,
    private val streamingTimeoutMillis: Long = DEFAULT_LLM_REQUEST_TIMEOUT_SECONDS * 1_000L,
    /** When false, images read by tools are described in text instead of sent as image parts. */
    private val supportsImageInput: Boolean = true,
    /** Called only at complete model/tool boundaries, never between a tool call and its result. */
    private val receiveInbox: (idle: Boolean) -> Boolean = { false },
    private val onNonStreamingText: (String) -> Unit = {},
    private val onOutputSpeed: (Double) -> Unit = {},
    private val currentChatModel: (() -> ChatModel)? = null,
    private val currentStreamingModel: (() -> StreamingChatModel?)? = null,
    /**
     * Whether the device currently has a usable network.
     *
     * After a screen lock the radio may take seconds to come back, and Doze tears down established
     * sockets before the app notices. Retrying a connection abort blindly burns the retry budget on
     * a dead network, so the gateway waits for this to report `true` first.
     */
    private val hasUsableNetwork: () -> Boolean = { true },
    /**
     * Discards pooled connections so a retry opens a fresh socket.
     *
     * A screen lock tears down established connections while the network still reports as
     * connected, and OkHttp would otherwise hand the dead connection to the retry.
     */
    private val evictConnectionPool: () -> Unit = {},
    private val onExecutionStep: (AgentExecutionStep) -> Unit = {},
) {
    private val toolScheduler = AgentToolScheduler(toolRegistry, maxParallelTools, checkCancelled)

    fun respond(systemPrompt: String, userPrompt: String): AgentReply {
        val messages = if (contextManager != null) mutableListOf() else mutableListOf<ChatMessage>(
            SystemMessage.from(systemPrompt),
            UserMessage.from(userPrompt),
        )

        var round = 0
        while (true) {
            receiveInbox(false)
            var lastResponse: ChatResponse? = null
            var response: ChatResponse? = null
            for (attempt in 0..MAX_EMPTY_RETRIES) {
                val candidate = modelTurn(messages) {
                    val startedAt = System.nanoTime()
                    (currentChatModel?.invoke() ?: chatModel).chat(chatRequest(it)).also { response ->
                        val elapsed = System.nanoTime() - startedAt
                        response.tokenUsage()?.outputTokenCount()?.takeIf { count -> count > 0 }?.let { count ->
                            if (elapsed > 0L) onOutputSpeed(count.toDouble() * 1_000_000_000.0 / elapsed)
                        }
                    }
                }
                val message = candidate.aiMessage()
                lastResponse = candidate
                if (message.hasToolExecutionRequests() || message.text().orEmpty().isNotBlank()) {
                    response = candidate
                    break
                }
                Log.i(TAG, "empty non-streaming round=$round attempt=$attempt finish=${finishReason(candidate)}")
            }
            val completed = response ?: run {
                logAssistantRound(round, "", lastResponse!!.aiMessage(), "non-streaming", lastResponse)
                throw AgentEmptyResponseException(finishReason(lastResponse))
            }
            val aiMessage = normalizeToolCallMessage(completed.aiMessage())
            messages += aiMessage
            contextManager?.append(aiMessage)
            val text = aiMessage.text().orEmpty()
            if (text.isNotBlank()) onNonStreamingText(text)
            logAssistantRound(round, text, aiMessage, "non-streaming", completed)
            if (!aiMessage.hasToolExecutionRequests()) {
                if (receiveInbox(true)) continue
                return AgentReply(text = text, toolRounds = round, contextTokens = completed.tokenUsage()?.totalTokenCount())
            }
            executeTools(aiMessage, messages)
            round++
        }
    }

    fun respondStreaming(
        systemPrompt: String,
        userPrompt: String,
        streamingModel: StreamingChatModel,
        onText: (String) -> Unit,
        onThinking: (String) -> Unit = {},
    ): AgentReply {
        val messages = if (contextManager != null) mutableListOf() else mutableListOf<ChatMessage>(
            SystemMessage.from(systemPrompt),
            UserMessage.from(userPrompt),
        )

        var round = 0
        while (true) {
            receiveInbox(false)
            var lastResult: StreamingResult? = null
            var accepted: StreamingResult? = null
            for (attempt in 0..MAX_EMPTY_RETRIES) {
                val candidate = modelTurn(messages) { activeMessages -> streamRequest(
                    streamingModel = currentStreamingModel?.invoke() ?: streamingModel,
                    messages = activeMessages,
                    onText = onText,
                    onThinking = onThinking,
                ) }
                val message = candidate.response.aiMessage()
                lastResult = candidate
                val candidateText = candidate.text.ifBlank { message.text().orEmpty() }
                if (message.hasToolExecutionRequests() || candidateText.isNotBlank()) {
                    accepted = candidate
                    break
                }
                Log.i(
                    TAG,
                    "empty streaming round=$round attempt=$attempt finish=${finishReason(candidate.response)}",
                )
            }
            if (accepted == null) {
                // Some OpenAI-compatible proxies stream tool calls in a shape the streaming client
                // cannot assemble, so the round arrives with no text and no tool call. Retrying the
                // same streamed request rarely helps; re-issue it non-streaming, where the tool call
                // is read straight from the response body.
                val fallback = modelTurn(messages) { (currentChatModel?.invoke() ?: chatModel).chat(chatRequest(it)) }
                val fallbackMessage = fallback?.aiMessage()
                val fallbackText = fallbackMessage?.text().orEmpty()
                if (fallback != null && fallbackMessage != null &&
                    (fallbackMessage.hasToolExecutionRequests() || fallbackText.isNotBlank())
                ) {
                    Log.i(
                        TAG,
                        "round=$round recovered via non-streaming fallback toolCalls=" +
                            "${fallbackMessage.toolExecutionRequests().size} textChars=${fallbackText.length} " +
                            "finish=${finishReason(fallback)}",
                    )
                    // The streamed attempt produced nothing, so surface the fallback text instead.
                    if (fallbackText.isNotBlank()) onText(fallbackText)
                    accepted = StreamingResult(fallback, fallbackText)
                }
            }
            val result = accepted ?: run {
                logAssistantRound(round, "", lastResult!!.response.aiMessage(), "streaming", lastResult.response)
                throw AgentEmptyResponseException(finishReason(lastResult.response))
            }
            val aiMessage = normalizeToolCallMessage(result.response.aiMessage())
            messages += aiMessage
            contextManager?.append(aiMessage)
            val text = result.text.ifBlank { aiMessage.text().orEmpty() }
            logAssistantRound(round, text, aiMessage, "streaming", result.response)
            if (!aiMessage.hasToolExecutionRequests()) {
                if (receiveInbox(true)) continue
                return AgentReply(text = text, toolRounds = round, contextTokens = result.response.tokenUsage()?.totalTokenCount())
            }
            executeTools(aiMessage, messages)
            round++
        }
    }

    private fun executeTools(aiMessage: AiMessage, messages: MutableList<ChatMessage>) {
        val media = ArrayList<AgentContextAttachment>()
        toolScheduler.execute(aiMessage.toolExecutionRequests()) { request, output ->
            val result = ToolExecutionResultMessage.from(request, boundedToolResult(output.text))
            messages += result
            val images = output.attachments.filter { it.isImage }
            contextManager?.append(result, images)
            media += images
        }
        // Without a context manager the caller owns the history; mirror the context path so a
        // tool-read image still reaches the model. The next model turn follows this turn, so the
        // extra user message never splits a tool result away from its call.
        if (contextManager == null && media.isNotEmpty()) {
            messages += agentMediaUserMessage(media, supportsImageInput)
        }
    }

    private fun <T> modelTurn(history: List<ChatMessage>, request: (List<ChatMessage>) -> T): T {
        checkCancelled()
        var active = contextManager?.prepare() ?: history
        var lastError: Exception? = null
        repeat(MAX_ABORT_RETRIES + 1) { attempt ->
            val result = try {
                checkCancelled()
                requestWithHistory(active, request)
            } catch (error: Exception) {
                checkCancelled()
                if (error is AgentHistoryCheckpointException) throw error
                lastError = error
                if (contextManager != null && isAgentContextOverflow(error)) {
                    // Context overflow gets an immediate compaction retry, not a delayed network retry.
                    active = contextManager.prepare(force = true)
                    return observeTurn(requestWithHistory(active, request))
                }
                if (!isRetryableAgentRequestFailure(error)) throw error
                // A dropped socket may need tens of seconds for the radio to come back, so it earns
                // its own, larger retry budget instead of being cut off at the transient-failure one.
                val maxRetries = if (isConnectionAbort(error)) MAX_ABORT_RETRIES else MAX_REQUEST_RETRIES
                if (attempt >= maxRetries) throw error
                val delaySeconds = retryDelaySeconds(attempt, error)
                onRetry(attempt + 1, delaySeconds, error)
                // A dropped socket must not be handed to the next attempt.
                if (isConnectionAbort(error)) evictConnectionPool()
                val wait = AgentStepTimer(AgentStepKind.RETRY, detail = error.javaClass.simpleName)
                publishExecutionStep(wait.step)
                try {
                    awaitNetworkAndBackoff(delaySeconds)
                } catch (cancelled: Exception) {
                    publishExecutionStep(wait.finish(agentStepFailureStatus(cancelled)))
                    throw cancelled
                }
                publishExecutionStep(wait.finish())
                return@repeat
            }
            // A checkpoint failure must never retry an already successful, billable request.
            return observeTurn(result)
        }
        throw requireNotNull(lastError)
    }

    /** Keep persistence failures outside the request catch: never replay a billable request. */
    private fun <T> requestWithHistory(messages: List<ChatMessage>, request: (List<ChatMessage>) -> T): T {
        val timer = AgentStepTimer(AgentStepKind.MODEL)
        publishExecutionStep(timer.step)
        val result = try {
            request(messages)
        } catch (error: Exception) {
            // An interrupt must be cleared before SQLite can persist the terminal step.
            val status = agentStepFailureStatus(error)
            val interrupted = Thread.interrupted()
            try {
                publishExecutionStep(timer.finish(status, error.javaClass.simpleName))
            } catch (checkpoint: Exception) {
                error.addSuppressed(checkpoint)
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
            throw error
        }
        val response = when (result) {
            is ChatResponse -> result
            is StreamingResult -> result.response
            else -> null
        }
        val message = response?.aiMessage()
        val detail = buildString {
            append(message?.text().orEmpty().take(320))
            val tools = message?.toolExecutionRequests().orEmpty()
            if (tools.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(tools.take(8).joinToString { it.name() })
            }
        }
        val usage = response?.let { AgentCacheUsage.from(it, contextManager?.state?.cacheStats?.inputExcludesCached ?: false) }
        publishExecutionStep(timer.finish(
            detail = detail,
            inputTokens = usage?.input,
            outputTokens = response?.tokenUsage()?.outputTokenCount()?.toLong(),
        ))
        return result
    }

    private class AgentHistoryCheckpointException(cause: Exception) : IllegalStateException("Execution history checkpoint failed", cause)

    private fun publishExecutionStep(step: AgentExecutionStep) {
        try { onExecutionStep(step) } catch (error: Exception) { throw AgentHistoryCheckpointException(error) }
    }

    /**
     * Waits out the backoff, then waits for the device to have a usable network again.
     *
     * A screen lock puts the radio into power save and Doze suspends networking outright, which
     * aborts in-flight sockets. The failure surfaces as `Software caused connection abort` and is
     * classified as retryable, but retrying while the network is still down only spends the retry
     * budget on the same dead socket. The wait is bounded so a genuinely offline device does not
     * hang forever: after [MAX_NETWORK_WAIT_MS] the caller retries anyway and reports the real
     * error if it fails again.
     */
    private fun awaitNetworkAndBackoff(delaySeconds: Long) {
        sleepCancellable(delaySeconds * 1_000L)
        if (hasUsableNetwork()) return
        val deadlineAtMs = System.nanoTime() / 1_000_000L + MAX_NETWORK_WAIT_MS
        while (!hasUsableNetwork()) {
            if (System.nanoTime() / 1_000_000L >= deadlineAtMs) {
                Log.w(TAG, "Network still unavailable after ${MAX_NETWORK_WAIT_MS}ms; retrying anyway")
                return
            }
            sleepCancellable(NETWORK_POLL_INTERVAL_MS)
        }
    }

    /** Sleeps in slices so cancellation is noticed promptly instead of after the whole delay. */
    private fun sleepCancellable(totalMs: Long) {
        var remaining = totalMs
        while (remaining > 0L) {
            checkCancelled()
            val slice = minOf(remaining, CANCELLABLE_SLEEP_SLICE_MS)
            try {
                Thread.sleep(slice)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.util.concurrent.CancellationException("LLM retry cancelled")
            }
            remaining -= slice
        }
        checkCancelled()
    }

    private fun <T> observeTurn(result: T): T {
        val response = when (result) {
            is ChatResponse -> result
            is StreamingResult -> result.response
            else -> null
        }
        if (response != null) contextManager?.let { it.observe(response, it.requestEstimate()) }
        return result
    }

    private fun chatRequest(messages: List<ChatMessage>) = ChatRequest.builder()
        .messages(sanitizeToolMessageSequence(messages))
        .toolSpecifications(toolRegistry.toolSpecifications())
        .apply { contextManager?.let { maxOutputTokens(it.budget.outputReserve) } }
        .build()

    /**
     * Never send a tool result unless the immediately preceding assistant turn declared its call.
     * This is a final network-boundary guard for contexts written by older builds or interrupted
     * jobs. OpenAI-compatible gateways commonly translate these messages to Anthropic blocks and
     * reject an orphaned tool_result instead of ignoring it.
     */
    internal fun sanitizeToolMessageSequence(messages: List<ChatMessage>): List<ChatMessage> {
        val sanitized = ArrayList<ChatMessage>(messages.size)
        var pendingCallIds = emptySet<String>()
        messages.forEach { message ->
            when (message) {
                is AiMessage -> {
                    val normalized = normalizeToolCallMessage(message)
                    sanitized += normalized
                    pendingCallIds = normalized.toolExecutionRequests()
                        .mapNotNull { it.id()?.takeIf(String::isNotBlank) }
                        .toSet()
                }
                is ToolExecutionResultMessage -> {
                    val id = message.id()
                    if (id != null && id in pendingCallIds) {
                        sanitized += message
                        pendingCallIds = pendingCallIds - id
                    } else {
                        Log.w(TAG, "Dropping orphaned tool result id=$id name=${message.toolName()}")
                    }
                }
                else -> {
                    sanitized += message
                    pendingCallIds = emptySet()
                }
            }
        }
        return sanitized
    }

    /** Keeps a non-null assistant content field on tool-call turns for strict compatibility bridges. */
    private fun normalizeToolCallMessage(message: AiMessage): AiMessage =
        if (!message.hasToolExecutionRequests() || message.text() != null) {
            message
        } else {
            AiMessage.builder()
                .text("")
                .thinking(message.thinking())
                .attributes(message.attributes())
                .toolExecutionRequests(message.toolExecutionRequests())
                .build()
        }

    /**
     * Caps a tool result before it becomes model context, using the same limits as the agent host:
     * [MAX_TOOL_RESULT_LINES] lines or [MAX_TOOL_RESULT_BYTES] bytes, whichever comes first.
     *
     * A single unbounded listing can exceed the model's context window and make the provider return
     * an empty completion. Nothing is lost: the full result is written into the workspace and the
     * model is told where to read it back.
     */
    private fun boundedToolResult(result: String): String {
        val lineCount = result.count { it == '\n' } + 1
        val byteCount = result.toByteArray(Charsets.UTF_8).size
        if (lineCount <= MAX_TOOL_RESULT_LINES && byteCount <= MAX_TOOL_RESULT_BYTES) return result
        val preview = truncateToolResult(result)
        val overflowPath = persistToolResult(result)
        val recovery = if (overflowPath != null) {
            "the full output was written to $overflowPath; read it with read_agent_workspace_file using encoding=utf8_chars and next_offset"
        } else {
            "narrow the request or read the source in smaller chunks"
        }
        return preview + "\n... [truncated: $lineCount lines / $byteCount bytes; $recovery] ..."
    }

    /** Keeps whole lines until either limit is reached, never splitting a line in half. */
    private fun truncateToolResult(result: String): String {
        val builder = StringBuilder()
        var bytes = 0
        var lines = 0
        var index = 0
        while (index < result.length && lines < MAX_TOOL_RESULT_LINES) {
            val newline = result.indexOf('\n', index)
            val end = if (newline < 0) result.length else newline + 1
            val size = result.substring(index, end).toByteArray(Charsets.UTF_8).size
            if (bytes + size > MAX_TOOL_RESULT_BYTES) break
            builder.append(result, index, end)
            bytes += size
            lines++
            index = end
        }
        if (builder.isEmpty()) {
            var end = minOf(result.length, MAX_TOOL_RESULT_BYTES)
            while (end > 0 && result.substring(0, end).toByteArray(Charsets.UTF_8).size > MAX_TOOL_RESULT_BYTES) {
                end = end * 3 / 4
            }
            if (end > 0 && Character.isHighSurrogate(result[end - 1])) end--
            builder.append(result, 0, end)
        }
        return builder.toString()
    }

    /** Writes the full tool result under the workspace and returns its workspace-relative path. */
    private fun persistToolResult(result: String): String? {
        val root = agentWorkspaceRoot ?: return null
        return runCatching {
            val directory = File(root, TOOL_OUTPUT_DIR)
            if (!directory.isDirectory && !directory.mkdirs()) return null
            val name = "tool-output-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}.txt"
            File(directory, name).writeText(result, Charsets.UTF_8)
            "$TOOL_OUTPUT_DIR/$name"
        }.getOrNull()
    }

    private fun finishReason(response: ChatResponse?): String =
        response?.let { runCatching { it.finishReason()?.name }.getOrNull() } ?: "unknown"

    /**
     * Records what a round actually produced. When a reply arrives empty (or a tool call carries an
     * enormous argument), this is the only evidence of what the model did.
     */
    private fun logAssistantRound(
        round: Int,
        text: String,
        aiMessage: AiMessage,
        mode: String,
        response: ChatResponse,
    ) {
        val requests = aiMessage.toolExecutionRequests()
        val summary = if (requests.isEmpty()) {
            "none"
        } else {
            requests.joinToString { "${it.name()}(${it.arguments().length} chars)" }
        }
        Log.i(
            TAG,
            "round=$round mode=$mode textChars=${text.length} toolCalls=${requests.size} " +
                "finish=${finishReason(response)} args=[$summary]",
        )
    }

    private fun streamRequest(
        streamingModel: StreamingChatModel,
        messages: List<ChatMessage>,
        onText: (String) -> Unit,
        onThinking: (String) -> Unit,
    ): StreamingResult {
        val startedAt = System.nanoTime()
        val lock = Object()
        val response = java.util.concurrent.atomic.AtomicReference<ChatResponse?>()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val text = StringBuilder()
        streamingModel.chat(
            chatRequest(messages),
            object : StreamingChatResponseHandler {
                override fun onPartialResponse(partialResponse: String) {
                    synchronized(lock) {
                        if (closed.get()) return
                        text.append(partialResponse)
                        onText(partialResponse)
                    }
                }

                override fun onPartialThinking(partialThinking: dev.langchain4j.model.chat.response.PartialThinking) {
                    synchronized(lock) {
                        if (closed.get()) return
                        val value = partialThinking.text()
                        if (value.isNotEmpty()) {
                            onThinking(value)
                        }
                    }
                }

                override fun onCompleteResponse(completed: ChatResponse) {
                    synchronized(lock) {
                        if (closed.getAndSet(true)) return
                        response.set(completed)
                        lock.notifyAll()
                    }
                }

                override fun onError(error: Throwable) {
                    synchronized(lock) {
                        if (closed.getAndSet(true)) return
                        failure.set(error)
                        lock.notifyAll()
                    }
                }
            },
        )
        try {
            synchronized(lock) {
                while (response.get() == null && failure.get() == null) {
                    checkCancelled()
                    val remaining = streamingTimeoutMillis -
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                    if (remaining <= 0L) throw java.net.SocketTimeoutException("Streaming model response timed out")
                    lock.wait(minOf(1000L, remaining))
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.util.concurrent.CancellationException("Streaming request cancelled")
        } finally {
            synchronized(lock) { closed.set(true) }
        }
        failure.get()?.let { throw it }
        val completed = requireNotNull(response.get())
        val elapsedNanos = System.nanoTime() - startedAt
        completed.tokenUsage()?.outputTokenCount()?.takeIf { it > 0 }?.let { tokens ->
            if (elapsedNanos > 0L) onOutputSpeed(tokens.toDouble() * 1_000_000_000.0 / elapsedNanos)
        }
        return StreamingResult(completed, text.toString())
    }

    private data class StreamingResult(val response: ChatResponse, val text: String)
}
