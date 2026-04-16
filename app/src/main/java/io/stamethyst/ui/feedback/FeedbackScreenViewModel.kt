package io.stamethyst.ui.feedback

import android.app.Activity
import android.net.Uri
import android.util.Patterns
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.stamethyst.BuildConfig
import io.stamethyst.R
import io.stamethyst.backend.feedback.FeedbackCategory
import io.stamethyst.backend.feedback.FeedbackInboxCoordinator
import io.stamethyst.backend.feedback.FeedbackSubscriptionChangeResult
import io.stamethyst.backend.feedback.FeedbackIssueSyncService
import io.stamethyst.backend.feedback.FeedbackScreenshotAttachment
import io.stamethyst.backend.feedback.FeedbackSelectedMod
import io.stamethyst.backend.feedback.FeedbackSubmissionDraft
import io.stamethyst.backend.feedback.FeedbackSubmissionService
import io.stamethyst.backend.feedback.FeedbackUploadResult
import io.stamethyst.backend.feedback.GameIssueType
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.ui.LauncherTransientNoticeBus
import io.stamethyst.ui.settings.SettingsFileService
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal const val BRIEF_FEEDBACK_WARNING_THRESHOLD = 100
internal const val FEEDBACK_SUBMISSION_WARNING_STATUS = 0

internal fun calculateDetailedFeedbackLength(
    detail: String,
    reproductionSteps: String
): Int {
    return detail.trim().count { !it.isWhitespace() } +
        reproductionSteps.trim().count { !it.isWhitespace() }
}

@Stable
class FeedbackScreenViewModel : ViewModel() {
    companion object {
        private const val MAX_SCREENSHOT_ATTACHMENTS = 4
    }

    enum class SubmissionStep {
        CATEGORY_SELECTION,
        FORM,
        SUBMISSION_CONFIRMATION;

        fun previousStep(): SubmissionStep? = when (this) {
            CATEGORY_SELECTION -> null
            FORM -> CATEGORY_SELECTION
            SUBMISSION_CONFIRMATION -> FORM
        }
    }

    enum class SubmissionAcknowledgement(
        @param:StringRes val titleResId: Int,
        val requiredForSubmission: Boolean = true,
        val interceptsSubmission: Boolean = false
    ) {
        UNCLEAR_DESCRIPTION_DELAYS_RESOLUTION(
            R.string.feedback_submission_acknowledgement_1
        ),
        MOD_CONFLICT_NOT_SUPPORTED(
            R.string.feedback_submission_acknowledgement_2
        ),
        TRIED_FIXING_BEFORE_SUBMITTING(
            R.string.feedback_submission_acknowledgement_3
        ),
        DEVELOPER_IS_NOT_CUSTOMER_SUPPORT(
            R.string.feedback_submission_acknowledgement_4
        ),
        DESCRIPTION_IS_CLEAR_TO_UNFAMILIAR_DEVELOPERS(
            R.string.feedback_submission_acknowledgement_5
        ),
        SHOULD_NOT_CHECK_THIS_BOX(
            R.string.feedback_submission_acknowledgement_6,
            requiredForSubmission = false,
            interceptsSubmission = true
        )
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
        val submissionStep: SubmissionStep = SubmissionStep.CATEGORY_SELECTION,
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
        val emailNotificationsEnabled: Boolean = true,
        val screenshots: List<ScreenshotItem> = emptyList(),
        val checkedSubmissionAcknowledgements: Set<SubmissionAcknowledgement> = emptySet(),
        val submissionStatus: Int? = null,
        val showSubmissionAttentionWarning: Boolean = false,
        val showBriefFeedbackConfirmation: Boolean = false
    ) {
        val detailedFeedbackLength: Int
            get() = calculateDetailedFeedbackLength(detail, reproductionSteps)

        val shouldWarnAboutBriefFeedback: Boolean
            get() = detailedFeedbackLength <= BRIEF_FEEDBACK_WARNING_THRESHOLD

        val allSubmissionAcknowledgementsChecked: Boolean
            get() = checkedSubmissionAcknowledgements.containsAll(
                SubmissionAcknowledgement.entries.filter { it.requiredForSubmission }
            )

        val hasSubmissionInterceptionAcknowledgementChecked: Boolean
            get() = checkedSubmissionAcknowledgements.any { it.interceptsSubmission }
    }

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

    fun onContinueAfterCategorySelected() {
        if (uiState.category == null) {
            return
        }
        uiState = uiState.copy(submissionStep = SubmissionStep.FORM)
    }

    fun onReturnToCategorySelection() {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(
            submissionStep = SubmissionStep.CATEGORY_SELECTION,
            showSubmissionAttentionWarning = false,
            showBriefFeedbackConfirmation = false
        )
    }

    fun onContinueAfterFormFilled(host: Activity) {
        if (uiState.busy) {
            return
        }
        val validationError = validateDraft(host)
        if (validationError != null) {
            LauncherTransientNoticeBus.show(host, validationError, Toast.LENGTH_LONG)
            return
        }
        if (!uiState.endpointConfigured) {
            LauncherTransientNoticeBus.show(
                host,
                host.getString(R.string.feedback_endpoint_missing_submit),
                Toast.LENGTH_LONG
            )
            return
        }
        uiState = uiState.copy(
            submissionStep = SubmissionStep.SUBMISSION_CONFIRMATION,
            showSubmissionAttentionWarning = false,
            showBriefFeedbackConfirmation = false
        )
    }

    fun onReturnToForm() {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(
            submissionStep = SubmissionStep.FORM,
            showSubmissionAttentionWarning = false,
            showBriefFeedbackConfirmation = false
        )
    }

    fun onSubmissionAcknowledgementChanged(
        acknowledgement: SubmissionAcknowledgement,
        checked: Boolean
    ) {
        val next = uiState.checkedSubmissionAcknowledgements.toMutableSet()
        if (checked) {
            next.add(acknowledgement)
        } else {
            next.remove(acknowledgement)
        }
        uiState = uiState.copy(checkedSubmissionAcknowledgements = next)
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

    fun onEmailNotificationsEnabledChanged(enabled: Boolean) {
        uiState = uiState.copy(emailNotificationsEnabled = enabled)
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
            LauncherTransientNoticeBus.show(
                host,
                host.getString(R.string.feedback_screenshot_limit, MAX_SCREENSHOT_ATTACHMENTS),
                Toast.LENGTH_SHORT
            )
            return
        }
        setBusy(true, host.getString(R.string.feedback_busy_processing_screenshots))
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
                        LauncherTransientNoticeBus.show(
                            host,
                            host.getString(
                                R.string.feedback_screenshot_limit_trimmed,
                                MAX_SCREENSHOT_ATTACHMENTS
                            ),
                            Toast.LENGTH_LONG
                        )
                    }
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    LauncherTransientNoticeBus.show(
                        host,
                        host.getString(
                            R.string.feedback_screenshot_processing_failed,
                            error.message ?: host.getString(R.string.feedback_unknown_error)
                        ),
                        Toast.LENGTH_LONG
                    )
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
        submitInternal(host, ignoreBriefFeedbackWarning = false)
    }

    fun onDismissBriefFeedbackConfirmation() {
        if (!uiState.showBriefFeedbackConfirmation) {
            return
        }
        uiState = uiState.copy(showBriefFeedbackConfirmation = false)
    }

    fun onDismissSubmissionAttentionWarning() {
        if (!uiState.showSubmissionAttentionWarning) {
            return
        }
        uiState = uiState.copy(showSubmissionAttentionWarning = false)
    }

    fun onSubmitDespiteBriefFeedback(host: Activity) {
        submitInternal(host, ignoreBriefFeedbackWarning = true)
    }

    private fun submitInternal(host: Activity, ignoreBriefFeedbackWarning: Boolean) {
        if (uiState.busy) {
            return
        }
        if (ignoreBriefFeedbackWarning && uiState.showBriefFeedbackConfirmation) {
            uiState = uiState.copy(showBriefFeedbackConfirmation = false)
        }
        val validationError = validateDraft(host)
        if (validationError != null) {
            LauncherTransientNoticeBus.show(host, validationError, Toast.LENGTH_LONG)
            return
        }
        val acknowledgementValidationError = validateSubmissionAcknowledgements(host)
        if (acknowledgementValidationError != null) {
            LauncherTransientNoticeBus.show(host, acknowledgementValidationError, Toast.LENGTH_LONG)
            return
        }
        if (uiState.hasSubmissionInterceptionAcknowledgementChecked) {
            uiState = uiState.copy(
                submissionStatus = FEEDBACK_SUBMISSION_WARNING_STATUS,
                showSubmissionAttentionWarning = true,
                showBriefFeedbackConfirmation = false
            )
            return
        }
        if (!uiState.endpointConfigured) {
            LauncherTransientNoticeBus.show(
                host,
                host.getString(R.string.feedback_endpoint_missing_submit),
                Toast.LENGTH_LONG
            )
            return
        }
        if (!ignoreBriefFeedbackWarning && uiState.shouldWarnAboutBriefFeedback) {
            uiState = uiState.copy(showBriefFeedbackConfirmation = true)
            return
        }

        uiState = uiState.copy(showBriefFeedbackConfirmation = false)
        val draft = buildDraft()
        setBusy(true, host.getString(R.string.feedback_busy_submitting))
        executor.execute {
            try {
                val result = FeedbackSubmissionService.submit(host, draft)
                val autoSubscription = runCatching {
                    autoSubscribeCreatedIssue(host, draft, result)
                }.getOrNull()
                host.runOnUiThread {
                    val notice = buildSubmissionNotice(host, result, autoSubscription)
                    applySubmissionSuccess()
                    _effects.tryEmit(Effect.SubmissionCompleted(notice))
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    LauncherTransientNoticeBus.show(
                        host,
                        host.getString(
                            R.string.feedback_submit_failed,
                            error.message ?: host.getString(R.string.feedback_unknown_error)
                        ),
                        Toast.LENGTH_LONG
                    )
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

    private fun validateDraft(host: Activity): String? {
        val category = uiState.category ?: return host.getString(R.string.feedback_validation_select_category)
        if (uiState.summary.trim().isEmpty()) {
            return host.getString(R.string.feedback_validation_summary_required)
        }
        if (uiState.emailNotificationsEnabled) {
            if (uiState.email.trim().isEmpty()) {
                return host.getString(R.string.feedback_validation_email_required)
            }
            if (!Patterns.EMAIL_ADDRESS.matcher(uiState.email.trim()).matches()) {
                return host.getString(R.string.feedback_validation_email_invalid)
            }
        }
        return when (category) {
            FeedbackCategory.FEATURE_REQUEST -> {
                if (uiState.detail.trim().isEmpty()) {
                    host.getString(R.string.feedback_validation_feature_detail)
                } else {
                    null
                }
            }
            FeedbackCategory.LAUNCHER_BUG -> {
                when {
                    uiState.detail.trim().isEmpty() -> host.getString(R.string.feedback_validation_launcher_detail)
                    uiState.reproductionSteps.trim().isEmpty() -> host.getString(R.string.feedback_validation_reproduction)
                    else -> null
                }
            }
            FeedbackCategory.GAME_BUG -> {
                when {
                    uiState.reproducedOnLastRun == null -> host.getString(R.string.feedback_validation_recent_run)
                    !uiState.suspectUnknown && uiState.selectedSuspectedModKeys.isEmpty() ->
                        host.getString(R.string.feedback_validation_suspected_mod)
                    uiState.gameIssueType == null -> host.getString(R.string.feedback_validation_issue_type)
                    uiState.detail.trim().isEmpty() -> host.getString(R.string.feedback_validation_game_detail)
                    uiState.reproductionSteps.trim().isEmpty() -> host.getString(R.string.feedback_validation_reproduction)
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
            email = if (uiState.emailNotificationsEnabled) {
                uiState.email.trim().ifEmpty { null }
            } else {
                null
            },
            emailNotificationsEnabled = uiState.emailNotificationsEnabled,
            reproducedOnLastRun = uiState.reproducedOnLastRun,
            gameIssueType = uiState.gameIssueType,
            suspectedMods = selectedMods,
            suspectUnknown = uiState.suspectUnknown,
            screenshots = screenshotAttachments.map { attachment ->
                FeedbackScreenshotAttachment(
                    file = attachment.file,
                    displayName = attachment.displayName
                )
            },
            submissionStatus = uiState.submissionStatus
        )
    }

    private fun applySubmissionSuccess() {
        val currentMods = uiState.availableMods
        clearWorkingDir()
        screenshotAttachments.clear()
        uiState = UiState(
            submissionStep = SubmissionStep.CATEGORY_SELECTION,
            endpointConfigured = uiState.endpointConfigured,
            availableMods = currentMods
        )
    }

    private fun autoSubscribeCreatedIssue(
        host: Activity,
        draft: FeedbackSubmissionDraft,
        result: FeedbackUploadResult
    ): FeedbackSubscriptionChangeResult? {
        val issueNumber = resolveCreatedIssueNumber(result) ?: return null
        val issueUrl = result.issueUrl ?: FeedbackIssueSyncService.buildIssueUrl(issueNumber)
        val changeResult = FeedbackIssueSyncService.saveLocalSubscription(
            context = host,
            issueNumber = issueNumber,
            issueUrl = issueUrl,
            title = buildSubmittedIssueTitle(draft),
            issueBody = buildSubmittedIssueBodyPreview(host, draft)
        )
        FeedbackInboxCoordinator.refreshFromStorage(host)
        return changeResult
    }

    private fun buildSubmissionNotice(
        host: Activity,
        result: FeedbackUploadResult,
        autoSubscription: FeedbackSubscriptionChangeResult?
    ): FeedbackSubmissionNotice {
        val displacedIssueNumber = autoSubscription?.displacedSubscriptions?.firstOrNull()?.issueNumber
        val message = when {
            result.issueNumber != null && displacedIssueNumber != null -> {
                host.getString(
                    R.string.feedback_submission_notice_issue_followed_with_replacement,
                    result.issueNumber,
                    displacedIssueNumber
                )
            }
            result.issueNumber != null && autoSubscription != null -> {
                host.getString(
                    R.string.feedback_submission_notice_issue_followed,
                    result.issueNumber
                )
            }
            result.issueNumber != null -> {
                host.getString(
                    R.string.feedback_submission_notice_issue_created,
                    result.issueNumber
                )
            }
            !result.issueUrl.isNullOrBlank() -> host.getString(R.string.feedback_submission_notice_issue_generic)
            else -> host.getString(R.string.feedback_submission_notice_request_received)
        }
        return FeedbackSubmissionNotice(
            title = host.getString(R.string.feedback_submission_notice_title),
            message = message,
            issueUrl = result.issueUrl ?: result.issueNumber?.let { issueNumber ->
                FeedbackIssueSyncService.buildIssueUrl(issueNumber)
            }
        )
    }

    private fun resolveCreatedIssueNumber(result: FeedbackUploadResult): Long? {
        result.issueNumber?.takeIf { it > 0L }?.let { return it }
        val issueUrl = result.issueUrl?.trim().orEmpty()
        if (issueUrl.isEmpty()) {
            return null
        }
        return Regex(""".*/issues/(\d+)(?:[/?#].*)?$""")
            .matchEntire(issueUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
    }

    private fun buildSubmittedIssueTitle(draft: FeedbackSubmissionDraft): String {
        val prefix = when (draft.category) {
            FeedbackCategory.FEATURE_REQUEST -> "[${draft.category.issuePrefix}]"
            FeedbackCategory.LAUNCHER_BUG -> "[${draft.category.issuePrefix}]"
            FeedbackCategory.GAME_BUG -> {
                val issueType = draft.gameIssueType
                if (issueType == null) {
                    "[${draft.category.issuePrefix}]"
                } else {
                    "[${draft.category.issuePrefix}][${issueType.issuePrefix}]"
                }
            }
        }
        return "$prefix ${draft.summary.trim().replace('\n', ' ')}".take(120)
    }

    private fun buildSubmittedIssueBodyPreview(host: Activity, draft: FeedbackSubmissionDraft): String {
        return buildString {
            append(draft.detail.trim())
            if (draft.reproductionSteps.isNotBlank()) {
                if (isNotEmpty()) {
                    append("\n\n")
                }
                append(host.getString(R.string.feedback_issue_body_preview_reproduction_steps))
                append('\n')
                append(draft.reproductionSteps.trim())
            }
        }
    }

    private fun validateSubmissionAcknowledgements(host: Activity): String? {
        return if (uiState.allSubmissionAcknowledgementsChecked) {
            null
        } else {
            host.getString(R.string.feedback_validation_submission_acknowledgements)
        }
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
