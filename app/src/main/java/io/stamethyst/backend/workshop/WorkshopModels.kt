package io.stamethyst.backend.workshop

import java.io.File
import kotlin.math.roundToInt

data class WorkshopBrowseQuery(
    val appId: UInt = 646570u,
    val searchText: String = "",
    val sort: WorkshopBrowseSort = WorkshopBrowseSort.MostPopular,
    val timeFilter: WorkshopBrowseTimeFilter = WorkshopBrowseTimeFilter.OneWeek,
    val category: WorkshopModCategory = WorkshopModCategory.All,
    val page: Int = 1,
    val pageSize: Int = 30,
)

enum class WorkshopBrowseSort(
    val browseSortValue: String,
    val actualSortValue: String,
    val displayName: String,
    val usesTimeFilter: Boolean = false,
) {
    MostPopular("trend", "trend", "热门", usesTimeFilter = true),
    MostRecent("mostrecent", "mostrecent", "最新发布"),
    LastUpdated("lastupdated", "lastupdated", "最近更新"),
    MostSubscribed("totaluniquesubscribers", "totaluniquesubscribers", "订阅最多");

    companion object {
        /**
         * Default browse ordering for the market: cumulative (historical) subscription/download
         * count, i.e. `MostSubscribed`.
         */
        val Default: WorkshopBrowseSort = MostSubscribed

        fun fromStorageValue(value: String?): WorkshopBrowseSort =
            entries.firstOrNull { it.browseSortValue == value?.trim() } ?: Default
    }
}

enum class WorkshopBrowseTimeFilter(
    val days: Int,
    val displayName: String,
) {
    Today(1, "今日"),
    OneWeek(7, "本周"),
    ThirtyDays(30, "30天"),
    ThreeMonths(90, "3个月"),
    SixMonths(180, "6个月"),
    OneYear(365, "1年"),
    AllTime(-1, "全部时间"),
}

enum class WorkshopModCategory(val requiredTag: String?) {
    All(null),
    Tools("Tools"),
    Api("Api"),
    Character("Character"),
    Utility("Utility"),
    Relics("Relics"),
    Events("Events"),
    Cards("Cards"),
    Bosses("Bosses"),
    Elites("Elites"),
    Monsters("Monsters"),
    Modifiers("Modifiers"),
    Potions("Potions"),
    Rooms("Rooms"),
    Neow("Neow"),
    Twitch("Twitch"),
    Qol("Qol"),
    Expansion("Expansion"),
    Content("Content"),
    Rewards("Rewards"),
}

data class WorkshopBrowseResult(
    val items: List<WorkshopItemSummary>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val hasNextPage: Boolean = items.size >= pageSize,
)

data class WorkshopItemSummary(
    val publishedFileId: ULong,
    val appId: UInt,
    val title: String,
    val previewUrl: String,
    val description: String,
    val authorName: String = "",
    val fileSizeBytes: Long = 0L,
    val updatedAtMillis: Long = 0L,
    val downloadCount: Long = 0L,
    val rating: WorkshopItemRating? = null,
)

data class WorkshopItemRating(
    val score: Int,
    val maxScore: Int = 5,
)

enum class WorkshopPreviewMediaKind {
    Image,
    YouTubeVideo,
    SteamVideo,
}

enum class WorkshopPreviewVideoSource {
    YouTube,
    Steam,
}

data class WorkshopPreviewMedia(
    val id: String,
    val kind: WorkshopPreviewMediaKind,
    val imageUrl: String = "",
    val thumbnailUrl: String = "",
    val youtubeVideoId: String = "",
    val steamVideoUrl: String = "",
    val videoSource: WorkshopPreviewVideoSource = WorkshopPreviewVideoSource.YouTube,
)

internal fun normalizedWorkshopRating(score: Float?, maxScore: Int = 5): WorkshopItemRating? {
    val rawScore = score ?: return null
    if (!rawScore.isFinite() || rawScore <= 0f || maxScore <= 0) return null
    val scaledScore = if (rawScore <= 1f) rawScore * maxScore else rawScore
    return WorkshopItemRating(
        score = scaledScore.roundToInt().coerceIn(1, maxScore),
        maxScore = maxScore,
    )
}

data class WorkshopItemDetails(
    val summary: WorkshopItemSummary,
    val fileUrl: String? = null,
    val hcontentFile: ULong? = null,
    val depotId: UInt? = null,
    val jsonMetadata: String = "",
    val previewMedia: List<WorkshopPreviewMedia> = emptyList(),
    val previewImageUrls: List<String> = emptyList(),
    val fullDescriptionUnavailable: Boolean = false,
    val changeNotes: String = "",
    val changeNotesUrl: String = "",
    val dependencies: List<WorkshopItemSummary> = emptyList(),
    val commentsUrl: String = "",
    val commentThreadContext: WorkshopCommentThreadContext? = null,
    val commentCount: Long? = null,
    val commentPage: Int = 1,
    val commentTotalPages: Int? = null,
    val hasPreviousCommentPage: Boolean = false,
    val hasNextCommentPage: Boolean = false,
    val comments: List<WorkshopComment> = emptyList(),
)

data class WorkshopChangeNotes(
    val publishedFileId: ULong,
    val markdown: String,
    val latestMarkdown: String,
    val url: String,
)

data class WorkshopSubscriptionResult(
    val publishedFileId: ULong,
    val appId: UInt,
    val subscribedAtMillis: Long,
)

data class WorkshopUnsubscriptionResult(
    val publishedFileId: ULong,
    val appId: UInt,
    val unsubscribedAtMillis: Long,
)

class WorkshopSteamLoginRequiredException : IllegalStateException("Steam login is required for this workshop action.")

class WorkshopSubscriptionVerificationException :
    IllegalStateException("Steam accepted the subscribe request but did not add the file to subscriptions.")

class WorkshopUnsubscriptionVerificationException :
    IllegalStateException("Steam accepted the unsubscribe request but kept the file in subscriptions.")

data class WorkshopCommentThreadContext(
    val ownerId: String,
    val featureId: String,
    val feature2: String? = null,
    val extendedData: String? = null,
    val sessionId: String? = null,
)

data class WorkshopCommentPage(
    val commentsUrl: String,
    val commentCount: Long? = null,
    val page: Int = 1,
    val totalPages: Int? = null,
    val hasPreviousPage: Boolean = false,
    val hasNextPage: Boolean = false,
    val comments: List<WorkshopComment> = emptyList(),
)

data class WorkshopComment(
    val id: String,
    val authorName: String,
    val profileUrl: String,
    val content: String,
    val postedEpochSeconds: Long? = null,
    val postedDisplayText: String = "",
    val translatedContent: String = "",
)

data class WorkshopDownloadRequest(
    val details: WorkshopItemDetails,
    val outputDir: File,
)

data class WorkshopDownloadedArtifact(
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
)

data class WorkshopDownloadFailure(
    val message: String,
    val detail: String = "",
)

data class WorkshopDownloadProgress(
    val writtenBytes: Long,
    val totalBytes: Long? = null,
    val completedChunks: Int? = null,
    val totalChunks: Int? = null,
    val completedFiles: Int? = null,
    val totalFiles: Int? = null,
)

data class WorkshopInstalledModRecord(
    val appId: UInt,
    val publishedFileId: ULong,
    val title: String,
    val description: String,
    val previewUrl: String,
    val versionText: String,
    val updatedAtMillis: Long,
    val installedAtMillis: Long,
    val localJarPath: String,
    val localJarPaths: List<String> = emptyList(),
    val contentKind: WorkshopInstalledContentKind = WorkshopInstalledContentKind.JarMod,
    val texturePackPath: String = "",
    val cardState: WorkshopModCardState = WorkshopModCardState.ImportedUnpatched,
    val statusText: String = "",
    val localPreviewImagePath: String = "",
    val dependencies: List<WorkshopItemSummary> = emptyList(),
)

enum class WorkshopInstalledContentKind {
    JarMod,
    TexturePack,
    NonStandard,
}

enum class WorkshopModCardState {
    ImportedUnpatched,
    ImportedPatched,
    Downloading,
    DownloadPaused,
    DownloadFailed,
    NonStandardDownloaded,
    TexturePackInstalled,
    UpdateAvailable,
    FileMissing,
}

data class WorkshopUpdateCheckResult(
    val appId: UInt,
    val publishedFileId: ULong,
    val hasUpdate: Boolean,
    val remoteUpdatedAtMillis: Long,
    val localUpdatedAtMillis: Long,
    val remoteVersionText: String,
)

data class WorkshopUpdateCheckReport(
    val results: List<WorkshopUpdateCheckResult>,
    val failedCount: Int,
)
