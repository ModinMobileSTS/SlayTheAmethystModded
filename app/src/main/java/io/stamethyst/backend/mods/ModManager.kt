package io.stamethyst.backend.mods

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.HashSet
import java.util.LinkedHashSet
import java.util.Locale

object ModManager {
    const val MOD_ID_BASEMOD = "basemod"
    const val MOD_ID_STSLIB = "stslib"
    private val REQUIRED_MOD_IDS: Set<String> = HashSet(
        listOf(
            MOD_ID_BASEMOD,
            MOD_ID_STSLIB
        )
    )

    class InstalledMod(
        @JvmField val modId: String,
        @JvmField val manifestModId: String,
        @JvmField val name: String,
        @JvmField val version: String,
        @JvmField val description: String,
        dependencies: List<String>?,
        @JvmField val jarFile: File,
        @JvmField val required: Boolean,
        @JvmField val installed: Boolean,
        @JvmField val enabled: Boolean
    ) {
        @JvmField
        val dependencies: List<String> = dependencies ?: ArrayList()
    }

    private data class OptionalModFileEntry(
        val storageKey: String,
        val jarFile: File,
        val normalizedModId: String,
        val rawModId: String
    )

    @JvmStatic
    fun normalizeModId(modId: String?): String {
        if (modId == null) {
            return ""
        }
        val normalized = modId.trim().lowercase(Locale.ROOT)
        return normalized.ifEmpty { "" }
    }

    @JvmStatic
    fun isRequiredModId(modId: String): Boolean {
        return REQUIRED_MOD_IDS.contains(normalizeModId(modId))
    }

    @JvmStatic
    fun hasBundledRequiredModAsset(context: Context, modId: String): Boolean {
        val normalized = normalizeModId(modId)
        if (MOD_ID_BASEMOD == normalized) {
            return hasBundledAsset(context, "components/mods/BaseMod.jar")
        }
        if (MOD_ID_STSLIB == normalized) {
            return hasBundledAsset(context, "components/mods/StSLib.jar")
        }
        return false
    }

    @JvmStatic
    fun resolveStorageFileForModId(context: Context, modId: String): File {
        val normalized = normalizeModId(modId)
        if (MOD_ID_BASEMOD == normalized) {
            return RuntimePaths.importedBaseModJar(context)
        }
        if (MOD_ID_STSLIB == normalized) {
            return RuntimePaths.importedStsLibJar(context)
        }
        return File(RuntimePaths.modsDir(context), "${sanitizeFileName(normalized)}.jar")
    }

    @JvmStatic
    fun resolveStorageFileForImportedMod(context: Context, requestedFileName: String?): File {
        val modsDir = RuntimePaths.modsDir(context)
        val preferredName = sanitizeImportedJarFileName(requestedFileName)
        return buildUniqueImportTarget(modsDir, preferredName)
    }

    @JvmStatic
    fun listEnabledOptionalModIds(context: Context): Set<String> {
        val optionalModFiles = findOptionalModFiles(context)
        if (optionalModFiles.isEmpty()) {
            return emptySet()
        }

        val rawSelection = readEnabledOptionalModKeysSafely(context)
        val normalizedSelection = normalizeEnabledOptionalSelection(context, rawSelection, optionalModFiles)
        maybePersistSelectionNormalization(context, rawSelection, normalizedSelection)

        val result: MutableSet<String> = LinkedHashSet()
        optionalModFiles.forEach { entry ->
            if (normalizedSelection.contains(entry.storageKey)) {
                result.add(entry.normalizedModId)
            }
        }
        return result
    }

    @JvmStatic
    @Throws(IOException::class)
    fun setOptionalModEnabled(context: Context, modKeyOrId: String, enabled: Boolean) {
        val optionalModFiles = findOptionalModFiles(context)
        if (optionalModFiles.isEmpty()) {
            return
        }
        val targetKey = resolveSelectionKey(context, modKeyOrId, optionalModFiles) ?: return

        val rawSelection = readEnabledOptionalModKeys(context)
        val normalizedSelection = normalizeEnabledOptionalSelection(context, rawSelection, optionalModFiles)
        val changed = if (enabled) {
            normalizedSelection.add(targetKey)
        } else {
            normalizedSelection.remove(targetKey)
        }
        if (changed || rawSelection != normalizedSelection) {
            writeEnabledOptionalModKeys(context, normalizedSelection)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun replaceEnabledOptionalModIds(context: Context, modKeysOrIds: Collection<String>) {
        val optionalModFiles = findOptionalModFiles(context)
        val selected = normalizeEnabledOptionalSelection(context, modKeysOrIds, optionalModFiles)
        writeEnabledOptionalModKeys(context, selected)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun deleteOptionalMod(context: Context, modKeyOrId: String): Boolean {
        val optionalModFiles = findOptionalModFiles(context)
        val targetKey = resolveSelectionKey(context, modKeyOrId, optionalModFiles) ?: return false
        return deleteOptionalModByStoragePath(context, targetKey)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun deleteOptionalModByStoragePath(context: Context, storagePath: String): Boolean {
        val normalizedPath = normalizeSelectionToken(context, storagePath)
        if (!looksLikePathToken(normalizedPath)) {
            return false
        }
        val target = File(normalizedPath)
        if (!target.isFile || isReservedJarName(target.name)) {
            return false
        }
        val expectedDirPath = RuntimePaths.modsDir(context).absolutePath
        if (target.parentFile?.absolutePath != expectedDirPath) {
            return false
        }

        val optionalModFiles = findOptionalModFiles(context)
        val storageKey = resolveOptionalStorageKey(target)

        val rawSelection = readEnabledOptionalModKeysSafely(context)
        val normalizedSelection = normalizeEnabledOptionalSelection(context, rawSelection, optionalModFiles)
        val removedFromSelection = normalizedSelection.remove(storageKey)
        if (removedFromSelection || rawSelection != normalizedSelection) {
            writeEnabledOptionalModKeys(context, normalizedSelection)
        }

        if (!target.delete()) {
            throw IOException("Failed to delete mod file: ${target.absolutePath}")
        }
        return true
    }

    @JvmStatic
    fun listInstalledMods(context: Context): List<InstalledMod> {
        val result = ArrayList<InstalledMod>()
        result.add(
            buildRequiredEntry(
                context,
                MOD_ID_BASEMOD,
                "BaseMod",
                RuntimePaths.importedBaseModJar(context)
            )
        )
        result.add(
            buildRequiredEntry(
                context,
                MOD_ID_STSLIB,
                "StSLib",
                RuntimePaths.importedStsLibJar(context)
            )
        )

        val optionalModFiles = findOptionalModFiles(context)
        val rawSelection = readEnabledOptionalModKeysSafely(context)
        val enabledSelection = normalizeEnabledOptionalSelection(context, rawSelection, optionalModFiles)
        maybePersistSelectionNormalization(context, rawSelection, enabledSelection)

        for (entry in optionalModFiles) {
            val jarFile = entry.jarFile
            val modId = entry.normalizedModId
            var manifestModId = entry.rawModId
            var name = modId
            var version = ""
            var description = ""
            var dependencies: List<String> = ArrayList()
            try {
                val manifest = ModJarSupport.readModManifest(jarFile)
                manifestModId = defaultIfBlank(manifest.modId, manifestModId)
                name = defaultIfBlank(manifest.name, manifestModId)
                version = trimToEmpty(manifest.version)
                description = trimToEmpty(manifest.description)
                dependencies = ArrayList(manifest.dependencies)
            } catch (_: Throwable) {
                name = modId
            }
            result.add(
                InstalledMod(
                    modId,
                    manifestModId,
                    name,
                    version,
                    description,
                    dependencies,
                    jarFile,
                    false,
                    true,
                    enabledSelection.contains(entry.storageKey)
                )
            )
        }
        return result
    }

    @JvmStatic
    @Throws(IOException::class)
    fun resolveLaunchModIds(context: Context): List<String> {
        val baseModId = resolveRequiredLaunchModId(
            RuntimePaths.importedBaseModJar(context),
            MOD_ID_BASEMOD,
            "BaseMod.jar"
        )
        val stsLibId = resolveRequiredLaunchModId(
            RuntimePaths.importedStsLibJar(context),
            MOD_ID_STSLIB,
            "StSLib.jar"
        )

        val optionalModFiles = findOptionalModFiles(context)
        val rawSelection = readEnabledOptionalModKeys(context)
        val enabledSelection = normalizeEnabledOptionalSelection(context, rawSelection, optionalModFiles)
        maybePersistSelectionNormalization(context, rawSelection, enabledSelection)

        val launchModIds = ArrayList<String>()
        launchModIds.add(baseModId)
        launchModIds.add(stsLibId)

        for (entry in optionalModFiles) {
            if (!enabledSelection.contains(entry.storageKey)) {
                continue
            }
            val rawModId = entry.rawModId.trim()
            if (rawModId.isNotEmpty()) {
                launchModIds.add(rawModId)
            }
        }
        return launchModIds
    }

    private fun buildRequiredEntry(
        context: Context,
        expectedModId: String,
        label: String,
        jarFile: File
    ): InstalledMod {
        var installed = false
        var manifestModId = expectedModId
        var name = label
        var version = ""
        var description = ""
        var dependencies: List<String> = ArrayList()
        if (jarFile.isFile) {
            try {
                val manifest = ModJarSupport.readModManifest(jarFile)
                installed = expectedModId == manifest.normalizedModId
                if (installed) {
                    manifestModId = defaultIfBlank(manifest.modId, expectedModId)
                    name = defaultIfBlank(manifest.name, label)
                    version = trimToEmpty(manifest.version)
                    description = trimToEmpty(manifest.description)
                    dependencies = ArrayList(manifest.dependencies)
                }
            } catch (_: Throwable) {
                installed = false
            }
        }
        val bundled = hasBundledRequiredModAsset(context, expectedModId)
        val available = installed || bundled
        return InstalledMod(
            expectedModId,
            manifestModId,
            name,
            version,
            description,
            dependencies,
            jarFile,
            true,
            available,
            available
        )
    }

    private fun hasBundledAsset(context: Context, assetPath: String): Boolean {
        return try {
            context.assets.open(assetPath).use { _: InputStream -> }
            true
        } catch (_: Throwable) {
            false
        }
    }

    @Throws(IOException::class)
    private fun resolveRequiredLaunchModId(
        jarFile: File,
        expectedModId: String,
        label: String
    ): String {
        if (!jarFile.isFile) {
            throw IOException("$label not found")
        }
        val raw = resolveRawModId(jarFile)
        val normalized = normalizeModId(raw)
        if (expectedModId != normalized) {
            throw IOException("$label has unexpected modid: $raw")
        }
        return raw
    }

    private fun findOptionalModFiles(context: Context): List<OptionalModFileEntry> {
        val result = ArrayList<OptionalModFileEntry>()
        val modsDir = RuntimePaths.modsDir(context)
        val files = modsDir.listFiles() ?: return result
        val sortedFiles = files
            .asSequence()
            .filter { it.isFile }
            .filter { it.name.lowercase(Locale.ROOT).endsWith(".jar") }
            .filterNot { isReservedJarName(it.name) }
            .sortedWith(compareBy<File>({ it.name.lowercase(Locale.ROOT) }, { it.name }, { it.absolutePath }))
            .toList()

        sortedFiles.forEach { file ->
            val rawModId = try {
                resolveRawModId(file)
            } catch (_: Throwable) {
                return@forEach
            }
            val normalizedModId = normalizeModId(rawModId)
            if (normalizedModId.isEmpty() || isRequiredModId(normalizedModId)) {
                return@forEach
            }
            result.add(
                OptionalModFileEntry(
                    storageKey = resolveOptionalStorageKey(file),
                    jarFile = file,
                    normalizedModId = normalizedModId,
                    rawModId = rawModId.trim()
                )
            )
        }
        return result
    }

    private fun isReservedJarName(fileName: String): Boolean {
        val normalized = fileName.lowercase(Locale.ROOT)
        return "basemod.jar" == normalized || "stslib.jar" == normalized
    }

    private fun readEnabledOptionalModKeysSafely(context: Context): MutableSet<String> {
        return try {
            readEnabledOptionalModKeys(context)
        } catch (_: Throwable) {
            LinkedHashSet()
        }
    }

    @Throws(IOException::class)
    private fun readEnabledOptionalModKeys(context: Context): MutableSet<String> {
        val keys: MutableSet<String> = LinkedHashSet()
        val config = RuntimePaths.enabledModsConfig(context)
        if (!config.isFile) {
            return keys
        }
        FileInputStream(config).use { input ->
            InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                BufferedReader(reader).use { buffered ->
                    while (true) {
                        val line = buffered.readLine() ?: break
                        val token = normalizeSelectionToken(context, line)
                        if (token.isNotEmpty()) {
                            keys.add(token)
                        }
                    }
                }
            }
        }
        return keys
    }

    @Throws(IOException::class)
    private fun writeEnabledOptionalModKeys(context: Context, modKeys: Set<String>) {
        val config = RuntimePaths.enabledModsConfig(context)
        val parent = config.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        FileOutputStream(config, false).use { output ->
            OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
                BufferedWriter(writer).use { buffered ->
                    for (modKey in modKeys) {
                        val token = normalizeSelectionToken(context, modKey)
                        if (token.isEmpty()) {
                            continue
                        }
                        if (!looksLikePathToken(token) && isRequiredModId(token)) {
                            continue
                        }
                        buffered.write(token)
                        buffered.newLine()
                    }
                }
            }
        }
    }

    private fun maybePersistSelectionNormalization(
        context: Context,
        rawSelection: Set<String>,
        normalizedSelection: Set<String>
    ) {
        if (rawSelection == normalizedSelection) {
            return
        }
        try {
            writeEnabledOptionalModKeys(context, normalizedSelection)
        } catch (_: Throwable) {
        }
    }

    private fun sanitizeFileName(value: String?): String {
        if (value.isNullOrEmpty()) {
            return "mod"
        }
        val sanitized = StringBuilder(value.length)
        for (i in value.indices) {
            val ch = value[i]
            if ((ch in 'a'..'z') ||
                (ch in 'A'..'Z') ||
                (ch in '0'..'9') ||
                ch == '.' ||
                ch == '_' ||
                ch == '-'
            ) {
                sanitized.append(ch)
            } else {
                sanitized.append('_')
            }
        }
        if (sanitized.isEmpty()) {
            return "mod"
        }
        return sanitized.toString()
    }

    private fun trimToEmpty(value: String?): String {
        return value?.trim() ?: ""
    }

    private fun defaultIfBlank(value: String?, fallback: String?): String {
        val trimmed = trimToEmpty(value)
        if (trimmed.isNotEmpty()) {
            return trimmed
        }
        return trimToEmpty(fallback)
    }

    @Throws(IOException::class)
    private fun resolveRawModId(jarFile: File): String {
        val raw = ModJarSupport.resolveModId(jarFile)
        return raw.trim()
    }

    private fun resolveSelectionKey(
        context: Context,
        modKeyOrId: String,
        optionalModFiles: List<OptionalModFileEntry>
    ): String? {
        val normalizedInput = normalizeSelectionToken(context, modKeyOrId)
        if (normalizedInput.isEmpty()) {
            return null
        }
        optionalModFiles.forEach { entry ->
            if (entry.storageKey == normalizedInput) {
                return entry.storageKey
            }
        }
        val normalizedModId = normalizeModId(normalizedInput)
        if (normalizedModId.isEmpty() || isRequiredModId(normalizedModId)) {
            return null
        }
        optionalModFiles.forEach { entry ->
            if (entry.normalizedModId == normalizedModId) {
                return entry.storageKey
            }
        }
        return null
    }

    private fun normalizeEnabledOptionalSelection(
        context: Context,
        selection: Collection<String>,
        optionalModFiles: List<OptionalModFileEntry>
    ): MutableSet<String> {
        val normalized: MutableSet<String> = LinkedHashSet()
        if (selection.isEmpty() || optionalModFiles.isEmpty()) {
            return normalized
        }

        val keyMap: MutableMap<String, OptionalModFileEntry> = HashMap()
        val modIdMap: MutableMap<String, MutableList<OptionalModFileEntry>> = HashMap()
        optionalModFiles.forEach { entry ->
            keyMap[entry.storageKey] = entry
            val list = modIdMap.getOrPut(entry.normalizedModId) { ArrayList() }
            list.add(entry)
        }

        selection.forEach { raw ->
            val token = normalizeSelectionToken(context, raw)
            if (token.isEmpty()) {
                return@forEach
            }
            val direct = keyMap[token]
            if (direct != null) {
                normalized.add(direct.storageKey)
            }
        }

        selection.forEach { raw ->
            val token = normalizeSelectionToken(context, raw)
            if (token.isEmpty() || looksLikePathToken(token)) {
                return@forEach
            }
            val normalizedModId = normalizeModId(token)
            if (normalizedModId.isEmpty() || isRequiredModId(normalizedModId)) {
                return@forEach
            }
            val candidates = modIdMap[normalizedModId] ?: return@forEach
            val next = candidates.firstOrNull { !normalized.contains(it.storageKey) }
                ?: candidates.firstOrNull()
            if (next != null) {
                normalized.add(next.storageKey)
            }
        }

        return normalized
    }

    private fun normalizeSelectionToken(context: Context, raw: String?): String {
        val trimmed = raw?.trim() ?: ""
        if (trimmed.isEmpty()) {
            return ""
        }
        return if (looksLikePathToken(trimmed)) {
            RuntimePaths.normalizeLegacyInternalStsPath(context = context, rawPath = trimmed)
                ?: ""
        } else {
            normalizeModId(trimmed)
        }
    }

    private fun looksLikePathToken(token: String): Boolean {
        return token.contains('/') || token.contains('\\')
    }

    private fun resolveOptionalStorageKey(file: File): String {
        return file.absolutePath
    }

    private fun sanitizeImportedJarFileName(requestedFileName: String?): String {
        val raw = requestedFileName?.trim().orEmpty()
        val leafName = if (raw.isEmpty()) {
            "mod.jar"
        } else {
            File(raw).name
        }
        var sanitized = leafName
            .replace('/', '_')
            .replace('\\', '_')
            .trim()
        if (sanitized.isEmpty() || sanitized == "." || sanitized == "..") {
            sanitized = "mod.jar"
        }
        if (!sanitized.lowercase(Locale.ROOT).endsWith(".jar")) {
            sanitized += ".jar"
        }
        return sanitized
    }

    private fun buildUniqueImportTarget(modsDir: File, preferredName: String): File {
        val normalizedName = sanitizeImportedJarFileName(preferredName)
        val baseName = removeJarSuffix(normalizedName).ifBlank { "mod" }
        var index = 1
        while (true) {
            val candidateName = if (index == 1) {
                "$baseName.jar"
            } else {
                "$baseName ($index).jar"
            }
            val candidate = File(modsDir, candidateName)
            if (!candidate.exists() && !isReservedJarName(candidate.name)) {
                return candidate
            }
            index++
        }
    }

    private fun removeJarSuffix(fileName: String): String {
        return if (fileName.lowercase(Locale.ROOT).endsWith(".jar")) {
            fileName.substring(0, fileName.length - 4)
        } else {
            fileName
        }
    }
}
