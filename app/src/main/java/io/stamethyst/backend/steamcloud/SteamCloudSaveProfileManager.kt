package io.stamethyst.backend.steamcloud

import android.content.Context
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RuntimePaths
import io.stamethyst.config.SteamCloudSaveMode
import io.stamethyst.ui.settings.files.SettingsSaveBackupService
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object SteamCloudSaveProfileManager {
    private const val PROFILE_ROOT_DIR_NAME = "steam-cloud-save-profiles"
    private const val CLOUD_ACCOUNT_ROOT_DIR_NAME = "cloud-accounts"
    private const val UNSCOPED_CLOUD_PROFILE_ID = "unscoped"
    private const val PROFILE_INITIALIZED_FILE_NAME = ".initialized"
    private const val PROFILE_LAYOUT_MIGRATED_FILE_NAME = ".account-layout-v1"
    // History belongs to a local save slot, but is not part of Steam's remote sync roots.
    private val profileRoots = SteamCloudRootKind.entries.map { it.directoryName } + "runs"

    @Throws(IOException::class)
    fun switchMode(
        context: Context,
        fromMode: SteamCloudSaveMode,
        toMode: SteamCloudSaveMode,
    ) {
        SteamCloudOperationMutex.runExclusive(context) {
            SteamCloudLiveSaveLease.runMutation(context) {
                migrateLegacyProfilesExclusive(context)
                if (LauncherConfig.readSteamCloudSaveMode(context) != fromMode) {
                    throw SteamCloudStalePlanException("Save mode changed before the profile switch; refresh and try again.")
                }
                if (fromMode == toMode) {
                    return@runMutation
                }

                val cloudProfileId = resolveCloudProfileId(context)
                saveActiveProfileExclusive(context, fromMode, cloudProfileId)
                SteamCloudLocalStateMutex.runExclusive(context) { SteamCloudControlStore.locked(context) {
                    restoreProfileExclusive(context, toMode, cloudProfileId,
                        SteamCloudControlStore.read(context).copy(mode = toMode.persistedValue))
                } }
            }
        }
    }

    @Throws(IOException::class)
    fun saveActiveProfile(context: Context, mode: SteamCloudSaveMode) {
        SteamCloudOperationMutex.runExclusive(context) {
            SteamCloudLiveSaveLease.runMutation(context) {
                migrateLegacyProfilesExclusive(context)
                saveActiveProfileExclusive(context, mode, resolveCloudProfileId(context))
            }
        }
    }

    @Throws(IOException::class)
    fun restoreProfile(context: Context, mode: SteamCloudSaveMode) {
        SteamCloudOperationMutex.runExclusive(context) {
            SteamCloudLiveSaveLease.runMutation(context) {
                migrateLegacyProfilesExclusive(context)
                restoreProfileExclusive(context, mode, resolveCloudProfileId(context))
            }
        }
    }

    @Throws(IOException::class)
    fun completeDeferredIndependentSwitch(context: Context) {
        SteamCloudOperationMutex.runExclusive(context) {
            if (!LauncherConfig.isSteamCloudIndependentSwitchPending(context)) {
                return@runExclusive
            }
            SteamCloudLiveSaveLease.runMutation(context) {
                migrateLegacyProfilesExclusive(context)
                val fromMode = LauncherConfig.readSteamCloudSaveMode(context)
                val pendingCloudProfileId = resolveCloudProfileId(
                    context,
                    LauncherConfig.readSteamCloudPendingProfileSteamId(context),
                )
                if (fromMode == SteamCloudSaveMode.INDEPENDENT) {
                    LauncherConfig.completeSteamCloudIndependentSwitch(context)
                    return@runMutation
                }

                saveActiveProfileExclusive(context, fromMode, pendingCloudProfileId)
                SteamCloudLocalStateMutex.runExclusive(context) { SteamCloudControlStore.locked(context) {
                    restoreProfileExclusive(context, SteamCloudSaveMode.INDEPENDENT, pendingCloudProfileId,
                        SteamCloudControlStore.read(context).copy(mode = SteamCloudSaveMode.INDEPENDENT.persistedValue,
                            independentSwitchPending = false, pendingSteamId = ""))
                } }
            }
        }
    }

    fun profileRoot(context: Context, mode: SteamCloudSaveMode): File =
        profileDir(context, mode, resolveCloudProfileId(context))

    fun cloudSlot(context: Context, steamId: String): File {
        SteamCloudRemoteCommitStore.checkedSteamId(steamId)
        return profileDir(context, SteamCloudSaveMode.STEAM_CLOUD, steamId)
    }

    /** The slot is restorable data, not a cache flag. Compare all managed files on disk
     * (including unexpected/excluded files), but never inspect the slot-local runs/ history. */
    fun cloudSlotMatches(target: File, expected: List<SteamCloudLocalFileSnapshotEntry>): Boolean {
        val marker = File(target, PROFILE_INITIALIZED_FILE_NAME)
        return try {
            if (marker.canonicalFile != marker.absoluteFile || !marker.isFile || marker.length() != 3L ||
                marker.readText() != "v2\n") return false
            val directories = hashSetOf<String>()
            val actual = SteamCloudLocalSnapshotCollector.collect(target, directories)
            if (actual.size != expected.size || actual.indices.any { i ->
                actual[i].localRelativePath != expected[i].localRelativePath ||
                    !SteamCloudSyncPlanner.sameLocal(actual[i], expected[i])
            }) return false
            // Staging omits empty directories, including excluded subtrees.
            SteamCloudRootKind.entries.filter { File(target, it.directoryName).exists() }.forEach { directories.add(it.directoryName) }
            actual.forEach {
                var path = it.localRelativePath
                while ('/' in path) {
                    path = path.substringBeforeLast('/')
                    if (!directories.remove(path)) break
                }
            }
            directories.isEmpty()
        } catch (_: IOException) { false }
    }

    /** Profile content and its initialization marker commit with pulled saves and mode metadata. */
    fun stageCloudProfile(target: File, blacklist: Set<String>, work: File, preparedRoot: File): List<SteamCloudPathReplacement> {
        val staged = File(work, "cloud-profile")
        val replacements = SteamCloudRootKind.entries.map { kind ->
            val source = File(preparedRoot, kind.directoryName)
            val destination = File(staged, kind.directoryName)
            if (source.exists()) copyPathExcluding(source, destination,
                SteamCloudSyncBlacklist.relativeSuffixesForRoot(kind, blacklist))
            SteamCloudPathReplacement(destination.takeIf { it.exists() }, File(target, kind.directoryName))
        }
        val marker = File(staged, PROFILE_INITIALIZED_FILE_NAME)
        SteamCloudAtomicFileStore.writeTextWithoutBackup(marker, "v2\n")
        return replacements + SteamCloudPathReplacement(marker, File(target, PROFILE_INITIALIZED_FILE_NAME))
    }

    fun stageLiveRoot(context: Context, work: File, preparedRoot: File): List<SteamCloudPathReplacement> {
        val staging = File(work, "live-install")
        val live = RuntimePaths.stsRoot(context)
        val blacklist = LauncherConfig.readSteamCloudSyncBlacklistPaths(context)
        return SteamCloudRootKind.entries.map { kind ->
            val destination = File(staging, kind.directoryName)
            val source = File(preparedRoot, kind.directoryName)
            val excluded = SteamCloudSyncBlacklist.relativeSuffixesForRoot(kind, blacklist)
            if (source.exists()) copyPathExcluding(source, destination, excluded)
            copySelectedPaths(File(live, kind.directoryName), destination, excluded)
            SteamCloudPathReplacement(destination.takeIf { it.exists() }, File(live, kind.directoryName))
        }
    }

    fun profileIsInitialized(context: Context, mode: SteamCloudSaveMode): Boolean {
        return isProfileInitialized(profileRoot(context, mode))
    }

    fun profileHasRegularFiles(context: Context, mode: SteamCloudSaveMode): Boolean {
        val syncBlacklist = LauncherConfig.readSteamCloudSyncBlacklistPaths(context)
        val cloudProfileId = resolveCloudProfileId(context)
        return SteamCloudRootKind.entries.any { rootKind ->
            containsRegularFile(
                file = File(profileDir(context, mode, cloudProfileId), rootKind.directoryName),
                excludedRelativeSuffixes = SteamCloudSyncBlacklist.relativeSuffixesForRoot(
                    rootKind = rootKind,
                    configuredBlacklist = syncBlacklist,
                ),
            )
        }
    }

    private fun saveActiveProfileExclusive(
        context: Context,
        mode: SteamCloudSaveMode,
        cloudProfileId: String,
    ) {
        val syncBlacklist = LauncherConfig.readSteamCloudSyncBlacklistPaths(context)
        applyProfileTransaction(
            context = context,
            targetRoot = profileDir(context, mode, cloudProfileId),
            markInitialized = true,
        ) { stagingRoot, rootKind ->
            val source = File(RuntimePaths.stsRoot(context), rootKind)
            if (source.exists()) {
                copyPathExcluding(
                    source = source,
                    target = File(stagingRoot, rootKind),
                    excludedRelativeSuffixes = profileExclusions(rootKind, syncBlacklist),
                )
            }
        }
    }

    private fun restoreProfileExclusive(
        context: Context,
        mode: SteamCloudSaveMode,
        cloudProfileId: String,
        controlState: SteamCloudControlStore.State? = null,
    ) {
        val syncBlacklist = LauncherConfig.readSteamCloudSyncBlacklistPaths(context)
        val liveRoot = RuntimePaths.stsRoot(context)
        val sourceProfile = profileDir(context, mode, cloudProfileId)
        if (!isProfileInitialized(sourceProfile)) {
            throw IOException(
                if (mode == SteamCloudSaveMode.STEAM_CLOUD) {
                    "Steam Cloud profile for this account is not initialized. Pull Steam Cloud first."
                } else {
                    "Independent save profile is not initialized."
                }
            )
        }
        applyProfileTransaction(
            context = context,
            targetRoot = liveRoot,
            markInitialized = false,
            controlState = controlState,
        ) { stagingRoot, rootKind ->
            val stagedRoot = File(stagingRoot, rootKind)
            val source = File(sourceProfile, rootKind)
            if (source.exists()) {
                copyPathExcluding(
                    source = source,
                    target = stagedRoot,
                    excludedRelativeSuffixes = profileExclusions(rootKind, syncBlacklist),
                )
            }
            copySelectedPaths(
                sourceRoot = File(liveRoot, rootKind),
                targetRoot = stagedRoot,
                relativeSuffixes = profileExclusions(rootKind, syncBlacklist),
            )
        }
    }

    private fun applyProfileTransaction(
        context: Context,
        targetRoot: File,
        markInitialized: Boolean,
        controlState: SteamCloudControlStore.State? = null,
        buildStagingRoot: (File, String) -> Unit,
    ) {
        val transactionRoot = File(
            RuntimePaths.storageRoot(context),
            ".steam-cloud-profile-${System.currentTimeMillis()}-${System.nanoTime()}",
        )
        val stagingRoot = File(transactionRoot, "staging")
        if (!stagingRoot.mkdirs()) {
            throw IOException("Failed to create profile staging directory: ${stagingRoot.absolutePath}")
        }

        try {
            profileRoots.forEach { rootKind ->
                buildStagingRoot(stagingRoot, rootKind)
            }
            val markerStagingPath = File(stagingRoot, ".initialized.new")
            if (markInitialized) {
                SteamCloudAtomicFileStore.writeTextWithoutBackup(markerStagingPath, "v1\n")
            }
            val replacements = buildList {
                addAll(
                    profileRoots.map { rootKind ->
                        SteamCloudPathReplacement(
                            stagedPath = File(stagingRoot, rootKind).takeIf { it.exists() },
                            targetPath = File(targetRoot, rootKind),
                        )
                    }
                )
                if (markInitialized) {
                    add(
                        SteamCloudPathReplacement(
                            stagedPath = markerStagingPath,
                            targetPath = File(targetRoot, PROFILE_INITIALIZED_FILE_NAME),
                        )
                    )
                }
                controlState?.let { state ->
                    val staged = File(stagingRoot, "control.json")
                    SteamCloudControlStore.write(staged, state)
                    add(SteamCloudControlStore.modeReplacement(context, staged))
                }
            }
            SteamCloudLocalStateMutex.runExclusive(context) { SteamCloudFileTransaction.execute(
                parent = File(SteamCloudManifestStore.outputDir(context), "transactions-v2"),
                allowedRoot = RuntimePaths.storageRoot(context),
                replacements = replacements,
            ) }
        } finally {
            transactionRoot.deleteRecursively()
        }
    }

    private fun profileDir(
        context: Context,
        mode: SteamCloudSaveMode,
        cloudProfileId: String,
    ): File {
        val profileRoot = File(RuntimePaths.storageRoot(context), PROFILE_ROOT_DIR_NAME)
        return when (mode) {
            SteamCloudSaveMode.INDEPENDENT -> File(profileRoot, mode.persistedValue)
            SteamCloudSaveMode.STEAM_CLOUD ->
                File(File(profileRoot, CLOUD_ACCOUNT_ROOT_DIR_NAME), cloudProfileId)
        }
    }

    private fun resolveCloudProfileId(context: Context, explicitSteamId: String = ""): String {
        val steamId = explicitSteamId.trim().ifBlank {
            SteamCloudAuthStore.readProfileAuthMaterial(context)?.steamId64.orEmpty()
        }
        return steamId.takeIf { value ->
            value.toULongOrNull()?.let { it > 0uL } == true
        } ?: UNSCOPED_CLOUD_PROFILE_ID
    }

    private fun isProfileInitialized(profileRoot: File): Boolean =
        File(profileRoot, PROFILE_INITIALIZED_FILE_NAME).isFile

    private fun migrateLegacyProfilesExclusive(context: Context) {
        SteamCloudSyncRepository.recoverLocal(context)
        val root = File(RuntimePaths.storageRoot(context), PROFILE_ROOT_DIR_NAME)
        val migrationMarker = File(root, PROFILE_LAYOUT_MIGRATED_FILE_NAME)
        if (migrationMarker.isFile) {
            return
        }

        val independentProfile = File(root, SteamCloudSaveMode.INDEPENDENT.persistedValue)
        if (!isProfileInitialized(independentProfile) && profileContainsManagedPath(independentProfile)) {
            SteamCloudAtomicFileStore.writeTextWithoutBackup(
                File(independentProfile, PROFILE_INITIALIZED_FILE_NAME),
                "legacy-v1\n",
            )
        }

        val auth = SteamCloudAuthStore.readProfileAuthMaterial(context)
        val legacyCloudProfile = File(root, SteamCloudSaveMode.STEAM_CLOUD.persistedValue)
        if (profileContainsManagedPath(legacyCloudProfile)) {
            if (LauncherConfig.readSteamCloudSaveMode(context) == SteamCloudSaveMode.STEAM_CLOUD &&
                auth != null
            ) {
                val accountProfile = profileDir(context, SteamCloudSaveMode.STEAM_CLOUD, auth.steamId64)
                if (!isProfileInitialized(accountProfile)) {
                    applyProfileTransaction(
                        context = context,
                        targetRoot = accountProfile,
                        markInitialized = true,
                    ) { stagingRoot, rootKind ->
                        val source = File(legacyCloudProfile, rootKind)
                        if (source.exists()) {
                            SteamCloudFileTransaction.copyPath(
                                source,
                                File(stagingRoot, rootKind),
                            )
                        }
                    }
                }
            } else {
                val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                SettingsSaveBackupService.backupSaveProfileToDownloads(
                    host = context,
                    sourceRoot = legacyCloudProfile,
                    backupFileName = "legacy-unscoped-steam-cloud-profile-$timestamp.zip",
                    relativeSubdirectory = LEGACY_PROFILE_BACKUP_SUBDIRECTORY,
                )
            }
        }

        SteamCloudAtomicFileStore.writeTextWithoutBackup(migrationMarker, "v1\n")
    }

    private fun profileContainsManagedPath(profileRoot: File): Boolean =
        SteamCloudRootKind.entries.any { rootKind ->
            File(profileRoot, rootKind.directoryName).exists()
        }

    private const val LEGACY_PROFILE_BACKUP_SUBDIRECTORY = "SlayTheAmethystBackup"

    private fun profileExclusions(directory: String, blacklist: Set<String>): Set<String> {
        val kind = SteamCloudRootKind.entries.firstOrNull { it.directoryName == directory } ?: return emptySet()
        return SteamCloudSyncBlacklist.relativeSuffixesForRoot(kind, blacklist)
    }

    private fun containsRegularFile(
        file: File,
        excludedRelativeSuffixes: Set<String>,
        relativeSuffix: String = "",
    ): Boolean {
        if (Files.isSymbolicLink(file.toPath())) throw IOException("Save symlinks are not supported: $file")
        if (!file.exists()) {
            return false
        }
        val normalizedRelativeSuffix = relativeSuffix.replace('\\', '/')
        if (normalizedRelativeSuffix.isNotBlank() &&
            normalizedRelativeSuffix in excludedRelativeSuffixes
        ) {
            return false
        }
        if (file.isFile) {
            return true
        }
        return file.listFiles()?.any { child ->
            val childRelativeSuffix = if (normalizedRelativeSuffix.isBlank()) {
                child.name
            } else {
                "$normalizedRelativeSuffix/${child.name}"
            }
            containsRegularFile(child, excludedRelativeSuffixes, childRelativeSuffix)
        } == true
    }

    private fun copyPathExcluding(
        source: File,
        target: File,
        excludedRelativeSuffixes: Set<String>,
        relativeSuffix: String = "",
    ): Boolean {
        if (Files.isSymbolicLink(source.toPath())) throw IOException("Save symlinks are not supported: $source")
        val normalizedRelativeSuffix = relativeSuffix.replace('\\', '/')
        if (normalizedRelativeSuffix.isNotBlank() &&
            normalizedRelativeSuffix in excludedRelativeSuffixes
        ) {
            return false
        }
        if (source.isDirectory) {
            var copiedAny = false
            val children = source.listFiles()
                ?: throw IOException("Failed to enumerate profile directory: ${source.absolutePath}")
            children.forEach { child ->
                val childRelativeSuffix = if (normalizedRelativeSuffix.isBlank()) {
                    child.name
                } else {
                    "$normalizedRelativeSuffix/${child.name}"
                }
                copiedAny = copyPathExcluding(
                    source = child,
                    target = File(target, child.name),
                    excludedRelativeSuffixes = excludedRelativeSuffixes,
                    relativeSuffix = childRelativeSuffix,
                ) || copiedAny
            }
            return copiedAny
        }
        SteamCloudFileTransaction.copyPath(source, target)
        return true
    }

    private fun copySelectedPaths(
        sourceRoot: File,
        targetRoot: File,
        relativeSuffixes: Set<String>,
    ) {
        relativeSuffixes.forEach { relativeSuffix ->
            val relativeFile = relativeSuffix.replace('/', File.separatorChar)
            val source = File(sourceRoot, relativeFile)
            if (!source.exists()) {
                return@forEach
            }
            val target = File(targetRoot, relativeFile)
            if (target.exists() && !target.deleteRecursively()) {
                throw IOException("Failed to replace preserved profile path: ${target.absolutePath}")
            }
            SteamCloudFileTransaction.copyPath(source, target)
        }
    }
}
