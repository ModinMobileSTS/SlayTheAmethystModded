package io.stamethyst.ui.feedback

import android.app.Activity
import android.widget.Toast
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.stamethyst.backend.feedback.FeedbackInboxCoordinator
import io.stamethyst.backend.feedback.FeedbackIssueSyncService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Stable
class FeedbackSubscriptionsViewModel : ViewModel() {
    data class UiState(
        val busy: Boolean = false,
        val busyMessage: String? = null
    )

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    var uiState by mutableStateOf(UiState())
        private set

    fun bind(host: Activity) {
        FeedbackInboxCoordinator.bind(host.applicationContext)
    }

    fun onRefreshAll(host: Activity) {
        if (uiState.busy) {
            return
        }
        setBusy(true, "正在同步我关注的议题...")
        FeedbackInboxCoordinator.runManualSync(host) { result ->
            host.runOnUiThread {
                setBusy(false, null)
                result.onSuccess {
                    Toast.makeText(host, "我关注的议题已同步。", Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    Toast.makeText(host, "同步失败：${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun onUnsubscribe(host: Activity, issueNumber: Long) {
        if (uiState.busy) {
            return
        }
        setBusy(true, "正在取消订阅...")
        executor.execute {
            FeedbackIssueSyncService.unsubscribe(host, issueNumber)
            FeedbackInboxCoordinator.refreshFromStorage(host)
            host.runOnUiThread {
                setBusy(false, null)
                Toast.makeText(host, "已取消关注 Issue #$issueNumber。", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCleared() {
        executor.shutdownNow()
        super.onCleared()
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
