package io.stamethyst.ui.compatibility

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.stamethyst.backend.mods.CompatibilitySettings

@Stable
class CompatibilityScreenViewModel : ViewModel() {
    data class UiState(
        val busy: Boolean = false,
        val busyMessage: String? = null,
        val virtualFboPocEnabled: Boolean = false,
        val globalAtlasFilterCompatEnabled: Boolean = true,
        val forceLinearMipmapFilterEnabled: Boolean = true
    )

    var uiState by mutableStateOf(UiState())
        private set

    fun refresh(host: Context) {
        uiState = uiState.copy(
            busy = false,
            busyMessage = null,
            virtualFboPocEnabled = CompatibilitySettings.isVirtualFboPocEnabled(host),
            globalAtlasFilterCompatEnabled = CompatibilitySettings.isGlobalAtlasFilterCompatEnabled(host),
            forceLinearMipmapFilterEnabled = CompatibilitySettings.isForceLinearMipmapFilterEnabled(host)
        )
    }

    fun onVirtualFboPocToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setVirtualFboPocEnabled(host, enabled)
        uiState = uiState.copy(virtualFboPocEnabled = enabled)
    }

    fun onGlobalAtlasFilterCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setGlobalAtlasFilterCompatEnabled(host, enabled)
        uiState = uiState.copy(globalAtlasFilterCompatEnabled = enabled)
    }

    fun onForceLinearMipmapFilterToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setForceLinearMipmapFilterEnabled(host, enabled)
        uiState = uiState.copy(forceLinearMipmapFilterEnabled = enabled)
    }
}
