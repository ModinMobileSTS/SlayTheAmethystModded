package io.stamethyst.ui.feedback

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.stamethyst.backend.feedback.FeedbackConversationService
import io.stamethyst.backend.feedback.FeedbackInboxCoordinator
import io.stamethyst.backend.feedback.FeedbackIssueLocalStore
import io.stamethyst.backend.feedback.FeedbackIssueSyncService
import io.stamethyst.backend.feedback.FeedbackIssueThreadCache
import io.stamethyst.backend.feedback.FeedbackPostedComment
import io.stamethyst.backend.feedback.FeedbackScreenshotAttachment
import io.stamethyst.backend.feedback.FeedbackThreadAuthorType
import io.stamethyst.backend.feedback.FeedbackThreadEvent
import io.stamethyst.backend.feedback.FeedbackThreadEventType
import io.stamethyst.ui.settings.SettingsFileService
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Stable
class FeedbackConversationViewModel(
    private val issueNumber: Long
) : ViewModel() {
    companion object {
        private const val MAX_SCREENSHOT_ATTACHMENTS = 4

        fun factory(issueNumber: Long): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return FeedbackConversationViewModel(issueNumber) as T
                }
            }
        }
    }

    sealed interface Effect {
        data object OpenScreenshotPicker : Effect
    }

    data class ScreenshotItem(
        val id: String,
        val displayName: String,
        val sizeLabel: String
    )

    data class UiState(
        val issueNumber: Long,
        val busy: Boolean = false,
        val busyMessage: String? = null,
        val title: String = "",
        val issueUrl: String = "",
        val state: String = "open",
        val issueBody: String = "",
        val events: List<FeedbackThreadEvent> = emptyList(),
        val messageText: String = "",
        val screenshots: List<ScreenshotItem> = emptyList()
    ) {
        val isClosed: Boolean
            get() = state.equals("closed", ignoreCase = true)
    }

    private data class ScreenshotAttachment(
        val id: String,
        val file: File,
        val displayName: String
    )

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 4)
    private val screenshotAttachments = ArrayList<ScreenshotAttachment>()
    private var workingDir: File? = null
    private var didBind = false

    var uiState by mutableStateOf(UiState(issueNumber = issueNumber))
        private set

    val effects = _effects.asSharedFlow()

    fun bind(host: Activity) {
        if (didBind) {
            return
        }
        didBind = true
        FeedbackInboxCoordinator.bind(host.applicationContext)
        FeedbackIssueLocalStore.loadIssueCache(host, issueNumber)?.let { cache ->
            applyCache(cache)
        }
        executor.execute {
            FeedbackIssueSyncService.markIssueViewed(host, issueNumber)
            FeedbackInboxCoordinator.refreshFromStorage(host)
            runCatching {
                FeedbackIssueSyncService.refreshIssue(host, issueNumber, markViewed = true)
            }.onSuccess { cache ->
                FeedbackInboxCoordinator.refreshFromStorage(host)
                host.runOnUiThread {
                    applyCache(cache)
                }
            }
        }
    }

    fun onRefresh(host: Activity) {
        if (uiState.busy) {
            return
        }
        setBusy(true, "正在刷新议题内容...")
        executor.execute {
            runCatching {
                FeedbackIssueSyncService.refreshIssue(host, issueNumber, markViewed = true)
            }.onSuccess { cache ->
                FeedbackInboxCoordinator.refreshFromStorage(host)
                host.runOnUiThread {
                    setBusy(false, null)
                    applyCache(cache)
                }
            }.onFailure { error ->
                host.runOnUiThread {
                    setBusy(false, null)
                    Toast.makeText(host, "刷新失败：${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun onMessageChanged(value: String) {
        uiState = uiState.copy(messageText = value)
    }

    fun onAddScreenshots() {
        if (uiState.busy || screenshotAttachments.size >= MAX_SCREENSHOT_ATTACHMENTS) {
            return
        }
        _effects.tryEmit(Effect.OpenScreenshotPicker)
    }

    fun onScreenshotUrisPicked(host: Activity, uris: List<Uri>) {
        if (uiState.busy || uris.isEmpty()) {
            return
        }
        val remaining = MAX_SCREENSHOT_ATTACHMENTS - screenshotAttachments.size
        if (remaining <= 0) {
            Toast.makeText(host, "最多附加 $MAX_SCREENSHOT_ATTACHMENTS 张截图。", Toast.LENGTH_SHORT).show()
            return
        }
        setBusy(true, "正在处理截图...")
        executor.execute {
            runCatching {
                uris.take(remaining).map { uri ->
                    copyScreenshotAttachment(host, uri)
                }
            }.onSuccess { copied ->
                host.runOnUiThread {
                    screenshotAttachments.addAll(copied)
                    publishScreenshotState()
                    setBusy(false, null)
                }
            }.onFailure { error ->
                host.runOnUiThread {
                    setBusy(false, null)
                    Toast.makeText(host, "处理截图失败：${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun onRemoveScreenshot(screenshotId: String) {
        val iterator = screenshotAttachments.iterator()
        while (iterator.hasNext()) {
            val attachment = iterator.next()
            if (attachment.id == screenshotId) {
                attachment.file.delete()
                iterator.remove()
                break
            }
        }
        publishScreenshotState()
    }

    fun onSendMessage(host: Activity) {
        if (uiState.busy) {
            return
        }
        val message = uiState.messageText.trim()
        if (message.isEmpty() && screenshotAttachments.isEmpty()) {
            Toast.makeText(host, "请先输入消息或附加截图。", Toast.LENGTH_LONG).show()
            return
        }
        val screenshots = screenshotAttachments.map {
            FeedbackScreenshotAttachment(
                file = it.file,
                displayName = it.displayName
            )
        }
        setBusy(true, "正在发送消息...")
        executor.execute {
            runCatching {
                FeedbackConversationService.postMessage(
                    host = host,
                    issueNumber = issueNumber,
                    messageText = message,
                    screenshots = screenshots
                )
            }.onSuccess { comment ->
                val optimisticCache = appendOptimisticComment(host, comment)
                FeedbackInboxCoordinator.refreshFromStorage(host)
                host.runOnUiThread {
                    clearDraftState()
                    setBusy(false, null)
                    applyCache(optimisticCache)
                    Toast.makeText(host, "消息已发送。", Toast.LENGTH_SHORT).show()
                }
                runCatching {
                    FeedbackIssueSyncService.refreshIssue(host, issueNumber, markViewed = true)
                }.onSuccess { refreshedCache ->
                    val mergedCache = mergePostedCommentIntoCache(host, refreshedCache, comment)
                    FeedbackInboxCoordinator.refreshFromStorage(host)
                    host.runOnUiThread {
                        applyCache(mergedCache)
                    }
                }
            }.onFailure { error ->
                host.runOnUiThread {
                    setBusy(false, null)
                    Toast.makeText(host, "发送失败：${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun onCloseIssue(host: Activity) {
        updateIssueState(host, "closed", "正在关闭议题...", "议题已关闭。")
    }

    fun onReopenIssue(host: Activity) {
        updateIssueState(host, "open", "正在重新打开议题...", "议题已重新打开。")
    }

    override fun onCleared() {
        executor.shutdownNow()
        clearWorkingDir()
        super.onCleared()
    }

    private fun updateIssueState(
        host: Activity,
        targetState: String,
        busyMessage: String,
        successMessage: String
    ) {
        if (uiState.busy) {
            return
        }
        setBusy(true, busyMessage)
        executor.execute {
            runCatching {
                FeedbackConversationService.updateIssueState(issueNumber, targetState)
            }.onSuccess { stateResult ->
                val updatedCache = runCatching {
                    FeedbackIssueSyncService.refreshIssue(host, issueNumber, markViewed = true)
                }.getOrElse {
                    appendOptimisticStateChange(host, stateResult.state)
                }
                FeedbackInboxCoordinator.refreshFromStorage(host)
                host.runOnUiThread {
                    setBusy(false, null)
                    applyCache(updatedCache)
                    Toast.makeText(host, successMessage, Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                host.runOnUiThread {
                    setBusy(false, null)
                    Toast.makeText(host, "操作失败：${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun appendOptimisticComment(
        host: Activity,
        comment: FeedbackPostedComment
    ): FeedbackIssueThreadCache {
        val current = FeedbackIssueLocalStore.loadIssueCache(host, issueNumber)
        return mergePostedCommentIntoCache(host, current, comment)
    }

    private fun mergePostedCommentIntoCache(
        host: Activity,
        baseCache: FeedbackIssueThreadCache?,
        comment: FeedbackPostedComment
    ): FeedbackIssueThreadCache {
        val current = baseCache
            ?: FeedbackIssueThreadCache(
                issueNumber = issueNumber,
                issueUrl = FeedbackIssueSyncService.buildIssueUrl(issueNumber),
                title = uiState.title.ifBlank { "Issue #$issueNumber" },
                state = uiState.state,
                body = uiState.issueBody,
                updatedAtMs = comment.createdAtMs,
                events = emptyList()
            )
        val eventId = "comment-${comment.commentId}"
        if (current.events.any { it.id == eventId }) {
            return current
        }
        val updated = current.copy(
            updatedAtMs = maxOf(current.updatedAtMs, comment.createdAtMs),
            events = (
                current.events + FeedbackThreadEvent(
                    id = eventId,
                    type = FeedbackThreadEventType.COMMENT,
                    authorType = FeedbackThreadAuthorType.ME,
                    authorLabel = comment.playerName.ifBlank { "我" },
                    body = comment.body,
                    createdAtMs = comment.createdAtMs,
                    htmlUrl = comment.commentUrl,
                    attachments = comment.attachments
                )
            ).sortedBy { it.createdAtMs }
        )
        FeedbackIssueLocalStore.saveIssueCache(host, updated)
        FeedbackIssueSyncService.markIssueViewed(host, issueNumber)
        return updated
    }

    private fun appendOptimisticStateChange(
        host: Activity,
        state: String
    ): FeedbackIssueThreadCache {
        val now = System.currentTimeMillis()
        val current = FeedbackIssueLocalStore.loadIssueCache(host, issueNumber)
            ?: FeedbackIssueThreadCache(
                issueNumber = issueNumber,
                issueUrl = FeedbackIssueSyncService.buildIssueUrl(issueNumber),
                title = uiState.title.ifBlank { "Issue #$issueNumber" },
                state = state,
                body = uiState.issueBody,
                updatedAtMs = now,
                events = emptyList()
            )
        val updated = current.copy(
            state = state,
            updatedAtMs = now,
            events = (
                current.events + FeedbackThreadEvent(
                    id = "local-state-$now",
                    type = FeedbackThreadEventType.STATE_CHANGE,
                    authorType = FeedbackThreadAuthorType.SYSTEM,
                    authorLabel = "反馈系统",
                    body = if (state.equals("closed", ignoreCase = true)) {
                        "已关闭这个议题"
                    } else {
                        "重新打开了这个议题"
                    },
                    createdAtMs = now,
                    htmlUrl = null,
                    state = state
                )
            ).sortedBy { it.createdAtMs }
        )
        FeedbackIssueLocalStore.saveIssueCache(host, updated)
        FeedbackIssueSyncService.markIssueViewed(host, issueNumber)
        return updated
    }

    private fun applyCache(cache: FeedbackIssueThreadCache) {
        uiState = uiState.copy(
            title = cache.title,
            issueUrl = cache.issueUrl,
            state = cache.state,
            issueBody = cache.body,
            events = cache.events
        )
    }

    private fun clearDraftState() {
        clearWorkingDir()
        screenshotAttachments.clear()
        uiState = uiState.copy(
            messageText = "",
            screenshots = emptyList()
        )
    }

    private fun copyScreenshotAttachment(host: Activity, uri: Uri): ScreenshotAttachment {
        val screenshotsDir = File(ensureWorkingDir(host), "conversation-screenshots")
        if (!screenshotsDir.exists() && !screenshotsDir.mkdirs()) {
            throw IOException("Failed to create screenshot directory")
        }
        val displayName = SettingsFileService.resolveDisplayName(host, uri)
        val targetFile = buildUniqueTargetFile(screenshotsDir, displayName)
        SettingsFileService.copyUriToFile(host, uri, targetFile)
        return ScreenshotAttachment(
            id = UUID.randomUUID().toString(),
            file = targetFile,
            displayName = targetFile.name
        )
    }

    private fun ensureWorkingDir(host: Activity): File {
        val existing = workingDir
        if (existing != null && existing.exists()) {
            return existing
        }
        val dir = File(host.cacheDir, "feedback-conversation-${UUID.randomUUID()}")
        if (!dir.mkdirs()) {
            throw IOException("Failed to create conversation working directory")
        }
        workingDir = dir
        return dir
    }

    private fun clearWorkingDir() {
        val dir = workingDir
        workingDir = null
        dir?.deleteRecursively()
    }

    private fun buildUniqueTargetFile(parent: File, requestedName: String): File {
        val sanitizedName = sanitizeAttachmentFileName(requestedName)
        val dotIndex = sanitizedName.lastIndexOf('.')
        val base = if (dotIndex > 0) sanitizedName.substring(0, dotIndex) else sanitizedName
        val suffix = if (dotIndex > 0) sanitizedName.substring(dotIndex) else ""
        var index = 1
        while (true) {
            val candidateName = if (index == 1) {
                "$base$suffix"
            } else {
                "$base-$index$suffix"
            }
            val candidate = File(parent, candidateName)
            if (!candidate.exists()) {
                return candidate
            }
            index++
        }
    }

    private fun sanitizeAttachmentFileName(input: String): String {
        val requested = input.trim().ifEmpty { "screenshot.png" }
        val sanitized = buildString(requested.length) {
            requested.forEach { ch ->
                if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') {
                    append(ch)
                } else {
                    append('_')
                }
            }
        }
        return sanitized.ifEmpty { "screenshot.png" }
    }

    private fun publishScreenshotState() {
        uiState = uiState.copy(
            screenshots = screenshotAttachments.map { attachment ->
                ScreenshotItem(
                    id = attachment.id,
                    displayName = attachment.displayName,
                    sizeLabel = formatBytes(attachment.file.length())
                )
            }
        )
    }

    private fun setBusy(busy: Boolean, message: String?) {
        uiState = if (busy) {
            uiState.copy(
                busy = true,
                busyMessage = message
            )
        } else {
            uiState.copy(
                busy = false,
                busyMessage = null
            )
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) {
            return "0 B"
        }
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return if (unitIndex == 0) {
            "${value.toLong()} ${units[unitIndex]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIndex])
        }
    }
}
