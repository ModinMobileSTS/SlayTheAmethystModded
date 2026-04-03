package io.stamethyst.backend.mods

import android.content.Context
import io.stamethyst.config.RuntimePaths
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale

internal object OptionalModMetadataIndex {
    internal data class OptionalModMetadata(
        val storagePath: String,
        val jarFile: File,
        val rawModId: String,
        val normalizedModId: String,
        val name: String,
        val version: String,
        val description: String,
        val dependencies: List<String>,
        val launchModId: String,
        val launchValidationError: String
    )

    private data class CachedEntry(
        val storagePath: String,
        val lastModified: Long,
        val length: Long,
        val displayable: Boolean,
        val rawModId: String,
        val normalizedModId: String,
        val name: String,
        val version: String,
        val description: String,
        val dependencies: List<String>,
        val launchModId: String,
        val launchValidationError: String
    ) {
        fun matches(file: File): Boolean {
            return file.absolutePath == storagePath &&
                file.lastModified() == lastModified &&
                file.length() == length
        }

        fun toRuntimeEntry(): OptionalModMetadata? {
            if (!displayable) {
                return null
            }
            if (storagePath.isBlank() || normalizedModId.isBlank()) {
                return null
            }
            return OptionalModMetadata(
                storagePath = storagePath,
                jarFile = File(storagePath),
                rawModId = rawModId,
                normalizedModId = normalizedModId,
                name = name,
                version = version,
                description = description,
                dependencies = ArrayList(dependencies),
                launchModId = launchModId,
                launchValidationError = launchValidationError
            )
        }

        fun toJson(): JSONObject {
            return JSONObject().apply {
                put(JSON_KEY_STORAGE_PATH, storagePath)
                put(JSON_KEY_LAST_MODIFIED, lastModified)
                put(JSON_KEY_LENGTH, length)
                put(JSON_KEY_DISPLAYABLE, displayable)
                put(JSON_KEY_RAW_MOD_ID, rawModId)
                put(JSON_KEY_NORMALIZED_MOD_ID, normalizedModId)
                put(JSON_KEY_NAME, name)
                put(JSON_KEY_VERSION, version)
                put(JSON_KEY_DESCRIPTION, description)
                put(JSON_KEY_DEPENDENCIES, JSONArray().apply {
                    dependencies.forEach(::put)
                })
                put(JSON_KEY_LAUNCH_MOD_ID, launchModId)
                put(JSON_KEY_LAUNCH_VALIDATION_ERROR, launchValidationError)
            }
        }
    }

    private data class ReadState(
        val entriesByPath: MutableMap<String, CachedEntry>,
        val needsRewrite: Boolean
    )

    private const val SCHEMA_VERSION = 1
    private const val JSON_KEY_SCHEMA_VERSION = "schemaVersion"
    private const val JSON_KEY_ENTRIES = "entries"
    private const val JSON_KEY_STORAGE_PATH = "storagePath"
    private const val JSON_KEY_LAST_MODIFIED = "lastModified"
    private const val JSON_KEY_LENGTH = "length"
    private const val JSON_KEY_DISPLAYABLE = "displayable"
    private const val JSON_KEY_RAW_MOD_ID = "rawModId"
    private const val JSON_KEY_NORMALIZED_MOD_ID = "normalizedModId"
    private const val JSON_KEY_NAME = "name"
    private const val JSON_KEY_VERSION = "version"
    private const val JSON_KEY_DESCRIPTION = "description"
    private const val JSON_KEY_DEPENDENCIES = "dependencies"
    private const val JSON_KEY_LAUNCH_MOD_ID = "launchModId"
    private const val JSON_KEY_LAUNCH_VALIDATION_ERROR = "launchValidationError"

    private val lock = Any()

    fun listOptionalMods(context: Context): List<OptionalModMetadata> {
        synchronized(lock) {
            OptionalModStorageCoordinator.ensureOptionalModLibraryReady(context)
            val readState = readEntries(context)
            val staleEntries = readState.entriesByPath
            val refreshedEntries = ArrayList<CachedEntry>()
            val result = ArrayList<OptionalModMetadata>()
            var changed = readState.needsRewrite

            listCurrentOptionalJarFiles(context).forEach { file ->
                val storagePath = normalizeStoragePath(context, file.absolutePath) ?: file.absolutePath
                val cached = staleEntries.remove(storagePath)
                val entry = if (cached != null && cached.matches(file)) {
                    cached
                } else {
                    changed = true
                    buildCachedEntry(file, storagePath)
                }
                refreshedEntries.add(entry)
                entry.toRuntimeEntry()?.let(result::add)
            }

            if (staleEntries.isNotEmpty()) {
                changed = true
            }
            if (changed) {
                writeEntries(context, refreshedEntries)
            }
            return result
        }
    }

    private fun buildCachedEntry(file: File, storagePath: String): CachedEntry {
        val lastModified = file.lastModified()
        val length = file.length()
        val manifest = try {
            ModJarSupport.readModManifest(file)
        } catch (_: Throwable) {
            return buildSkippedEntry(storagePath, lastModified, length)
        }

        val rawModId = manifest.modId.trim()
        val normalizedModId = ModManager.normalizeModId(rawModId)
        if (normalizedModId.isEmpty() || ModManager.isRequiredModId(normalizedModId)) {
            return buildSkippedEntry(storagePath, lastModified, length)
        }

        var launchModId = ""
        var launchValidationError = ""
        try {
            launchModId = MtsLaunchManifestValidator.resolveLaunchModId(file).trim()
        } catch (failure: Throwable) {
            launchValidationError = failure.message?.trim().orEmpty()
        }

        return CachedEntry(
            storagePath = storagePath,
            lastModified = lastModified,
            length = length,
            displayable = true,
            rawModId = rawModId,
            normalizedModId = normalizedModId,
            name = manifest.name.trim().ifBlank { rawModId.ifBlank { normalizedModId } },
            version = manifest.version.trim(),
            description = manifest.description.trim(),
            dependencies = ArrayList(
                manifest.dependencies
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
            ),
            launchModId = launchModId,
            launchValidationError = launchValidationError
        )
    }

    private fun buildSkippedEntry(
        storagePath: String,
        lastModified: Long,
        length: Long
    ): CachedEntry {
        return CachedEntry(
            storagePath = storagePath,
            lastModified = lastModified,
            length = length,
            displayable = false,
            rawModId = "",
            normalizedModId = "",
            name = "",
            version = "",
            description = "",
            dependencies = emptyList(),
            launchModId = "",
            launchValidationError = ""
        )
    }

    private fun readEntries(context: Context): ReadState {
        val file = RuntimePaths.optionalModIndexFile(context)
        if (!file.isFile) {
            return ReadState(
                entriesByPath = LinkedHashMap(),
                needsRewrite = false
            )
        }

        return try {
            val root = JSONTokener(file.readText(StandardCharsets.UTF_8)).nextValue() as? JSONObject
                ?: return ReadState(LinkedHashMap(), true)
            if (root.optInt(JSON_KEY_SCHEMA_VERSION, -1) != SCHEMA_VERSION) {
                return ReadState(LinkedHashMap(), true)
            }
            val array = root.optJSONArray(JSON_KEY_ENTRIES)
                ?: return ReadState(LinkedHashMap(), true)
            val entriesByPath = LinkedHashMap<String, CachedEntry>()
            var needsRewrite = false
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                if (item == null) {
                    needsRewrite = true
                    continue
                }
                val rawStoragePath = item.optString(JSON_KEY_STORAGE_PATH).trim()
                val normalizedStoragePath = normalizeStoragePath(context, rawStoragePath)
                if (normalizedStoragePath.isNullOrEmpty()) {
                    needsRewrite = true
                    continue
                }
                if (normalizedStoragePath != rawStoragePath) {
                    needsRewrite = true
                }
                val cached = readCachedEntry(item, normalizedStoragePath)
                if (cached == null) {
                    needsRewrite = true
                    continue
                }
                entriesByPath[normalizedStoragePath] = cached
            }
            ReadState(
                entriesByPath = entriesByPath,
                needsRewrite = needsRewrite
            )
        } catch (_: Throwable) {
            ReadState(
                entriesByPath = LinkedHashMap(),
                needsRewrite = true
            )
        }
    }

    private fun readCachedEntry(
        source: JSONObject,
        storagePath: String
    ): CachedEntry? {
        val lastModified = source.optLong(JSON_KEY_LAST_MODIFIED, -1L)
        val length = source.optLong(JSON_KEY_LENGTH, -1L)
        if (lastModified < 0L || length < 0L) {
            return null
        }

        val rawModId = source.optString(JSON_KEY_RAW_MOD_ID).trim()
        val normalizedModId = source.optString(JSON_KEY_NORMALIZED_MOD_ID).trim()
        val displayable = source.optBoolean(JSON_KEY_DISPLAYABLE, false)
        val dependencies = LinkedHashSet<String>()
        val dependencyArray = source.optJSONArray(JSON_KEY_DEPENDENCIES)
        if (dependencyArray != null) {
            for (index in 0 until dependencyArray.length()) {
                val dependency = dependencyArray.optString(index).trim()
                if (dependency.isNotEmpty()) {
                    dependencies.add(dependency)
                }
            }
        }

        return CachedEntry(
            storagePath = storagePath,
            lastModified = lastModified,
            length = length,
            displayable = displayable && normalizedModId.isNotEmpty(),
            rawModId = rawModId,
            normalizedModId = normalizedModId,
            name = source.optString(JSON_KEY_NAME).trim(),
            version = source.optString(JSON_KEY_VERSION).trim(),
            description = source.optString(JSON_KEY_DESCRIPTION).trim(),
            dependencies = ArrayList(dependencies),
            launchModId = source.optString(JSON_KEY_LAUNCH_MOD_ID).trim(),
            launchValidationError = source.optString(JSON_KEY_LAUNCH_VALIDATION_ERROR).trim()
        )
    }

    private fun writeEntries(
        context: Context,
        entries: List<CachedEntry>
    ) {
        runCatching {
            val file = RuntimePaths.optionalModIndexFile(context)
            val parent = file.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return@runCatching
            }
            val root = JSONObject().apply {
                put(JSON_KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                put(
                    JSON_KEY_ENTRIES,
                    JSONArray().apply {
                        entries.forEach { put(it.toJson()) }
                    }
                )
            }
            file.writeText(root.toString(), StandardCharsets.UTF_8)
        }
    }

    private fun listCurrentOptionalJarFiles(context: Context): List<File> {
        val libraryDir = RuntimePaths.optionalModsLibraryDir(context)
        val files = libraryDir.listFiles() ?: return emptyList()
        return files
            .asSequence()
            .filter { it.isFile }
            .filter { it.name.lowercase(Locale.ROOT).endsWith(".jar") }
            .filterNot { isReservedJarName(it.name) }
            .sortedWith(compareBy<File>({ it.name.lowercase(Locale.ROOT) }, { it.name }, { it.absolutePath }))
            .toList()
    }

    private fun normalizeStoragePath(context: Context, rawPath: String?): String? {
        return RuntimePaths.normalizeLegacyStsPath(context, rawPath)
            ?.trim()
            ?.ifEmpty { null }
    }

    private fun isReservedJarName(fileName: String): Boolean {
        val normalized = fileName.lowercase(Locale.ROOT)
        return normalized == "basemod.jar" ||
            normalized == "stslib.jar" ||
            normalized == "amethystruntimecompat.jar"
    }
}
