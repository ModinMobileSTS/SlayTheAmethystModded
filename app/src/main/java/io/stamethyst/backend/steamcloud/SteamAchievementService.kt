package io.stamethyst.backend.steamcloud

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import java.io.File
import io.stamethyst.config.RuntimePaths

/** Bundled Slay the Spire achievement state service with schema-validated CM debug writes. */
object SteamAchievementService {
    const val APP_ID = 646570L
    internal const val SHRUG_IT_OFF_API_NAME = "shrug_it_off"

    data class Achievement(
        val apiName: String,
        @get:StringRes val titleResId: Int,
        @get:StringRes val descriptionResId: Int,
        @get:DrawableRes val unlockedIconResId: Int,
        @get:DrawableRes val lockedIconResId: Int,
        val unlocked: Boolean,
    )

    data class Snapshot(
        val steamId64: String,
        val achievements: List<Achievement>,
        val fetchedAtMs: Long,
        val fromCache: Boolean,
    ) {
        val unlockedCount: Int get() = achievements.count { it.unlocked }
    }

    fun fetchViaCm(
        context: Context,
        accountName: String,
        refreshToken: String,
        steamId64: String,
    ): Snapshot {
        require(accountName.isNotBlank()) { "Steam account name is not available." }
        require(refreshToken.isNotBlank()) { "Steam refresh token is not available." }
        val normalizedId = steamId64.trim()
        require(normalizedId.isNotEmpty()) { "Steam account is not available." }
        SteamCloudClient(context).use { client ->
            client.beginOperationDiagnostics("steam_achievements_cm", accountName, false)
            client.start()
            client.logOnWithRefreshToken(accountName, refreshToken, normalizedId)
            val result = client.getUserStats(APP_ID, normalizedId.toLong(), CM_TIMEOUT_MS)
            AchievementSyncLogStore.append(
                context,
                "cm_schema_received",
                "operation=fetch definitions=${result.definitions.size} stat_values=${result.statValues.size} " +
                    "writable_targets=${result.achievementStatTargets.size} crc_stats=${result.crcStats}",
            )
            result.achievementStatSchemaEntries.forEach { entry ->
                AchievementSyncLogStore.append(
                    context,
                    "cm_schema_raw_stat_bit",
                    "operation=fetch $entry",
                )
            }
            AchievementSyncLogStore.append(
                context,
                "cm_achievement_blocks_received",
                "operation=fetch count=${result.achievementBlocks.size} " +
                    result.achievementBlocks.joinToString(";") { block ->
                        "achievement_id=${block.achievementId},unlock_times=${block.unlockTimes.joinToString(",")}"
                    },
            )
            AchievementSyncLogStore.append(
                context,
                "cm_achievement_block_write_probe",
                "operation=fetch result=${SteamCloudClient.describeAchievementBlockWriteProtocol()}",
            )
            return snapshotFromResult(normalizedId, result, System.currentTimeMillis()).also {
                writeCached(context, it)
            }
        }
    }

    /** Changes one schema-defined achievement bit and confirms the requested state by rereading CM. */
    fun setAchievementUnlockedViaCm(
        context: Context,
        accountName: String,
        refreshToken: String,
        steamId64: String,
        apiName: String,
        unlocked: Boolean,
    ): Snapshot {
        require(accountName.isNotBlank()) { "Steam account name is not available." }
        require(refreshToken.isNotBlank()) { "Steam refresh token is not available." }
        val normalizedId = steamId64.trim()
        require(normalizedId.isNotEmpty()) { "Steam account is not available." }
        val normalizedApiName = apiName.trim().lowercase()
        require(normalizedApiName in SteamAchievementCatalog.apiNames) {
            "Unknown Steam achievement: $apiName"
        }
        SteamCloudClient(context).use { client ->
            client.beginOperationDiagnostics("steam_achievement_debug_mutation", accountName, false)
            client.start()
            client.logOnWithRefreshToken(accountName, refreshToken, normalizedId)
            val initial = client.getUserStats(APP_ID, normalizedId.toLong(), CM_TIMEOUT_MS)
            val target = initial.achievementStatTargets[normalizedApiName]
                ?: fallbackTargetFor(normalizedApiName)
                ?: run {
                    val error = IllegalStateException(
                        "Steam CM schema does not define $normalizedApiName.",
                    )
                    AchievementSyncLogStore.append(
                        context,
                        "cm_schema_target_missing",
                        "operation=mutation id=$normalizedApiName definitions=${initial.definitions.size} " +
                            "stat_values=${initial.statValues.size} writable_targets=${initial.achievementStatTargets.size} " +
                            AchievementSyncLogStore.errorDetails(error),
                    )
                    throw error
                }
            AchievementSyncLogStore.append(
                context,
                "cm_schema_target_resolved",
                "operation=mutation id=$normalizedApiName stat_id=${target.statId} " +
                    "bit_index=${target.bitIndex} mask=${target.mask} definitions=${initial.definitions.size} " +
                    "stat_values=${initial.statValues.size} writable_targets=${initial.achievementStatTargets.size} " +
                    "crc_stats=${initial.crcStats}",
            )
            initial.achievementStatSchemaEntries.forEach { entry ->
                AchievementSyncLogStore.append(
                    context,
                    "cm_schema_raw_stat_bit",
                    "operation=mutation id=$normalizedApiName $entry",
                )
            }
            AchievementSyncLogStore.append(
                context,
                "cm_achievement_blocks_received",
                "operation=mutation id=$normalizedApiName count=${initial.achievementBlocks.size} " +
                    initial.achievementBlocks.joinToString(";") { block ->
                        "achievement_id=${block.achievementId},unlock_times=${block.unlockTimes.joinToString(",")}"
                    },
            )
            AchievementSyncLogStore.append(
                context,
                "cm_achievement_block_write_probe",
                "operation=mutation id=$normalizedApiName result=${SteamCloudClient.describeAchievementBlockWriteProtocol()}",
            )
            val currentValue = initial.statValues[target.statId] ?: 0
            val currentUnlocked = currentValue and target.mask != 0
            AchievementSyncLogStore.append(
                context,
                "cm_achievement_state",
                "operation=mutation id=$normalizedApiName stat_id=${target.statId} bit_index=${target.bitIndex} " +
                    "current_value=$currentValue current_unlocked=$currentUnlocked requested_unlocked=$unlocked",
            )
            if (currentUnlocked == unlocked) {
                AchievementSyncLogStore.append(
                    context,
                    "cm_achievement_already_in_requested_state",
                    "operation=mutation id=$normalizedApiName unlocked=$unlocked",
                )
                return snapshotFromResult(normalizedId, initial, System.currentTimeMillis()).also {
                    writeCached(context, it)
                }
            }
            val requestedValue = if (unlocked) {
                currentValue or target.mask
            } else {
                currentValue and target.mask.inv()
            }

            AchievementSyncLogStore.append(
                context,
                "cm_stat_write_started",
                "operation=mutation id=$normalizedApiName stat_id=${target.statId} " +
                    "bit_index=${target.bitIndex} current_value=$currentValue requested_value=$requestedValue " +
                    "crc_stats=${initial.crcStats}",
            )
            try {
                client.storeUserStat(
                    APP_ID,
                    normalizedId.toLong(),
                    initial.crcStats,
                    target.statId,
                    requestedValue,
                    CM_TIMEOUT_MS,
                )
            } catch (error: Throwable) {
                AchievementSyncLogStore.append(
                    context,
                    "cm_stat_write_failed",
                    "operation=mutation id=$normalizedApiName stat_id=${target.statId} " +
                        "requested_value=$requestedValue ${AchievementSyncLogStore.errorDetails(error)}",
                )
                throw error
            }
            AchievementSyncLogStore.append(
                context,
                "cm_stat_write_accepted",
                "operation=mutation id=$normalizedApiName stat_id=${target.statId} requested_value=$requestedValue",
            )

            val verified = try {
                client.getUserStats(APP_ID, normalizedId.toLong(), CM_TIMEOUT_MS)
            } catch (error: Throwable) {
                AchievementSyncLogStore.append(
                    context,
                    "cm_stat_verify_fetch_failed",
                    "operation=mutation id=$normalizedApiName stat_id=${target.statId} " +
                        AchievementSyncLogStore.errorDetails(error),
                )
                throw error
            }
            val verifiedValue = verified.statValues[target.statId] ?: 0
            val verifiedUnlocked = verifiedValue and target.mask != 0
            AchievementSyncLogStore.append(
                context,
                "cm_stat_verify_result",
                "operation=mutation id=$normalizedApiName stat_id=${target.statId} bit_index=${target.bitIndex} " +
                    "verified_value=$verifiedValue verified_unlocked=$verifiedUnlocked requested_unlocked=$unlocked " +
                    "crc_stats=${verified.crcStats}",
            )
            if (verifiedUnlocked != unlocked) {
                val error = IllegalStateException(
                    "Steam CM accepted the write but did not confirm $normalizedApiName as requested.",
                )
                AchievementSyncLogStore.append(
                    context,
                    "cm_stat_verify_failed",
                    "operation=mutation id=$normalizedApiName stat_id=${target.statId} " +
                        "verified_value=$verifiedValue verified_unlocked=$verifiedUnlocked requested_unlocked=$unlocked " +
                        AchievementSyncLogStore.errorDetails(error),
                )
                throw error
            }
            return snapshotFromResult(normalizedId, verified, System.currentTimeMillis()).also {
                writeCached(context, it)
            }
        }
    }

    fun readCached(context: Context, steamId64: String): Snapshot? {
        val normalizedId = steamId64.trim()
        if (normalizedId.isEmpty()) return null
        val cache = cacheFile(context, normalizedId)
        if (!cache.isFile) return null
        return parseCache(normalizedId, cache.readLines())
    }

    internal fun buildSnapshot(
        steamId64: String,
        unlockedApiNames: Set<String>,
        fetchedAtMs: Long,
        fromCache: Boolean,
    ): Snapshot = Snapshot(
        steamId64 = steamId64,
        achievements = SteamAchievementCatalog.entries.map { entry ->
            Achievement(
                apiName = entry.apiName,
                titleResId = entry.titleResId,
                descriptionResId = entry.descriptionResId,
                unlockedIconResId = entry.unlockedIconResId,
                lockedIconResId = entry.lockedIconResId,
                unlocked = entry.apiName in unlockedApiNames,
            )
        },
        fetchedAtMs = fetchedAtMs,
        fromCache = fromCache,
    )

    private fun snapshotFromResult(
        steamId64: String,
        result: SteamCloudClient.UserStatsResult,
        fetchedAtMs: Long,
    ): Snapshot {
        val targets = result.achievementStatTargets +
            (SHRUG_IT_OFF_API_NAME to knownShrugItOffTarget())
        val unlockedApiNames = targets.mapNotNull { (apiName, target) ->
            val statValue = result.statValues[target.statId] ?: return@mapNotNull null
            if (statValue and target.mask != 0 && apiName in SteamAchievementCatalog.apiNames) {
                apiName
            } else {
                null
            }
        }.toSet()
        return buildSnapshot(steamId64, unlockedApiNames, fetchedAtMs, false)
    }

    private fun fallbackTargetFor(apiName: String): SteamCloudClient.UserStatsResult.AchievementStatTarget? =
        if (apiName == SHRUG_IT_OFF_API_NAME) knownShrugItOffTarget() else null

    private fun knownShrugItOffTarget(): SteamCloudClient.UserStatsResult.AchievementStatTarget =
        SteamCloudClient.UserStatsResult.AchievementStatTarget(1, 1)

    private fun writeCached(context: Context, snapshot: Snapshot) {
        val text = buildString {
            append(CACHE_VERSION).append('\t').append(snapshot.fetchedAtMs).append('\n')
            snapshot.achievements.forEach { achievement ->
                append(achievement.apiName).append('\t').append(if (achievement.unlocked) 1 else 0).append('\n')
            }
        }
        SteamCloudAtomicFileStore.writeText(cacheFile(context, snapshot.steamId64), text)
    }

    private fun parseCache(steamId64: String, lines: List<String>): Snapshot? {
        val header = lines.firstOrNull()?.split('\t') ?: return null
        if (header.firstOrNull() != CACHE_VERSION) return null
        val fetchedAtMs = header.getOrNull(1)?.toLongOrNull() ?: return null
        val unlockedApiNames = lines.drop(1).mapNotNull { line ->
            val fields = line.split('\t')
            val apiName = fields.getOrNull(0)?.takeIf(SteamAchievementCatalog.apiNames::contains) ?: return@mapNotNull null
            if ((fields.getOrNull(1)?.toIntOrNull() ?: 0) > 0) apiName else null
        }.toSet()
        return buildSnapshot(steamId64, unlockedApiNames, fetchedAtMs, true)
    }

    private fun cacheFile(context: Context, steamId64: String): File =
        File(File(RuntimePaths.externalCacheRoot(context.applicationContext), CACHE_DIRECTORY), "$steamId64.tsv")

    private const val CACHE_VERSION = "v3"
    private const val CACHE_DIRECTORY = "steam-achievements"
    private const val CM_TIMEOUT_MS = 30_000L
}
