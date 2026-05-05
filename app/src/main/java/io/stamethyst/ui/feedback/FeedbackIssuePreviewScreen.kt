package io.stamethyst.ui.feedback

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.stamethyst.R
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.Icons
import io.stamethyst.ui.SimpleMarkdownCard
import io.stamethyst.ui.icon.ArrowBack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherFeedbackIssuePreviewScreen(
    issueNumber: Long,
    modifier: Modifier = Modifier
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val uriHandler = LocalUriHandler.current
    val viewModel: FeedbackIssuePreviewViewModel = viewModel(
        factory = FeedbackIssuePreviewViewModel.factory(issueNumber)
    )
    val uiState = viewModel.uiState

    LaunchedEffect(Unit) {
        viewModel.bind(activity)
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
            FeedbackIssuePreviewBottomBar(
                uiState = uiState,
                onFollowIssue = { viewModel.onFollowIssue(activity) }
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
                            SimpleMarkdownCard(
                                title = "",
                                markdown = uiState.issueBody
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
}

@Composable
private fun FeedbackIssuePreviewBottomBar(
    uiState: FeedbackIssuePreviewViewModel.UiState,
    onFollowIssue: () -> Unit
) {
    androidx.compose.material3.Surface(shadowElevation = 6.dp, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (uiState.isFollowed) {
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.feedback_followed))
                }
            } else {
                Button(
                    onClick = onFollowIssue,
                    enabled = !uiState.busy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.feedback_follow_issue))
                }
            }
        }
    }
}
