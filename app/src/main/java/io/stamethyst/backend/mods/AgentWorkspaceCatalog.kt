package io.stamethyst.backend.mods

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Workspace identity comes from the parent directory AND descriptor, never a model-supplied path. */
internal data class AgentWorkspaceEntry(val id: String, val name: String)

internal object AgentWorkspaceCatalog {
    private val safeId = Regex("patch-[A-Za-z0-9_-]+")

    fun entry(root: File, id: String): AgentWorkspaceEntry? {
        if (!safeId.matches(id)) return null
        val base = File(root.canonicalFile, "patch_source")
        val dir = File(base, id)
        if (dir.canonicalFile != dir || !dir.isDirectory) return null
        val descriptor = File(dir, "agent-patch.json")
        val manifest = File(dir, "ModTheSpire.json")
        if (manifest.canonicalFile != manifest || !manifest.isFile ||
            descriptor.canonicalFile != descriptor || !descriptor.isFile || descriptor.length() > 1_048_576) return null
        return runCatching {
            val data = Json.parseToJsonElement(descriptor.readText()).jsonObject
            if (data["patch_id"]?.jsonPrimitive?.content != id ||
                !root.name.equals(data["parent_mod_id"]?.jsonPrimitive?.content, ignoreCase = true)) return null
            AgentWorkspaceEntry(id, data["name"]?.jsonPrimitive?.content?.ifBlank { id } ?: id)
        }.getOrNull()
    }

    fun list(root: File): List<AgentWorkspaceEntry> = File(root, "patch_source").list().orEmpty()
        .mapNotNull { entry(root, it) }.sortedBy { it.id }
}
