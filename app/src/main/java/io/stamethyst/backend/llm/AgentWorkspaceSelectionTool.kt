package io.stamethyst.backend.llm

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import kotlinx.serialization.json.*

internal const val SWITCH_WORKSPACE = "switch_agent_workspace"

internal class AgentWorkspaceSelectionTool(
    private val choices: () -> JsonObject,
    private val select: (String) -> Boolean,
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder().name(SWITCH_WORKSPACE)
        .description("Omit patch_id to list this parent's drafts and packaged workspaces. Pass an exact ID to bind this conversation; call alone and wait before edits. Does not package or enable.")
        .parameters(JsonObjectSchema.builder().addStringProperty("patch_id", "Existing patch ID.")
            .additionalProperties(false).build()).build()
    override val safety = AgentToolSafety.WORKSPACE_WRITE
    override fun execute(arguments: String): String {
        val obj = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return """{"error":"invalid_arguments"}"""
        if ("patch_id" !in obj) return choices().toString()
        val id = (obj["patch_id"] as? JsonPrimitive)?.contentOrNull ?: return """{"error":"invalid_arguments"}"""
        if (!select(id)) return """{"error":"patch_workspace_not_found"}"""
        return buildJsonObject { put("status", "selected"); put("patch_id", id); put("patch_workspace_path", "/patch_source/$id") }.toString()
    }
}

/** Explicit legacy patch_id arguments may assert the bound target, never override it. */
internal class BoundRevisionTool(
    private val delegate: AgentTool,
    private val checkTree: (() -> Boolean)? = null,
    private val current: () -> String?,
) : AgentTool by delegate {
    override fun execute(arguments: String): String {
        val id = current() ?: return """{"error":"workspace_not_selected","hint":"Call switch_agent_workspace first."}"""
        val obj = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return """{"error":"invalid_arguments"}"""
        if ("patch_id" in obj && (obj["patch_id"] as? JsonPrimitive)?.contentOrNull != id)
            return """{"error":"workspace_mismatch","hint":"Call switch_agent_workspace in a separate turn first."}"""
        // The compiler and packager walk the tree, not just a single validated file path.
        if (checkTree?.invoke() == false)
            return """{"error":"unsafe_workspace_link","hint":"Compile and package require a workspace without symbolic links."}"""
        return delegate.execute(arguments)
    }
    override fun executeWithMedia(arguments: String) = AgentToolOutput(execute(arguments))
}
