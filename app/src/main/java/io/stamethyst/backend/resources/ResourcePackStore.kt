package io.stamethyst.backend.resources

import android.content.Context
import io.stamethyst.BuildConfig
import io.stamethyst.backend.fs.FileTreeCleaner
import io.stamethyst.backend.launch.StartupProgressCallback
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong

internal data class ResourcePackInspection(
    val ready: Boolean,
    val packId: String?,
    val version: String?,
    val generationDir: File?,
    val issues: List<String>,
    val legacyPaths: List<String>,
    val state: String?
)

internal object ResourcePackStore {
    private const val ACTIVE_SCHEMA_VERSION = 2
    private const val ACTIVE_PACK_ID_KEY = "packId"
    private const val STATE_OPERATION_ID_KEY = "operationId"
    private const val STATE_PHASE_KEY = "phase"
    private const val STATE_SOURCE_KEY = "source"
    private const val STATE_ERROR_KEY = "error"
    private const val STATE_UPDATED_AT_KEY = "updatedAt"
    private const val GENERATION_INSTALLING_PREFIX = ".installing-"
    private const val ROOT_STAGING_PREFIX = "staging-"
    private const val ROOT_MIGRATION_PREFIX = "external_resources.migration-"
    private const val MAX_DIAGNOSTIC_ISSUES = 32
    private const val GENERATIONS_TO_KEEP = 2
    private val operationCounter = AtomicLong(0L)
    private val processLock = Any()
    private val lockDepth = ThreadLocal.withInitial { 0 }

    @JvmStatic
    fun inspect(context: Context): ResourcePackInspection {
        val root = RuntimePaths.externalResourcesRoot(context)
        val pointer = readActivePointer(root)
        val legacyPaths = legacyCandidateRoots(context).map { file -> file.absolutePath }
        val state = readState(root)?.getProperty(STATE_PHASE_KEY)
        if (root.exists() && !root.isDirectory) {
            return ResourcePackInspection(
                ready = false,
                packId = null,
                version = null,
                generationDir = null,
                issues = listOf("resource repository path is not a directory"),
                legacyPaths = legacyPaths,
                state = state
            )
        }
        if (pointer == null) {
            val pointerIssue = if (activePointerFile(root).exists()) {
                "active resource pack pointer is invalid"
            } else {
                "active resource pack pointer is missing"
            }
            return ResourcePackInspection(
                ready = false,
                packId = null,
                version = null,
                generationDir = null,
                issues = listOf(pointerIssue),
                legacyPaths = legacyPaths,
                state = state
            )
        }

        val generation = generationDir(root, pointer.packId)
        val validation = ResourcePackArchive.validateGeneration(
            generation,
            BuildConfig.RESOURCE_PACK_VERSION.trim()
        )
        return ResourcePackInspection(
            ready = validation.issues.isEmpty(),
            packId = validation.manifest?.packId ?: pointer.packId,
            version = validation.manifest?.resourcePackVersion,
            generationDir = generation,
            issues = validation.issues,
            legacyPaths = legacyPaths,
            state = state
        )
    }

    @JvmStatic
    fun activeGenerationDir(context: Context): File? {
        val root = RuntimePaths.externalResourcesRoot(context)
        val pointer = readActivePointer(root) ?: return null
        val generation = generationDir(root, pointer.packId)
        return runCatching {
            generation.takeIf {
                ResourcePackArchive.validateGeneration(
                    it,
                    BuildConfig.RESOURCE_PACK_VERSION.trim()
                ).issues.isEmpty()
            }
        }.getOrNull()
    }

    @JvmStatic
    fun activePackId(context: Context): String? {
        return activeGenerationDir(context)?.name
    }

    @JvmStatic
    fun activeAssetsDir(context: Context): File? {
        return activeGenerationDir(context)?.let { generation -> File(generation, "assets") }
    }

    @JvmStatic
    fun activeNativeLibDir(context: Context): File? {
        return activeGenerationDir(context)?.let { generation ->
            File(File(generation, "lib"), ResourcePackContract.ABI)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun recover(context: Context) {
        withExclusiveLock(context) {
            ensureRepositoryDirs(context)
            cleanupInterruptedArtifacts(context)

            val root = RuntimePaths.externalResourcesRoot(context)
            val pointer = readActivePointer(root)
            if (pointer != null && ResourcePackArchive.validateGeneration(
                    generationDir(root, pointer.packId),
                    BuildConfig.RESOURCE_PACK_VERSION.trim()
                ).issues.isEmpty()
            ) {
                quarantineLegacyResources(context, root)
                return@withExclusiveLock
            }

            val recoveredGeneration = findNewestValidGeneration(context)
            if (recoveredGeneration != null) {
                activatePointer(root, recoveredGeneration.name)
                quarantineLegacyResources(context, root)
                writeState(
                    root = root,
                    operationId = "recovery-${System.currentTimeMillis()}",
                    phase = "recovered_generation",
                    source = "local-generation",
                    error = null
                )
                return@withExclusiveLock
            }

            val migrationFailure = runCatching { importLegacyResources(context) }.exceptionOrNull()
            if (migrationFailure != null) {
                if (Thread.currentThread().isInterrupted) {
                    throw migrationFailure
                }
                writeState(
                    root = root,
                    operationId = "recovery-${System.currentTimeMillis()}",
                    phase = "migration_failed",
                    source = "legacy",
                    error = summarizeError(migrationFailure)
                )
                // Leave the legacy tree in place so a later retry can import it.
                // Invalid legacy content is already quarantined inside importLegacyResources.
            }
            val recoveredPointer = readActivePointer(root)
            if (recoveredPointer == null || ResourcePackArchive.validateGeneration(
                    generationDir(root, recoveredPointer.packId),
                    BuildConfig.RESOURCE_PACK_VERSION.trim()
                ).issues.isNotEmpty()
            ) {
                writeState(
                    root = root,
                    operationId = "recovery-${System.currentTimeMillis()}",
                    phase = "needs_install",
                    source = "local-recovery",
                    error = "No valid local resource generation"
                )
            }
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun clear(context: Context) {
        withExclusiveLock(context) {
            ensureRepositoryDirs(context)
            val root = RuntimePaths.externalResourcesRoot(context)
            deleteTreeForCleanup(activePointerFile(root))
            RuntimePaths.externalResourcesGenerationsDir(context).listFiles().orEmpty()
                .forEach(::deleteTreeForCleanup)
            RuntimePaths.externalResourcesStagingRoot(context).listFiles().orEmpty()
                .forEach(::deleteTreeForCleanup)
            RuntimePaths.externalResourcesQuarantineRoot(context).listFiles().orEmpty()
                .forEach(::deleteTreeForCleanup)
            clearDerivedInstallMarkers(context)
            legacyCandidateRoots(context).mapNotNull(::resolveLegacyContentRoot)
                .distinctBy { it.canonicalPath }
                .filter { it.exists() }
                .forEach { legacy -> quarantineOrDelete(legacy, root) }
            writeState(
                root = root,
                operationId = "clear-${newOperationId()}",
                phase = "cleared",
                source = "user",
                error = null
            )
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun installArchive(
        context: Context,
        archiveFile: File,
        progressCallback: StartupProgressCallback?,
        source: String
    ): ResourcePackManifest {
        return withExclusiveLock(context) {
            ensureRepositoryDirs(context)
            if (!archiveFile.isFile || archiveFile.length() <= 0L) {
                throw IOException("Resource pack archive is missing or empty: ${archiveFile.absolutePath}")
            }
            ResourcePackContract.requireArchiveBytes(archiveFile.length())
            val expectedArchiveHash = BuildConfig.RESOURCE_PACK_SHA256.trim()
            if (expectedArchiveHash.isNotEmpty()) {
                if (!ResourcePackArchive.isSha256(expectedArchiveHash)) {
                    throw IOException("Configured resource pack archive hash is invalid")
                }
                val actualArchiveHash = ResourcePackArchive.sha256(archiveFile)
                if (!actualArchiveHash.equals(expectedArchiveHash, ignoreCase = true)) {
                    throw IOException("Resource pack archive hash mismatch")
                }
            }

            val root = RuntimePaths.externalResourcesRoot(context)
            val operationId = newOperationId()
            val operationDir = File(RuntimePaths.externalResourcesStagingRoot(context), "install-$operationId")
            val extractedDir = File(operationDir, "extracted")
            prepareDirectory(operationDir)
            writeState(root, operationId, "extracting", source, null)
            try {
                ResourcePackArchive.extractArchive(
                    archiveFile = archiveFile,
                    targetDir = extractedDir,
                    progressCallback = progressCallback,
                    context = context
                )
                throwIfInterrupted()
                writeState(root, operationId, "verifying", source, null)
                val missing = ResourcePackContract.collectMissingContent(extractedDir)
                if (missing.isNotEmpty()) {
                    throw IOException("Resource pack is incomplete. Missing: ${missing.joinToString(", ")}")
                }
                val manifest = ResourcePackArchive.buildManifest(
                    extractedDir,
                    BuildConfig.RESOURCE_PACK_VERSION.trim()
                )
                ResourcePackArchive.writeGenerationMetadata(extractedDir, manifest)
                writeState(root, operationId, "activating", source, null)
                materializeAndActivate(context, extractedDir, manifest, operationId)
                // Reinstall derived components even when a repaired archive resolves to
                // the same content id as the prior healthy generation.
                clearDerivedInstallMarkers(context)
                writeState(root, operationId, "ready", source, null)
                garbageCollectGenerations(context, manifest.packId)
                return@withExclusiveLock manifest
            } catch (error: Throwable) {
                writeState(root, operationId, "failed", source, summarizeError(error))
                throw error
            } finally {
                deleteTreeForCleanup(operationDir)
            }
        }
    }

    @JvmStatic
    fun buildDiagnostics(context: Context): String {
        val inspection = runCatching { inspect(context) }.getOrElse { error ->
            return "resourcePack.formatVersion=2\n" +
                "resourcePack.state=inspection_failed\n" +
                "resourcePack.error=${sanitize(summarizeError(error))}\n"
        }
        val root = RuntimePaths.externalResourcesRoot(context)
        val stateProperties = readState(root)
        return buildString {
            append("resourcePack.formatVersion=2\n")
            append("resourcePack.root=").append(root.absolutePath).append('\n')
            append("resourcePack.expectedVersion=")
                .append(BuildConfig.RESOURCE_PACK_VERSION.trim())
                .append('\n')
            append("resourcePack.expectedArchiveSha256=")
                .append(BuildConfig.RESOURCE_PACK_SHA256.trim().ifBlank { "none" })
                .append('\n')
            append("resourcePack.embeddedArchive=")
                .append(embeddedArchiveExists(context))
                .append('\n')
            append("resourcePack.ready=").append(inspection.ready).append('\n')
            append("resourcePack.packId=").append(inspection.packId ?: "none").append('\n')
            append("resourcePack.version=").append(inspection.version ?: "none").append('\n')
            append("resourcePack.generation=")
                .append(inspection.generationDir?.absolutePath ?: "none")
                .append('\n')
            append("resourcePack.state=").append(inspection.state ?: "none").append('\n')
            append("resourcePack.stateOperation=")
                .append(stateProperties?.getProperty(STATE_OPERATION_ID_KEY) ?: "none")
                .append('\n')
            append("resourcePack.stateSource=")
                .append(stateProperties?.getProperty(STATE_SOURCE_KEY) ?: "none")
                .append('\n')
            append("resourcePack.stateError=")
                .append(sanitize(stateProperties?.getProperty(STATE_ERROR_KEY).orEmpty().ifBlank { "none" }))
                .append('\n')
            append("resourcePack.pointer=")
                .append(buildFileState(RuntimePaths.externalResourcesActivePointerFile(context)))
                .append('\n')
            append("resourcePack.legacyPaths=")
                .append(inspection.legacyPaths.joinToString("|"))
                .append('\n')
            inspection.issues.forEachIndexed { index, issue ->
                append("resourcePack.issue.").append(index).append('=').append(sanitize(issue)).append('\n')
            }
            append("resourcePack.staging=")
                .append(listChildren(RuntimePaths.externalResourcesStagingRoot(context)))
                .append('\n')
            append("resourcePack.quarantine=")
                .append(listChildren(RuntimePaths.externalResourcesQuarantineRoot(context)))
                .append('\n')
            append("resourcePack.nativeTarget=")
                .append(RuntimePaths.externalNativeLibDir(context).absolutePath)
                .append('\n')
            append("resourcePack.nativeTargetMarker=")
                .append(readNativeTargetMarker(context) ?: "none")
                .append('\n')
            append("resourcePack.componentMarker=")
                .append(readSmallFile(RuntimePaths.componentInstallMarkerFile(context)))
                .append('\n')
            append("resourcePack.runtimeMarker=")
                .append(readSmallFile(RuntimePaths.runtimeInstallMarkerFile(context)))
                .append('\n')
        }
    }

    internal fun <T> withExclusiveLock(context: Context, block: () -> T): T {
        if ((lockDepth.get() ?: 0) > 0) {
            return block()
        }
        return synchronized(processLock) {
            val repositoryRoot = RuntimePaths.externalResourcesRoot(context)
            val lockFile = RuntimePaths.externalResourcesLockFile(context)
            val parent = lockFile.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw IOException("Failed to create resource pack lock directory: ${parent.absolutePath}")
            }
            if (lockFile.exists() && !lockFile.isFile) {
                val displaced = File(
                    parent ?: throw IOException("Resource pack lock has no parent"),
                    "${lockFile.name}.corrupt-${newOperationId()}"
                )
                if (!lockFile.renameTo(displaced)) {
                    throw IOException("Resource pack lock path is not a file: ${lockFile.absolutePath}")
                }
            }
            RandomAccessFile(lockFile, "rw").use { randomAccessFile ->
                randomAccessFile.channel.lock().use {
                    if (repositoryRoot.exists() && !repositoryRoot.isDirectory) {
                        val displaced = File(
                            repositoryRoot.parentFile
                                ?: throw IOException("External resource repository has no parent"),
                            "${repositoryRoot.name}.corrupt-${newOperationId()}"
                        )
                        if (!repositoryRoot.renameTo(displaced)) {
                            throw IOException(
                                "External resource repository is not a directory: " +
                                    repositoryRoot.absolutePath
                            )
                        }
                    }
                    lockDepth.set(1)
                    try {
                        block()
                    } finally {
                        lockDepth.remove()
                    }
                }
            }
        }
    }

    internal fun tryCleanupTransient(context: Context): Boolean {
        if ((lockDepth.get() ?: 0) > 0) {
            cleanupTransientLocked(context)
            return true
        }
        return synchronized(processLock) {
            val lockFile = RuntimePaths.externalResourcesLockFile(context)
            val parent = lockFile.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return@synchronized false
            }
            if (lockFile.exists() && !lockFile.isFile) {
                return@synchronized false
            }
            RandomAccessFile(lockFile, "rw").use { randomAccessFile ->
                val lock = randomAccessFile.channel.tryLock() ?: return@synchronized false
                lock.use {
                    lockDepth.set(1)
                    try {
                        cleanupTransientLocked(context)
                        true
                    } finally {
                        lockDepth.remove()
                    }
                }
            }
        }
    }

    private fun cleanupTransientLocked(context: Context) {
        RuntimePaths.externalResourcesStagingRoot(context).listFiles().orEmpty()
            .forEach(::deleteTreeForCleanup)
        RuntimePaths.externalResourcesQuarantineRoot(context).listFiles().orEmpty()
            .forEach(::deleteTreeForCleanup)
    }

    private data class ActivePointer(val packId: String)

    private fun ensureRepositoryDirs(context: Context) {
        val root = RuntimePaths.externalResourcesRoot(context)
        if (root.exists() && !root.isDirectory) {
            val displaced = File(
                root.parentFile ?: throw IOException("External resource repository has no parent"),
                "${root.name}.corrupt-${newOperationId()}"
            )
            if (!root.renameTo(displaced)) {
                throw IOException("External resource repository is not a directory: ${root.absolutePath}")
            }
        }
        listOf(
            root,
            RuntimePaths.externalResourcesGenerationsDir(context),
            RuntimePaths.externalResourcesStagingRoot(context),
            RuntimePaths.externalResourcesQuarantineRoot(context)
        ).forEach { directory ->
            if (directory.exists() && !directory.isDirectory) {
                val displaced = File(
                    directory.parentFile ?: throw IOException("Resource pack path has no parent"),
                    "${directory.name}.corrupt-${newOperationId()}"
                )
                if (!directory.renameTo(displaced)) {
                    throw IOException("Resource pack path is not a directory: ${directory.absolutePath}")
                }
            }
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Failed to create resource pack directory: ${directory.absolutePath}")
            }
            if (!directory.isDirectory) {
                throw IOException("Resource pack path is not a directory: ${directory.absolutePath}")
            }
        }
    }

    private fun cleanupInterruptedArtifacts(context: Context) {
        val root = RuntimePaths.externalResourcesRoot(context)
        val stagingRoot = RuntimePaths.externalResourcesStagingRoot(context)
        stagingRoot.listFiles().orEmpty().forEach(::deleteTreeForCleanup)
        root.listFiles().orEmpty()
            .filter { file ->
                file.name.startsWith(ROOT_STAGING_PREFIX) ||
                    file.name.startsWith(ROOT_MIGRATION_PREFIX) ||
                    file.name == ".lock" ||
                    file.name.startsWith(".active.properties.") ||
                    file.name.startsWith(".state.properties.")
            }
            .forEach { file ->
                if (file.name == ".lock" ||
                    file.name.startsWith(".active.properties.") ||
                    file.name.startsWith(".state.properties.")
                ) {
                    deleteTreeForCleanup(file)
                } else {
                    quarantineOrDelete(file)
                }
            }
        root.parentFile?.listFiles().orEmpty()
            .filter { file -> file.name.startsWith(ROOT_MIGRATION_PREFIX) }
            .forEach(::quarantineOrDelete)
        RuntimePaths.externalResourcesGenerationsDir(context).listFiles().orEmpty()
            .filter { file -> file.name.startsWith(GENERATION_INSTALLING_PREFIX) }
            .forEach(::quarantineOrDelete)
    }

    private fun findNewestValidGeneration(context: Context): File? {
        val expectedVersion = BuildConfig.RESOURCE_PACK_VERSION.trim()
        return RuntimePaths.externalResourcesGenerationsDir(context).listFiles().orEmpty()
            .asSequence()
            .filter { file -> file.isDirectory && ResourcePackArchive.isSafePackId(file.name) }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            .firstOrNull {
                file -> ResourcePackArchive.validateGeneration(file, expectedVersion).issues.isEmpty()
            }
    }

    private fun importLegacyResources(context: Context) {
        val expectedVersion = BuildConfig.RESOURCE_PACK_VERSION.trim()
        val legacyRoots = legacyCandidateRoots(context)
        val candidates = legacyRoots
            .mapNotNull { root ->
                val contentRoot = resolveLegacyContentRoot(root) ?: return@mapNotNull null
                val markerVersion = ResourcePackArchive.readMarkerProperty(contentRoot, "version")
                if (markerVersion != expectedVersion) return@mapNotNull null
                val missing = ResourcePackContract.collectMissingContent(contentRoot)
                if (missing.isNotEmpty()) return@mapNotNull null
                contentRoot
            }
            .distinctBy { it.canonicalPath }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.absolutePath })
        val source = candidates.firstOrNull()
        if (source == null) {
            legacyRoots.mapNotNull(::resolveLegacyContentRoot)
                .distinctBy { it.canonicalPath }
                .filter { root -> root.exists() }
                .forEach { file ->
                    quarantineOrDelete(file, RuntimePaths.externalResourcesRoot(context))
                }
            return
        }

        val root = RuntimePaths.externalResourcesRoot(context)
        val operationId = newOperationId()
        val operationDir = File(RuntimePaths.externalResourcesStagingRoot(context), "legacy-$operationId")
        val copiedDir = File(operationDir, "extracted")
        prepareDirectory(operationDir)
        writeState(root, operationId, "migrating", source.absolutePath, null)
        try {
            copyLegacyContent(source, copiedDir)
            val manifest = ResourcePackArchive.buildManifest(copiedDir, expectedVersion)
            ResourcePackArchive.writeGenerationMetadata(copiedDir, manifest)
            materializeAndActivate(context, copiedDir, manifest, operationId)
            clearDerivedInstallMarkers(context)
            quarantineOrDelete(source, root)
            writeState(root, operationId, "migrated", source.absolutePath, null)
        } catch (error: Throwable) {
            writeState(root, operationId, "migration_failed", source.absolutePath, summarizeError(error))
            throw error
        } finally {
            deleteTreeForCleanup(operationDir)
        }
    }

    private fun quarantineLegacyResources(context: Context, repositoryRoot: File) {
        val active = readActivePointer(repositoryRoot)?.packId
        val legacyRoots = legacyCandidateRoots(context)
        legacyRoots.mapNotNull(::resolveLegacyContentRoot)
            .distinctBy { it.canonicalPath }
            .filter { legacy ->
                active == null || !samePath(legacy, generationDir(repositoryRoot, active))
            }
            .filter { legacy -> legacy.exists() }
            .forEach { legacy -> quarantineOrDelete(legacy, repositoryRoot) }
        legacyRoots.map { root -> if (root.name == "current") root.parentFile else root }
            .filterNotNull()
            .distinctBy { it.canonicalPath }
            .flatMap { parent ->
                parent.listFiles().orEmpty().filter { file ->
                    file.name == "previous" ||
                        file.name.startsWith("staging-") ||
                        file.name.startsWith("bundled-staging-") ||
                        file.name.startsWith(".migration-") ||
                        file.name.startsWith(ROOT_MIGRATION_PREFIX)
                }
            }
            .distinctBy { it.canonicalPath }
            .forEach { artifact -> quarantineOrDelete(artifact, repositoryRoot) }
    }

    private fun legacyCandidateRoots(context: Context): List<File> {
        val roots = LinkedHashMap<String, File>()
        fun add(file: File) {
            val key = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
            roots.putIfAbsent(key, file)
        }

        add(RuntimePaths.externalResourcesRoot(context))
        add(RuntimePaths.externalResourcesCurrentDir(context))
        add(RuntimePaths.legacyInternalExternalResourcesRoot(context))
        add(File(RuntimePaths.legacyInternalExternalResourcesRoot(context), "current"))
        return roots.values.toList()
    }

    private fun resolveLegacyContentRoot(root: File): File? {
        if (!root.exists()) return null
        if (looksLikeLegacyContentRoot(root)) {
            return root
        }
        val current = File(root, "current")
        if (looksLikeLegacyContentRoot(current)) {
            return current
        }
        return null
    }

    private fun looksLikeLegacyContentRoot(root: File): Boolean {
        return root.exists() && (
            File(root, "assets").exists() ||
                File(root, "lib").exists() ||
                File(root, ResourcePackContract.INSTALL_MARKER_FILE_NAME).isFile
            )
    }

    private fun copyLegacyContent(source: File, target: File) {
        if (File(source, "assets").isDirectory && File(source, "lib").isDirectory) {
            copyDirectory(File(source, "assets"), File(target, "assets"))
            copyDirectory(File(source, "lib"), File(target, "lib"))
            return
        }
        copyDirectory(source, target)
    }

    private fun materializeAndActivate(
        context: Context,
        extractedDir: File,
        manifest: ResourcePackManifest,
        operationId: String
    ) {
        val root = RuntimePaths.externalResourcesRoot(context)
        val generationsDir = RuntimePaths.externalResourcesGenerationsDir(context)
        val installingDir = File(generationsDir, "$GENERATION_INSTALLING_PREFIX${manifest.packId}-$operationId")
        deleteTreeForCleanup(installingDir)
        if (!extractedDir.renameTo(installingDir)) {
            copyDirectory(extractedDir, installingDir)
        }

        val installingValidation = ResourcePackArchive.validateGeneration(
            installingDir,
            manifest.resourcePackVersion
        )
        if (installingValidation.issues.isNotEmpty()) {
            quarantineOrDelete(installingDir)
            throw IOException(
                "Prepared resource generation failed validation: " +
                    installingValidation.issues.take(MAX_DIAGNOSTIC_ISSUES).joinToString(", ")
            )
        }

        val finalDir = generationDir(root, manifest.packId)
        if (finalDir.exists()) {
            val existing = ResourcePackArchive.validateGeneration(finalDir, manifest.resourcePackVersion)
            if (existing.issues.isEmpty()) {
                deleteTreeForCleanup(installingDir)
            } else {
                quarantineOrDelete(finalDir)
                moveDirectory(installingDir, finalDir)
            }
        } else {
            moveDirectory(installingDir, finalDir)
        }

        val previousPointer = readActivePointer(root)
        try {
            activatePointer(root, manifest.packId)
            val activeValidation = ResourcePackArchive.validateGeneration(finalDir, manifest.resourcePackVersion)
            if (activeValidation.issues.isNotEmpty()) {
                restorePointer(root, previousPointer)
                throw IOException(
                    "Activated resource generation failed validation: " +
                        activeValidation.issues.take(MAX_DIAGNOSTIC_ISSUES).joinToString(", ")
                )
            }
        } catch (error: Throwable) {
            restorePointer(root, previousPointer)
            throw error
        }
    }

    private fun garbageCollectGenerations(context: Context, activePackId: String) {
        val generations = RuntimePaths.externalResourcesGenerationsDir(context).listFiles().orEmpty()
            .asSequence()
            .filter { file -> file.isDirectory && ResourcePackArchive.isSafePackId(file.name) }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            .toList()
        val keep = LinkedHashSet<String>()
        keep += activePackId
        generations.forEach { file ->
            if (keep.size < GENERATIONS_TO_KEEP) {
                keep += file.name
            }
        }
        generations.filter { file -> file.name !in keep }.forEach(::deleteTreeForCleanup)
    }

    private fun activatePointer(root: File, packId: String) {
        if (!ResourcePackArchive.isSafePackId(packId)) {
            throw IOException("Invalid resource pack id: $packId")
        }
        val pointer = Properties()
        pointer["schemaVersion"] = ACTIVE_SCHEMA_VERSION.toString()
        pointer[ACTIVE_PACK_ID_KEY] = packId
        pointer["activatedAt"] = System.currentTimeMillis().toString()
        writePropertiesAtomically(activePointerFile(root), pointer)
    }

    private fun restorePointer(root: File, pointer: ActivePointer?) {
        val pointerFile = activePointerFile(root)
        if (pointer == null) {
            deleteTreeForCleanup(pointerFile)
            if (pointerFile.exists()) {
                throw IOException("Failed to clear invalid active resource pointer")
            }
            return
        }
        activatePointer(root, pointer.packId)
    }

    private fun readActivePointer(root: File): ActivePointer? {
        val file = activePointerFile(root)
        if (!file.isFile) return null
        val properties = Properties()
        return runCatching {
            file.reader(StandardCharsets.UTF_8).use(properties::load)
            val schema = properties.getProperty("schemaVersion")?.toIntOrNull()
            val packId = properties.getProperty(ACTIVE_PACK_ID_KEY)?.trim().orEmpty()
            if (schema != ACTIVE_SCHEMA_VERSION || !ResourcePackArchive.isSafePackId(packId)) {
                null
            } else {
                ActivePointer(packId)
            }
        }.getOrNull()
    }

    private fun writeState(root: File, operationId: String, phase: String, source: String, error: String?) {
        val properties = Properties()
        properties[STATE_OPERATION_ID_KEY] = operationId
        properties[STATE_PHASE_KEY] = phase
        properties[STATE_SOURCE_KEY] = source
        properties[STATE_UPDATED_AT_KEY] = System.currentTimeMillis().toString()
        error?.let { properties[STATE_ERROR_KEY] = it }
        runCatching {
            writePropertiesAtomically(stateFile(root), properties)
        }
    }

    private fun readState(root: File): Properties? {
        val file = stateFile(root)
        if (!file.isFile) return null
        return runCatching {
            Properties().also { properties ->
                file.reader(StandardCharsets.UTF_8).use(properties::load)
            }
        }.getOrNull()
    }

    private fun writePropertiesAtomically(file: File, properties: Properties) {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create metadata directory: ${parent.absolutePath}")
        }
        val temporary = File(parent, ".${file.name}.${newOperationId()}.tmp")
        try {
            FileOutputStream(temporary, false).use { output ->
                properties.store(output, null)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Throwable) {
                try {
                    Files.move(
                        temporary.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (_: Throwable) {
                    if (file.exists() && !file.delete()) {
                        throw IOException("Failed to replace metadata file: ${file.absolutePath}")
                    }
                    if (!temporary.renameTo(file)) {
                        throw IOException("Failed to install metadata file: ${file.absolutePath}")
                    }
                }
            }
        } finally {
            deleteTreeForCleanup(temporary)
        }
    }

    private fun activePointerFile(root: File): File = File(root, "active.properties")

    private fun stateFile(root: File): File = File(root, "state.properties")

    private fun generationDir(root: File, packId: String): File =
        File(File(root, "generations"), packId)

    private fun moveDirectory(source: File, target: File) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create generation directory: ${parent.absolutePath}")
        }
        if (source.renameTo(target)) return
        copyDirectory(source, target)
        if (ResourcePackArchive.validateGeneration(target, BuildConfig.RESOURCE_PACK_VERSION.trim())
                .issues.isNotEmpty()
        ) {
            deleteTreeForCleanup(target)
            throw IOException("Failed to validate copied resource generation: ${target.absolutePath}")
        }
        deleteTreeForCleanup(source)
    }

    private fun copyDirectory(source: File, target: File) {
        if (!source.exists()) {
            throw IOException("Resource copy source does not exist: ${source.absolutePath}")
        }
        if (source.isDirectory) {
            if (!target.exists() && !target.mkdirs()) {
                throw IOException("Failed to create directory: ${target.absolutePath}")
            }
            source.listFiles()?.forEach { child -> copyDirectory(child, File(target, child.name)) }
            return
        }
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        FileInputStream(source).use { input ->
            FileOutputStream(target, false).use { output -> input.copyTo(output) }
        }
        target.setLastModified(source.lastModified())
    }

    private fun prepareDirectory(directory: File) {
        val parent = directory.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        if (directory.exists()) {
            if (!directory.isDirectory) {
                throw IOException("Resource staging path is not a directory: ${directory.absolutePath}")
            }
            deleteTreeForCleanup(directory)
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Failed to create directory: ${directory.absolutePath}")
        }
    }

    private fun quarantineOrDelete(file: File, repositoryRoot: File? = null) {
        if (!file.exists()) return
        if (repositoryRoot != null && samePath(file, repositoryRoot)) {
            listOf(
                "assets",
                "lib",
                ResourcePackContract.INSTALL_MARKER_FILE_NAME,
                ResourcePackContract.MANIFEST_FILE_NAME
            ).map { childName -> File(file, childName) }
                .filter { child -> child.exists() }
                .forEach { child -> quarantineOrDelete(child, repositoryRoot) }
            return
        }
        val quarantineRoot = file.parentFile?.let { parent ->
            if (parent.name == "quarantine") parent else null
        }
        if (quarantineRoot != null) {
            deleteTreeForCleanup(file)
            return
        }
        val root = repositoryRoot ?: locateRepositoryRoot(file)
        if (root == null) {
            deleteTreeForCleanup(file)
            return
        }
        val destinationRoot = File(root, "quarantine")
        if (!destinationRoot.exists()) destinationRoot.mkdirs()
        val destination = File(destinationRoot, "${file.name}-${System.currentTimeMillis()}-${newOperationId()}")
        if (file.renameTo(destination)) return
        runCatching {
            copyDirectory(file, destination)
            deleteTreeForCleanup(file)
        }.onFailure {
            deleteTreeForCleanup(destination)
        }
    }

    private fun locateRepositoryRoot(file: File): File? {
        var current: File? = file.absoluteFile
        while (current != null) {
            if (current.name == "external_resources") return current
            current = current.parentFile
        }
        return null
    }

    private fun samePath(left: File, right: File): Boolean {
        return runCatching { left.canonicalPath == right.canonicalPath }
            .getOrElse { left.absolutePath == right.absolutePath }
    }

    private fun deleteTreeForCleanup(file: File?) {
        if (file == null || !file.exists()) return
        val wasInterrupted = Thread.interrupted()
        try {
            FileTreeCleaner.deleteRecursively(file)
            if (file.exists()) {
                FileTreeCleaner.deleteRecursively(file)
            }
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    private fun listChildren(directory: File): String {
        return directory.listFiles()
            ?.joinToString("|") { file -> file.name }
            .orEmpty()
            .ifBlank { "none" }
    }

    private fun buildFileState(file: File): String {
        return when {
            !file.exists() -> "missing"
            file.isDirectory -> "directory"
            else -> "file:${file.length()}"
        }
    }

    private fun readNativeTargetMarker(context: Context): String? {
        val marker = RuntimePaths.externalNativeLibMarkerFile(context)
        return runCatching {
            marker.takeIf { file -> file.isFile }?.readText(StandardCharsets.UTF_8)?.trim()
        }.getOrNull()
    }

    private fun clearDerivedInstallMarkers(context: Context) {
        deleteTreeForCleanup(RuntimePaths.componentInstallMarkerFile(context))
        deleteTreeForCleanup(RuntimePaths.runtimeInstallMarkerFile(context))
        val nativeDir = RuntimePaths.externalNativeLibDir(context)
        ResourcePackContract.nativeLibraries.forEach { libraryName ->
            deleteTreeForCleanup(File(nativeDir, libraryName))
        }
        deleteTreeForCleanup(RuntimePaths.externalNativeLibMarkerFile(context))
    }

    private fun readSmallFile(file: File): String {
        return runCatching {
            file.takeIf { candidate -> candidate.isFile }
                ?.readText(StandardCharsets.UTF_8)
                ?.replace('\n', '|')
                ?.replace('\r', ' ')
                ?.trim()
                ?.take(512)
                .orEmpty()
        }.getOrDefault("none").ifBlank { "none" }
    }

    private fun embeddedArchiveExists(context: Context): Boolean {
        return runCatching {
            context.assets.open(ResourcePackContract.EMBEDDED_ARCHIVE_ASSET_PATH).use { }
            true
        }.getOrDefault(false)
    }

    private fun summarizeError(error: Throwable): String =
        error.message?.trim()?.takeIf(String::isNotEmpty) ?: error.javaClass.simpleName

    private fun sanitize(value: String): String =
        value.replace('=', ':').replace('\n', ' ').replace('\r', ' ').trim()

    private fun newOperationId(): String =
        "${System.currentTimeMillis()}-${operationCounter.incrementAndGet()}"

    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw IOException("External resource preparation cancelled")
        }
    }

}
