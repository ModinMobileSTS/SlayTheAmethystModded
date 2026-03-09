package io.stamethyst.ui.feedback

import android.app.Activity
import android.net.Uri
import android.util.Patterns
import android.widget.Toast
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.stamethyst.BuildConfig
import io.stamethyst.backend.feedback.FeedbackCategory
import io.stamethyst.backend.feedback.FeedbackScreenshotAttachment
import io.stamethyst.backend.feedback.FeedbackSelectedMod
import io.stamethyst.backend.feedback.FeedbackSubmissionDraft
import io.stamethyst.backend.feedback.FeedbackSubmissionService
import io.stamethyst.backend.feedback.FeedbackUploadResult
import io.stamethyst.backend.feedback.GameIssueType
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.ui.settings.SettingsFileService
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Stable
class FeedbackScreenViewModel : ViewModel() {
    companion object {
        private const val MAX_SCREENSHOT_ATTACHMENTS = 4
    }

    sealed interface Effect {
        data object OpenScreenshotPicker : Effect
        data class SubmissionCompleted(
            val notice: FeedbackSubmissionNotice
        ) : Effect
    }

    data class ModOption(
        val key: String,
        val modId: String,
        val manifestModId: String,
        val name: String,
        val version: String,
        val required: Boolean,
        val storagePath: String
    )

    data class ScreenshotItem(
        val id: String,
        val displayName: String,
        val sizeLabel: String
    )

    data class UiState(
        val busy: Boolean = false,
        val busyMessage: String? = null,
        val endpointConfigured: Boolean = BuildConfig.FEEDBACK_ENDPOINT.trim().isNotEmpty(),
        val availableMods: List<ModOption> = emptyList(),
        val category: FeedbackCategory? = null,
        val gameIssueType: GameIssueType? = null,
        val reproducedOnLastRun: Boolean? = null,
        val suspectUnknown: Boolean = false,
        val selectedSuspectedModKeys: Set<String> = emptySet(),
        val summary: String = "",
        val detail: String = "",
        val reproductionSteps: String = "",
        val email: String = "",
        val screenshots: List<ScreenshotItem> = emptyList()
    )

    private data class ScreenshotAttachment(
        val id: String,
        val file: File,
        val displayName: String
    )

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val screenshotAttachments = ArrayList<ScreenshotAttachment>()
    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 4)
    private var workingDir: File? = null
    private var didBind = false

    var uiState by mutableStateOf(UiState())
        private set

    val effects = _effects.asSharedFlow()

    fun bind(host: Activity) {
        if (didBind) {
            return
        }
        didBind = true
        refreshAvailableMods(host)
    }

    fun onCategorySelected(category: FeedbackCategory) {
        uiState = when (category) {
            FeedbackCategory.GAME_BUG -> uiState.copy(
                category = category
            )
            else -> uiState.copy(
                category = category,
                gameIssueType = null,
                reproducedOnLastRun = null,
                suspectUnknown = false,
                selectedSuspectedModKeys = emptySet()
            )
        }
    }

    fun onGameIssueTypeSelected(issueType: GameIssueType) {
        uiState = uiState.copy(
            gameIssueType = issueType
        )
    }

    fun onReproducedOnLastRunSelected(value: Boolean) {
        uiState = uiState.copy(
            reproducedOnLastRun = value,
        )
    }

    fun onSuspectUnknownChanged(checked: Boolean) {
        uiState = uiState.copy(
            suspectUnknown = checked,
            selectedSuspectedModKeys = if (checked) emptySet() else uiState.selectedSuspectedModKeys
        )
    }

    fun onSuspectedModToggled(modKey: String, checked: Boolean) {
        val next = LinkedHashSet(uiState.selectedSuspectedModKeys)
        if (checked) {
            next.add(modKey)
        } else {
            next.remove(modKey)
        }
        uiState = uiState.copy(
            suspectUnknown = if (checked) false else uiState.suspectUnknown,
            selectedSuspectedModKeys = next
        )
    }

    fun onSummaryChanged(value: String) {
        uiState = uiState.copy(summary = value)
    }

    fun onDetailChanged(value: String) {
        uiState = uiState.copy(detail = value)
    }

    fun onReproductionStepsChanged(value: String) {
        uiState = uiState.copy(
            reproductionSteps = value
        )
    }

    fun onEmailChanged(value: String) {
        uiState = uiState.copy(email = value)
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
            try {
                val copied = ArrayList<ScreenshotAttachment>()
                uris.take(remaining).forEach { uri ->
                    val copiedAttachment = copyScreenshotAttachment(host, uri)
                    copied.add(copiedAttachment)
                }
                host.runOnUiThread {
                    screenshotAttachments.addAll(copied)
                    publishScreenshotState()
                    setBusy(false, null)
                    if (uris.size > remaining) {
                        Toast.makeText(
                            host,
                            "已达到截图上限，仅保留前 $MAX_SCREENSHOT_ATTACHMENTS 张。",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (error: Throwable) {
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

    fun onSubmit(host: Activity) {
        if (uiState.busy) {
            return
        }
        val validationError = validateDraft()
        if (validationError != null) {
            Toast.makeText(host, validationError, Toast.LENGTH_LONG).show()
            return
        }
        if (!uiState.endpointConfigured) {
            Toast.makeText(
                host,
                "当前构建未配置反馈上传地址，暂时无法提交。",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val draft = buildDraft()
        setBusy(true, "正在整理反馈并上传...")
        executor.execute {
            try {
                val result = FeedbackSubmissionService.submit(host, draft)
                host.runOnUiThread {
                    val notice = buildSubmissionNotice(result)
                    applySubmissionSuccess()
                    _effects.tryEmit(Effect.SubmissionCompleted(notice))
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    Toast.makeText(host, "提交反馈失败：${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCleared() {
        executor.shutdownNow()
        clearWorkingDir()
        super.onCleared()
    }

    private fun refreshAvailableMods(host: Activity) {
        executor.execute {
            val mods = ModManager.listInstalledMods(host)
                .asSequence()
                .filter { it.enabled }
                .map { mod ->
                    ModOption(
                        key = mod.jarFile.absolutePath,
                        modId = mod.modId,
                        manifestModId = mod.manifestModId,
                        name = mod.name,
                        version = mod.version,
                        required = mod.required,
                        storagePath = mod.jarFile.absolutePath
                    )
                }
                .toList()
            host.runOnUiThread {
                uiState = uiState.copy(availableMods = mods)
            }
        }
    }

    private fun copyScreenshotAttachment(host: Activity, uri: Uri): ScreenshotAttachment {
        val screenshotsDir = File(ensureWorkingDir(host), "screenshots")
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
        val dir = File(host.cacheDir, "feedback-draft-${UUID.randomUUID()}")
        if (!dir.mkdirs()) {
            throw IOException("Failed to create feedback working directory")
        }
        workingDir = dir
        return dir
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

    private fun validateDraft(): String? {
        val category = uiState.category ?: return "请先选择反馈类型。"
        if (uiState.summary.trim().isEmpty()) {
            return "请先填写一句话总结。"
        }
        if (uiState.email.isNotBlank() && !Patterns.EMAIL_ADDRESS.matcher(uiState.email.trim()).matches()) {
            return "邮箱格式看起来不正确。"
        }
        return when (category) {
            FeedbackCategory.FEATURE_REQUEST -> {
                if (uiState.detail.trim().isEmpty()) {
                    "请描述你希望加入的功能。"
                } else {
                    null
                }
            }
            FeedbackCategory.LAUNCHER_BUG -> {
                when {
                    uiState.detail.trim().isEmpty() -> "请描述启动器问题。"
                    uiState.reproductionSteps.trim().isEmpty() -> "请填写复现步骤。"
                    else -> null
                }
            }
            FeedbackCategory.GAME_BUG -> {
                when {
                    uiState.reproducedOnLastRun == null -> "请先判断这是不是最近一次运行复现的问题。"
                    !uiState.suspectUnknown && uiState.selectedSuspectedModKeys.isEmpty() -> "请勾选你怀疑的模组，或选择“不确定”。"
                    uiState.gameIssueType == null -> "请选择问题表现类型。"
                    uiState.detail.trim().isEmpty() -> "请描述游戏内问题。"
                    uiState.reproductionSteps.trim().isEmpty() -> "请填写复现步骤。"
                    else -> null
                }
            }
        }
    }

    private fun buildDraft(): FeedbackSubmissionDraft {
        val selectedMods = uiState.availableMods
            .filter { uiState.selectedSuspectedModKeys.contains(it.key) }
            .map { mod ->
                FeedbackSelectedMod(
                    key = mod.key,
                    modId = mod.modId,
                    manifestModId = mod.manifestModId,
                    name = mod.name,
                    version = mod.version,
                    required = mod.required,
                    storagePath = mod.storagePath
                )
            }
        return FeedbackSubmissionDraft(
            category = requireNotNull(uiState.category),
            summary = uiState.summary.trim(),
            detail = uiState.detail.trim(),
            reproductionSteps = uiState.reproductionSteps.trim(),
            email = uiState.email.trim().ifEmpty { null },
            reproducedOnLastRun = uiState.reproducedOnLastRun,
            gameIssueType = uiState.gameIssueType,
            suspectedMods = selectedMods,
            suspectUnknown = uiState.suspectUnknown,
            screenshots = screenshotAttachments.map { attachment ->
                FeedbackScreenshotAttachment(
                    file = attachment.file,
                    displayName = attachment.displayName
                )
            }
        )
    }

    private fun applySubmissionSuccess() {
        val currentMods = uiState.availableMods
        clearWorkingDir()
        screenshotAttachments.clear()
        uiState = UiState(
            endpointConfigured = uiState.endpointConfigured,
            availableMods = currentMods
        )
    }

    private fun buildSubmissionNotice(result: FeedbackUploadResult): FeedbackSubmissionNotice {
        val message = when {
            result.issueNumber != null -> "反馈已提交，GitHub Issue #${result.issueNumber} 已创建。"
            !result.issueUrl.isNullOrBlank() -> "反馈已提交，Issue 已创建。"
            else -> "反馈已提交，云函数已接收请求。"
        }
        return FeedbackSubmissionNotice(
            title = "反馈已提交",
            message = message,
            issueUrl = result.issueUrl
        )
    }

    private fun clearWorkingDir() {
        val dir = workingDir
        workingDir = null
        dir?.deleteRecursively()
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
