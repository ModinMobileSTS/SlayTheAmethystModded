package io.stamethyst.backend.mods

import android.content.Context
import io.stamethyst.config.RuntimePaths
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

data class AgentModInspectionWorkspace(
    val parentModId: String,
    val inspectionId: String,
    val root: File,
    val sourceRoot: File,
    val sourceSha256: String,
)

/** Owns the shared, read-only decompiled source tree for a selected parent mod. */
object AgentModInspectionManager {
    fun createInspection(
        context: Context,
        parentModId: String,
        sourceJar: File,
    ): AgentModInspectionWorkspace {
        require(sourceJar.isFile) { "The selected mod JAR is missing." }
        val resolvedParent = resolveParentModId(sourceJar, parentModId)
        val parentSegment = safeSegment(resolvedParent)
        val inspectionId = "inspection-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val root = RuntimePaths.agentModWorkspaceRoot(context, parentSegment)
        val sourceRoot = RuntimePaths.agentModSourceRoot(context, parentSegment)
        val sourceHash = AgentModSourceSnapshot.prepare(root, sourceRoot, sourceJar)
        val metadataFile = root.resolve("source-metadata.json")
        val metadata = JSONObject(metadataFile.readText(StandardCharsets.UTF_8))
        metadataFile.writeText(
            metadata.put("parent_mod_id", resolvedParent)
                .put("inspection_id", inspectionId)
                .put("source_sha256", sourceHash)
                .toString(2),
            StandardCharsets.UTF_8,
        )
        return AgentModInspectionWorkspace(resolvedParent, inspectionId, root, sourceRoot, sourceHash)
    }

    fun recordOnDemandClass(
        workspace: AgentModInspectionWorkspace,
        className: String,
    ) {
        val metadataFile = workspace.root.resolve("source-metadata.json")
        val metadata = runCatching {
            if (metadataFile.isFile) JSONObject(metadataFile.readText(StandardCharsets.UTF_8)) else JSONObject()
        }.getOrElse { JSONObject() }
        val materializedCount = workspace.sourceRoot.walkTopDown()
            .count { it.isFile && it.extension.equals("java", ignoreCase = true) }
        metadata.put("source_mode", "on_demand")
            .put("source_extracted", false)
            .put("source_decompiled", true)
            .put("source_materialized_classes", materializedCount)
            .put("last_materialized_class", className)
        runCatching { metadataFile.writeText(metadata.toString(2), StandardCharsets.UTF_8) }
    }

    private fun resolveParentModId(sourceJar: File, fallback: String): String {
        val manifestModId = runCatching {
            ModJarManifestParser.readModManifest(sourceJar).modId.trim()
        }.getOrNull().orEmpty()
        val resolved = manifestModId.ifEmpty { fallback.trim() }
        require(resolved.isNotEmpty()) { "Invalid mod identifier." }
        return resolved
    }

    private fun safeSegment(raw: String): String {
        val normalized = ModManager.normalizeModId(raw)
        require(normalized.matches(Regex("[a-z0-9._-]+"))) { "Invalid mod identifier." }
        return normalized
    }

}
