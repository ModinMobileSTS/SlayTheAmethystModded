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
import io.stamethyst.backend.mods.AgentPatchClassDecompiler
import io.stamethyst.backend.mods.AgentModInspectionManager
import io.stamethyst.backend.mods.AgentPatchModManager
import io.stamethyst.backend.mods.AgentPatchPreflight
import io.stamethyst.backend.mods.AgentPatchSourceCompiler
import io.stamethyst.backend.mods.AgentPatchTargetInspector
import io.stamethyst.backend.mods.AgentPatchWorkspace
import dev.langchain4j.http.client.okhttp.OkHttpClientBuilder
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiResponsesChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
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

/** Text reads above this size are refused rather than streamed into an unbounded line buffer. */
private const val MAX_TEXT_READ_BYTES = 16L * 1024L * 1024L

/** Tool results are capped at this many lines and bytes before they become model context. */
private const val MAX_TOOL_RESULT_LINES = 2000
private const val MAX_TOOL_RESULT_BYTES = 51_200

/** Workspace subdirectory where oversized tool results are persisted for later reading. */
private const val TOOL_OUTPUT_DIR = "tool_output"
private const val TOOL_OUTPUT_KEEP = 20

/** How many extra times an empty model turn is retried before it is reported as a failure. */
private const val MAX_EMPTY_RETRIES = 1

data class OpenAiCompatibleModelConfig(
    val baseUrl: String,
    val apiKey: String,
    val modelName: String,
    val endpoint: LlmEndpoint = LlmEndpoint.CHAT_COMPLETIONS,
    val requestTimeoutSeconds: Int = DEFAULT_LLM_REQUEST_TIMEOUT_SECONDS,
    val reasoningEffort: LlmReasoningEffort = LlmReasoningEffort.OFF,
    val organizationId: String = "",
)

enum class LlmEndpoint {
    CHAT_COMPLETIONS,
    RESPONSES,
}

object AgentChatModelFactory {
    private const val CONNECT_TIMEOUT_SECONDS = 30L

    private fun httpClientBuilder(config: OpenAiCompatibleModelConfig): OkHttpClientBuilder {
        val timeoutSeconds = config.requestTimeoutSeconds
            .coerceIn(MIN_LLM_REQUEST_TIMEOUT_SECONDS, MAX_LLM_REQUEST_TIMEOUT_SECONDS)
            .toLong()
        val okHttpClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder().apply {
                    if (config.organizationId.isNotBlank()) {
                        header("OpenAI-Organization", config.organizationId.trim())
                    }
                }.build()
                chain.proceed(request)
            }
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        return OkHttpClientBuilder().okHttpClientBuilder(okHttpClient)
    }

    fun create(config: OpenAiCompatibleModelConfig): ChatModel = when (config.endpoint) {
        LlmEndpoint.CHAT_COMPLETIONS -> OpenAiChatModel.builder()
            .httpClientBuilder(httpClientBuilder(config))
            .baseUrl(config.baseUrl)
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .reasoningEffort(config.reasoningEffort.requestValue)
            .maxRetries(1)
            .build()
        LlmEndpoint.RESPONSES -> OpenAiResponsesChatModel.builder()
            .httpClientBuilder(httpClientBuilder(config))
            .baseUrl(config.baseUrl)
            .apiKey(config.apiKey)
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
                .modelName(config.modelName)
                .reasoningEffort(config.reasoningEffort.requestValue)
                .timeout(java.time.Duration.ofSeconds(config.requestTimeoutSeconds.toLong()))
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

interface AgentTool {
    val specification: ToolSpecification
    val safety: AgentToolSafety

    fun execute(arguments: String): String
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
)

class AgentToolRegistry(
    tools: List<AgentTool>,
    private val policyGate: AgentToolPolicyGate = AgentToolPolicyGate(),
    private val onToolExecution: (AgentToolExecutionEvent) -> Unit = {},
) {
    private val toolsByName = tools.associateBy { it.specification.name() }

    init {
        require(toolsByName.size == tools.size) { "Agent tool names must be unique." }
    }

    fun toolSpecifications(): List<ToolSpecification> = toolsByName.values
        .filter(policyGate::allows)
        .map(AgentTool::specification)

    fun execute(request: ToolExecutionRequest): String {
        val event = AgentToolExecutionEvent(
            id = java.util.UUID.randomUUID().toString(),
            name = request.name(),
            arguments = request.arguments(),
        )
        onToolExecution(event)
        val tool = toolsByName[request.name()]
        val result = when {
            tool == null -> failure("unknown_tool")
            !policyGate.allows(tool) -> failure("approval_required")
            else -> runCatching { tool.execute(request.arguments()) }
                .getOrElse { failure("tool_execution_failed") }
        }
        Log.i(TAG, "tool=${request.name()} resultChars=${result.length}")
        val failed = runCatching {
            Json.parseToJsonElement(result).jsonObject.containsKey("error")
        }.getOrDefault(false)
        onToolExecution(event.copy(result = result, failed = failed))
        return result
    }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()
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
                "is true. Use encoding=base64 for binary files, where 'offset' and 'limit' are bytes.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("path", "Relative path inside the agent workspace.")
                .addStringProperty("encoding", "utf8 or base64; defaults to utf8.")
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

    override fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments")
        val relativePath = json["path"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: return failure("invalid_arguments")
        val file = resolveWorkspaceFile(workspaceRoot, relativePath) ?: return failure("path_outside_workspace")
        if (!file.isFile) return failure("file_not_found")
        return when (json["encoding"]?.jsonPrimitive?.content?.lowercase() ?: "utf8") {
            "utf8" -> readText(relativePath, file, json)
            "base64" -> readBytes(relativePath, file, json)
            else -> failure("unsupported_encoding")
        }
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
            put("content", JsonPrimitive(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
        }.toString()
    }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()
}

class AgentWorkspaceWriteTool(
    private val workspaceRoot: File,
    private val maxBytes: Long = 64L * 1024L * 1024L,
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("write_agent_workspace_file")
        .description("Create or replace a file under patch_source/<patch_id>/ in the selected mod's agent workspace. Use the patch_workspace_path returned by create_agent_patch_mod. The source/ tree is read-only and cannot be modified by the agent.")
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
            if (hasBytes) android.util.Base64.decode(json["contentBase64"]!!.jsonPrimitive.content, android.util.Base64.DEFAULT)
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

class AgentPatchModCreateTool(
    private val context: Context,
    private val parentModId: String,
    private val sourceJar: File,
    private val onCreated: (AgentPatchWorkspace) -> Unit = {},
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("create_agent_patch_mod")
        .description(
            "Create a new patch-mod workspace for the selected parent mod. Extracts the " +
                "complete original mod under source/ and prepares an editable patch_source/<patch_id>/ tree. Call this " +
                "once for each new patch mod before writing its files; do not call it unless the user asked for a change. " +
                "Call decompile_agent_mod_source independently if you need to read the parent mod's classes.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("name", "Short display name for the patch mod; you choose this.")
                .addStringProperty("version", "Semantic version of the patch mod; defaults to 0.1.0.")
                .addStringProperty("description", "What this patch changes.")
                .required("name")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.PATCH_CREATE

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
                    "source/ holds the parent mod's extracted and decompiled files as read-only context. " +
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
            "Extract and decompile the selected parent mod into source/ in the selected mod workspace. " +
                "The source/ tree is readable agent context and is never writable through workspace tools. " +
                "Classes CFR cannot handle stay as .class.",
        )
        .parameters(JsonObjectSchema.builder().additionalProperties(false).build())
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.MOD_INSPECTION

    override fun execute(arguments: String): String {
        val inspection = runCatching {
            AgentModInspectionManager.createInspection(
                context = context,
                parentModId = parentModId,
                sourceJar = parentJar,
            )
        }.getOrElse { return failure(it.message ?: "inspection_failed") }
        val result = runCatching {
            AgentPatchClassDecompiler.decompileJarInto(
                sourceDir = inspection.sourceRoot,
                jarFile = parentJar,
                classpath = AgentPatchSourceCompiler.buildClasspath(context, parentJar),
            )
        }.getOrElse { return failure(it.message ?: "decompile_failed") }
        AgentModInspectionManager.recordDecompilation(inspection, result)
        return buildJsonObject {
            put("status", JsonPrimitive(if (result.skipped) "skipped" else "decompiled"))
            put("parent_mod_id", JsonPrimitive(inspection.parentModId))
            put("inspection_id", JsonPrimitive(inspection.inspectionId))
            put("workspace_root", JsonPrimitive(inspection.root.absolutePath))
            put("source_root", JsonPrimitive(inspection.sourceRoot.absolutePath))
            put("total_classes", JsonPrimitive(result.totalClasses))
            put("decompiled_classes", JsonPrimitive(result.decompiledClasses))
            put("failed_classes", JsonPrimitive(result.failedClasses))
            if (result.skipped) {
                put("reason", JsonPrimitive("Too many classes to decompile; source/ keeps raw .class files."))
            }
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
                "and descriptor instead of guessing signatures.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("target_class", "Binary class name, for example com.example.Target.")
                .addStringProperty("member", "Method name, constructor, <ctor>, or <class>.")
                .required("target_class", "member")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.READ_ONLY

    override fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments")
        val targetClass = json["target_class"]?.jsonPrimitive?.content.orEmpty()
        val member = json["member"]?.jsonPrimitive?.content.orEmpty()
        if (targetClass.isBlank() || member.isBlank()) return failure("invalid_arguments")
        val classpath = runCatching { AgentPatchSourceCompiler.resolveCompileClasspath(context, parentJar) }
            .getOrElse { return failure(it.message ?: "classpath_failed") }
        val inspection = runCatching {
            AgentPatchTargetInspector.inspectTarget(targetClass, member, classpath)
        }.getOrElse { return failure(it.message ?: "target_inspection_failed") }
        return buildJsonObject {
            put("status", JsonPrimitive("inspected"))
            put("target_class", JsonPrimitive(inspection.targetClassName))
            put("member", JsonPrimitive(inspection.memberName))
            put("class_origin", JsonPrimitive(inspection.classOrigin))
            put("overloads", kotlinx.serialization.json.buildJsonArray {
                inspection.overloads.forEachIndexed { index, signature ->
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
                .addStringProperty("name", "Display name of the patch mod.")
                .addStringProperty("version", "Patch mod version.")
                .addStringProperty("description", "What this patch changes.")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.PATCH_PACKAGE

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
                .addStringProperty("name", "New display name; unchanged when omitted.")
                .addStringProperty("description", "New description; unchanged when omitted.")
                .required("patch_id", "version")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.PATCH_PACKAGE

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
) {
    fun respond(systemPrompt: String, userPrompt: String): AgentReply {
        val messages = mutableListOf<ChatMessage>(
            SystemMessage.from(systemPrompt),
            UserMessage.from(userPrompt),
        )

        var round = 0
        while (true) {
            var lastResponse: ChatResponse? = null
            var response: ChatResponse? = null
            for (attempt in 0..MAX_EMPTY_RETRIES) {
                val candidate = chatModel.chat(chatRequest(messages))
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
            val aiMessage = completed.aiMessage()
            messages += aiMessage
            val text = aiMessage.text().orEmpty()
            logAssistantRound(round, text, aiMessage, "non-streaming", completed)
            if (!aiMessage.hasToolExecutionRequests()) {
                return AgentReply(text = text, toolRounds = round, contextTokens = completed.tokenUsage()?.totalTokenCount())
            }
            aiMessage.toolExecutionRequests().forEach { request ->
                messages += ToolExecutionResultMessage.from(request, boundedToolResult(toolRegistry.execute(request)))
            }
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
        val messages = mutableListOf<ChatMessage>(
            SystemMessage.from(systemPrompt),
            UserMessage.from(userPrompt),
        )

        var round = 0
        while (true) {
            var lastResult: StreamingResult? = null
            var accepted: StreamingResult? = null
            for (attempt in 0..MAX_EMPTY_RETRIES) {
                val candidate = streamRequest(
                    streamingModel = streamingModel,
                    messages = messages,
                    onText = onText,
                    onThinking = onThinking,
                )
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
                val fallback = runCatching { chatModel.chat(chatRequest(messages)) }.getOrNull()
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
            val aiMessage = result.response.aiMessage()
            messages += aiMessage
            val text = result.text.ifBlank { aiMessage.text().orEmpty() }
            logAssistantRound(round, text, aiMessage, "streaming", result.response)
            if (!aiMessage.hasToolExecutionRequests()) {
                return AgentReply(text = text, toolRounds = round, contextTokens = result.response.tokenUsage()?.totalTokenCount())
            }
            aiMessage.toolExecutionRequests().forEach { request ->
                messages += ToolExecutionResultMessage.from(request, boundedToolResult(toolRegistry.execute(request)))
            }
            round++
        }
    }

    private fun chatRequest(messages: List<ChatMessage>) = ChatRequest.builder()
        .messages(messages)
        .toolSpecifications(toolRegistry.toolSpecifications())
        .build()

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
            "the full output was written to $overflowPath; read it with read_agent_workspace_file"
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
        if (builder.isEmpty()) builder.append(result.take(MAX_TOOL_RESULT_BYTES))
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
            pruneToolResults(directory)
            "$TOOL_OUTPUT_DIR/$name"
        }.getOrNull()
    }

    private fun pruneToolResults(directory: File) {
        directory.listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile }
            .sortedByDescending { it.lastModified() }
            .drop(TOOL_OUTPUT_KEEP)
            .forEach { it.delete() }
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
        val lock = Object()
        var response: ChatResponse? = null
        var failure: Throwable? = null
        val text = StringBuilder()
        streamingModel.chat(
            ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(toolRegistry.toolSpecifications())
                .build(),
            object : StreamingChatResponseHandler {
                override fun onPartialResponse(partialResponse: String) {
                    text.append(partialResponse)
                    onText(partialResponse)
                }

                override fun onPartialThinking(partialThinking: dev.langchain4j.model.chat.response.PartialThinking) {
                    val value = partialThinking.text()
                    if (value.isNotEmpty()) onThinking(value)
                }

                override fun onCompleteResponse(completed: ChatResponse) {
                    response = completed
                    synchronized(lock) { lock.notifyAll() }
                }

                override fun onError(error: Throwable) {
                    failure = error
                    synchronized(lock) { lock.notifyAll() }
                }
            },
        )
        try {
            synchronized(lock) {
                while (response == null && failure == null) lock.wait(1000L)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.util.concurrent.CancellationException("Streaming request cancelled")
        }
        failure?.let { throw it }
        return StreamingResult(requireNotNull(response), text.toString())
    }

    private data class StreamingResult(val response: ChatResponse, val text: String)
}
