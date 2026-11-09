package io.stamethyst.ui.workshop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.stamethyst.backend.workshop.SteamLanguagePreference
import io.stamethyst.backend.workshop.WorkshopAuthor
import io.stamethyst.backend.workshop.WorkshopAuthorPage
import io.stamethyst.backend.workshop.WorkshopDiscussionDetails
import io.stamethyst.backend.workshop.WorkshopDiscussionPage
import io.stamethyst.backend.workshop.WorkshopDiscussionReplyPage
import io.stamethyst.backend.workshop.WorkshopDiscussionSummary
import io.stamethyst.backend.workshop.WorkshopSocialService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class WorkshopSocialLoadState<T>(
    val value: T? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** Independent from selected mod/comments: browsing an author or forum cannot overwrite details. */
@Stable
internal class WorkshopSocialViewModel : ViewModel() {
    private val authors = mutableStateMapOf<String, WorkshopSocialLoadState<WorkshopAuthorPage>>()
    private val discussions = mutableStateMapOf<String, WorkshopSocialLoadState<WorkshopDiscussionPage>>()
    private val topics = mutableStateMapOf<String, WorkshopSocialLoadState<WorkshopDiscussionDetails>>()
    private val replies = mutableStateMapOf<String, WorkshopSocialLoadState<WorkshopDiscussionReplyPage>>()
    private val jobs = mutableMapOf<String, Job>()

    fun authorState(appId: UInt, author: WorkshopAuthor, language: SteamLanguagePreference, page: Int) =
        authors[authorKey(appId, author, language, page)] ?: WorkshopSocialLoadState()

    fun discussionState(id: ULong, language: SteamLanguagePreference, page: Int) =
        discussions[discussionKey(id, language, page)] ?: WorkshopSocialLoadState()

    fun topicState(id: ULong, topic: WorkshopDiscussionSummary, language: SteamLanguagePreference) =
        topics[topicKey(id, topic, language)] ?: WorkshopSocialLoadState()

    fun replyState(id: ULong, topic: WorkshopDiscussionSummary, language: SteamLanguagePreference, page: Int) =
        replies["${topicKey(id, topic, language)}:replies:$page"] ?: WorkshopSocialLoadState()

    fun loadAuthor(service: WorkshopSocialService, appId: UInt, author: WorkshopAuthor, language: SteamLanguagePreference, page: Int, force: Boolean = false) {
        load(authorKey(appId, author, language, page), authors, force) { service.authorPage(appId, author, page, language) }
    }

    fun loadDiscussions(service: WorkshopSocialService, id: ULong, language: SteamLanguagePreference, page: Int, force: Boolean = false) {
        load(discussionKey(id, language, page), discussions, force) {
            val first = discussions[discussionKey(id, language, 1)]?.value
                ?: if (page > 1) service.discussions(id, language).also {
                    discussions[discussionKey(id, language, 1)] = WorkshopSocialLoadState(value = it)
                } else null
            service.discussions(id, language, first, page)
        }
    }

    fun loadTopic(service: WorkshopSocialService, id: ULong, topic: WorkshopDiscussionSummary, language: SteamLanguagePreference, force: Boolean = false) {
        load(topicKey(id, topic, language), topics, force) { service.discussionDetails(id, topic, language) }
    }

    fun loadReplies(service: WorkshopSocialService, id: ULong, topic: WorkshopDiscussionSummary, language: SteamLanguagePreference, page: Int, force: Boolean = false) {
        load("${topicKey(id, topic, language)}:replies:$page", replies, force) {
            val details = topics[topicKey(id, topic, language)]?.value ?: error("Steam discussion context was missing")
            if (page == 1) details.firstReplyPage else service.replies(details.replyContext, page, details.firstReplyPage.pageSize, language)
        }
    }

    private fun <T> load(key: String, states: MutableMap<String, WorkshopSocialLoadState<T>>, force: Boolean, request: suspend () -> T) {
        val current = states[key]
        if (jobs[key]?.isActive == true || (!force && (current?.value != null || current?.error != null))) return
        states[key] = WorkshopSocialLoadState(current?.value, loading = true)
        // Start only after registering the job: a cached first reply page can complete without
        // suspending, and must not leave a completed job installed after its finally block.
        jobs[key] = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                states[key] = WorkshopSocialLoadState(value = request())
            } catch (error: CancellationException) {
                states[key] = WorkshopSocialLoadState(current?.value)
                throw error
            } catch (error: Exception) {
                states[key] = WorkshopSocialLoadState(current?.value, error = error.message ?: error.javaClass.simpleName)
            } finally {
                jobs.remove(key)
                // Bound transcript retention while keeping pages currently loading intact.
                while (states.size > MAX_CACHED_PAGES) {
                    val oldest = states.keys.firstOrNull { it != key && jobs[it]?.isActive != true } ?: break
                    states.remove(oldest)
                }
            }
        }
        jobs.getValue(key).start()
    }

    private fun authorKey(appId: UInt, author: WorkshopAuthor, language: SteamLanguagePreference, page: Int) =
        "author:$appId:${author.identity}:${language.requestValue}:$page"
    private fun discussionKey(id: ULong, language: SteamLanguagePreference, page: Int) = "forum:$id:${language.requestValue}:$page"
    private fun topicKey(id: ULong, topic: WorkshopDiscussionSummary, language: SteamLanguagePreference) =
        "topic:$id:${topic.id}:${language.requestValue}"

    private companion object {
        const val MAX_CACHED_PAGES = 32
    }
}
