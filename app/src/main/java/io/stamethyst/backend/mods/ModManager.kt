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
import java.util.ArrayDeque
import java.util.ArrayList
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
        @JvmField val enabled: Boolean,
        @JvmField val priorityRoot: Boolean,
        @JvmField val priorityLoad: Boolean
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

    private data class OptionalInstalledModEntry(
        val storageKey: String,
        val modId: String,
        val manifestModId: String,
        val name: String,
        val version: String,
        val description: String,
        val dependencies: List<String>,
        val jarFile: File,
        val enabled: Boolean
    ) {
        fun toPriorityEntry(): OptionalModPriorityEntry {
            return OptionalModPriorityEntry(
                storageKey = storageKey,
                normalizedModId = normalizeModId(modId),
                normalizedManifestModId = normalizeModId(manifestModId),
                dependencies = dependencies
            )
        }
    }

    private data class OptionalModPriorityEntry(
        val storageKey: String,
        val normalizedModId: String,
        val normalizedManifestModId: String,
        val dependencies: List<String>
    )

    private data class PrioritySelectionState(
        val explicitKeys: Set<String>,
        val effectiveKeys: Set<String>
    )

    private data class OptionalModLaunchEntry(
        val storageKey: String,
        val normalizedModId: String,
        val normalizedManifestModId: String,
        val launchModId: String,
        val dependencies: List<String>,
        val originalIndex: Int
    ) {
        fun toPriorityEntry(): OptionalModPriorityEntry {
            return OptionalModPriorityEntry(
                storageKey = storageKey,
                normalizedModId = normalizedModId,
                normalizedManifestModId = normalizedManifestModId,
                dependencies = dependencies
            )
        }
    }

    class LaunchIdConflict(
        @JvmField val launchModId: String,
        jarFiles: List<File>
    ) {
        @JvmField
        val jarFiles: List<File> = ArrayList(jarFiles)
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
        OptionalModStorageCoordinator.ensureOptionalModLibraryReady(context)
        return File(RuntimePaths.optionalModsLibraryDir(context), "${sanitizeFileName(normalized)}.jar")
    }

    @JvmStatic
    fun resolveStorageFileForImportedMod(context: Context, requestedFileName: String?): File {
        OptionalModStorageCoordinator.ensureOptionalModLibraryReady(context)
        val modsDir = RuntimePaths.optionalModsLibraryDir(context)
        val preferredName = sanitizeImportedJarFileName(requestedFileName)
        return buildUniqueImportTarget(modsDir, preferredName)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun removeExistingOptionalModsForImport(
        context: Context,
        normalizedModId: String,
        launchModId: String?,
        excludedPath: String? = null
    ) {
        OptionalModStorageCoordinator.ensureOptionalModLibraryReady(context)
        val targetNormalizedModId = normalizeModId(normalizedModId)
        val targetLaunchModId = launchModId?.trim().orEmpty()
        val excludedAbsolutePath = excludedPath?.trim().orEmpty()
        if (targetNormalizedModId.isEmpty() && targetLaunchModId.isEmpty()) {
            return
        }

        val rawSelection = readEnabledOptionalModKeysSafely(context)
        val optionalModFiles = findOptionalModFiles(context)
        val normalizedSelection = normalizeEnabledOptionalSelection(context, rawSelection, optionalModFiles)
        var selectionChanged = rawSelection != normalizedSelection
        var deletedAny = false

        listJarFilesInOptionalModLibrary(context).forEach { file ->
            if (excludedAbsolutePath.isNotEmpty() && file.absolutePath == excludedAbsolutePath) {
                return@forEach
            }
            if (isReservedJarName(file.name)) {
                return@forEach
            }
            if (!shouldReplaceImportedModFile(file, targetNormalizedModId, targetLaunchModId)) {
                return@forEach
            }
            val storageKey = resolveOptionalStorageKey(file)
            if (normalizedSelection.remove(storageKey)) {
                selectionChanged = true
            }
            if (!file.delete()) {
                throw IOException("Failed to delete mod file: ${file.absolutePath}")
            }
            deletedAny = true
        }

        if (deletedAny || selectionChanged) {
            writeEnabledOptionalModKeys(context, normalizedSelection)
        }
    }

    @JvmStatic
    fun findModsDirLaunchIdConflicts(context: Context, launchModIds: Collection<String>): List<LaunchIdConflict> {
        val requestedIds: MutableSet<String> = LinkedHashSet()
        launchModIds.forEach { launchModId ->
            val value = launchModId.trim()
            if (value.isNotEmpty()) {
                requestedIds.add(value)
            }
        }
        if (requestedIds.isEmpty()) {
            return emptyList()
        }

        val filesByLaunchId: MutableMap<String, MutableList<File>> = LinkedHashMap()
        listJarFilesInRuntimeModsDir(context).forEach { file ->
            val launchModId = try {
                MtsLaunchManifestValidator.resolveLaunchModId(file).trim()
            } catch (_: Throwable) {
                return@forEach
            }
            if (launchModId.isEmpty() || !requestedIds.contains(launchModId)) {
                return@forEach
            }
            filesByLaunchId.getOrPut(launchModId) { ArrayList() }.add(file)
        }

        val conflicts = ArrayList<LaunchIdConflict>()
        requestedIds.forEach { launchModId ->
            val files = filesByLaunchId[launchModId] ?: return@forEach
            if (files.size <= 1) {
                return@forEach
            }
            conflicts.add(
                LaunchIdConflict(
                    launchModId = launchModId,
                    jarFiles = files.sortedWith(compareBy<File>({ it.name.lowercase(Locale.ROOT) }, { it.name }, { it.absolutePath }))
                )
            )
        }
        return conflicts
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
    fun setOptionalModPriorityRoot(context: Context, modKeyOrId: String, enabled: Boolean) {
        val optionalModFiles = findOptionalModFiles(context)
        if (optionalModFiles.isEmpty()) {
            return
        }
        val targetKey = resolveSelectionKey(context, modKeyOrId, optionalModFiles) ?: return

        val rawSelection = readPriorityRootOptionalModKeys(context)
        val normalizedSelection = normalizeEnabledOptionalSelection(context, rawSelection, optionalModFiles)
        val changed = if (enabled) {
            normalizedSelection.add(targetKey)
        } else {
            normalizedSelection.remove(targetKey)
        }
        if (changed || rawSelection != normalizedSelection) {
            writePriorityRootOptionalModKeys(context, normalizedSelection)
        }
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
        OptionalModStorageCoordinator.ensureOptionalModLibraryReady(context)
        val normalizedPath = normalizeSelectionToken(context, storagePath)
        if (!looksLikePathToken(normalizedPath)) {
            return false
        }
        val target = File(normalizedPath)
        if (!target.isFile || isReservedJarName(target.name)) {
            return false
        }
        val expectedDirPath = RuntimePaths.optionalModsLibraryDir(context).absolutePath
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
    @Throws(IOException::class)
    fun renameOptionalModByStoragePath(
        context: Context,
        storagePath: String,
        requestedFileName: String
    ): File {
        OptionalModStorageCoordinator.ensureOptionalModLibraryReady(context)
        val normalizedPath = normalizeSelectionToken(context, storagePath)
        if (!looksLikePathToken(normalizedPath)) {
            throw IOException("Invalid mod storage path: $storagePath")
        }

        val libraryDir = RuntimePaths.optionalModsLibraryDir(context)
        var source = File(normalizedPath)
        if (
            source.parentFile?.absolutePath != libraryDir.absolutePath &&
            !isReservedJarName(source.name)
        ) {
            val libraryCandidate = File(libraryDir, source.name)
            if (libraryCandidate.isFile) {
                source = libraryCandidate
            }
        }
        if (!source.isFile || isReservedJarName(source.name)) {
            throw IOException("Mod file missing: ${source.absolutePath}")
        }
        val expectedDirPath = libraryDir.absolutePath
        if (source.parentFile?.absolutePath != expectedDirPath) {
            throw IOException("Only optional library mods can be renamed: ${source.absolutePath}")
        }

        val targetName = sanitizeImportedJarFileName(requestedFileName)
        if (isReservedJarName(targetName)) {
            throw IOException("Reserved mod file name: $targetName")
        }
        val target = File(source.parentFile, targetName)
        if (source.absolutePath == target.absolutePath) {
            return source
        }
        if (target.exists()) {
            throw IOException("Target already exists: ${target.absolutePath}")
        }

        val optionalModFiles = findOptionalModFiles(context)
        val sourceKey = resolveSelectionKey(context, source.absolutePath, optionalModFiles)
            ?: resolveOptionalStorageKey(source)
        moveFileReplacing(source, target)

        val targetKey = resolveOptionalStorageKey(target)
        rewriteRenamedOptionalSelection(
            context = context,
            sourceKey = sourceKey,
            targetKey = targetKey,
            readSelection = ::readEnabledOptionalModKeysSafely,
            normalizeSelection = ::normalizeEnabledOptionalSelection,
            writeSelection = ::writeEnabledOptionalModKeys
        )
        rewriteRenamedOptionalSelection(
            context = context,
            sourceKey = sourceKey,
            targetKey = targetKey,
            readSelection = ::readPriorityRootOptionalModKeysSafely,
            normalizeSelection = ::normalizeEnabledOptionalSelection,
            writeSelection = ::writePriorityRootOptionalModKeys
        )
        return target
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
        val rawPrioritySelection = readPriorityRootOptionalModKeysSafely(context)
        val explicitPrioritySelection = normalizeEnabledOptionalSelection(context, rawPrioritySelection, optionalModFiles)
        maybePersistPriorityRootSelectionNormalization(context, rawPrioritySelection, explicitPrioritySelection)
        val optionalEntries = ArrayList<OptionalInstalledModEntry>()

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
            optionalEntries.add(
                OptionalInstalledModEntry(
                    storageKey = entry.storageKey,
                    modId = modId,
                    manifestModId = manifestModId,
                    name = name,
                    version = version,
                    description = description,
                    dependencies = ArrayList(dependencies),
                    jarFile = jarFile,
                    enabled = enabledSelection.contains(entry.storageKey)
                )
            )
        }
        val priorityState = resolvePrioritySelectionState(
            entries = optionalEntries.map { it.toPriorityEntry() },
            explicitSelection = explicitPrioritySelection
        )
        optionalEntries.forEach { entry ->
            result.add(
                InstalledMod(
                    entry.modId,
                    entry.manifestModId,
                    entry.name,
                    entry.version,
                    entry.description,
                    entry.dependencies,
                    entry.jarFile,
                    false,
                    true,
                    entry.enabled,
                    priorityState.explicitKeys.contains(entry.storageKey),
                    priorityState.effectiveKeys.contains(entry.storageKey)
                )
            )
        }
        return result
    }

    @JvmStatic
    fun listEnabledOptionalModFiles(context: Context): List<File> {
        val optionalModFiles = findOptionalModFiles(context)
        if (optionalModFiles.isEmpty()) {
            return emptyList()
        }

        val rawSelection = readEnabledOptionalModKeysSafely(context)
        val enabledSelection = normalizeEnabledOptionalSelection(context, rawSelection, optionalModFiles)
        maybePersistSelectionNormalization(context, rawSelection, enabledSelection)
        return optionalModFiles
            .asSequence()
            .filter { enabledSelection.contains(it.storageKey) }
            .map { it.jarFile }
            .toList()
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
        val rawPrioritySelection = readPriorityRootOptionalModKeysSafely(context)
        val explicitPrioritySelection = normalizeEnabledOptionalSelection(context, rawPrioritySelection, optionalModFiles)
        maybePersistPriorityRootSelectionNormalization(context, rawPrioritySelection, explicitPrioritySelection)

        val launchModIds = ArrayList<String>()
        launchModIds.add(baseModId)
        launchModIds.add(stsLibId)

        val enabledOptionalEntries = ArrayList<OptionalModLaunchEntry>()
        optionalModFiles.forEachIndexed { index, entry ->
            if (!enabledSelection.contains(entry.storageKey)) {
                return@forEachIndexed
            }
            val launchModId = MtsLaunchManifestValidator.resolveLaunchModId(entry.jarFile).trim()
            if (launchModId.isNotEmpty()) {
                val manifest = ModJarSupport.readModManifest(entry.jarFile)
                enabledOptionalEntries.add(
                    OptionalModLaunchEntry(
                        storageKey = entry.storageKey,
                        normalizedModId = entry.normalizedModId,
                        normalizedManifestModId = normalizeModId(manifest.modId),
                        launchModId = launchModId,
                        dependencies = ArrayList(manifest.dependencies),
                        originalIndex = index
                    )
                )
            }
        }
        val priorityState = resolvePrioritySelectionState(
            entries = enabledOptionalEntries.map { it.toPriorityEntry() },
            explicitSelection = explicitPrioritySelection
        )
        val priorityOrderedEntries = sortPriorityLaunchEntries(
            entries = enabledOptionalEntries,
            effectivePriorityKeys = priorityState.effectiveKeys
        )
        val priorityKeys = priorityOrderedEntries.mapTo(LinkedHashSet()) { it.storageKey }
        priorityOrderedEntries.forEach { entry ->
            launchModIds.add(entry.launchModId)
        }
        enabledOptionalEntries.forEach { entry ->
            if (!priorityKeys.contains(entry.storageKey)) {
                launchModIds.add(entry.launchModId)
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
            available,
            false,
            false
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
        OptionalModStorageCoordinator.ensureOptionalModLibraryReady(context)
        val result = ArrayList<OptionalModFileEntry>()
        val modsDir = RuntimePaths.optionalModsLibraryDir(context)
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

    private fun listJarFilesInOptionalModLibrary(context: Context): List<File> {
        OptionalModStorageCoordinator.ensureOptionalModLibraryReady(context)
        val modsDir = RuntimePaths.optionalModsLibraryDir(context)
        val files = modsDir.listFiles() ?: return emptyList()
        return files
            .asSequence()
            .filter { it.isFile }
            .filter { it.name.lowercase(Locale.ROOT).endsWith(".jar") }
            .sortedWith(compareBy<File>({ it.name.lowercase(Locale.ROOT) }, { it.name }, { it.absolutePath }))
            .toList()
    }

    private fun listJarFilesInRuntimeModsDir(context: Context): List<File> {
        val modsDir = RuntimePaths.modsDir(context)
        val files = modsDir.listFiles() ?: return emptyList()
        return files
            .asSequence()
            .filter { it.isFile }
            .filter { it.name.lowercase(Locale.ROOT).endsWith(".jar") }
            .sortedWith(compareBy<File>({ it.name.lowercase(Locale.ROOT) }, { it.name }, { it.absolutePath }))
            .toList()
    }

    private fun readEnabledOptionalModKeysSafely(context: Context): MutableSet<String> {
        return try {
            readEnabledOptionalModKeys(context)
        } catch (_: Throwable) {
            LinkedHashSet()
        }
    }

    private fun readPriorityRootOptionalModKeysSafely(context: Context): MutableSet<String> {
        return try {
            readPriorityRootOptionalModKeys(context)
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

    @Throws(IOException::class)
    private fun readPriorityRootOptionalModKeys(context: Context): MutableSet<String> {
        val keys: MutableSet<String> = LinkedHashSet()
        val config = RuntimePaths.priorityModsConfig(context)
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
    private fun writePriorityRootOptionalModKeys(context: Context, modKeys: Set<String>) {
        val config = RuntimePaths.priorityModsConfig(context)
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

    private fun maybePersistPriorityRootSelectionNormalization(
        context: Context,
        rawSelection: Set<String>,
        normalizedSelection: Set<String>
    ) {
        if (rawSelection == normalizedSelection) {
            return
        }
        try {
            writePriorityRootOptionalModKeys(context, normalizedSelection)
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

    private fun shouldReplaceImportedModFile(
        file: File,
        normalizedModId: String,
        launchModId: String
    ): Boolean {
        if (!file.isFile) {
            return false
        }
        if (normalizedModId.isNotEmpty()) {
            val existingNormalized = try {
                normalizeModId(resolveRawModId(file))
            } catch (_: Throwable) {
                ""
            }
            if (existingNormalized == normalizedModId) {
                return true
            }
        }
        if (launchModId.isEmpty()) {
            return false
        }
        val existingLaunchModId = try {
            MtsLaunchManifestValidator.resolveLaunchModId(file).trim()
        } catch (_: Throwable) {
            ""
        }
        return existingLaunchModId == launchModId
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

    private fun resolvePrioritySelectionState(
        entries: List<OptionalModPriorityEntry>,
        explicitSelection: Set<String>
    ): PrioritySelectionState {
        if (entries.isEmpty()) {
            return PrioritySelectionState(
                explicitKeys = emptySet(),
                effectiveKeys = emptySet()
            )
        }

        val entryByStorageKey = LinkedHashMap<String, OptionalModPriorityEntry>()
        val entryByModId = LinkedHashMap<String, OptionalModPriorityEntry>()
        entries.forEach { entry ->
            entryByStorageKey[entry.storageKey] = entry
            if (entry.normalizedModId.isNotEmpty() && !entryByModId.containsKey(entry.normalizedModId)) {
                entryByModId[entry.normalizedModId] = entry
            }
            if (
                entry.normalizedManifestModId.isNotEmpty() &&
                !entryByModId.containsKey(entry.normalizedManifestModId)
            ) {
                entryByModId[entry.normalizedManifestModId] = entry
            }
        }

        val normalizedExplicit = LinkedHashSet<String>()
        explicitSelection.forEach { key ->
            if (entryByStorageKey.containsKey(key)) {
                normalizedExplicit.add(key)
            }
        }
        if (normalizedExplicit.isEmpty()) {
            return PrioritySelectionState(
                explicitKeys = emptySet(),
                effectiveKeys = emptySet()
            )
        }

        val effective = LinkedHashSet<String>()
        val queue: ArrayDeque<OptionalModPriorityEntry> = ArrayDeque()
        normalizedExplicit.forEach { key ->
            entryByStorageKey[key]?.let(queue::add)
        }
        while (!queue.isEmpty()) {
            val current = queue.removeFirst()
            if (!effective.add(current.storageKey)) {
                continue
            }
            current.dependencies.forEach { dependency ->
                val normalizedDependency = normalizeModId(dependency)
                if (normalizedDependency.isEmpty() || isRequiredModId(normalizedDependency)) {
                    return@forEach
                }
                entryByModId[normalizedDependency]?.let(queue::add)
            }
        }
        return PrioritySelectionState(
            explicitKeys = normalizedExplicit,
            effectiveKeys = effective
        )
    }

    private fun sortPriorityLaunchEntries(
        entries: List<OptionalModLaunchEntry>,
        effectivePriorityKeys: Set<String>
    ): List<OptionalModLaunchEntry> {
        val priorityEntries = entries.filter { effectivePriorityKeys.contains(it.storageKey) }
            .sortedBy { it.originalIndex }
        if (priorityEntries.size <= 1) {
            return priorityEntries
        }

        val entryByKey = priorityEntries.associateBy { it.storageKey }
        val entryByModId = LinkedHashMap<String, OptionalModLaunchEntry>()
        priorityEntries.forEach { entry ->
            if (entry.normalizedModId.isNotEmpty() && !entryByModId.containsKey(entry.normalizedModId)) {
                entryByModId[entry.normalizedModId] = entry
            }
            if (
                entry.normalizedManifestModId.isNotEmpty() &&
                !entryByModId.containsKey(entry.normalizedManifestModId)
            ) {
                entryByModId[entry.normalizedManifestModId] = entry
            }
        }

        val outgoing = LinkedHashMap<String, MutableSet<String>>()
        val indegree = LinkedHashMap<String, Int>()
        priorityEntries.forEach { entry ->
            outgoing[entry.storageKey] = LinkedHashSet()
            indegree[entry.storageKey] = 0
        }

        priorityEntries.forEach { entry ->
            val dependencies = entry.dependencies
                .asSequence()
                .map { normalizeModId(it) }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()
            dependencies.forEach { dependencyId ->
                val dependencyEntry = entryByModId[dependencyId] ?: return@forEach
                if (dependencyEntry.storageKey == entry.storageKey) {
                    return@forEach
                }
                val edges = outgoing[dependencyEntry.storageKey] ?: return@forEach
                if (edges.add(entry.storageKey)) {
                    indegree[entry.storageKey] = (indegree[entry.storageKey] ?: 0) + 1
                }
            }
        }

        val available = ArrayList<OptionalModLaunchEntry>()
        priorityEntries.forEach { entry ->
            if ((indegree[entry.storageKey] ?: 0) == 0) {
                available.add(entry)
            }
        }
        available.sortBy { it.originalIndex }

        val ordered = ArrayList<OptionalModLaunchEntry>(priorityEntries.size)
        while (available.isNotEmpty()) {
            val current = available.removeAt(0)
            ordered.add(current)
            val nextKeys = outgoing[current.storageKey].orEmpty().toList()
            nextKeys.forEach { nextKey ->
                val nextDegree = (indegree[nextKey] ?: 0) - 1
                indegree[nextKey] = nextDegree
                if (nextDegree == 0) {
                    entryByKey[nextKey]?.let { nextEntry ->
                        available.add(nextEntry)
                        available.sortBy { it.originalIndex }
                    }
                }
            }
        }

        if (ordered.size == priorityEntries.size) {
            return ordered
        }

        val orderedKeys = ordered.mapTo(LinkedHashSet()) { it.storageKey }
        priorityEntries.forEach { entry ->
            if (!orderedKeys.contains(entry.storageKey)) {
                ordered.add(entry)
            }
        }
        return ordered
    }

    private fun normalizeSelectionToken(context: Context, raw: String?): String {
        val trimmed = raw?.trim() ?: ""
        if (trimmed.isEmpty()) {
            return ""
        }
        return if (looksLikePathToken(trimmed)) {
            RuntimePaths.normalizeLegacyStsPath(context = context, rawPath = trimmed)
                ?: ""
        } else {
            normalizeModId(trimmed)
        }
    }

    private fun looksLikePathToken(token: String): Boolean {
        return token.contains('/') || token.contains('\\')
    }

    @Throws(IOException::class)
    private fun moveFileReplacing(source: File, target: File) {
        if (target.exists()) {
            throw IOException("Target already exists: ${target.absolutePath}")
        }
        if (source.renameTo(target)) {
            return
        }
        source.inputStream().use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
                output.flush()
            }
        }
        if (!source.delete()) {
            if (!target.delete()) {
                // Keep the copied file as best-effort output if cleanup fails.
            }
            throw IOException("Failed to delete old file: ${source.absolutePath}")
        }
    }

    @Throws(IOException::class)
    private fun rewriteRenamedOptionalSelection(
        context: Context,
        sourceKey: String,
        targetKey: String,
        readSelection: (Context) -> MutableSet<String>,
        normalizeSelection: (Context, Collection<String>, List<OptionalModFileEntry>) -> MutableSet<String>,
        writeSelection: (Context, Set<String>) -> Unit
    ) {
        val rawSelection = readSelection(context)
        val normalizedSelection = normalizeSelection(context, rawSelection, findOptionalModFiles(context))
        val changed = normalizedSelection.remove(sourceKey)
        if (changed) {
            normalizedSelection.add(targetKey)
        }
        if (changed || rawSelection != normalizedSelection) {
            writeSelection(context, normalizedSelection)
        }
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
