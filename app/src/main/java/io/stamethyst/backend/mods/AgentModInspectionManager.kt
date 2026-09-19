package io.stamethyst.backend.mods

import android.content.Context
import io.stamethyst.config.RuntimePaths
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.ZipFile

data class AgentModInspectionWorkspace(
    val parentModId: String,
    val inspectionId: String,
    val root: File,
    val sourceRoot: File,
)

/** Owns source inspections that are independent of patch revisions. */
object AgentModInspectionManager {
    private const val MAX_FILES = 4096
    private const val MAX_ENTRY_BYTES = 128L * 1024L * 1024L
    private const val MAX_JAR_BYTES = 512L * 1024L * 1024L

    fun createInspection(
        context: Context,
        parentModId: String,
        sourceJar: File,
    ): AgentModInspectionWorkspace {
        require(sourceJar.isFile) { "The selected mod JAR is missing." }
        val resolvedParent = resolveParentModId(sourceJar, parentModId)
        val parentSegment = safeSegment(resolvedParent)
        val inspectionId = "inspection-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val root = RuntimePaths.agentModInspectionRoot(context, parentSegment).resolve(inspectionId)
        val sourceRoot = root.resolve("source")
        sourceRoot.mkdirs()
        extractJar(sourceJar, sourceRoot)
        root.resolve("inspection-metadata.json").writeText(
            JSONObject()
                .put("parent_mod_id", resolvedParent)
                .put("inspection_id", inspectionId)
                .put("source_path", sourceJar.canonicalPath)
                .put("source_sha256", sha256(sourceJar))
                .put("source_root", "source")
                .put("source_decompiled", false)
                .toString(2),
            StandardCharsets.UTF_8,
        )
        return AgentModInspectionWorkspace(resolvedParent, inspectionId, root, sourceRoot)
    }

    fun recordDecompilation(
        workspace: AgentModInspectionWorkspace,
        result: AgentPatchJarDecompileResult,
    ) {
        val metadataFile = workspace.root.resolve("inspection-metadata.json")
        val metadata = runCatching {
            if (metadataFile.isFile) JSONObject(metadataFile.readText(StandardCharsets.UTF_8)) else JSONObject()
        }.getOrElse { JSONObject() }
        metadata.put("source_decompiled", !result.skipped)
            .put("source_classes", result.totalClasses)
            .put("source_decompiled_classes", result.decompiledClasses)
            .put("source_failed_classes", result.failedClasses)
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

    private fun extractJar(source: File, destination: File) {
        val canonicalDestination = destination.canonicalFile
        ZipFile(source).use { zip ->
            var count = 0
            var total = 0L
            zip.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                if (++count > MAX_FILES) throw IOException("The source mod contains too many files.")
                val name = normalizeEntry(entry.name)
                val target = File(canonicalDestination, name).canonicalFile
                if (!target.toPath().startsWith(canonicalDestination.toPath())) {
                    throw IOException("Invalid archive entry: ${entry.name}")
                }
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var entryBytes = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            entryBytes += read
                            total += read
                            if (entryBytes > MAX_ENTRY_BYTES) {
                                throw IOException("Source entry is too large: $name")
                            }
                            if (total > MAX_JAR_BYTES) {
                                throw IOException("The source mod is too large.")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
        }
    }

    private fun normalizeEntry(raw: String): String {
        val value = raw.replace('\\', '/').trim()
        require(value.isNotEmpty() && !value.startsWith('/') && !value.contains(':')) {
            "Invalid archive entry."
        }
        val parts = value.split('/')
        require(parts.none { it.isEmpty() || it == "." || it == ".." }) {
            "Invalid archive entry: $raw"
        }
        return parts.joinToString("/")
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    }
}
