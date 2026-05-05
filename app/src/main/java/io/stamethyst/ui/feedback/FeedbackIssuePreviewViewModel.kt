package io.stamethyst.ui.feedback

import android.app.Activity
import android.widget.Toast
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.stamethyst.R
import io.stamethyst.backend.feedback.FeedbackInboxCoordinator
import io.stamethyst.backend.feedback.FeedbackIssueLocalStore
import io.stamethyst.backend.feedback.FeedbackIssueSyncService
import io.stamethyst.backend.feedback.FeedbackIssueThreadCache
import io.stamethyst.backend.feedback.FeedbackThreadEvent
import io.stamethyst.ui.LauncherTransientNoticeBus
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Stable
class FeedbackIssuePreviewViewModel(
    private val issueNumber: Long
) : ViewModel() {
    companion object {
        fun factory(issueNumber: Long): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return FeedbackIssuePreviewViewModel(issueNumber) as T
                }
            }
        }
    }

    data class UiState(
        val issueNumber: Long,
        val busy: Boolean = false,
        val busyMessage: String? = null,
        val title: String = "",
        val issueUrl: String = "",
        val state: String = "open",
        val issueBody: String = "",
        val events: List<FeedbackThreadEvent> = emptyList(),
        val isFollowed: Boolean = false
    ) {
        val isClosed: Boolean
            get() = state.equals("closed", ignoreCase = true)
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var didBind = false

    var uiState by mutableStateOf(UiState(issueNumber = issueNumber))
        private set

    fun bind(host: Activity) {
        if (didBind) {
            return
        }
        didBind = true
        FeedbackInboxCoordinator.bind(host.applicationContext)
        FeedbackIssueLocalStore.loadIssueCache(host, issueNumber)?.let { cache ->
            applyCache(cache)
        }
        checkFollowState(host)
        executor.execute {
            runCatching {
                FeedbackIssueSyncService.fetchIssuePreview(host, issueNumber)
            }.onSuccess { cache ->
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
        setBusy(true, host.getString(R.string.feedback_busy_refreshing_issue))
        executor.execute {
            runCatching {
                FeedbackIssueSyncService.fetchIssuePreview(host, issueNumber)
            }.onSuccess { cache ->
                host.runOnUiThread {
                    setBusy(false, null)
                    applyCache(cache)
                }
            }.onFailure { error ->
                host.runOnUiThread {
                    setBusy(false, null)
                    LauncherTransientNoticeBus.show(
                        host,
                        host.getString(
                            R.string.feedback_refresh_failed,
                            error.message ?: host.getString(R.string.feedback_unknown_error)
                        ),
                        Toast.LENGTH_LONG
                    )
                }
            }
        }
    }

    fun onFollowIssue(host: Activity) {
        if (uiState.busy || uiState.isFollowed) {
            return
        }
        setBusy(true, host.getString(R.string.feedback_busy_following_issue))
        executor.execute {
            runCatching {
                FeedbackIssueSyncService.subscribeToIssue(host, issueNumber)
            }.onSuccess { result ->
                FeedbackInboxCoordinator.refreshFromStorage(host)
                host.runOnUiThread {
                    setBusy(false, null)
                    uiState = uiState.copy(isFollowed = true)
                    val message = if (result.displacedSubscriptions.isNotEmpty()) {
                        host.getString(
                            R.string.feedback_follow_success_with_replacement,
                            issueNumber,
                            result.displacedSubscriptions.first().issueNumber
                        )
                    } else {
                        host.getString(R.string.feedback_follow_success, issueNumber)
                    }
                    LauncherTransientNoticeBus.show(host, message, Toast.LENGTH_SHORT)
                }
            }.onFailure { error ->
                host.runOnUiThread {
                    setBusy(false, null)
                    LauncherTransientNoticeBus.show(
                        host,
                        host.getString(
                            R.string.feedback_follow_failed,
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
        super.onCleared()
    }

    private fun checkFollowState(host: Activity) {
        executor.execute {
            val subscriptions = FeedbackIssueLocalStore.loadSubscriptions(host)
            val isFollowed = subscriptions.any { it.issueNumber == issueNumber }
            host.runOnUiThread {
                uiState = uiState.copy(isFollowed = isFollowed)
            }
        }
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
}
