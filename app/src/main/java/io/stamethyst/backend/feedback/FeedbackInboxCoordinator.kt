package io.stamethyst.backend.feedback

import android.content.Context
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object FeedbackInboxCoordinator {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val _uiState = MutableStateFlow(FeedbackInboxUiState())
    @Volatile
    private var startupSyncStarted = false

    val uiState: StateFlow<FeedbackInboxUiState> = _uiState.asStateFlow()

    fun bind(context: Context) {
        refreshFromStorage(context.applicationContext)
    }

    fun syncOnLauncherStart(context: Context) {
        if (startupSyncStarted) {
            return
        }
        startupSyncStarted = true
        runSync(context = context.applicationContext, announceUnread = true)
    }

    fun runManualSync(
        context: Context,
        onCompleted: ((Result<FeedbackSyncResult>) -> Unit)? = null
    ) {
        runSync(
            context = context.applicationContext,
            announceUnread = false,
            onCompleted = onCompleted
        )
    }

    fun refreshFromStorage(context: Context) {
        val subscriptions = FeedbackIssueLocalStore.loadSubscriptions(context.applicationContext)
        val current = _uiState.value
        _uiState.value = current.copy(
            subscriptions = subscriptions,
            unreadIssueCount = subscriptions.count { it.unread }
        )
    }

    fun dismissUnreadNotice() {
        val current = _uiState.value
        if (current.pendingNotice != null) {
            _uiState.value = current.copy(pendingNotice = null)
        }
    }

    private fun runSync(
        context: Context,
        announceUnread: Boolean,
        onCompleted: ((Result<FeedbackSyncResult>) -> Unit)? = null
    ) {
        _uiState.value = _uiState.value.copy(syncing = true)
        executor.execute {
            runCatching {
                FeedbackIssueSyncService.syncAllSubscriptions(context)
            }.onSuccess { result ->
                val subscriptions = FeedbackIssueLocalStore.loadSubscriptions(context)
                val current = _uiState.value
                val previousUnread = current.subscriptions
                    .asSequence()
                    .filter { it.unread }
                    .map { it.issueNumber }
                    .toSet()
                val newlyUnread = result.unreadIssueNumbers
                    .filterNot(previousUnread::contains)
                val pendingNoticeIssueNumbers = LinkedHashSet<Long>().apply {
                    current.pendingNotice?.unreadIssueNumbers?.forEach(::add)
                    if (announceUnread) {
                        newlyUnread.forEach(::add)
                    }
                }
                val notice = if (pendingNoticeIssueNumbers.isEmpty()) {
                    null
                } else {
                    FeedbackUnreadNotice(pendingNoticeIssueNumbers.toList())
                }
                _uiState.value = FeedbackInboxUiState(
                    subscriptions = subscriptions,
                    unreadIssueCount = subscriptions.count { it.unread },
                    syncing = false,
                    pendingNotice = notice
                )
                onCompleted?.invoke(Result.success(result))
            }.onFailure { error ->
                val current = _uiState.value
                _uiState.value = current.copy(syncing = false)
                onCompleted?.invoke(Result.failure(error))
            }
        }
    }
}
