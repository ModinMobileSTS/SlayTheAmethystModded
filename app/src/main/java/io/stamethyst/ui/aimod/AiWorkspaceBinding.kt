package io.stamethyst.ui.aimod

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Null *column* means migration pending. A stored unbound result is a real decision. */
@Serializable
internal data class AiWorkspaceBinding(
    val id: String? = null,
    val origin: String = "unbound",
    val name: String? = null,
)

internal fun inferWorkspaceBinding(session: AiEditorSession, nameFor: (String) -> String?): AiWorkspaceBinding {
    val candidates = linkedSetOf<String>()
    val deleted = mutableSetOf<String>()
    val saved = session.context?.activePatchId
    saved?.let(candidates::add)
    val statuses = mapOf("create_agent_patch_workspace" to "created", "create_agent_patch_mod" to "created",
        "update_agent_patch_mod" to "updated", "package_agent_patch_mod" to "packaged",
        "compile_agent_patch_source" to "compiled", "generate_agent_patch_skeleton" to "generated",
        "edit_agent_workspace_file" to "edited", "delete_agent_patch_mod" to "deleted")
    val safeId = Regex("patch-[A-Za-z0-9_-]+")
    fun evidence(tool: String, result: String?): String? {
        if (result == null || tool !in statuses && tool != "write_agent_workspace_file") return null
        val j = runCatching { Json.parseToJsonElement(result).jsonObject }.getOrNull() ?: return null
        fun JsonObject.field(key: String) = (get(key) as? JsonPrimitive)?.content
        if ("error" in j || if (tool == "write_agent_workspace_file") j.field("bytes")?.toLongOrNull() == null
            else j.field("status") != statuses[tool]) return null
        val parts = j.field("path").orEmpty().trim('/').split('/')
        val id = (j.field("patch_id") ?: when {
            "patch_source" in parts -> parts.getOrNull(parts.indexOf("patch_source") + 1)
            parts.getOrNull(1) == "patch" -> parts.first()
            else -> null
        })?.takeIf(safeId::matches) ?: return null
        candidates += id
        if (tool == "delete_agent_patch_mod") deleted += id
        return id
    }
    // Stable UI message IDs order both logs. Do not replay a duplicated UI preview after a
    // newer protocol event, or use prose/failed attempts/reads as evidence of editing.
    val lastByMessage = sortedMapOf<Long, String>()
    session.messages.forEach { m -> m.tools.filterNot { it.failed || it.truncated }.forEach { t ->
        evidence(t.name, t.result)?.let { lastByMessage[m.id] = it }
    } }
    session.context?.messages.orEmpty().forEach { m ->
        m.patchState?.takeIf(safeId::matches)?.let(candidates::add)
        if (m.role == "tool") evidence(m.toolName, m.text)?.let { lastByMessage[m.sourceMessageId] = it }
    }
    val id = candidates.singleOrNull() ?: saved?.takeIf { it == lastByMessage.values.lastOrNull() }
    if (id == null) return AiWorkspaceBinding(origin = if (candidates.isEmpty()) "unbound" else "ambiguous")
    val name = if (id !in deleted) nameFor(id) else null
    return if (name != null) AiWorkspaceBinding(id, if (saved == id) "restored" else "inferred", name)
        else AiWorkspaceBinding(id, "missing")
}
