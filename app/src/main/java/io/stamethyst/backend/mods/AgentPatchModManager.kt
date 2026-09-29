package io.stamethyst.backend.mods

import android.content.Context
import io.stamethyst.config.RuntimePaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class AgentPatchWorkspace(
    /** Raw manifest modid of the parent mod, preserving its original casing. */
    val parentModId: String,
    /** Lowercased, filesystem-safe form of [parentModId] used for workspace paths. */
    val parentModSegment: String,
    val patchId: String,
    val root: File,
    val sourceRoot: File,
    val patchRoot: File,
)

data class AgentPatchModInfo(
    val parentModId: String,
    val patchId: String,
    val patchModId: String,
    val name: String,
    val version: String,
    val description: String,
    val jarFile: File,
    val enabled: Boolean,
)

/**
 * Owns the shared source workspace, per-patch source workspaces, and packaged AI patch mods.
 *
 * Packaged patch mods live under `agent_mods/<parent>/` and are loaded by the launcher directly
 * from there, so there is no separate "installed copy" in the optional-mod library. Enablement
 * is tracked by `patchModId` in `enabled_agent_patch_mods.txt`.
 */
object AgentPatchModManager {
    private const val MANIFEST_ENTRY = "ModTheSpire.json"
    private const val DESCRIPTOR_ENTRY = "agent-patch.json"
    private const val DEFAULT_PATCH_VERSION = "0.1.0"
    private const val MAX_FILES = 4096
    private const val MAX_ENTRY_BYTES = 128L * 1024L * 1024L
    private const val MAX_JAR_BYTES = 512L * 1024L * 1024L
    private val SAFE_PATH_SEGMENT = Regex("[A-Za-z0-9._-]+")

    /**
     * Resolves the lowercased, filesystem-safe segment used for a parent mod's workspace paths.
     *
     * Exposed so the launcher can address the parent mod's workspace (for example to read its
     * conversation history) without creating a patch revision first.
     */
    fun parentModSegment(parentModId: String): String = safeSegment(parentModId)

    /**
     * Creates a new patch-mod workspace revision for [parentModId].
     *
     * [name], [version], and [description] seed the generated `ModTheSpire.json` and
     * `agent-patch.json`; the caller (the AI agent) is expected to supply the display name.
     */
    fun createWorkspace(
        context: Context,
        parentModId: String,
        sourceJar: File,
        name: String = "",
        version: String = "",
        description: String = "",
    ): AgentPatchWorkspace {
        require(sourceJar.isFile) { "The selected mod JAR is missing." }
        val resolvedParent = resolveParentModId(sourceJar, parentModId)
        val parentSegment = safeSegment(resolvedParent)
        val patchId = "patch-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val root = RuntimePaths.agentModWorkspaceRoot(context, parentSegment)
        val sourceRoot = RuntimePaths.agentModSourceRoot(context, parentSegment)
        val patchRoot = RuntimePaths.agentModPatchSourceRoot(context, parentSegment, patchId)
        patchRoot.mkdirs()
        val sourceHash = AgentModSourceSnapshot.prepare(root, sourceRoot, sourceJar)

        val cleanName = name.trim().ifBlank { "AI Patch: $resolvedParent" }
        val cleanVersion = version.trim().ifBlank { DEFAULT_PATCH_VERSION }
        val cleanDescription = description.trim().ifBlank { "AI-generated patch for $resolvedParent" }
        val patchModId = patchModId(parentSegment, patchId)
        patchRoot.resolve(MANIFEST_ENTRY).writeText(
            JSONObject()
                .put("modid", patchModId)
                .put("name", cleanName)
                .put("version", cleanVersion)
                .put("description", cleanDescription)
                .put("dependencies", JSONArray().put(resolvedParent))
                .toString(2),
            StandardCharsets.UTF_8,
        )
        writeDescriptor(
            patchRoot.resolve(DESCRIPTOR_ENTRY),
            resolvedParent,
            patchId,
            patchModId,
            cleanName,
            cleanVersion,
            cleanDescription,
        )
        root.resolve("mod-metadata.json").writeText(
            JSONObject()
                .put("mod_id", resolvedParent)
                .put("name", cleanName)
                .put("patch_id", patchId)
                .put("source_path", sourceJar.canonicalPath)
                .put("source_sha256", sourceHash)
                .put("source_root", "source")
                .put("source_mode", "on_demand")
                .put("source_extracted", false)
                .put("patch_source_root", "patch_source/$patchId")
                .toString(2),
            StandardCharsets.UTF_8,
        )
        return AgentPatchWorkspace(resolvedParent, parentSegment, patchId, root, sourceRoot, patchRoot)
    }

    fun packagePatchMod(
        context: Context,
        workspace: AgentPatchWorkspace,
        name: String,
        version: String,
        description: String,
    ): AgentPatchModInfo {
        val parentDependency = workspace.parentModId.trim()
        require(parentDependency.isNotEmpty()) { "Invalid mod identifier." }
        val parentSegment = safeSegment(parentDependency)
        val patchModId = patchModId(parentSegment, workspace.patchId)
        val cleanName = name.trim().ifBlank { "AI Patch: $parentDependency" }
        val cleanVersion = version.trim().ifBlank { DEFAULT_PATCH_VERSION }
        val cleanDescription = description.trim().ifBlank { "AI-generated patch for $parentDependency" }
        val manifestFile = workspace.patchRoot.resolve(MANIFEST_ENTRY)
        val manifest = if (manifestFile.isFile) {
            JSONObject(manifestFile.readText(StandardCharsets.UTF_8))
        } else {
            JSONObject()
        }
        manifest.put("modid", patchModId)
            .put("name", cleanName)
            .put("version", cleanVersion)
            .put("description", cleanDescription)
        val dependencies = JSONArray()
        val existingDependencies = manifest.optJSONArray("dependencies")
        if (existingDependencies != null) {
            for (index in 0 until existingDependencies.length()) {
                val dependency = existingDependencies.optString(index).trim()
                if (dependency.isNotEmpty() && !dependency.equals(parentDependency, ignoreCase = true)) {
                    dependencies.put(dependency)
                }
            }
        }
        dependencies.put(parentDependency)
        manifest.put("dependencies", dependencies)
        manifestFile.writeText(manifest.toString(2), StandardCharsets.UTF_8)
        writeDescriptor(
            workspace.patchRoot.resolve(DESCRIPTOR_ENTRY),
            parentDependency,
            workspace.patchId,
            patchModId,
            cleanName,
            cleanVersion,
            cleanDescription,
        )

        val outputRoot = RuntimePaths.agentModsForModRoot(context, parentSegment)
        outputRoot.mkdirs()
        val outputJar = outputRoot.resolve("$patchModId.jar")
        val temporaryJar = File(outputRoot, ".${outputJar.name}.tmp")
        if (temporaryJar.exists()) temporaryJar.delete()
        zipDirectory(workspace.patchRoot, temporaryJar)
        verifyZip(temporaryJar)
        runCatching {
            Files.move(
                temporaryJar.toPath(),
                outputJar.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(
                temporaryJar.toPath(),
                outputJar.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        return AgentPatchModInfo(
            parentModId = ModManager.normalizeModId(parentDependency),
            patchId = workspace.patchId,
            patchModId = patchModId,
            name = cleanName,
            version = cleanVersion,
            description = cleanDescription,
            jarFile = outputJar,
            enabled = readEnabledPatchModIds(context).contains(patchModId),
        )
    }

    /**
     * Resolves the on-disk workspace of a patch revision by [patchId], whether or not it is
     * packaged and whether or not it was created in the current session.
     *
     * The returned [AgentPatchWorkspace.parentModId] keeps the parent's manifest casing: it comes
     * from [sourceJar] when readable, otherwise from the dependency recorded in the revision's own
     * `ModTheSpire.json`. ModTheSpire matches dependencies case-sensitively, so an update must not
     * rewrite the dependency with a lowercased id.
     */
    fun resolvePatchWorkspace(
        context: Context,
        parentModId: String,
        patchId: String,
        sourceJar: File? = null,
    ): AgentPatchWorkspace? {
        val parentSegment = safeSegmentOrNull(parentModId) ?: return null
        if (!patchId.matches(SAFE_PATH_SEGMENT)) return null
        val patchRoot = RuntimePaths.agentModPatchSourceRoot(context, parentSegment, patchId)
        if (!patchRoot.isDirectory) return null
        val resolvedParent = sourceJar?.takeIf { it.isFile }?.let { resolveParentModId(it, parentModId) }
            ?: readWorkspaceParentDependency(patchRoot, parentModId)
            ?: parentModId.trim()
        return AgentPatchWorkspace(
            parentModId = resolvedParent,
            parentModSegment = parentSegment,
            patchId = patchId,
            root = RuntimePaths.agentModWorkspaceRoot(context, parentSegment),
            sourceRoot = RuntimePaths.agentModSourceRoot(context, parentSegment),
            patchRoot = patchRoot,
        )
    }

    /**
     * Repackages an existing revision from its patch workspace with a new version, keeping the same
     * [patchId], patch mod id, and enabled state. Name and description default to the current values
     * when omitted. Sources must already be compiled.
     */
    @Throws(IOException::class)
    fun updatePatchMod(
        context: Context,
        parentModId: String,
        patchId: String,
        name: String = "",
        version: String = "",
        description: String = "",
        sourceJar: File? = null,
    ): AgentPatchModInfo {
        val existing = listPackaged(context, parentModId).firstOrNull { it.patchId == patchId }
            ?: throw IOException("AI patch not found: $patchId")
        val workspace = resolvePatchWorkspace(context, existing.parentModId, patchId, sourceJar)
            ?: throw IOException("The patch workspace for $patchId is missing.")
        requireCompiledPatchSources(workspace.patchRoot)
        return packagePatchMod(
            context = context,
            workspace = workspace,
            name = name.trim().ifBlank { existing.name },
            version = version.trim().ifBlank { existing.version },
            description = description.trim().ifBlank { existing.description },
        )
    }

    /** Rejects packaging a revision whose Java sources have not been compiled yet. */
    @Throws(IOException::class)
    private fun requireCompiledPatchSources(patchRoot: File) {
        val hasSources = AgentPatchSourceCompiler.collectSources(patchRoot).isNotEmpty()
        val hasClasses = patchRoot.walkTopDown()
            .any { it.isFile && it.extension.equals("class", ignoreCase = true) }
        if (hasSources && !hasClasses) {
            throw IOException("patch_sources_not_compiled: call compile_agent_patch_source first")
        }
    }

    /** Reads the parent dependency's original casing from the revision's own manifest. */
    private fun readWorkspaceParentDependency(patchRoot: File, fallback: String): String? {
        val manifestFile = patchRoot.resolve(MANIFEST_ENTRY)
        val normalizedFallback = ModManager.normalizeModId(fallback)
        return runCatching {
            if (!manifestFile.isFile) return@runCatching null
            val dependencies = JSONObject(manifestFile.readText(StandardCharsets.UTF_8))
                .optJSONArray("dependencies") ?: return@runCatching null
            (0 until dependencies.length())
                .map { dependencies.optString(it) }
                .firstOrNull { it.isNotBlank() && ModManager.normalizeModId(it) == normalizedFallback }
        }.getOrNull()
    }

    /** Enables or disables a packaged AI patch revision. */
    @Throws(IOException::class)
    fun setEnabled(context: Context, parentModId: String, patchId: String, enabled: Boolean) {
        val info = listPackaged(context, parentModId).firstOrNull { it.patchId == patchId }
            ?: throw IOException("AI patch not found: $patchId")
        if (enabled && !ModManager.listInstalledMods(context).any {
                ModManager.normalizeModId(it.modId) == info.parentModId && it.enabled
            }) {
            throw IOException("Enable the parent mod before enabling this AI patch.")
        }
        val enabledIds = readEnabledPatchModIds(context)
        val changed = if (enabled) {
            enabledIds.add(info.patchModId)
        } else {
            enabledIds.remove(info.patchModId)
        }
        if (changed) {
            writeEnabledPatchModIds(context, enabledIds)
        }
    }

    /** Deletes a packaged AI patch revision and clears its enablement. */
    @Throws(IOException::class)
    fun delete(context: Context, parentModId: String, patchId: String): AgentPatchModInfo {
        val info = listPackaged(context, parentModId).firstOrNull { it.patchId == patchId }
            ?: throw IOException("AI patch not found: $patchId")
        val enabledIds = readEnabledPatchModIds(context)
        if (enabledIds.remove(info.patchModId)) {
            writeEnabledPatchModIds(context, enabledIds)
        }
        if (!info.jarFile.delete() && info.jarFile.exists()) {
            throw IOException("Failed to delete the AI patch: $patchId")
        }
        return info.copy(enabled = false)
    }

    fun listPackaged(context: Context, parentModId: String): List<AgentPatchModInfo> {
        val normalizedParent = ModManager.normalizeModId(parentModId)
        val parentSegment = safeSegmentOrNull(normalizedParent) ?: return emptyList()
        val packagedRoot = RuntimePaths.agentModsForModRoot(context, parentSegment)
        val enabledIds = readEnabledPatchModIds(context)
        return packagedRoot.listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .mapNotNull { jar ->
                readDescriptor(jar)?.takeIf { it.parentModId == normalizedParent }
            }
            .map { descriptor ->
                descriptor.copy(enabled = descriptor.patchModId in enabledIds)
            }
            .toList()
    }

    /** All enabled patch revisions whose parent mod is part of the launch list. */
    fun listEnabledForLaunch(
        context: Context,
        enabledParentModIds: Set<String>,
    ): List<AgentPatchModInfo> {
        if (enabledParentModIds.isEmpty()) {
            return emptyList()
        }
        val normalizedParents = enabledParentModIds
            .asSequence()
            .map { ModManager.normalizeModId(it) }
            .filter { it.isNotEmpty() }
            .toHashSet()
        if (normalizedParents.isEmpty()) {
            return emptyList()
        }
        val enabledIds = readEnabledPatchModIds(context)
        if (enabledIds.isEmpty()) {
            return emptyList()
        }
        val result = ArrayList<AgentPatchModInfo>()
        RuntimePaths.agentModsRoot(context).listFiles().orEmpty()
            .asSequence()
            .filter { it.isDirectory }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .forEach { parentDir ->
                parentDir.listFiles().orEmpty()
                    .asSequence()
                    .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
                    .sortedBy { it.name.lowercase(Locale.ROOT) }
                    .forEach { jar ->
                        val descriptor = readDescriptor(jar) ?: return@forEach
                        if (descriptor.patchModId !in enabledIds) return@forEach
                        if (descriptor.parentModId !in normalizedParents) return@forEach
                        result.add(descriptor.copy(enabled = true))
                    }
            }
        return result
    }

    /**
     * Moves AI patch revisions that older launcher versions installed into the optional-mod
     * library over to `agent_mods/`, preserving their enabled state.
     *
     * Returns `true` when at least one library copy was migrated, so callers holding a stale
     * installed-mod list can refresh it.
     */
    @Throws(IOException::class)
    fun migrateLegacyLibraryPatches(
        context: Context,
        installedMods: List<ModManager.InstalledMod>,
    ): Boolean {
        var migrated = false
        val enabledIds = readEnabledPatchModIds(context)
        var enabledChanged = false
        installedMods.forEach { mod ->
            if (mod.required) return@forEach
            val descriptor = readDescriptor(mod.jarFile) ?: return@forEach
            val parentSegment = safeSegmentOrNull(descriptor.parentModId) ?: return@forEach
            val targetDir = RuntimePaths.agentModsForModRoot(context, parentSegment)
            targetDir.mkdirs()
            val target = targetDir.resolve("${descriptor.patchModId}.jar")
            if (!target.isFile) {
                val copied = runCatching {
                    Files.copy(
                        mod.jarFile.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }.isSuccess
                if (!copied) return@forEach
            }
            if (mod.enabled && enabledIds.add(descriptor.patchModId)) {
                enabledChanged = true
            }
            val deleted = runCatching {
                ModManager.deleteOptionalModByStoragePath(context, mod.jarFile.absolutePath)
            }.getOrDefault(false)
            if (deleted) {
                migrated = true
            }
        }
        if (enabledChanged) {
            writeEnabledPatchModIds(context, enabledIds)
        }
        return migrated
    }

    fun readDescriptor(jarFile: File): AgentPatchModInfo? = runCatching {
        ZipFile(jarFile).use { zip ->
            val entry = zip.getEntry(DESCRIPTOR_ENTRY) ?: return null
            val descriptor = JSONObject(zip.getInputStream(entry).use { it.readBytes() }.toString(StandardCharsets.UTF_8))
            AgentPatchModInfo(
                parentModId = ModManager.normalizeModId(descriptor.getString("parent_mod_id")),
                patchId = descriptor.getString("patch_id"),
                patchModId = ModManager.normalizeModId(descriptor.getString("patch_mod_id")),
                name = descriptor.optString("name"),
                version = descriptor.optString("version"),
                description = descriptor.optString("description"),
                jarFile = jarFile,
                enabled = false,
            )
        }
    }.getOrNull()

    private fun readEnabledPatchModIds(context: Context): MutableSet<String> {
        val file = RuntimePaths.enabledAgentPatchModsConfig(context)
        if (!file.isFile) {
            return LinkedHashSet()
        }
        return runCatching {
            file.readLines(StandardCharsets.UTF_8)
                .asSequence()
                .map { ModManager.normalizeModId(it) }
                .filter { it.isNotEmpty() }
                .toCollection(LinkedHashSet())
        }.getOrElse { LinkedHashSet() }
    }

    private fun writeEnabledPatchModIds(context: Context, patchModIds: Set<String>) {
        val file = RuntimePaths.enabledAgentPatchModsConfig(context)
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        val content = patchModIds
            .asSequence()
            .filter { it.isNotEmpty() }
            .sorted()
            .joinToString(separator = "\n", postfix = "\n")
        file.writeText(content, StandardCharsets.UTF_8)
    }

    private fun writeDescriptor(
        file: File,
        parentModId: String,
        patchId: String,
        patchModId: String,
        name: String,
        version: String,
        description: String,
    ) {
        file.writeText(
            JSONObject()
                .put("parent_mod_id", parentModId)
                .put("patch_id", patchId)
                .put("patch_mod_id", patchModId)
                .put("name", name)
                .put("version", version)
                .put("description", description)
                .toString(2),
            StandardCharsets.UTF_8,
        )
    }

    private fun zipDirectory(source: File, output: File) {
        ZipOutputStream(output.outputStream()).use { zip ->
            val files = source.walkTopDown()
                .filter { it.isFile && !isSourceEntry(it, source) }
                .toList()
            if (files.size > MAX_FILES) throw IOException("The patch mod contains too many files.")
            var total = 0L
            files.forEach { file ->
                val name = normalizeEntry(file.relativeTo(source).invariantSeparatorsPath)
                if (file.length() > MAX_ENTRY_BYTES) throw IOException("Patch entry is too large: $name")
                total += file.length()
                if (total > MAX_JAR_BYTES) throw IOException("The patch mod is too large.")
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Java sources under the patch workspace's `src/` are build input; only compiled classes ship. */
    private fun isSourceEntry(file: File, source: File): Boolean {
        val relative = file.relativeTo(source).invariantSeparatorsPath
        val sourceDir = AgentPatchSourceCompiler.PATCH_SOURCE_DIR
        return relative == sourceDir || relative.startsWith("$sourceDir/")
    }

    private fun verifyZip(file: File) {
        ZipFile(file).use { zip ->
            require(zip.getEntry(MANIFEST_ENTRY) != null) { "Patch mod is missing ModTheSpire.json." }
            require(zip.getEntry(DESCRIPTOR_ENTRY) != null) { "Patch mod is missing agent-patch.json." }
        }
    }

    private fun normalizeEntry(raw: String): String {
        val value = raw.replace('\\', '/').trim()
        require(value.isNotEmpty() && !value.startsWith('/') && !value.contains(':')) { "Invalid archive entry." }
        val parts = value.split('/')
        require(parts.none { it.isEmpty() || it == "." || it == ".." }) { "Invalid archive entry: $raw" }
        return parts.joinToString("/")
    }

    private fun safeSegment(raw: String): String {
        val normalized = ModManager.normalizeModId(raw)
        require(normalized.matches(Regex("[a-z0-9._-]+"))) { "Invalid mod identifier." }
        return normalized
    }

    private fun safeSegmentOrNull(raw: String): String? {
        val normalized = ModManager.normalizeModId(raw)
        return normalized.takeIf { it.matches(Regex("[a-z0-9._-]+")) }
    }

    /**
     * Resolves the parent mod's exact `modid` from the source JAR manifest.
     *
     * ModTheSpire matches dependency ids case-sensitively (`String.equals`), so the injected
     * dependency must use the manifest's original casing. Falling back to the caller value keeps
     * the workspace usable for source JARs whose manifest cannot be parsed.
     */
    private fun resolveParentModId(sourceJar: File, fallback: String): String {
        val manifestModId = runCatching {
            ModJarManifestParser.readModManifest(sourceJar).modId.trim()
        }.getOrNull().orEmpty()
        val resolved = manifestModId.ifEmpty { fallback.trim() }
        require(resolved.isNotEmpty()) { "Invalid mod identifier." }
        return resolved
    }

    private fun patchModId(parentModId: String, patchId: String): String =
        "amethyst.ai.patch.${safeSegment(parentModId)}.${safeSegment(patchId)}"

}
