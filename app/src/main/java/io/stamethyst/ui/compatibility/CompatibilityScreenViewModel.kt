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
        val globalAtlasFilterCompatEnabled: Boolean = true,
        val modManifestRootCompatEnabled: Boolean = true,
        val frierenModCompatEnabled: Boolean = true,
        val downfallImportCompatEnabled: Boolean = true,
        val vupShionModCompatEnabled: Boolean = true,
        val fragmentShaderPrecisionCompatEnabled: Boolean = true,
        val runtimeTextureCompatEnabled: Boolean = false,
        val mainMenuPreviewReuseCompatEnabled: Boolean = true,
        val nativeTouchscreenAllowlistCompatEnabled: Boolean = true,
        val largeTextureDownscaleCompatEnabled: Boolean = false,
        val textureResidencyManagerCompatEnabled: Boolean = false,
        val texturePressureDownscaleDivisor: Int = 2,
        val forceLinearMipmapFilterEnabled: Boolean = true,
        val hinaCharacterRenderCompatEnabled: Boolean = true,
        val nonRenderableFboFormatCompatEnabled: Boolean = true,
        val fboManagerCompatEnabled: Boolean = false,
        val fboIdleReclaimCompatEnabled: Boolean = false,
        val fboPressureDownscaleCompatEnabled: Boolean = false
    )

    var uiState by mutableStateOf(UiState())
        private set

    fun refresh(host: Context) {
        uiState = uiState.copy(
            busy = false,
            busyMessage = null,
            globalAtlasFilterCompatEnabled = CompatibilitySettings.isGlobalAtlasFilterCompatEnabled(host),
            modManifestRootCompatEnabled = CompatibilitySettings.isModManifestRootCompatEnabled(host),
            frierenModCompatEnabled = CompatibilitySettings.isFrierenModCompatEnabled(host),
            downfallImportCompatEnabled = CompatibilitySettings.isDownfallImportCompatEnabled(host),
            vupShionModCompatEnabled = CompatibilitySettings.isVupShionModCompatEnabled(host),
            fragmentShaderPrecisionCompatEnabled = CompatibilitySettings.isFragmentShaderPrecisionCompatEnabled(host),
            runtimeTextureCompatEnabled = CompatibilitySettings.isRuntimeTextureCompatEnabled(host),
            mainMenuPreviewReuseCompatEnabled = CompatibilitySettings.isMainMenuPreviewReuseCompatEnabled(host),
            nativeTouchscreenAllowlistCompatEnabled =
                CompatibilitySettings.isNativeTouchscreenAllowlistCompatEnabled(host),
            largeTextureDownscaleCompatEnabled = CompatibilitySettings.isLargeTextureDownscaleCompatEnabled(host),
            textureResidencyManagerCompatEnabled = CompatibilitySettings.isTextureResidencyManagerCompatEnabled(host),
            texturePressureDownscaleDivisor = CompatibilitySettings.readTexturePressureDownscaleDivisor(host),
            forceLinearMipmapFilterEnabled = CompatibilitySettings.isForceLinearMipmapFilterEnabled(host),
            hinaCharacterRenderCompatEnabled = CompatibilitySettings.isHinaCharacterRenderCompatEnabled(host),
            nonRenderableFboFormatCompatEnabled = CompatibilitySettings.isNonRenderableFboFormatCompatEnabled(host),
            fboManagerCompatEnabled = CompatibilitySettings.isFboManagerCompatEnabled(host),
            fboIdleReclaimCompatEnabled = CompatibilitySettings.isFboIdleReclaimCompatEnabled(host),
            fboPressureDownscaleCompatEnabled = CompatibilitySettings.isFboPressureDownscaleCompatEnabled(host)
        )
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

    fun onMainMenuPreviewReuseCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setMainMenuPreviewReuseCompatEnabled(host, enabled)
        uiState = uiState.copy(mainMenuPreviewReuseCompatEnabled = enabled)
    }

    fun onNativeTouchscreenAllowlistCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setNativeTouchscreenAllowlistCompatEnabled(host, enabled)
        uiState = uiState.copy(nativeTouchscreenAllowlistCompatEnabled = enabled)
    }

    fun onLargeTextureDownscaleCompatToggled(host: Context, enabled: Boolean) {
        CompatibilitySettings.setLargeTextureDownscaleCompatEnabled(host, false)
        uiState = uiState.copy(largeTextureDownscaleCompatEnabled = false)
    }

    fun onTextureResidencyManagerCompatToggled(host: Context, enabled: Boolean) {
        CompatibilitySettings.setTextureResidencyManagerCompatEnabled(host, false)
        uiState = uiState.copy(textureResidencyManagerCompatEnabled = false)
    }

    fun onTexturePressureDownscaleDivisorChanged(host: Context, divisor: Int) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.saveTexturePressureDownscaleDivisor(host, divisor)
        uiState = uiState.copy(texturePressureDownscaleDivisor = divisor)
    }

    fun onForceLinearMipmapFilterToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setForceLinearMipmapFilterEnabled(host, enabled)
        uiState = uiState.copy(forceLinearMipmapFilterEnabled = enabled)
    }

    fun onHinaCharacterRenderCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setHinaCharacterRenderCompatEnabled(host, enabled)
        uiState = uiState.copy(hinaCharacterRenderCompatEnabled = enabled)
    }

    fun onNonRenderableFboFormatCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setNonRenderableFboFormatCompatEnabled(host, enabled)
        uiState = uiState.copy(nonRenderableFboFormatCompatEnabled = enabled)
    }

    fun onFboManagerCompatToggled(host: Context, enabled: Boolean) {
        CompatibilitySettings.setFboManagerCompatEnabled(host, false)
        uiState = uiState.copy(fboManagerCompatEnabled = false)
    }

    fun onFboIdleReclaimCompatToggled(host: Context, enabled: Boolean) {
        CompatibilitySettings.setFboIdleReclaimCompatEnabled(host, false)
        uiState = uiState.copy(fboIdleReclaimCompatEnabled = false)
    }

    fun onFboPressureDownscaleCompatToggled(host: Context, enabled: Boolean) {
        CompatibilitySettings.setFboPressureDownscaleCompatEnabled(host, false)
        uiState = uiState.copy(fboPressureDownscaleCompatEnabled = false)
    }
}
