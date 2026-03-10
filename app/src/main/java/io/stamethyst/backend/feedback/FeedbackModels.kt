package io.stamethyst.backend.feedback

import java.io.File

enum class FeedbackCategory(val displayName: String, val issuePrefix: String) {
    GAME_BUG(displayName = "游戏内 Bug", issuePrefix = "Game Bug"),
    FEATURE_REQUEST(displayName = "功能建议", issuePrefix = "Feature"),
    LAUNCHER_BUG(displayName = "启动器 Bug", issuePrefix = "Launcher Bug")
}

enum class GameIssueType(val displayName: String, val issuePrefix: String) {
    PERFORMANCE(displayName = "卡顿", issuePrefix = "Performance"),
    DISPLAY(displayName = "显示不正常", issuePrefix = "Display"),
    CRASH(displayName = "崩溃", issuePrefix = "Crash")
}

data class FeedbackSelectedMod(
    val key: String,
    val modId: String,
    val manifestModId: String,
    val name: String,
    val version: String,
    val required: Boolean,
    val storagePath: String
)

data class FeedbackScreenshotAttachment(
    val file: File,
    val displayName: String
)

data class FeedbackSubmissionDraft(
    val category: FeedbackCategory,
    val summary: String,
    val detail: String,
    val reproductionSteps: String,
    val email: String?,
    val emailNotificationsEnabled: Boolean,
    val reproducedOnLastRun: Boolean?,
    val gameIssueType: GameIssueType?,
    val suspectedMods: List<FeedbackSelectedMod>,
    val suspectUnknown: Boolean,
    val screenshots: List<FeedbackScreenshotAttachment>
)

data class FeedbackUploadResult(
    val issueUrl: String?,
    val issueNumber: Long?,
    val rawResponse: String
)
