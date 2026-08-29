package io.stamethyst.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object QuickStart : Route
    @Serializable
    data object QuickStartAutoImport : Route
    @Serializable
    data object QuickStartSteamLogin : Route
    @Serializable
    data object QuickStartSteamMethod : Route
    @Serializable
    data object QuickStartSteamGuard : Route
    @Serializable
    data object QuickStartSteamDownload : Route
    @Serializable
    data object QuickStartJarImport : Route
    @Serializable
    data object FirstRunSetup : Route
    @Serializable
    data object Main : Route
    @Serializable
    data object CrashRecovery : Route
    @Serializable
    data object Mods : Route
    @Serializable
    data object Settings : Route
    @Serializable
    data object SettingsLauncher : Route
    @Serializable
    data object SettingsGame : Route
    @Serializable
    data object SettingsPerformance : Route
    @Serializable
    data object SettingsMarketCloud : Route
    @Serializable
    data object SettingsWorkshopAutoImportDefaults : Route
    @Serializable
    data object SettingsFeedback : Route
    @Serializable
    data object SettingsAbout : Route
    @Serializable
    data class BaiduTranslationCredentials(
        val notice: String? = null,
    ) : Route
    @Serializable
    data object Workshop : Route
    @Serializable
    data object WorkshopSubscriptions : Route
    @Serializable
    data class WorkshopDetail(
        val publishedFileId: String,
        val appId: Long = 646570L,
    ) : Route
    @Serializable
    data object WorkshopDownloadCenter : Route
    @Serializable
    data object SteamCloudLogin : Route
    @Serializable
    data object SteamCloudLoginMethod : Route
    @Serializable
    data object SteamCloudGuard : Route
    @Serializable
    data object SteamCloudSaveSettings : Route
    @Serializable
    data object SteamCloudSyncBlacklistSettings : Route
    @Serializable
    data object DeveloperSettings : Route
    @Serializable
    data object NativeLibraryMarket : Route
    @Serializable
    data object Compatibility : Route
    @Serializable
    data object MobileGluesSettings : Route
    @Serializable
    data object Feedback : Route
    @Serializable
    data object FeedbackSubscriptions : Route
    @Serializable
    data object FeedbackIssueBrowser : Route
    @Serializable
    data class FeedbackConversation(
        val issueNumber: Long
    ) : Route
    @Serializable
    data class FeedbackIssuePreview(
        val issueNumber: Long
    ) : Route
}
