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
import io.stamethyst.backend.mods.AgentPatchSourceCompiler
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

private const val DEFAULT_AGENT_TOOL_ROUNDS = 24
private const val TAG = "AgentGateway"
private const val DEFAULT_MAX_LIST_ENTRIES = 1200
private const val DEFAULT_MAX_LIST_CHARS = 20_000
private const val DEFAULT_MAX_READ_BYTES = 16L * 1024L

/**
 * Hard ceiling on a single tool result handed back to the model.
 *
 * Tool output is model context: an unbounded listing or file read can exceed the model's context
 * window and make the provider answer with an empty completion. Tools also bound their own output;
 * this is the last line of defence.
 */
private const val MAX_TOOL_RESULT_CHARS = 24_000

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
                "it with 'path' (for example patch/<id>/src or inspection/<id>/source) when the workspace " +
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
    private val maxBytes: Long = DEFAULT_MAX_READ_BYTES,
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("read_agent_workspace_file")
        .description(
            "Read a byte range of a file in the selected mod's isolated agent workspace; use " +
                "encoding=base64 for binary files. Large files are returned in chunks: read with " +
                "'offset', then continue from 'next_offset' while 'truncated' is true.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("path", "Relative path inside the agent workspace.")
                .addStringProperty("encoding", "utf8 or base64; defaults to utf8.")
                .addStringProperty("offset", "Byte offset to start reading from; defaults to 0.")
                .addStringProperty("length", "Maximum number of bytes to read; defaults to $DEFAULT_MAX_READ_BYTES.")
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
        val totalBytes = file.length()
        val offset = json["offset"]?.jsonPrimitive?.content?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        if (offset > totalBytes) return failure("offset_past_end_of_file")
        val requested = json["length"]?.jsonPrimitive?.content?.toLongOrNull()?.coerceAtLeast(1L) ?: maxBytes
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
        val encoding = json["encoding"]?.jsonPrimitive?.content?.lowercase() ?: "utf8"
        val content = when (encoding) {
            "base64" -> android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            "utf8" -> bytes.toString(Charsets.UTF_8)
            else -> return failure("unsupported_encoding")
        }
        val nextOffset = offset + bytes.size
        val truncated = nextOffset < totalBytes
        return buildJsonObject {
            put("path", JsonPrimitive(relativePath))
            put("encoding", JsonPrimitive(encoding))
            put("total_bytes", JsonPrimitive(totalBytes))
            put("offset", JsonPrimitive(offset))
            put("returned_bytes", JsonPrimitive(bytes.size))
            put("truncated", JsonPrimitive(truncated))
            if (truncated) put("next_offset", JsonPrimitive(nextOffset))
            put("content", JsonPrimitive(content))
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
        .description("Create or replace any file under the selected mod's agent workspace. Paths may target inspections, previous revisions, or the current patch revision.")
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("path", "Relative path inside the agent workspace.")
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
        val target = resolveWorkspaceFile(workspaceRoot, relativePath) ?: return failure("path_outside_workspace")
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
            "Delete any file or directory under the selected mod's agent workspace. Pass recursive=true " +
                "to delete a directory and everything under it. The workspace root itself cannot be deleted.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("path", "Relative path inside the agent workspace.")
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
        val target = resolveWorkspaceFile(workspaceRoot, relativePath) ?: return failure("path_outside_workspace")
        val targetPath = target.toPath()
        if (targetPath == workspaceRoot.canonicalFile.toPath()) return failure("cannot_delete_workspace_root")
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
    private val currentWorkspace: () -> AgentPatchWorkspace?,
    private val onCreated: (AgentPatchWorkspace) -> Unit = {},
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("create_agent_patch_mod")
        .description(
            "Create the isolated patch-mod workspace for the selected parent mod. Extracts the " +
                "complete original mod under source/ and prepares an editable patch/ tree. Call this " +
                "once before writing any patch files; do not call it unless the user asked for a change. " +
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

        currentWorkspace()?.let { existing -> return describe("exists", existing, reused = true) }

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
        return describe("created", workspace, reused = false)
    }

    private fun describe(status: String, workspace: AgentPatchWorkspace, reused: Boolean): String =
        buildJsonObject {
            put("status", JsonPrimitive(status))
            if (reused) put("note", JsonPrimitive("A patch workspace already exists for this session; reusing it."))
            put("parent_mod_id", JsonPrimitive(workspace.parentModId))
            put("patch_id", JsonPrimitive(workspace.patchId))
            put("workspace_root", JsonPrimitive(workspace.root.absolutePath))
            put("source_root", JsonPrimitive(workspace.sourceRoot.absolutePath))
            put("patch_root", JsonPrimitive(workspace.patchRoot.absolutePath))
            put(
                "source_note",
                JsonPrimitive(
                    "source/ holds the parent mod's extracted classes as .class. " +
                        "decompile_agent_mod_source writes readable Java to a separate inspection tree.",
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
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("compile_agent_patch_source")
        .description(
            "Compile the Java sources under patch/src/ into Java 8 .class files at the patch root. " +
                "Run this after writing source and before package_agent_patch_mod. The classpath already " +
                "includes the game jar, ModTheSpire, the required mods, and the parent mod, so imports such " +
                "as com.megacrit.cardcrawl.*, basemod.* and com.evacipated.cardcrawl.modthespire.lib.* resolve.",
        )
        .parameters(JsonObjectSchema.builder().additionalProperties(false).build())
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.PATCH_COMPILE

    override fun execute(arguments: String): String {
        val activeWorkspace = workspace() ?: return failure("patch_workspace_not_created")
        val result = runCatching {
            AgentPatchSourceCompiler.compile(context, activeWorkspace, parentJar)
        }.getOrElse { return failure(it.message ?: "compile_failed") }
        return buildJsonObject {
            put("status", JsonPrimitive(if (result.success) "compiled" else "failed"))
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
            "Decompile the selected parent mod into a readable Java project: every " +
                "inspection/source/**/*.class becomes a .java next to it. This is an independent " +
                "inspection operation and does not create a patch workspace. Call it directly when " +
                "you only need to inspect the parent mod. Classes CFR cannot handle stay as .class.",
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
            put("inspection_root", JsonPrimitive(inspection.root.absolutePath))
            put("source_root", JsonPrimitive(inspection.sourceRoot.absolutePath))
            put("total_classes", JsonPrimitive(result.totalClasses))
            put("decompiled_classes", JsonPrimitive(result.decompiledClasses))
            put("failed_classes", JsonPrimitive(result.failedClasses))
            if (result.skipped) {
                put("reason", JsonPrimitive("Too many classes to decompile; inspection/source keeps raw .class files."))
            }
        }.toString()
    }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()
}

class AgentPatchModPackageTool(
    private val context: Context,
    private val workspace: () -> AgentPatchWorkspace?,
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("package_agent_patch_mod")
        .description("Package the files under patch/ into a versioned patch mod for the selected parent mod.")
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
        val hasSources = activeWorkspace.patchRoot.walkTopDown()
            .any { it.isFile && it.extension.equals("java", ignoreCase = true) }
        val hasClasses = activeWorkspace.patchRoot.walkTopDown()
            .any { it.isFile && it.extension.equals("class", ignoreCase = true) }
        if (hasSources && !hasClasses) {
            return failure("patch_sources_not_compiled: call compile_agent_patch_source first")
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

private fun failure(code: String): String = buildJsonObject {
    put("error", JsonPrimitive(code))
}.toString()

data class AgentReply(
    val text: String,
    val toolRounds: Int,
    val contextTokens: Int? = null,
)

class AgentToolRoundLimitExceeded : IllegalStateException("The agent exceeded its tool-call budget.")

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
    // A round is one model request, not one tool call. File inspection commonly needs several
    // rounds, especially when the model first reads an index and then verifies source snapshots.
    private val maxToolRounds: Int = DEFAULT_AGENT_TOOL_ROUNDS,
) {
    init {
        require(maxToolRounds > 0) { "maxToolRounds must be positive." }
    }

    fun respond(systemPrompt: String, userPrompt: String): AgentReply {
        val messages = mutableListOf<ChatMessage>(
            SystemMessage.from(systemPrompt),
            UserMessage.from(userPrompt),
        )

        repeat(maxToolRounds) { round ->
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
        }

        throw AgentToolRoundLimitExceeded()
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

        repeat(maxToolRounds) { round ->
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
        }
        throw AgentToolRoundLimitExceeded()
    }

    private fun chatRequest(messages: List<ChatMessage>) = ChatRequest.builder()
        .messages(messages)
        .toolSpecifications(toolRegistry.toolSpecifications())
        .build()

    /**
     * Caps a tool result before it becomes model context. A single unbounded listing can exceed the
     * model's context window and make the provider return an empty completion.
     */
    private fun boundedToolResult(result: String): String {
        if (result.length <= MAX_TOOL_RESULT_CHARS) return result
        return result.take(MAX_TOOL_RESULT_CHARS) +
            "\n... [truncated: the tool returned ${result.length} characters; narrow the request or read in chunks] ..."
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
