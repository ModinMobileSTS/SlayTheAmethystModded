package io.stamethyst.ui.compatibility

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
        val modManifestRootCompatEnabled: Boolean = true,
        val frierenModCompatEnabled: Boolean = true,
        val downfallImportCompatEnabled: Boolean = true,
        val vupShionModCompatEnabled: Boolean = true,
        val fragmentShaderPrecisionCompatEnabled: Boolean = true,
        val runtimeTextureCompatEnabled: Boolean = false,
        val forceLinearMipmapFilterEnabled: Boolean = true,
        val nonRenderableFboFormatCompatEnabled: Boolean = true
    )

    var uiState by mutableStateOf(UiState())
        private set

    fun refresh(host: Context) {
        uiState = uiState.copy(
            busy = false,
            busyMessage = null,
            virtualFboPocEnabled = CompatibilitySettings.isVirtualFboPocEnabled(host),
            globalAtlasFilterCompatEnabled = CompatibilitySettings.isGlobalAtlasFilterCompatEnabled(host),
            modManifestRootCompatEnabled = CompatibilitySettings.isModManifestRootCompatEnabled(host),
            frierenModCompatEnabled = CompatibilitySettings.isFrierenModCompatEnabled(host),
            downfallImportCompatEnabled = CompatibilitySettings.isDownfallImportCompatEnabled(host),
            vupShionModCompatEnabled = CompatibilitySettings.isVupShionModCompatEnabled(host),
            fragmentShaderPrecisionCompatEnabled = CompatibilitySettings.isFragmentShaderPrecisionCompatEnabled(host),
            runtimeTextureCompatEnabled = CompatibilitySettings.isRuntimeTextureCompatEnabled(host),
            forceLinearMipmapFilterEnabled = CompatibilitySettings.isForceLinearMipmapFilterEnabled(host),
            nonRenderableFboFormatCompatEnabled = CompatibilitySettings.isNonRenderableFboFormatCompatEnabled(host)
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

    fun onModManifestRootCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setModManifestRootCompatEnabled(host, enabled)
        uiState = uiState.copy(modManifestRootCompatEnabled = enabled)
    }

    fun onFrierenModCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setFrierenModCompatEnabled(host, enabled)
        uiState = uiState.copy(frierenModCompatEnabled = enabled)
    }

    fun onDownfallImportCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setDownfallImportCompatEnabled(host, enabled)
        uiState = uiState.copy(downfallImportCompatEnabled = enabled)
    }

    fun onVupShionModCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setVupShionModCompatEnabled(host, enabled)
        uiState = uiState.copy(vupShionModCompatEnabled = enabled)
    }

    fun onFragmentShaderPrecisionCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setFragmentShaderPrecisionCompatEnabled(host, enabled)
        uiState = uiState.copy(fragmentShaderPrecisionCompatEnabled = enabled)
    }

    fun onRuntimeTextureCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setRuntimeTextureCompatEnabled(host, enabled)
        uiState = uiState.copy(runtimeTextureCompatEnabled = enabled)
    }

    fun onForceLinearMipmapFilterToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setForceLinearMipmapFilterEnabled(host, enabled)
        uiState = uiState.copy(forceLinearMipmapFilterEnabled = enabled)
    }

    fun onNonRenderableFboFormatCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setNonRenderableFboFormatCompatEnabled(host, enabled)
        uiState = uiState.copy(nonRenderableFboFormatCompatEnabled = enabled)
    }
}
