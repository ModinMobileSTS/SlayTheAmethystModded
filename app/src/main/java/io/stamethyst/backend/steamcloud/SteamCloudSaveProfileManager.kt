package io.stamethyst.backend.steamcloud

import android.content.Context
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RuntimePaths
import io.stamethyst.config.SteamCloudSaveMode
import io.stamethyst.ui.settings.files.SettingsSaveBackupService
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object SteamCloudSaveProfileManager {
    private const val PROFILE_ROOT_DIR_NAME = "steam-cloud-save-profiles"
    private const val CLOUD_ACCOUNT_ROOT_DIR_NAME = "cloud-accounts"
    private const val UNSCOPED_CLOUD_PROFILE_ID = "unscoped"
    private const val PROFILE_INITIALIZED_FILE_NAME = ".initialized"
    private const val PROFILE_LAYOUT_MIGRATED_FILE_NAME = ".account-layout-v1"

    @Throws(IOException::class)
    fun switchMode(
        context: Context,
        fromMode: SteamCloudSaveMode,
        toMode: SteamCloudSaveMode,
    ) {
        SteamCloudOperationMutex.runExclusive(context) {
            SteamCloudLiveSaveLease.runMutation(context) {
                migrateLegacyProfilesExclusive(context)
                if (fromMode == toMode) {
                    return@runMutation
                }

                val cloudProfileId = resolveCloudProfileId(context)
                saveActiveProfileExclusive(context, fromMode, cloudProfileId)
                restoreProfileExclusive(context, toMode, cloudProfileId)
                try {
                    LauncherConfig.saveSteamCloudSaveMode(context, toMode)
                } catch (error: Throwable) {
                    runCatching { restoreProfileExclusive(context, fromMode, cloudProfileId) }
                        .onFailure(error::addSuppressed)
                    throw error
                }
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
                restoreProfileExclusive(
                    context,
                    SteamCloudSaveMode.INDEPENDENT,
                    pendingCloudProfileId,
                )
                try {
                    LauncherConfig.completeSteamCloudIndependentSwitch(context)
                } catch (error: Throwable) {
                    runCatching {
                        restoreProfileExclusive(context, fromMode, pendingCloudProfileId)
                    }
                        .onFailure(error::addSuppressed)
                    throw error
                }
            }
        }
    }

    fun profileRoot(context: Context, mode: SteamCloudSaveMode): File =
        profileDir(context, mode, resolveCloudProfileId(context))

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
            val source = File(RuntimePaths.stsRoot(context), rootKind.directoryName)
            if (source.exists()) {
                copyPathExcluding(
                    source = source,
                    target = File(stagingRoot, rootKind.directoryName),
                    excludedRelativeSuffixes = SteamCloudSyncBlacklist.relativeSuffixesForRoot(
                        rootKind = rootKind,
                        configuredBlacklist = syncBlacklist,
                    ),
                )
            }
        }
    }

    private fun restoreProfileExclusive(
        context: Context,
        mode: SteamCloudSaveMode,
        cloudProfileId: String,
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
        ) { stagingRoot, rootKind ->
            val stagedRoot = File(stagingRoot, rootKind.directoryName)
            val source = File(sourceProfile, rootKind.directoryName)
            if (source.exists()) {
                copyPathExcluding(
                    source = source,
                    target = stagedRoot,
                    excludedRelativeSuffixes = SteamCloudSyncBlacklist.relativeSuffixesForRoot(
                        rootKind = rootKind,
                        configuredBlacklist = syncBlacklist,
                    ),
                )
            }
            copySelectedPaths(
                sourceRoot = File(liveRoot, rootKind.directoryName),
                targetRoot = stagedRoot,
                relativeSuffixes = SteamCloudSyncBlacklist.relativeSuffixesForRoot(
                    rootKind = rootKind,
                    configuredBlacklist = syncBlacklist,
                ),
            )
        }
    }

    private inline fun applyProfileTransaction(
        context: Context,
        targetRoot: File,
        markInitialized: Boolean,
        buildStagingRoot: (File, SteamCloudRootKind) -> Unit,
    ) {
        val transactionRoot = File(
            RuntimePaths.storageRoot(context),
            ".steam-cloud-profile-${System.currentTimeMillis()}-${System.nanoTime()}",
        )
        val stagingRoot = File(transactionRoot, "staging")
        val rollbackRoot = File(transactionRoot, "rollback")
        if (!stagingRoot.mkdirs()) {
            throw IOException("Failed to create profile staging directory: ${stagingRoot.absolutePath}")
        }

        var preserveRecoveryData = false
        try {
            SteamCloudRootKind.entries.forEach { rootKind ->
                buildStagingRoot(stagingRoot, rootKind)
            }
            val markerStagingPath = File(stagingRoot, ".initialized.new")
            if (markInitialized) {
                SteamCloudAtomicFileStore.writeTextWithoutBackup(markerStagingPath, "v1\n")
            }
            val replacements = buildList {
                if (markInitialized) {
                    add(
                        SteamCloudStagedPathReplacement(
                            stagedPath = File(stagingRoot, ".initialized.removed"),
                            targetPath = File(targetRoot, PROFILE_INITIALIZED_FILE_NAME),
                        )
                    )
                }
                addAll(
                    SteamCloudRootKind.entries.map { rootKind ->
                        SteamCloudStagedPathReplacement(
                            stagedPath = File(stagingRoot, rootKind.directoryName),
                            targetPath = File(targetRoot, rootKind.directoryName),
                        )
                    }
                )
                if (markInitialized) {
                    add(
                        SteamCloudStagedPathReplacement(
                            stagedPath = markerStagingPath,
                            targetPath = File(targetRoot, PROFILE_INITIALIZED_FILE_NAME),
                        )
                    )
                }
            }
            SteamCloudStagedPathStore.apply(
                replacements = replacements,
                rollbackRoot = rollbackRoot,
            )
        } catch (error: Throwable) {
            preserveRecoveryData = error is SteamCloudReconciliationException &&
                error.recoveryDataPreserved
            throw error
        } finally {
            stagingRoot.deleteRecursively()
            if (!preserveRecoveryData) {
                rollbackRoot.deleteRecursively()
                transactionRoot.deleteRecursively()
            }
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
            SteamCloudAuthStore.readAuthMaterial(context)?.steamId64.orEmpty()
        }
        return steamId.takeIf { value ->
            value.toULongOrNull()?.let { it > 0uL } == true
        } ?: UNSCOPED_CLOUD_PROFILE_ID
    }

    private fun isProfileInitialized(profileRoot: File): Boolean =
        File(profileRoot, PROFILE_INITIALIZED_FILE_NAME).isFile

    private fun migrateLegacyProfilesExclusive(context: Context) {
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

        val auth = SteamCloudAuthStore.readAuthMaterial(context)
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
                        val source = File(legacyCloudProfile, rootKind.directoryName)
                        if (source.exists()) {
                            SteamCloudStagedPathStore.copyPath(
                                source,
                                File(stagingRoot, rootKind.directoryName),
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

    private fun containsRegularFile(
        file: File,
        excludedRelativeSuffixes: Set<String>,
        relativeSuffix: String = "",
    ): Boolean {
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
        SteamCloudStagedPathStore.copyPath(source, target)
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
            SteamCloudStagedPathStore.copyPath(source, target)
        }
    }
}
