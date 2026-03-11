package io.stamethyst.ui.feedback

import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.stamethyst.backend.feedback.FeedbackInboxCoordinator
import io.stamethyst.backend.feedback.FeedbackIssueBrowseItem
import io.stamethyst.backend.feedback.FeedbackIssueSyncService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Stable
class FeedbackIssueBrowserViewModel : ViewModel() {
    companion object {
        private const val TAG = "FeedbackIssueBrowser"
    }

    enum class IssueStateFilter(val label: String) {
        ALL("全部议题"),
        OPEN_ONLY("只看进行中"),
        CLOSED_ONLY("只看已解决");

        fun matches(issue: FeedbackIssueBrowseItem): Boolean {
            return when (this) {
                ALL -> true
                OPEN_ONLY -> !issue.isClosed
                CLOSED_ONLY -> issue.isClosed
            }
        }
    }

    data class UiState(
        val busy: Boolean = false,
        val busyMessage: String? = null,
        val loadingMore: Boolean = false,
        val issues: List<FeedbackIssueBrowseItem> = emptyList(),
        val nextPage: Int = 1,
        val hasMore: Boolean = true,
        val initialLoaded: Boolean = false,
        val issueStateFilter: IssueStateFilter = IssueStateFilter.OPEN_ONLY
    ) {
        val visibleIssues: List<FeedbackIssueBrowseItem>
            get() = issues.asSequence()
                .filter(issueStateFilter::matches)
                .sortedWith(
                    compareByDescending<FeedbackIssueBrowseItem> { it.updatedAtMs }
                        .thenByDescending { it.issueNumber }
                )
                .toList()
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    var uiState by mutableStateOf(UiState())
        private set

    fun bind(host: Activity) {
        FeedbackInboxCoordinator.bind(host.applicationContext)
        if (!uiState.initialLoaded && !uiState.busy && !uiState.loadingMore) {
            loadPage(host, reset = true)
        }
    }

    fun onRefresh(host: Activity) {
        if (uiState.busy || uiState.loadingMore) {
            return
        }
        loadPage(host, reset = true)
    }

    fun onLoadMore(host: Activity) {
        if (uiState.busy || uiState.loadingMore || !uiState.hasMore) {
            return
        }
        loadPage(host, reset = false)
    }

    fun onIssueStateFilterSelected(filter: IssueStateFilter) {
        if (uiState.issueStateFilter == filter) {
            return
        }
        uiState = uiState.copy(issueStateFilter = filter)
    }

    fun onSubscribe(host: Activity, issueNumber: Long) {
        if (uiState.busy || uiState.loadingMore) {
            return
        }
        setBusy(true, "正在关注议题...")
        executor.execute {
            runCatching {
                FeedbackIssueSyncService.subscribeToIssue(host, issueNumber)
            }.onSuccess { subscription ->
                FeedbackInboxCoordinator.refreshFromStorage(host)
                host.runOnUiThread {
                    setBusy(false, null)
                    Toast.makeText(
                        host,
                        "已关注 Issue #${subscription.issueNumber}，可从“我关注的议题”继续跟进。",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to follow issue", error)
                host.runOnUiThread {
                    setBusy(false, null)
                    Toast.makeText(
                        host,
                        "关注失败：${error.toReadableMessage()}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onCleared() {
        executor.shutdownNow()
        super.onCleared()
    }

    private fun loadPage(host: Activity, reset: Boolean) {
        if (reset) {
            setBusy(true, "正在加载议题列表...")
        } else {
            uiState = uiState.copy(loadingMore = true)
        }
        val requestPage = if (reset) 1 else uiState.nextPage
        executor.execute {
            runCatching {
                FeedbackIssueSyncService.listIssues(host, requestPage)
            }.onSuccess { page ->
                host.runOnUiThread {
                    val mergedIssues = if (reset) {
                        page.issues
                    } else {
                        mergeIssues(uiState.issues, page.issues)
                    }
                    uiState = uiState.copy(
                        busy = false,
                        busyMessage = null,
                        loadingMore = false,
                        issues = mergedIssues,
                        nextPage = page.nextPage,
                        hasMore = page.hasMore,
                        initialLoaded = true
                    )
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to load issue browser page", error)
                host.runOnUiThread {
                    uiState = uiState.copy(
                        busy = false,
                        busyMessage = null,
                        loadingMore = false,
                        initialLoaded = true
                    )
                    Toast.makeText(
                        host,
                        "议题列表加载失败：${error.toReadableMessage()}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun mergeIssues(
        existing: List<FeedbackIssueBrowseItem>,
        appended: List<FeedbackIssueBrowseItem>
    ): List<FeedbackIssueBrowseItem> {
        if (appended.isEmpty()) {
            return existing
        }
        val merged = LinkedHashMap<Long, FeedbackIssueBrowseItem>(existing.size + appended.size)
        existing.forEach { merged[it.issueNumber] = it }
        appended.forEach { merged[it.issueNumber] = it }
        return merged.values.toList()
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

    private fun Throwable.toReadableMessage(): String {
        val rawMessage = message?.trim().orEmpty()
        if (rawMessage.isNotEmpty() && rawMessage != javaClass.name) {
            return rawMessage
        }
        val causeMessage = cause?.message?.trim().orEmpty()
        if (causeMessage.isNotEmpty()) {
            return "${javaClass.simpleName}: $causeMessage"
        }
        return javaClass.simpleName.ifBlank { "未知错误" }
    }
}
