package io.stamethyst.ui.workshop

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Scaffold
import io.stamethyst.R
import io.stamethyst.backend.workshop.SteamLanguagePreference
import io.stamethyst.backend.workshop.WorkshopAuthor
import io.stamethyst.backend.workshop.WorkshopDiscussionReplyPage
import io.stamethyst.backend.workshop.WorkshopDiscussionSummary
import io.stamethyst.backend.workshop.WorkshopItemSummary
import io.stamethyst.backend.workshop.WorkshopSocialService
import io.stamethyst.ui.Icons
import io.stamethyst.ui.LauncherTopAppBar
import io.stamethyst.ui.LoadingSkeletonBlock
import io.stamethyst.ui.RemoteBitmapCacheStore
import io.stamethyst.ui.SimpleMarkdownContent
import io.stamethyst.ui.icon.ArrowBack
import io.stamethyst.ui.icon.ArrowForward
import io.stamethyst.ui.preferences.LauncherPreferences
import io.stamethyst.ui.rememberLoadingSkeletonStyle
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun WorkshopAuthorLinks(authors: List<WorkshopAuthor>, fallbackName: String, onOpenAuthor: (WorkshopAuthor) -> Unit) {
    if (authors.isEmpty()) {
        Text(fallbackName.ifBlank { stringResource(R.string.workshop_unknown_author) }, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else Column {
        authors.forEach { author ->
            TextButton(onClick = { onOpenAuthor(author) }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    WorkshopAuthorAvatar(author, Modifier.size(24.dp))
                    Text(author.name.ifBlank { stringResource(R.string.workshop_unknown_author) }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.ArrowForward, contentDescription = stringResource(R.string.workshop_author_view_works), modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun WorkshopAuthorAvatar(author: WorkshopAuthor, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, author.avatarUrl) {
        value = if (author.avatarUrl.isBlank()) null else withContext(Dispatchers.IO) {
            RemoteBitmapCacheStore.load(context.applicationContext, author.avatarUrl)
        }
    }
    Surface(modifier.clip(CircleShape), color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
internal fun WorkshopDiscussionsEntry(onClick: () -> Unit) {
    WorkshopDetailCard(onClick = onClick) {
        Row(Modifier.padding(18.dp).heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.workshop_discussions_title), modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Icon(Icons.ArrowForward, contentDescription = null)
        }
    }
}

@Composable
internal fun WorkshopAuthorScreen(
    appId: UInt,
    author: WorkshopAuthor,
    viewModel: WorkshopSocialViewModel,
    service: WorkshopSocialService,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenDetails: (WorkshopItemSummary) -> Unit,
) {
    val language = socialLanguage()
    var page by rememberSaveable { mutableIntStateOf(1) }
    val state = viewModel.authorState(appId, author, language, page)
    val retry = { viewModel.loadAuthor(service, appId, author, language, page, force = true) }
    LaunchedEffect(appId, author.identity, language, page) { viewModel.loadAuthor(service, appId, author, language, page) }
    SocialScaffold(stringResource(R.string.workshop_author_works_title), author.name,
        "${author.profileUrl}/myworkshopfiles/?appid=$appId", page, modifier, onBack) {
        item("author") {
            Card(colors = socialCardColors()) {
                Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    WorkshopAuthorAvatar(author, Modifier.size(56.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(author.name.ifBlank { stringResource(R.string.workshop_unknown_author) }, style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.workshop_author_game_scope), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        socialState(state, retry) { result ->
            item("count") {
                Text(result.total?.let { stringResource(R.string.workshop_author_total_format, it) }
                    ?: stringResource(R.string.workshop_author_all_works), style = MaterialTheme.typography.titleMedium)
            }
            if (result.items.isEmpty()) item("empty") { SocialEmpty(R.string.workshop_author_empty) }
            items(result.items, key = { "work:${it.publishedFileId}" }) { item ->
                WorkshopSummaryCard(
                    publishedFileId = item.publishedFileId, previewUrl = item.previewUrl, title = item.title,
                    supportingText = item.authorName, onClick = { onOpenDetails(item) }, footerContent = {},
                    trailingContent = { Icon(Icons.ArrowForward, contentDescription = null) },
                )
            }
            item("paging") {
                SocialPaging(page, result.hasNextPage, result.total?.let { (it.toLong() + result.pageSize - 1).div(result.pageSize).toInt().coerceAtLeast(1) },
                    state.loading, onPrevious = { page-- }, onNext = { page++ })
            }
        }
    }
}

@Composable
internal fun WorkshopDiscussionsScreen(
    publishedFileId: ULong,
    modTitle: String,
    viewModel: WorkshopSocialViewModel,
    service: WorkshopSocialService,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenTopic: (WorkshopDiscussionSummary) -> Unit,
) {
    val language = socialLanguage()
    var page by rememberSaveable { mutableIntStateOf(1) }
    val state = viewModel.discussionState(publishedFileId, language, page)
    val retry = { viewModel.loadDiscussions(service, publishedFileId, language, page, force = true) }
    LaunchedEffect(publishedFileId, language, page) { viewModel.loadDiscussions(service, publishedFileId, language, page) }
    SocialScaffold(stringResource(R.string.workshop_discussions_title), modTitle,
        "https://steamcommunity.com/sharedfiles/filedetails/discussions/$publishedFileId?l=${language.requestValue}", page, modifier, onBack) {
        socialState(state, retry) { result ->
            item("count") { Text(stringResource(R.string.workshop_discussions_total_format, result.total), style = MaterialTheme.typography.bodyMedium) }
            if (result.topics.isEmpty()) item("empty") { SocialEmpty(R.string.workshop_discussions_empty) }
            items(result.topics, key = { "topic:${it.id}" }) { topic ->
                Card(onClick = { onOpenTopic(topic) }, modifier = Modifier.fillMaxWidth(), colors = socialCardColors()) {
                    Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Title only: no tooltip excerpt, author, reply body or additional prefetch.
                        Text(topic.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Icon(Icons.ArrowForward, contentDescription = null)
                    }
                }
            }
            item("paging") { SocialPaging(result.page, result.hasNextPage,
                ((result.total.toLong() + result.pageSize - 1) / result.pageSize).toInt().coerceAtLeast(1),
                state.loading, onPrevious = { page = (result.page - 1).coerceAtLeast(1) }, onNext = { page = result.page + 1 }) }
        }
    }
}

@Composable
internal fun WorkshopDiscussionScreen(
    publishedFileId: ULong,
    modTitle: String,
    topic: WorkshopDiscussionSummary,
    viewModel: WorkshopSocialViewModel,
    service: WorkshopSocialService,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val language = socialLanguage()
    var page by rememberSaveable { mutableIntStateOf(1) }
    val state = viewModel.topicState(publishedFileId, topic, language)
    val retry = { viewModel.loadTopic(service, publishedFileId, topic, language, force = true) }
    LaunchedEffect(publishedFileId, topic.id, language) { viewModel.loadTopic(service, publishedFileId, topic, language) }
    LaunchedEffect(state.value, page) {
        if (state.value != null && page > 1) viewModel.loadReplies(service, publishedFileId, topic, language, page)
    }
    val replyState = if (page == 1) WorkshopSocialLoadState(value = state.value?.firstReplyPage)
        else viewModel.replyState(publishedFileId, topic, language, page)
    val uriHandler = LocalUriHandler.current
    SocialScaffold(stringResource(R.string.workshop_discussion_detail_title), modTitle, topic.url, page, modifier, onBack) {
        socialState(state, retry) { details ->
            item("post") {
                Card(colors = socialCardColors()) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(details.topic.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(listOf(details.topic.authorName, socialDate(details.postedEpochSeconds)).filter(String::isNotBlank).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SimpleMarkdownContent(details.content, textSelectable = true, textColor = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            item("replies-title") {
                Text(stringResource(R.string.workshop_discussion_replies_format, details.firstReplyPage.total), style = MaterialTheme.typography.titleMedium)
            }
            val retryReplies = { viewModel.loadReplies(service, publishedFileId, topic, language, page, force = true) }
            socialState(replyState, retryReplies, prefix = "replies:") { result: WorkshopDiscussionReplyPage ->
                item("top-paging") { SocialPaging(result.page, result.hasNextPage,
                    ((result.total.toLong() + result.pageSize - 1) / result.pageSize).toInt().coerceAtLeast(1), replyState.loading,
                    onPrevious = { page = (result.page - 1).coerceAtLeast(1) }, onNext = { page = result.page + 1 }) }
                if (result.replies.isEmpty()) item("no-replies") { SocialEmpty(R.string.workshop_discussion_no_replies) }
                items(result.replies, key = { "reply:${it.comment.id}" }) { reply ->
                    Card(colors = socialCardColors()) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(reply.comment.authorName.ifBlank { stringResource(R.string.workshop_unknown_author) },
                                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                if (reply.isDeveloper) Text(stringResource(R.string.workshop_discussion_developer), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            socialDate(reply.comment.postedEpochSeconds).takeIf(String::isNotBlank)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            SimpleMarkdownContent(reply.comment.content, textSelectable = true, textColor = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                if (result.total > result.pageSize) item("bottom-paging") { SocialPaging(result.page, result.hasNextPage,
                    ((result.total.toLong() + result.pageSize - 1) / result.pageSize).toInt().coerceAtLeast(1), replyState.loading,
                    onPrevious = { page = (result.page - 1).coerceAtLeast(1) }, onNext = { page = result.page + 1 }) }
            }
            item("reply-on-steam") {
                OutlinedButton(onClick = { runCatching { uriHandler.openUri(topic.url) } }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.workshop_discussion_reply_on_steam))
                }
            }
        }
    }
}

@Composable
private fun socialLanguage(): SteamLanguagePreference = LauncherPreferences.readWorkshopSteamLanguage(LocalContext.current)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SocialScaffold(
    title: String,
    subtitle: String,
    url: String,
    page: Int,
    modifier: Modifier,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val listState = rememberSaveable(page, saver = LazyListState.Saver) { LazyListState() }
    Scaffold(modifier = modifier.fillMaxSize(), topBar = {
        LauncherTopAppBar(title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.ArrowBack, stringResource(R.string.common_content_desc_back)) } },
            actions = { TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) { Text(stringResource(R.string.workshop_social_open_steam)) } })
    }) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize().navigationBarsPadding(), state = listState,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

private fun <T> LazyListScope.socialState(
    state: WorkshopSocialLoadState<T>,
    onRetry: () -> Unit,
    prefix: String = "",
    content: LazyListScope.(T) -> Unit,
) {
    if (state.value == null && state.error == null) {
        items(4, key = { "${prefix}skeleton:$it" }) {
            val style = rememberLoadingSkeletonStyle("workshop_social_skeleton")
            LoadingSkeletonBlock(Modifier.fillMaxWidth().height(76.dp), style = style, shape = RoundedCornerShape(18.dp))
        }
    }
    state.error?.let { error ->
        item("${prefix}error") {
            Card(colors = socialCardColors()) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.workshop_error_loading_title), style = MaterialTheme.typography.titleMedium)
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    WorkshopLoadFailureHint()
                    OutlinedButton(onClick = onRetry, enabled = !state.loading) { Text(stringResource(R.string.workshop_action_retry)) }
                }
            }
        }
    }
    state.value?.let { content(it) }
}

@Composable
private fun SocialPaging(page: Int, hasNext: Boolean, totalPages: Int?, loading: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
    if (page == 1 && !hasNext) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onPrevious, enabled = page > 1 && !loading, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.workshop_action_previous_page)) }
        Text(totalPages?.let { "$page / $it" } ?: page.toString(), style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = onNext, enabled = hasNext && !loading, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.workshop_action_next_page)) }
    }
}

@Composable
private fun SocialEmpty(message: Int) {
    Text(stringResource(message), modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun socialCardColors() = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)

private fun socialDate(seconds: Long?): String = seconds?.takeIf { it in 1..Long.MAX_VALUE / 1000 }
    ?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it * 1000)) }.orEmpty()
