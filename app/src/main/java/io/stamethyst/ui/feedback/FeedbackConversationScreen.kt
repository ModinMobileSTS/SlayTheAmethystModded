package io.stamethyst.ui.feedback

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.stamethyst.R
import io.stamethyst.backend.feedback.FeedbackThreadAuthorType
import io.stamethyst.backend.feedback.FeedbackThreadEvent
import io.stamethyst.backend.feedback.FeedbackThreadEventType
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.Icons
import io.stamethyst.ui.icon.ArrowBack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherFeedbackConversationScreen(
    issueNumber: Long,
    modifier: Modifier = Modifier
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val uriHandler = LocalUriHandler.current
    val viewModel: FeedbackConversationViewModel = viewModel(
        factory = FeedbackConversationViewModel.factory(issueNumber)
    )
    val uiState = viewModel.uiState
    var pendingStateAction by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.bind(activity)
    }

    val screenshotPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        viewModel.onScreenshotUrisPicked(activity, uris)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                FeedbackConversationViewModel.Effect.OpenScreenshotPicker -> {
                    screenshotPicker.launch(arrayOf("image/*"))
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (uiState.title.isBlank()) {
                            stringResource(R.string.feedback_issue_fallback_title, issueNumber)
                        } else {
                            uiState.title
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = navigator::goBack) {
                        Icon(
                            imageVector = Icons.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back)
                        )
                    }
                },
                actions = {
                    if (uiState.issueUrl.isNotBlank()) {
                        TextButton(onClick = { uriHandler.openUri(uiState.issueUrl) }) {
                            Text(stringResource(R.string.common_action_open_issue))
                        }
                    }
                    TextButton(onClick = { viewModel.onRefresh(activity) }) {
                        Text(stringResource(R.string.feedback_conversation_refresh))
                    }
                }
            )
        },
        bottomBar = {
            FeedbackConversationComposer(
                uiState = uiState,
                onMessageChanged = viewModel::onMessageChanged,
                onAddScreenshots = viewModel::onAddScreenshots,
                onRemoveScreenshot = viewModel::onRemoveScreenshot,
                onSendMessage = { viewModel.onSendMessage(activity) },
                onRequestClose = {
                    pendingStateAction = if (uiState.isClosed) "open" else "closed"
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
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
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.feedback_conversation_status_format,
                                stringResource(
                                    if (uiState.isClosed) {
                                        R.string.feedback_conversation_state_closed
                                    } else {
                                        R.string.feedback_conversation_state_in_progress
                                    }
                                )
                            ),
                            style = MaterialTheme.typography.labelMedium
                        )
                        if (uiState.issueBody.isNotBlank()) {
                            HorizontalDivider()
                            Text(
                                text = uiState.issueBody,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            items(uiState.events, key = { it.id }) { event ->
                FeedbackConversationEventCard(
                    event = event,
                    onOpenAttachment = { url -> uriHandler.openUri(url) },
                    onOpenComment = { url ->
                        if (!url.isNullOrBlank()) {
                            uriHandler.openUri(url)
                        }
                    }
                )
            }
        }
    }

    pendingStateAction?.let { targetState ->
        AlertDialog(
            onDismissRequest = { pendingStateAction = null },
            title = {
                Text(
                    stringResource(
                        if (targetState == "closed") {
                            R.string.feedback_conversation_close_title
                        } else {
                            R.string.feedback_conversation_reopen_title
                        }
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        if (targetState == "closed") {
                            R.string.feedback_conversation_close_confirm
                        } else {
                            R.string.feedback_conversation_reopen_confirm
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingStateAction = null
                        if (targetState == "closed") {
                            viewModel.onCloseIssue(activity)
                        } else {
                            viewModel.onReopenIssue(activity)
                        }
                    }
                ) {
                    Text(stringResource(R.string.common_action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingStateAction = null }) {
                    Text(stringResource(R.string.main_folder_dialog_cancel))
                }
            }
        )
    }
}

@Composable
private fun FeedbackConversationComposer(
    uiState: FeedbackConversationViewModel.UiState,
    onMessageChanged: (String) -> Unit,
    onAddScreenshots: () -> Unit,
    onRemoveScreenshot: (String) -> Unit,
    onSendMessage: () -> Unit,
    onRequestClose: () -> Unit
) {
    Surface(shadowElevation = 6.dp, tonalElevation = 2.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (uiState.screenshots.isNotEmpty()) {
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
                        TextButton(onClick = { onRemoveScreenshot(screenshot.id) }) {
                            Text(stringResource(R.string.feedback_remove))
                        }
                    }
                }
            }
            OutlinedTextField(
                value = uiState.messageText,
                onValueChange = onMessageChanged,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                enabled = !uiState.busy,
                label = { Text(stringResource(R.string.feedback_conversation_message_label)) },
                placeholder = { Text(stringResource(R.string.feedback_conversation_message_placeholder)) }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onAddScreenshots,
                    enabled = !uiState.busy && uiState.screenshots.size < 4
                ) {
                    Text(stringResource(R.string.feedback_add_screenshot))
                }
                TextButton(
                    onClick = onRequestClose,
                    enabled = !uiState.busy
                ) {
                    Text(
                        stringResource(
                            if (uiState.isClosed) {
                                R.string.feedback_conversation_reopen_action
                            } else {
                                R.string.feedback_conversation_close_action
                            }
                        )
                    )
                }
                Button(
                    onClick = onSendMessage,
                    enabled = !uiState.busy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.feedback_conversation_send))
                }
            }
        }
    }
}

@Composable
private fun FeedbackConversationEventCard(
    event: FeedbackThreadEvent,
    onOpenAttachment: (String) -> Unit,
    onOpenComment: (String?) -> Unit
) {
    val arrangement = if (event.authorType == FeedbackThreadAuthorType.ME) {
        Arrangement.End
    } else {
        Arrangement.Start
    }
    val containerColor = when (event.type) {
        FeedbackThreadEventType.STATE_CHANGE -> MaterialTheme.colorScheme.surfaceContainerHighest
        FeedbackThreadEventType.COMMENT -> when (event.authorType) {
            FeedbackThreadAuthorType.ME -> MaterialTheme.colorScheme.primaryContainer
            FeedbackThreadAuthorType.DEVELOPER -> MaterialTheme.colorScheme.secondaryContainer
            FeedbackThreadAuthorType.OTHER -> MaterialTheme.colorScheme.surfaceContainerHigh
            FeedbackThreadAuthorType.SYSTEM -> MaterialTheme.colorScheme.surfaceContainerHigh
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = arrangement
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.9f),
            colors = CardDefaults.cardColors(containerColor = containerColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${event.authorLabel} · ${formatFeedbackEventTime(event.createdAtMs)}",
                    style = MaterialTheme.typography.labelSmall
                )
                if (event.body.isNotBlank()) {
                    Text(
                        text = event.body,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                event.attachments.forEach { attachment ->
                    TextButton(
                        onClick = { onOpenAttachment(attachment.url) },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            stringResource(
                                R.string.feedback_attachment_view_format,
                                attachment.name.ifBlank { attachment.url }
                            )
                        )
                    }
                }
                if (!event.htmlUrl.isNullOrBlank()) {
                    TextButton(
                        onClick = { onOpenComment(event.htmlUrl) },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(stringResource(R.string.feedback_view_on_github))
                    }
                }
            }
        }
    }
}

@Composable
private fun formatFeedbackEventTime(timestampMs: Long): String {
    if (timestampMs <= 0L) {
        return stringResource(R.string.feedback_unknown_time)
    }
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestampMs))
}
