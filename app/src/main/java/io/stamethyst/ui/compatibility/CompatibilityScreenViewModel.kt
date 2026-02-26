package io.stamethyst.ui.compatibility

import android.app.Activity
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.stamethyst.backend.CompatibilitySettings

@Stable
class CompatibilityScreenViewModel : ViewModel() {
    data class UiState(
        val busy: Boolean = false,
        val busyMessage: String? = null,
        val originalFboPatchEnabled: Boolean = true,
        val downfallFboPatchEnabled: Boolean = true,
        val virtualFboPocEnabled: Boolean = false
    )

    var uiState by mutableStateOf(UiState())
        private set

    fun refresh(host: Activity) {
        uiState = uiState.copy(
            busy = false,
            busyMessage = null,
            originalFboPatchEnabled = CompatibilitySettings.isOriginalFboPatchEnabled(host),
            downfallFboPatchEnabled = CompatibilitySettings.isDownfallFboPatchEnabled(host),
            virtualFboPocEnabled = CompatibilitySettings.isVirtualFboPocEnabled(host)
        )
    }

    fun onOriginalFboPatchToggled(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setOriginalFboPatchEnabled(host, enabled)
        uiState = uiState.copy(originalFboPatchEnabled = enabled)
    }

    fun onDownfallFboPatchToggled(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setDownfallFboPatchEnabled(host, enabled)
        uiState = uiState.copy(downfallFboPatchEnabled = enabled)
    }

    fun onVirtualFboPocToggled(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setVirtualFboPocEnabled(host, enabled)
        uiState = uiState.copy(virtualFboPocEnabled = enabled)
    }
}
