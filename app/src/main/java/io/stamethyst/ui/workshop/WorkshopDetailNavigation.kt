package io.stamethyst.ui.workshop

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import io.stamethyst.backend.workshop.WorkshopAuthor
import io.stamethyst.backend.workshop.WorkshopDiscussionSummary
import io.stamethyst.backend.workshop.WorkshopItemSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal sealed interface WorkshopDetailDestination {
    @Serializable
    data class Detail(
        val appId: String,
        val id: String,
        val title: String = "",
        val previewUrl: String = "",
        val author: WorkshopAuthor? = null,
    ) : WorkshopDetailDestination

    @Serializable
    data class Author(val appId: String, val author: WorkshopAuthor) : WorkshopDetailDestination

    @Serializable
    data class Discussions(val id: String, val title: String) : WorkshopDetailDestination

    @Serializable
    data class Topic(val id: String, val modTitle: String, val topicId: String, val title: String, val url: String) : WorkshopDetailDestination
}

private val DetailStackSaver = Saver<List<WorkshopDetailDestination>, String>(
    save = { Json.encodeToString(it) },
    restore = { runCatching { Json.decodeFromString<List<WorkshopDetailDestination>>(it) }.getOrNull()?.takeIf(List<*>::isNotEmpty) },
)

/** Local navigation works both in Navigation3 and inside the multiplayer room's detail sheet. */
@Composable
internal fun WorkshopDetailScreen(
    appId: UInt,
    publishedFileId: ULong,
    viewModel: WorkshopViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenBaiduTranslationCredentials: (String) -> Unit,
    onOpenDetails: (WorkshopItemSummary) -> Unit,
) {
    var stack by rememberSaveable(appId.toString(), publishedFileId.toString(), stateSaver = DetailStackSaver) {
        mutableStateOf<List<WorkshopDetailDestination>>(listOf(WorkshopDetailDestination.Detail(appId.toString(), publishedFileId.toString())))
    }
    val holder = rememberSaveableStateHolder()
    val context = LocalContext.current
    val socialViewModel: WorkshopSocialViewModel = composeViewModel(key = "workshop-social:$appId:$publishedFileId")
    fun push(destination: WorkshopDetailDestination) { stack = stack + destination }
    fun pop() {
        if (stack.size == 1) onBack() else {
            val removedKey = "${stack.size - 1}:${Json.encodeToString(stack.last())}"
            stack = stack.dropLast(1)
            holder.removeState(removedKey)
        }
    }
    // Registered before child content, so fullscreen gallery back handling keeps priority.
    BackHandler(enabled = stack.size > 1) { pop() }
    val destination = stack.last()
    holder.SaveableStateProvider("${stack.size - 1}:${Json.encodeToString(destination)}") {
        when (destination) {
            is WorkshopDetailDestination.Detail -> WorkshopDetailContent(
                appId = destination.appId.toUInt(), publishedFileId = destination.id.toULong(),
                viewModel = viewModel, modifier = modifier, onBack = ::pop,
                onOpenBaiduTranslationCredentials = onOpenBaiduTranslationCredentials,
                onOpenDetails = { item -> push(WorkshopDetailDestination.Detail(
                    item.appId.toString(), item.publishedFileId.toString(), item.title, item.previewUrl, item.author,
                )) },
                fallbackSummary = destination.title.takeIf(String::isNotBlank)?.let {
                    WorkshopItemSummary(destination.id.toULong(), destination.appId.toUInt(), it, destination.previewUrl, "",
                        authorName = destination.author?.name.orEmpty(), author = destination.author)
                },
                onOpenAuthor = { author -> push(WorkshopDetailDestination.Author(destination.appId, author)) },
                onOpenDiscussions = { title -> push(WorkshopDetailDestination.Discussions(destination.id, title)) },
            )
            is WorkshopDetailDestination.Author -> WorkshopAuthorScreen(
                appId = destination.appId.toUInt(), author = destination.author, viewModel = socialViewModel,
                service = viewModel.socialService(context), modifier = modifier, onBack = ::pop,
                onOpenDetails = { item -> push(WorkshopDetailDestination.Detail(
                    item.appId.toString(), item.publishedFileId.toString(), item.title, item.previewUrl, item.author,
                )) },
            )
            is WorkshopDetailDestination.Discussions -> WorkshopDiscussionsScreen(
                publishedFileId = destination.id.toULong(), modTitle = destination.title, viewModel = socialViewModel,
                service = viewModel.socialService(context), modifier = modifier, onBack = ::pop,
                onOpenTopic = { topic -> push(WorkshopDetailDestination.Topic(destination.id, destination.title, topic.id, topic.title, topic.url)) },
            )
            is WorkshopDetailDestination.Topic -> WorkshopDiscussionScreen(
                publishedFileId = destination.id.toULong(), modTitle = destination.modTitle,
                topic = WorkshopDiscussionSummary(destination.topicId, destination.title, destination.url),
                viewModel = socialViewModel, service = viewModel.socialService(context), modifier = modifier, onBack = ::pop,
            )
        }
    }
}
