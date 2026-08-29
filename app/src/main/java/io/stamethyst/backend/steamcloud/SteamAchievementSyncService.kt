package io.stamethyst.backend.steamcloud

import android.content.Context
import io.stamethyst.config.RuntimePaths
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Synchronizes achievement state produced by the game runtime in the launcher process. */
object SteamAchievementSyncService {
    private const val PREFS = "steam_achievement_sync"
    private const val PENDING_IDS = "pending_ids"
    private const val FILE_NAME = "STSAchievements"
    private const val REQUEST_VERSION = 1
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    data class Request(val id: String, val achievementIds: Set<String>, val saveSlot: Int? = null) {
        val dedupeKey: String get() = "$id:$saveSlot"
    }

    data class SyncPlan(val upload: Set<String>, val localFiles: List<File>)

    fun parseRequest(text: String): Request? = runCatching {
        val json = JSONObject(text.trim())
        if (json.optInt("version", REQUEST_VERSION) != REQUEST_VERSION) return null
        val ids = buildSet {
            val array = json.optJSONArray("achievements")
            if (array != null) for (index in 0 until array.length()) {
                array.optString(index).trim().lowercase(Locale.ROOT)
                    .takeIf { it in SteamAchievementCatalog.apiNames }
                    ?.let(::add)
            }
            json.optString("achievement").trim().lowercase(Locale.ROOT)
                .takeIf { it in SteamAchievementCatalog.apiNames }
                ?.let(::add)
        }
        val id = json.optString("request_id").trim()
            .ifBlank { json.optString("id").trim() }
            .ifBlank { ids.sorted().joinToString(",") }
        if (id.isBlank() || ids.isEmpty()) return null
        val slot = if (json.has("save_slot") && !json.isNull("save_slot")) {
            json.optInt("save_slot", -1).takeIf { it in 0..2 }
        } else null
        Request(id, ids, slot)
    }.getOrNull()

    fun pendingIds(context: Context): Set<String> {
        val appContext = context.applicationContext ?: context
        val steamId64 = SteamCloudAuthStore.readAuthMaterial(appContext)?.steamId64
            ?.takeIf(::isValidSteamId64)
            ?: return emptySet()
        val file = pendingFile(appContext, steamId64)
        if (file.isFile) {
            return readPendingFileWithBackup(file)
        }
        return emptySet()
    }

    fun syncRequestAsync(context: Context, request: Request, onFinished: (Throwable?) -> Unit = {}) {
        val appContext = context.applicationContext
        AchievementSyncLogStore.append(
            appContext,
            "sync_queued",
            "source=runtime request=${request.id} slot=${request.saveSlot ?: "none"} ids=${request.achievementIds.sorted().joinToString(",")}",
        )
        executor.execute {
            val error = runCatching { syncRequest(appContext, request) }.exceptionOrNull()
            AchievementSyncLogStore.append(
                appContext,
                if (error == null) "sync_completed" else "sync_failed",
                if (error == null) {
                    "source=runtime request=${request.id}"
                } else {
                    "source=runtime request=${request.id} error=${AchievementSyncLogStore.errorType(error)}"
                },
            )
            onFinished(error)
        }
    }

    /** Reconciles the union of all three local achievement files with the current Steam state. */
    fun syncAllLocalAchievementsAsync(context: Context, onFinished: (Throwable?) -> Unit = {}) {
        val appContext = context.applicationContext
        AchievementSyncLogStore.append(appContext, "sync_queued", "source=manual")
        executor.execute {
            val error = runCatching { syncLocalAchievements(appContext, "manual") }.exceptionOrNull()
            AchievementSyncLogStore.append(
                appContext,
                if (error == null) "sync_completed" else "sync_failed",
                if (error == null) {
                    "source=manual"
                } else {
                    "source=manual error=${AchievementSyncLogStore.errorType(error)}"
                },
            )
            onFinished(error)
        }
    }

    fun retryPendingUploadsAsync(context: Context, onFinished: (Throwable?) -> Unit = {}) {
        syncAllLocalAchievementsAsync(context, onFinished)
    }

    /** Clears a remotely locked achievement from every existing local achievement preference file. */
    fun lockAchievementInAllLocalSaves(context: Context, apiName: String) {
        val normalizedApiName = apiName.trim().lowercase()
        require(normalizedApiName in SteamAchievementCatalog.apiNames) {
            "Unknown Steam achievement: $apiName"
        }
        SteamCloudOperationMutex.runExclusive(context) {
            SteamCloudLiveSaveLease.runMutation(context) {
                lockAchievementInAllLocalSavesExclusive(context, normalizedApiName)
            }
        }
    }

    internal fun setAchievementUnlocked(
        context: Context,
        expectedAuth: SteamCloudAuthStore.SavedAuthMaterial,
        apiName: String,
        unlocked: Boolean,
    ): SteamAchievementService.Snapshot = SteamCloudOperationMutex.runExclusive(context) {
        val currentAuth = SteamCloudAuthStore.readAuthMaterial(context)
        if (currentAuth != expectedAuth) {
            error("Steam authentication changed before the achievement update started.")
        }
        if (unlocked) {
            return@runExclusive SteamAchievementService.setAchievementUnlockedViaCm(
                context = context,
                accountName = currentAuth.accountName,
                refreshToken = currentAuth.refreshToken,
                steamId64 = currentAuth.steamId64,
                apiName = apiName,
                unlocked = true,
            )
        }

        SteamCloudLiveSaveLease.runMutation(context) {
            val snapshot = SteamAchievementService.setAchievementUnlockedViaCm(
                context = context,
                accountName = currentAuth.accountName,
                refreshToken = currentAuth.refreshToken,
                steamId64 = currentAuth.steamId64,
                apiName = apiName,
                unlocked = false,
            )
            lockAchievementInAllLocalSavesExclusive(
                context,
                apiName.trim().lowercase(Locale.ROOT),
            )
            snapshot
        }
    }

    internal fun plan(localUnlocked: Set<String>, remoteUnlocked: Set<String>, files: List<File>): SyncPlan =
        SyncPlan(localUnlocked - remoteUnlocked, files)

    /** Reads all local save slots and returns achievements missing from the supplied Steam snapshot. */
    internal fun localAchievementsMissingFromSteam(
        context: Context,
        remoteUnlocked: Set<String>,
    ): Set<String> = localAchievementsMissingFromSteam(achievementFiles(context), remoteUnlocked)

    internal fun localAchievementsMissingFromSteam(
        files: List<File>,
        remoteUnlocked: Set<String>,
    ): Set<String> {
        val localUnlocked = files.flatMap { readUnlocked(it).asSequence() }.toSet()
        return plan(localUnlocked, remoteUnlocked, files).upload
    }

    private fun syncRequest(context: Context, request: Request) {
        syncLocalAchievements(context, "runtime")
    }

    private fun syncLocalAchievements(context: Context, source: String) {
        SteamCloudOperationMutex.runExclusive(context) {
            AchievementSyncLogStore.append(context, "auth_read_started", "source=$source")
            val auth = SteamCloudAuthStore.readAuthMaterial(context)
                ?: error("Steam authentication is unavailable")
            AchievementSyncLogStore.append(context, "remote_fetch_started", "source=$source")
            val remote = SteamAchievementService.fetchViaCm(
                context, auth.accountName, auth.refreshToken, auth.steamId64
            ).achievements.filter { it.unlocked }.map { it.apiName }.toSet()
            AchievementSyncLogStore.append(
                context,
                "remote_fetch_completed",
                "source=$source unlocked_count=${remote.size}",
            )
            val upload = localAchievementsMissingFromSteam(context, remote)
            AchievementSyncLogStore.append(
                context,
                "upload_plan",
                "source=$source count=${upload.size} ids=${upload.sorted().joinToString(",")}",
            )
            replacePending(context, auth.steamId64, upload)
            upload.forEach { apiName ->
                AchievementSyncLogStore.append(context, "upload_started", "source=$source id=$apiName")
                try {
                    SteamAchievementService.setAchievementUnlockedViaCm(
                        context, auth.accountName, auth.refreshToken, auth.steamId64, apiName, true
                    )
                    removePending(context, auth.steamId64, apiName)
                    AchievementSyncLogStore.append(context, "upload_completed", "source=$source id=$apiName")
                } catch (error: Throwable) {
                    AchievementSyncLogStore.append(
                        context,
                        "upload_failed",
                        "source=$source id=$apiName error=${AchievementSyncLogStore.errorType(error)}",
                    )
                    throw error
                }
            }
        }
    }

    private fun achievementFiles(context: Context): List<File> {
        val dir = RuntimePaths.preferencesDir(context)
        return listOf(FILE_NAME, "1_$FILE_NAME", "2_$FILE_NAME").map { File(dir, it) }
    }

    internal fun readUnlocked(file: File): Set<String> = runCatching {
        if (!file.isFile) return emptySet()
        val json = JSONObject(file.readText())
        json.keys().asSequence().mapNotNull { storedKey ->
            storedKey.lowercase().takeIf { apiName ->
                apiName in SteamAchievementCatalog.apiNames && isUnlockedValue(json.opt(storedKey))
            }
        }.toSet()
    }.getOrDefault(emptySet())

    internal fun lockAchievementInFiles(files: List<File>, apiName: String) {
        files.forEach { file ->
            if (!file.isFile) return@forEach
            val json = JSONObject(file.readText())
            val storedKey = json.keys().asSequence().firstOrNull { it.equals(apiName, ignoreCase = true) }
            if (storedKey != null) {
                json.remove(storedKey)
                SteamCloudAtomicFileStore.writeText(file, json.toString())
            }
        }
    }

    private fun lockAchievementInAllLocalSavesExclusive(context: Context, apiName: String) {
        lockAchievementInFiles(achievementFiles(context), apiName)
        SteamCloudAuthStore.readAuthMaterial(context)?.steamId64?.let { steamId64 ->
            removePending(context, steamId64, apiName)
        }
        val commandFile = RuntimePaths.achievementLockCommandFile(context)
        val queuedCommands = runCatching {
            commandFile.takeIf { it.isFile }
                ?.readLines()
                .orEmpty()
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter { it in SteamAchievementCatalog.apiNames }
                .toMutableSet()
        }.getOrDefault(mutableSetOf())
        queuedCommands += apiName
        SteamCloudAtomicFileStore.writeText(commandFile, queuedCommands.sorted().joinToString("\n"))
    }

    private fun isUnlockedValue(value: Any?): Boolean = when (value) {
        is Number -> value.toInt() != 0
        is Boolean -> value
        is String -> value == "1" || value.equals("true", ignoreCase = true)
        else -> false
    }

    private fun replacePending(context: Context, steamId64: String, ids: Set<String>) {
        updatePending(context, steamId64) { ids }
    }

    private fun removePending(context: Context, steamId64: String, apiName: String) {
        updatePending(context, steamId64) { ids -> ids - apiName }
    }

    private fun updatePending(
        context: Context,
        steamId64: String,
        transform: (Set<String>) -> Set<String>,
    ) {
        require(isValidSteamId64(steamId64)) { "Steam account is required for pending achievements." }
        val appContext = context.applicationContext ?: context
        SteamCloudOperationMutex.runExclusive(appContext) {
            clearLegacyPendingState(appContext)
            val file = pendingFile(appContext, steamId64)
            val current = if (file.isFile) {
                readPendingFileWithBackup(file)
            } else {
                emptySet()
            }
            val updated = transform(current)
                .filterTo(sortedSetOf()) { it in SteamAchievementCatalog.apiNames }
            SteamCloudAtomicFileStore.writeText(
                file,
                updated.joinToString(separator = "\n", postfix = if (updated.isEmpty()) "" else "\n"),
            )
        }
    }

    internal fun readPendingFile(file: File): Set<String>? {
        if (!file.isFile) {
            return null
        }
        val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrNull() ?: return null
        val normalized = lines.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
        if (normalized.any { it !in SteamAchievementCatalog.apiNames }) {
            return null
        }
        return normalized.toSet()
    }

    private fun readPendingFileWithBackup(file: File): Set<String> {
        readPendingFile(file)?.let { return it }
        return readPendingFile(SteamCloudAtomicFileStore.backupFile(file)).orEmpty()
    }

    private fun clearLegacyPendingState(context: Context) {
        val legacyFile = File(File(context.filesDir, "steam-achievements"), "pending-uploads.txt")
        legacyFile.delete()
        SteamCloudAtomicFileStore.backupFile(legacyFile).delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteSharedPreferences(PREFS)
    }

    private fun pendingFile(context: Context, steamId64: String): File =
        File(File(File(context.filesDir, "steam-achievements"), "pending-uploads"), "$steamId64.txt")

    private fun isValidSteamId64(value: String): Boolean =
        value.trim().toULongOrNull()?.let { it > 0uL } == true
}
