package io.stamethyst.backend.mods

import android.content.Context
import io.stamethyst.backend.core.RuntimePaths
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
import java.util.Arrays
import java.util.HashSet
import java.util.LinkedHashSet
import java.util.Locale
import java.util.TreeMap

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
    fun listEnabledOptionalModIds(context: Context): Set<String> {
        return LinkedHashSet(readEnabledOptionalModIdsSafely(context))
    }

    @JvmStatic
    @Throws(IOException::class)
    fun setOptionalModEnabled(context: Context, modId: String, enabled: Boolean) {
        val normalized = normalizeModId(modId)
        if (normalized.isEmpty() || isRequiredModId(normalized)) {
            return
        }
        val selected = readEnabledOptionalModIds(context)
        val changed = if (enabled) selected.add(normalized) else selected.remove(normalized)
        if (changed) {
            writeEnabledOptionalModIds(context, selected)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun replaceEnabledOptionalModIds(context: Context, modIds: Collection<String>) {
        val selected: MutableSet<String> = LinkedHashSet()
        for (modId in modIds) {
            val normalized = normalizeModId(modId)
            if (normalized.isNotEmpty() && !isRequiredModId(normalized)) {
                selected.add(normalized)
            }
        }
        if (selected.isNotEmpty()) {
            val optionalModFiles = findOptionalModFiles(context)
            selected.retainAll(optionalModFiles.keys)
        }
        writeEnabledOptionalModIds(context, selected)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun deleteOptionalMod(context: Context, modId: String): Boolean {
        val normalized = normalizeModId(modId)
        if (normalized.isEmpty() || isRequiredModId(normalized)) {
            return false
        }

        val optionalModFiles = findOptionalModFiles(context)
        val target = optionalModFiles[normalized]

        setOptionalModEnabled(context, normalized, false)

        if (target == null || !target.isFile) {
            return false
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

        val enabledOptional = readEnabledOptionalModIdsSafely(context)
        val optionalModFiles = findOptionalModFiles(context)
        maybePruneEnabledSelection(context, enabledOptional, optionalModFiles.keys)

        for (modId in optionalModFiles.keys) {
            val jarFile = optionalModFiles[modId] ?: continue
            var manifestModId = modId
            var name = modId
            var version = ""
            var description = ""
            var dependencies: List<String> = ArrayList()
            try {
                val manifest = ModJarSupport.readModManifest(jarFile)
                manifestModId = defaultIfBlank(manifest.modId, modId)
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
                    enabledOptional.contains(modId)
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

        val enabledOptional = readEnabledOptionalModIds(context)
        val optionalModFiles = findOptionalModFiles(context)
        maybePruneEnabledSelection(context, enabledOptional, optionalModFiles.keys)

        val launchModIds = ArrayList<String>()
        launchModIds.add(baseModId)
        launchModIds.add(stsLibId)

        for (modId in enabledOptional) {
            val modJar = optionalModFiles[modId]
            if (modJar != null) {
                val rawModId = resolveRawModId(modJar)
                if (rawModId.isNotEmpty()) {
                    launchModIds.add(rawModId)
                }
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

    private fun findOptionalModFiles(context: Context): TreeMap<String, File> {
        val modsById = TreeMap<String, File>()
        val modsDir = RuntimePaths.modsDir(context)
        val files = modsDir.listFiles() ?: return modsById
        for (file in files) {
            if (!file.isFile) {
                continue
            }
            val name = file.name
            if (!name.lowercase(Locale.ROOT).endsWith(".jar")) {
                continue
            }
            if (isReservedJarName(name)) {
                continue
            }
            val modId: String = try {
                normalizeModId(ModJarSupport.resolveModId(file))
            } catch (_: Throwable) {
                continue
            }
            if (modId.isEmpty() || isRequiredModId(modId) || modsById.containsKey(modId)) {
                continue
            }
            modsById[modId] = file
        }
        return modsById
    }

    private fun isReservedJarName(fileName: String): Boolean {
        val normalized = fileName.lowercase(Locale.ROOT)
        return "basemod.jar" == normalized || "stslib.jar" == normalized
    }

    private fun readEnabledOptionalModIdsSafely(context: Context): MutableSet<String> {
        return try {
            readEnabledOptionalModIds(context)
        } catch (_: Throwable) {
            LinkedHashSet()
        }
    }

    @Throws(IOException::class)
    private fun readEnabledOptionalModIds(context: Context): MutableSet<String> {
        val ids: MutableSet<String> = LinkedHashSet()
        val config = RuntimePaths.enabledModsConfig(context)
        if (!config.isFile) {
            return ids
        }
        FileInputStream(config).use { input ->
            InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                BufferedReader(reader).use { buffered ->
                    while (true) {
                        val line = buffered.readLine() ?: break
                        val modId = normalizeModId(line)
                        if (modId.isNotEmpty() && !isRequiredModId(modId)) {
                            ids.add(modId)
                        }
                    }
                }
            }
        }
        return ids
    }

    @Throws(IOException::class)
    private fun writeEnabledOptionalModIds(context: Context, modIds: Set<String>) {
        val config = RuntimePaths.enabledModsConfig(context)
        val parent = config.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        FileOutputStream(config, false).use { output ->
            OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
                BufferedWriter(writer).use { buffered ->
                    for (modId in modIds) {
                        val normalized = normalizeModId(modId)
                        if (normalized.isEmpty() || isRequiredModId(normalized)) {
                            continue
                        }
                        buffered.write(normalized)
                        buffered.newLine()
                    }
                }
            }
        }
    }

    private fun maybePruneEnabledSelection(
        context: Context,
        enabledOptional: MutableSet<String>,
        installedOptional: Set<String>
    ) {
        if (enabledOptional.isEmpty()) {
            return
        }
        val pruned = LinkedHashSet(enabledOptional)
        pruned.retainAll(installedOptional)
        if (pruned == enabledOptional) {
            return
        }
        try {
            writeEnabledOptionalModIds(context, pruned)
        } catch (_: Throwable) {
        }
        enabledOptional.clear()
        enabledOptional.addAll(pruned)
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
}
