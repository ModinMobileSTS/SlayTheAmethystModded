package io.stamethyst.ui.feedback

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.stamethyst.R
import io.stamethyst.backend.feedback.FeedbackCategory
import io.stamethyst.backend.feedback.FeedbackInboxCoordinator
import io.stamethyst.backend.feedback.FeedbackInboxUiState
import io.stamethyst.backend.feedback.FeedbackIssueBrowseItem
import io.stamethyst.backend.feedback.FeedbackIssueSubscription
import io.stamethyst.backend.feedback.GameIssueType
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.Icons
import io.stamethyst.ui.icon.ArrowBack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private const val SUBMISSION_ATTENTION_WARNING_CLOSE_DELAY_SECONDS = 5
private const val MISSING_EMAIL_WARNING_SUBMIT_DELAY_SECONDS = 3

private data class FeedbackCategoryOption(
    val category: FeedbackCategory,
    @param:StringRes val titleResId: Int,
    @param:StringRes val descriptionResId: Int
)

private val FeedbackCategoryOptions = listOf(
    FeedbackCategoryOption(
        category = FeedbackCategory.GAME_BUG,
        titleResId = R.string.feedback_category_game_bug,
        descriptionResId = R.string.feedback_category_game_bug_description
    ),
    FeedbackCategoryOption(
        category = FeedbackCategory.LAUNCHER_BUG,
        titleResId = R.string.feedback_category_launcher_bug,
        descriptionResId = R.string.feedback_category_launcher_bug_description
    ),
    FeedbackCategoryOption(
        category = FeedbackCategory.FEATURE_REQUEST,
        titleResId = R.string.feedback_category_feature_request,
        descriptionResId = R.string.feedback_category_feature_request_description
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherFeedbackScreen(
    modifier: Modifier = Modifier,
    onSubmissionCompleted: (FeedbackSubmissionNotice) -> Unit = {}
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val formViewModel: FeedbackScreenViewModel = viewModel()

    LaunchedEffect(Unit) {
        formViewModel.bind(activity)
    }

    LauncherFeedbackScreenContent(
        modifier = modifier,
        formUiState = formViewModel.uiState,
        onGoBack = navigator::goBack,
        onContinueAfterAcknowledgementsConfirmed = formViewModel::onContinueAfterAcknowledgementsConfirmed,
        onReturnToAcknowledgements = formViewModel::onReturnToAcknowledgements,
        onCategorySelected = formViewModel::onCategorySelected,
        onGameIssueTypeSelected = formViewModel::onGameIssueTypeSelected,
        onReproducedOnLastRunSelected = formViewModel::onReproducedOnLastRunSelected,
        onSuspectUnknownChanged = formViewModel::onSuspectUnknownChanged,
        onSuspectedModToggled = formViewModel::onSuspectedModToggled,
        onSummaryChanged = formViewModel::onSummaryChanged,
        onDetailChanged = formViewModel::onDetailChanged,
        onReproductionStepsChanged = formViewModel::onReproductionStepsChanged,
        onEmailChanged = formViewModel::onEmailChanged,
        onEmailNotificationsEnabledChanged = formViewModel::onEmailNotificationsEnabledChanged,
        onSubmissionAcknowledgementChanged = formViewModel::onSubmissionAcknowledgementChanged,
        onAddScreenshots = formViewModel::onAddScreenshots,
        onRemoveScreenshot = formViewModel::onRemoveScreenshot,
        onSubmit = { formViewModel.onSubmit(activity) },
        onDismissSubmissionAttentionWarning = formViewModel::onDismissSubmissionAttentionWarning,
        onDismissMissingEmailConfirmation = formViewModel::onDismissMissingEmailConfirmation,
        onSubmitWithoutEmail = { formViewModel.onSubmitWithoutEmail(activity) },
        onDismissBriefFeedbackConfirmation = formViewModel::onDismissBriefFeedbackConfirmation,
        onSubmitDespiteBriefFeedback = { formViewModel.onSubmitDespiteBriefFeedback(activity) }
    )
    FeedbackEffectsHandler(
        viewModel = formViewModel,
        onSubmissionCompleted = onSubmissionCompleted
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherFeedbackSubscriptionsScreen(
    modifier: Modifier = Modifier,
    onOpenConversation: (Long) -> Unit = {}
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val subscriptionsViewModel: FeedbackSubscriptionsViewModel = viewModel()
    val inboxState by FeedbackInboxCoordinator.uiState.collectAsState()

    LaunchedEffect(Unit) {
        subscriptionsViewModel.bind(activity)
        FeedbackInboxCoordinator.bind(activity.applicationContext)
    }

    LauncherFeedbackSubscriptionsScreenContent(
        modifier = modifier,
        uiState = subscriptionsViewModel.uiState,
        inboxState = inboxState,
        onGoBack = navigator::goBack,
        onRefreshSubscriptions = { subscriptionsViewModel.onRefreshAll(activity) },
        onUnsubscribeIssue = { issueNumber ->
            subscriptionsViewModel.onUnsubscribe(activity, issueNumber)
        },
        onOpenConversation = onOpenConversation
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherFeedbackIssueBrowserScreen(
    modifier: Modifier = Modifier
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val browserViewModel: FeedbackIssueBrowserViewModel = viewModel()
    val inboxState by FeedbackInboxCoordinator.uiState.collectAsState()

    LaunchedEffect(Unit) {
        browserViewModel.bind(activity)
        FeedbackInboxCoordinator.bind(activity.applicationContext)
    }

    LauncherFeedbackIssueBrowserScreenContent(
        modifier = modifier,
        uiState = browserViewModel.uiState,
        followedIssueNumbers = inboxState.subscriptions.map { it.issueNumber }.toSet(),
        onGoBack = navigator::goBack,
        onRefreshIssues = { browserViewModel.onRefresh(activity) },
        onLoadMoreIssues = { browserViewModel.onLoadMore(activity) },
        onIssueStateFilterSelected = browserViewModel::onIssueStateFilterSelected,
        onFollowIssue = { issueNumber -> browserViewModel.onSubscribe(activity, issueNumber) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LauncherFeedbackScreenContent(
    modifier: Modifier = Modifier,
    formUiState: FeedbackScreenViewModel.UiState,
    onGoBack: () -> Unit = {},
    onContinueAfterAcknowledgementsConfirmed: () -> Unit = {},
    onReturnToAcknowledgements: () -> Unit = {},
    onCategorySelected: (FeedbackCategory) -> Unit = {},
    onGameIssueTypeSelected: (GameIssueType) -> Unit = {},
    onReproducedOnLastRunSelected: (Boolean) -> Unit = {},
    onSuspectUnknownChanged: (Boolean) -> Unit = {},
    onSuspectedModToggled: (String, Boolean) -> Unit = { _, _ -> },
    onSummaryChanged: (String) -> Unit = {},
    onDetailChanged: (String) -> Unit = {},
    onReproductionStepsChanged: (String) -> Unit = {},
    onEmailChanged: (String) -> Unit = {},
    onEmailNotificationsEnabledChanged: (Boolean) -> Unit = {},
    onSubmissionAcknowledgementChanged: (
        FeedbackScreenViewModel.SubmissionAcknowledgement,
        Boolean
    ) -> Unit = { _, _ -> },
    onAddScreenshots: () -> Unit = {},
    onRemoveScreenshot: (String) -> Unit = {},
    onSubmit: () -> Unit = {},
    onDismissSubmissionAttentionWarning: () -> Unit = {},
    onDismissMissingEmailConfirmation: () -> Unit = {},
    onSubmitWithoutEmail: () -> Unit = {},
    onDismissBriefFeedbackConfirmation: () -> Unit = {},
    onSubmitDespiteBriefFeedback: () -> Unit = {}
) {
    val currentStep = formUiState.submissionStep
    val previousStep = currentStep.previousStep()

    val handleBackNavigation = {
        when (previousStep) {
            FeedbackScreenViewModel.SubmissionStep.ACKNOWLEDGEMENT -> onReturnToAcknowledgements()
            FeedbackScreenViewModel.SubmissionStep.FORM -> Unit
            null -> onGoBack()
        }
    }

    BackHandler(
        enabled = previousStep != null && !formUiState.busy
    ) {
        handleBackNavigation()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feedback_new_issue_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            handleBackNavigation()
                        },
                        enabled = !formUiState.busy
                    ) {
                        Icon(
                            imageVector = Icons.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (currentStep) {
                FeedbackScreenViewModel.SubmissionStep.ACKNOWLEDGEMENT -> {
                    FeedbackAcknowledgementGateContent(
                        modifier = Modifier.fillMaxSize(),
                        uiState = formUiState,
                        onSubmissionAcknowledgementChanged = onSubmissionAcknowledgementChanged,
                        onContinueAfterAcknowledgementsConfirmed =
                            onContinueAfterAcknowledgementsConfirmed
                    )
                }
                FeedbackScreenViewModel.SubmissionStep.FORM -> {
                    FeedbackSubmissionTabContent(
                        modifier = Modifier.fillMaxSize(),
                        uiState = formUiState,
                        onCategorySelected = onCategorySelected,
                        onGameIssueTypeSelected = onGameIssueTypeSelected,
                        onReproducedOnLastRunSelected = onReproducedOnLastRunSelected,
                        onSuspectUnknownChanged = onSuspectUnknownChanged,
                        onSuspectedModToggled = onSuspectedModToggled,
                        onSummaryChanged = onSummaryChanged,
                        onDetailChanged = onDetailChanged,
                        onReproductionStepsChanged = onReproductionStepsChanged,
                        onEmailChanged = onEmailChanged,
                        onEmailNotificationsEnabledChanged = onEmailNotificationsEnabledChanged,
                        onAddScreenshots = onAddScreenshots,
                        onRemoveScreenshot = onRemoveScreenshot,
                        onSubmit = onSubmit
                    )
                }
            }
        }
    }

    if (formUiState.showSubmissionAttentionWarning) {
        var remainingSeconds by remember { mutableIntStateOf(SUBMISSION_ATTENTION_WARNING_CLOSE_DELAY_SECONDS) }
        LaunchedEffect(Unit) {
            while (remainingSeconds > 0) {
                delay(1_000L)
                remainingSeconds--
            }
        }
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.feedback_submission_attention_warning_title)) },
            text = {
                Text(
                    stringResource(R.string.feedback_submission_attention_warning_message)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = onDismissSubmissionAttentionWarning,
                    enabled = remainingSeconds == 0
                ) {
                    Text(
                        if (remainingSeconds > 0) {
                            stringResource(
                                R.string.feedback_submission_attention_warning_close_countdown,
                                remainingSeconds
                            )
                        } else {
                            stringResource(R.string.common_action_close)
                        }
                    )
                }
            }
        )
    }

    if (formUiState.showBriefFeedbackConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissBriefFeedbackConfirmation,
            title = { Text(stringResource(R.string.feedback_brief_warning_title)) },
            text = {
                Text(
                    stringResource(R.string.feedback_brief_warning_message)
                )
            },
            confirmButton = {
                TextButton(onClick = onDismissBriefFeedbackConfirmation) {
                    Text(stringResource(R.string.feedback_brief_warning_continue_editing))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onSubmitDespiteBriefFeedback,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(stringResource(R.string.feedback_brief_warning_submit_anyway))
                }
            }
        )
    }

    if (formUiState.showMissingEmailConfirmation) {
        var remainingSeconds by remember { mutableIntStateOf(MISSING_EMAIL_WARNING_SUBMIT_DELAY_SECONDS) }
        LaunchedEffect(Unit) {
            while (remainingSeconds > 0) {
                delay(1_000L)
                remainingSeconds--
            }
        }
        AlertDialog(
            onDismissRequest = onDismissMissingEmailConfirmation,
            title = { Text(stringResource(R.string.feedback_missing_email_warning_title)) },
            text = {
                Text(
                    stringResource(R.string.feedback_missing_email_warning_message)
                )
            },
            confirmButton = {
                TextButton(onClick = onDismissMissingEmailConfirmation) {
                    Text(stringResource(R.string.feedback_missing_email_warning_continue_editing))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onSubmitWithoutEmail,
                    enabled = remainingSeconds == 0,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(
                        if (remainingSeconds > 0) {
                            stringResource(
                                R.string.feedback_missing_email_warning_submit_countdown,
                                remainingSeconds
                            )
                        } else {
                            stringResource(R.string.feedback_missing_email_warning_submit_anyway)
                        }
                    )
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LauncherFeedbackSubscriptionsScreenContent(
    modifier: Modifier = Modifier,
    uiState: FeedbackSubscriptionsViewModel.UiState,
    inboxState: FeedbackInboxUiState,
    onGoBack: () -> Unit = {},
    onRefreshSubscriptions: () -> Unit = {},
    onUnsubscribeIssue: (Long) -> Unit = {},
    onOpenConversation: (Long) -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (inboxState.unreadIssueCount > 0) {
                            stringResource(
                                R.string.feedback_subscriptions_title_with_count,
                                inboxState.unreadIssueCount
                            )
                        } else {
                            stringResource(R.string.feedback_subscriptions_title)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onGoBack) {
                        Icon(
                            imageVector = Icons.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        FeedbackSubscriptionsContent(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            uiState = uiState,
            inboxState = inboxState,
            onRefreshSubscriptions = onRefreshSubscriptions,
            onUnsubscribeIssue = onUnsubscribeIssue,
            onOpenConversation = onOpenConversation
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LauncherFeedbackIssueBrowserScreenContent(
    modifier: Modifier = Modifier,
    uiState: FeedbackIssueBrowserViewModel.UiState,
    followedIssueNumbers: Set<Long>,
    onGoBack: () -> Unit = {},
    onRefreshIssues: () -> Unit = {},
    onLoadMoreIssues: () -> Unit = {},
    onIssueStateFilterSelected: (FeedbackIssueBrowserViewModel.IssueStateFilter) -> Unit = {},
    onFollowIssue: (Long) -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feedback_issue_browser_title)) },
                navigationIcon = {
                    IconButton(onClick = onGoBack) {
                        Icon(
                            imageVector = Icons.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        FeedbackIssueBrowserContent(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            uiState = uiState,
            followedIssueNumbers = followedIssueNumbers,
            onRefreshIssues = onRefreshIssues,
            onLoadMoreIssues = onLoadMoreIssues,
            onIssueStateFilterSelected = onIssueStateFilterSelected,
            onFollowIssue = onFollowIssue
        )
    }
}

@Composable
private fun FeedbackAcknowledgementGateContent(
    modifier: Modifier = Modifier,
    uiState: FeedbackScreenViewModel.UiState,
    onSubmissionAcknowledgementChanged: (
        FeedbackScreenViewModel.SubmissionAcknowledgement,
        Boolean
    ) -> Unit = { _, _ -> },
    onContinueAfterAcknowledgementsConfirmed: () -> Unit = {}
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            FeedbackSubmissionIntroCard()
        }

        if (!uiState.endpointConfigured) {
            item {
                FeedbackEndpointUnconfiguredCard()
            }
        }

        item {
            FeedbackSectionCard(title = stringResource(R.string.feedback_submission_confirmation_title)) {
                FeedbackScreenViewModel.SubmissionAcknowledgement.entries.forEach { acknowledgement ->
                    FeedbackCheckboxRow(
                        checked = uiState.checkedSubmissionAcknowledgements.contains(acknowledgement),
                        enabled = !uiState.busy,
                        title = stringResource(acknowledgement.titleResId),
                        onCheckedChange = { checked ->
                            onSubmissionAcknowledgementChanged(acknowledgement, checked)
                        }
                    )
                }
            }
        }

        item {
            Button(
                onClick = onContinueAfterAcknowledgementsConfirmed,
                enabled = !uiState.busy && uiState.allSubmissionAcknowledgementsChecked,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.feedback_acknowledgement_continue))
            }
        }
    }
}

@Composable
private fun FeedbackSubmissionTabContent(
    modifier: Modifier = Modifier,
    uiState: FeedbackScreenViewModel.UiState,
    onCategorySelected: (FeedbackCategory) -> Unit = {},
    onGameIssueTypeSelected: (GameIssueType) -> Unit = {},
    onReproducedOnLastRunSelected: (Boolean) -> Unit = {},
    onSuspectUnknownChanged: (Boolean) -> Unit = {},
    onSuspectedModToggled: (String, Boolean) -> Unit = { _, _ -> },
    onSummaryChanged: (String) -> Unit = {},
    onDetailChanged: (String) -> Unit = {},
    onReproductionStepsChanged: (String) -> Unit = {},
    onEmailChanged: (String) -> Unit = {},
    onEmailNotificationsEnabledChanged: (Boolean) -> Unit = {},
    onAddScreenshots: () -> Unit = {},
    onRemoveScreenshot: (String) -> Unit = {},
    onSubmit: () -> Unit = {}
) {
    val isGameBug = uiState.category == FeedbackCategory.GAME_BUG

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            if (uiState.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                uiState.busyMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        if (!uiState.endpointConfigured) {
            item {
                FeedbackEndpointUnconfiguredCard()
            }
        }

        item {
            FeedbackSectionCard(title = stringResource(R.string.feedback_section_category)) {
                Text(
                    text = stringResource(R.string.feedback_question_category_hint),
                    style = MaterialTheme.typography.bodySmall
                )
                FeedbackCategoryOptions.forEach { option ->
                    FeedbackChoiceRow(
                        selected = uiState.category == option.category,
                        enabled = !uiState.busy,
                        title = stringResource(option.titleResId),
                        subtitle = stringResource(option.descriptionResId),
                        onClick = { onCategorySelected(option.category) }
                    )
                }
            }
        }

        if (isGameBug) {
            item {
                FeedbackSectionCard(title = stringResource(R.string.feedback_section_recent_run)) {
                    FeedbackRadioRow(
                        selected = uiState.reproducedOnLastRun == true,
                        enabled = !uiState.busy,
                        text = stringResource(R.string.feedback_recent_run_yes),
                        onClick = { onReproducedOnLastRunSelected(true) }
                    )
                    FeedbackRadioRow(
                        selected = uiState.reproducedOnLastRun == false,
                        enabled = !uiState.busy,
                        text = stringResource(R.string.feedback_recent_run_no),
                        onClick = { onReproducedOnLastRunSelected(false) }
                    )
                    if (uiState.reproducedOnLastRun == false) {
                        Text(
                            text = stringResource(R.string.feedback_recent_run_hint),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                FeedbackSectionCard(title = stringResource(R.string.feedback_section_suspected_mods)) {
                    FeedbackCheckboxRow(
                        checked = uiState.suspectUnknown,
                        enabled = !uiState.busy,
                        title = stringResource(R.string.feedback_suspected_unknown_title),
                        subtitle = stringResource(R.string.feedback_suspected_unknown_subtitle),
                        onCheckedChange = onSuspectUnknownChanged
                    )
                    if (uiState.availableMods.isEmpty()) {
                        Text(
                            text = stringResource(R.string.feedback_suspected_no_mods),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        uiState.availableMods.forEach { mod ->
                            val label = buildString {
                                append(mod.name.ifBlank { mod.manifestModId.ifBlank { mod.modId } })
                                if (mod.version.isNotBlank()) {
                                    append(" · ").append(mod.version)
                                }
                                if (mod.required) {
                                    append(" · ").append(stringResource(R.string.feedback_mod_core))
                                }
                            }
                            FeedbackCheckboxRow(
                                checked = uiState.selectedSuspectedModKeys.contains(mod.key),
                                enabled = !uiState.busy,
                                title = label,
                                subtitle = mod.manifestModId.ifBlank { mod.modId.ifBlank { mod.storagePath } },
                                onCheckedChange = { checked ->
                                    onSuspectedModToggled(mod.key, checked)
                                }
                            )
                        }
                    }
                }
            }

            item {
                FeedbackSectionCard(title = stringResource(R.string.feedback_section_issue_type)) {
                    FeedbackRadioRow(
                        selected = uiState.gameIssueType == GameIssueType.PERFORMANCE,
                        enabled = !uiState.busy,
                        text = stringResource(R.string.feedback_issue_type_performance),
                        onClick = { onGameIssueTypeSelected(GameIssueType.PERFORMANCE) }
                    )
                    FeedbackRadioRow(
                        selected = uiState.gameIssueType == GameIssueType.DISPLAY,
                        enabled = !uiState.busy,
                        text = stringResource(R.string.feedback_issue_type_display),
                        onClick = { onGameIssueTypeSelected(GameIssueType.DISPLAY) }
                    )
                    FeedbackRadioRow(
                        selected = uiState.gameIssueType == GameIssueType.CRASH,
                        enabled = !uiState.busy,
                        text = stringResource(R.string.feedback_issue_type_crash),
                        onClick = { onGameIssueTypeSelected(GameIssueType.CRASH) }
                    )
                }
            }
        }

        item {
            FeedbackSectionCard(
                title = stringResource(
                    if (isGameBug) {
                        R.string.feedback_section_details_game
                    } else {
                        R.string.feedback_section_details_other
                    }
                )
            ) {
                Text(
                    text = stringResource(
                        if (isGameBug) {
                            R.string.feedback_details_subtitle_game
                        } else {
                            R.string.feedback_details_subtitle
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = uiState.summary,
                    onValueChange = onSummaryChanged,
                    enabled = !uiState.busy,
                    label = { Text(stringResource(R.string.feedback_summary_label)) },
                    placeholder = { Text(summaryPlaceholder(uiState)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(1.dp))
                OutlinedTextField(
                    value = uiState.detail,
                    onValueChange = onDetailChanged,
                    enabled = !uiState.busy,
                    label = { Text(detailLabel(uiState)) },
                    placeholder = { Text(detailPlaceholder(uiState)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4
                )
                OutlinedTextField(
                    value = uiState.reproductionSteps,
                    onValueChange = onReproductionStepsChanged,
                    enabled = !uiState.busy,
                    label = { Text(reproductionLabel(uiState)) },
                    placeholder = { Text(reproductionPlaceholder(uiState)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        }

        item {
            FeedbackSectionCard(
                title = stringResource(
                    if (isGameBug) {
                        R.string.feedback_section_screenshots_game
                    } else {
                        R.string.feedback_section_screenshots_other
                    }
                )
            ) {
                Text(
                    text = stringResource(R.string.feedback_screenshots_hint),
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(
                    onClick = onAddScreenshots,
                    enabled = !uiState.busy && uiState.screenshots.size < 4
                ) {
                    Text(stringResource(R.string.feedback_add_screenshot))
                }
                if (uiState.screenshots.isEmpty()) {
                    Text(
                        text = stringResource(R.string.feedback_no_screenshots),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    uiState.screenshots.forEach { screenshot ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = screenshot.displayName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = screenshot.sizeLabel,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            TextButton(
                                onClick = { onRemoveScreenshot(screenshot.id) },
                                enabled = !uiState.busy
                            ) {
                                Text(stringResource(R.string.feedback_remove))
                            }
                        }
                    }
                }
            }
        }

        item {
            FeedbackSectionCard(
                title = stringResource(
                    if (isGameBug) {
                        R.string.feedback_section_email_game
                    } else {
                        R.string.feedback_section_email_other
                    }
                )
            ) {
                FeedbackCheckboxRow(
                    checked = uiState.emailNotificationsEnabled,
                    enabled = !uiState.busy,
                    title = stringResource(R.string.feedback_email_opt_in_title),
                    subtitle = stringResource(R.string.feedback_email_opt_in_subtitle),
                    onCheckedChange = onEmailNotificationsEnabledChanged
                )
                OutlinedTextField(
                    value = uiState.email,
                    onValueChange = onEmailChanged,
                    enabled = !uiState.busy,
                    label = { Text(stringResource(R.string.feedback_email_label)) },
                    placeholder = { Text("name@example.com") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }

        item {
            Button(
                onClick = onSubmit,
                enabled = !uiState.busy && uiState.endpointConfigured,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.feedback_submit))
            }
        }
    }
}

@Composable
private fun FeedbackSubscriptionsContent(
    modifier: Modifier = Modifier,
    uiState: FeedbackSubscriptionsViewModel.UiState,
    inboxState: FeedbackInboxUiState,
    onRefreshSubscriptions: () -> Unit = {},
    onUnsubscribeIssue: (Long) -> Unit = {},
    onOpenConversation: (Long) -> Unit = {}
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            if (uiState.busy || inboxState.syncing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = uiState.busyMessage ?: stringResource(R.string.feedback_busy_syncing_subscriptions),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        item {
            FeedbackSectionCard(title = stringResource(R.string.feedback_section_intro_title)) {
                Text(
                    text = stringResource(R.string.feedback_subscriptions_intro),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = onRefreshSubscriptions,
                    enabled = !uiState.busy
                ) {
                    Text(stringResource(R.string.feedback_refresh_now))
                }
            }
        }

        item {
            FeedbackSectionCard(title = stringResource(R.string.feedback_subscriptions_list_title)) {
                if (inboxState.subscriptions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.feedback_subscriptions_empty),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    inboxState.subscriptions.forEachIndexed { index, subscription ->
                        if (index > 0) {
                            HorizontalDivider()
                        }
                        FeedbackSubscriptionRow(
                            subscription = subscription,
                            onOpenConversation = { onOpenConversation(subscription.issueNumber) },
                            onUnsubscribe = { onUnsubscribeIssue(subscription.issueNumber) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedbackIssueBrowserContent(
    modifier: Modifier = Modifier,
    uiState: FeedbackIssueBrowserViewModel.UiState,
    followedIssueNumbers: Set<Long>,
    onRefreshIssues: () -> Unit = {},
    onLoadMoreIssues: () -> Unit = {},
    onIssueStateFilterSelected: (FeedbackIssueBrowserViewModel.IssueStateFilter) -> Unit = {},
    onFollowIssue: (Long) -> Unit = {}
) {
    val visibleIssues = uiState.visibleIssues
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            if (uiState.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                uiState.busyMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        item {
            FeedbackSectionCard(title = stringResource(R.string.feedback_issue_browser_intro_title)) {
                Text(
                    text = stringResource(R.string.feedback_issue_browser_intro),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = onRefreshIssues,
                    enabled = !uiState.busy && !uiState.loadingMore
                ) {
                    Text(stringResource(R.string.feedback_refresh_list))
                }
            }
        }

        item {
            FeedbackSectionCard(title = stringResource(R.string.feedback_followable_issues_title)) {
                Text(
                    text = stringResource(R.string.feedback_filter_state),
                    style = MaterialTheme.typography.labelMedium
                )
                FeedbackIssueBrowserViewModel.IssueStateFilter.values().forEach { filter ->
                    FeedbackRadioRow(
                        selected = uiState.issueStateFilter == filter,
                        enabled = !uiState.busy && !uiState.loadingMore,
                        text = stringResource(filter.labelResId),
                        onClick = { onIssueStateFilterSelected(filter) }
                    )
                }
                if (!uiState.initialLoaded && !uiState.busy) {
                    Text(
                        text = stringResource(R.string.feedback_issue_browser_preparing),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (uiState.issues.isEmpty()) {
                    Text(
                        text = stringResource(R.string.feedback_issue_browser_empty),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (visibleIssues.isEmpty()) {
                    Text(
                        text = if (uiState.hasMore) {
                            stringResource(R.string.feedback_issue_browser_empty_filtered_more)
                        } else {
                            stringResource(R.string.feedback_issue_browser_empty_filtered_done)
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        visibleIssues.forEachIndexed { index, issue ->
                            if (index > 0) {
                                HorizontalDivider()
                            }
                            FeedbackIssueBrowserRow(
                                issue = issue,
                                followed = followedIssueNumbers.contains(issue.issueNumber),
                                busy = uiState.busy,
                                onFollowIssue = { onFollowIssue(issue.issueNumber) }
                            )
                        }
                    }
                }
            }
        }

        if (uiState.issues.isNotEmpty()) {
            item {
                if (uiState.loadingMore) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else if (uiState.hasMore) {
                    OutlinedButton(
                        onClick = onLoadMoreIssues,
                        enabled = !uiState.busy
                    ) {
                        Text(stringResource(R.string.feedback_load_more_issues))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.feedback_issue_browser_loaded_all),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedbackSubscriptionRow(
    subscription: FeedbackIssueSubscription,
    onOpenConversation: () -> Unit,
    onUnsubscribe: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpenConversation)
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = subscription.title.ifBlank {
                    stringResource(R.string.feedback_issue_fallback_title, subscription.issueNumber)
                },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (subscription.unread) {
                Text(
                    text = stringResource(R.string.feedback_updates_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text(
            text = buildFeedbackIssueMetaText(
                issueNumber = subscription.issueNumber,
                isClosed = subscription.isClosed
            ),
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onOpenConversation) {
                Text(stringResource(R.string.feedback_open_conversation))
            }
            TextButton(onClick = onUnsubscribe) {
                Text(stringResource(R.string.feedback_unsubscribe))
            }
        }
    }
}

@Composable
private fun FeedbackIssueBrowserRow(
    issue: FeedbackIssueBrowseItem,
    followed: Boolean,
    busy: Boolean,
    onFollowIssue: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = issue.title.ifBlank {
                    stringResource(R.string.feedback_issue_fallback_title, issue.issueNumber)
                },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (followed) {
                Text(
                    text = stringResource(R.string.feedback_followed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text(
            text = buildFeedbackIssueMetaText(
                issueNumber = issue.issueNumber,
                isClosed = issue.isClosed,
                updatedAtMs = issue.updatedAtMs,
                commentCount = issue.commentCount
            ),
            style = MaterialTheme.typography.bodySmall
        )
        if (issue.bodyPreview.isNotBlank()) {
            Text(
                text = issue.bodyPreview,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.feedback_author_format, issue.authorLabel),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            if (followed) {
                OutlinedButton(
                    onClick = {},
                    enabled = false
                ) {
                    Text(stringResource(R.string.feedback_followed))
                }
            } else {
                Button(
                    onClick = onFollowIssue,
                    enabled = !busy
                ) {
                    Text(stringResource(R.string.feedback_follow))
                }
            }
        }
    }
}

@Composable
private fun FeedbackSectionCard(
    title: String,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun FeedbackSubmissionIntroCard() {
    FeedbackSectionCard(title = stringResource(R.string.feedback_section_intro_title)) {
        Text(
            text = stringResource(R.string.feedback_submission_intro),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun FeedbackEndpointUnconfiguredCard() {
    FeedbackSectionCard(
        title = stringResource(R.string.feedback_endpoint_unconfigured_title),
        containerColor = MaterialTheme.colorScheme.errorContainer
    ) {
        Text(
            text = stringResource(R.string.feedback_endpoint_unconfigured_message),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun FeedbackRadioRow(
    selected: Boolean,
    enabled: Boolean,
    text: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text)
    }
}

@Composable
private fun FeedbackChoiceRow(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun FeedbackCheckboxRow(
    checked: Boolean,
    enabled: Boolean,
    title: String,
    subtitle: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun FeedbackEffectsHandler(
    viewModel: FeedbackScreenViewModel,
    onSubmissionCompleted: (FeedbackSubmissionNotice) -> Unit = {}
) {
    val activity = requireNotNull(LocalActivity.current)
    val screenshotPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        viewModel.onScreenshotUrisPicked(activity, uris)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                FeedbackScreenViewModel.Effect.OpenScreenshotPicker -> {
                    screenshotPicker.launch(arrayOf("image/*"))
                }

                is FeedbackScreenViewModel.Effect.SubmissionCompleted -> {
                    onSubmissionCompleted(effect.notice)
                }
            }
        }
    }
}

@Composable
private fun summaryPlaceholder(uiState: FeedbackScreenViewModel.UiState): String {
    return when (uiState.category) {
        FeedbackCategory.FEATURE_REQUEST -> stringResource(R.string.feedback_summary_placeholder_feature)
        FeedbackCategory.LAUNCHER_BUG -> stringResource(R.string.feedback_summary_placeholder_launcher)
        FeedbackCategory.GAME_BUG -> when (uiState.gameIssueType) {
            GameIssueType.PERFORMANCE -> stringResource(R.string.feedback_summary_placeholder_game_performance)
            GameIssueType.DISPLAY -> stringResource(R.string.feedback_summary_placeholder_game_display)
            GameIssueType.CRASH -> stringResource(R.string.feedback_summary_placeholder_game_crash)
            null -> stringResource(R.string.feedback_summary_placeholder_game_generic)
        }

        null -> stringResource(R.string.feedback_summary_placeholder_unselected)
    }
}

@Composable
private fun detailLabel(uiState: FeedbackScreenViewModel.UiState): String {
    return when (uiState.category) {
        FeedbackCategory.FEATURE_REQUEST -> stringResource(R.string.feedback_detail_label_feature)
        FeedbackCategory.LAUNCHER_BUG -> stringResource(R.string.feedback_detail_label_launcher)
        FeedbackCategory.GAME_BUG -> stringResource(R.string.feedback_detail_label_game)
        null -> stringResource(R.string.feedback_detail_label_default)
    }
}

@Composable
private fun detailPlaceholder(uiState: FeedbackScreenViewModel.UiState): String {
    return when (uiState.category) {
        FeedbackCategory.FEATURE_REQUEST -> stringResource(R.string.feedback_detail_placeholder_feature)
        FeedbackCategory.LAUNCHER_BUG -> stringResource(R.string.feedback_detail_placeholder_launcher)
        FeedbackCategory.GAME_BUG -> when (uiState.gameIssueType) {
            GameIssueType.PERFORMANCE -> stringResource(R.string.feedback_detail_placeholder_game_performance)
            GameIssueType.DISPLAY -> stringResource(R.string.feedback_detail_placeholder_game_display)
            GameIssueType.CRASH -> stringResource(R.string.feedback_detail_placeholder_game_crash)
            null -> stringResource(R.string.feedback_detail_placeholder_game_generic)
        }

        null -> stringResource(R.string.feedback_detail_placeholder_unselected)
    }
}

@Composable
private fun reproductionLabel(uiState: FeedbackScreenViewModel.UiState): String {
    return if (uiState.category == FeedbackCategory.FEATURE_REQUEST) {
        stringResource(R.string.feedback_reproduction_label_feature)
    } else {
        stringResource(R.string.feedback_reproduction_label_other)
    }
}

@Composable
private fun reproductionPlaceholder(uiState: FeedbackScreenViewModel.UiState): String {
    return if (uiState.category == FeedbackCategory.FEATURE_REQUEST) {
        stringResource(R.string.feedback_reproduction_placeholder_feature)
    } else {
        stringResource(R.string.feedback_reproduction_placeholder_other)
    }
}

@Composable
private fun formatFeedbackIssueListTime(timestampMs: Long): String {
    if (timestampMs <= 0L) {
        return stringResource(R.string.feedback_unknown_time)
    }
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestampMs))
}

private val FeedbackIssueResolvedColor = Color(0xFF2E7D32)
private val FeedbackIssueInProgressColor = Color(0xFFC62828)

@Composable
private fun buildFeedbackIssueMetaText(
    issueNumber: Long,
    isClosed: Boolean,
    updatedAtMs: Long? = null,
    commentCount: Int? = null
) = buildAnnotatedString {
    append("#")
    append(issueNumber.toString())
    append(" · ")
    withStyle(
        SpanStyle(
            color = if (isClosed) {
                FeedbackIssueResolvedColor
            } else {
                FeedbackIssueInProgressColor
            }
        )
    ) {
        append(
            stringResource(
                if (isClosed) {
                    R.string.feedback_meta_resolved
                } else {
                    R.string.feedback_meta_in_progress
                }
            )
        )
    }
    updatedAtMs?.let {
        append(" · ")
        append(formatFeedbackIssueListTime(it))
    }
    commentCount?.let {
        append(" · ")
        append(stringResource(R.string.feedback_meta_comments_format, it))
    }
}
