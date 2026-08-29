package io.stamethyst.backend.diag

import android.content.Context
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.mods.importing.patches.ImportPatchRegistry
import io.stamethyst.backend.mods.importing.patches.texture.AtlasFilterPatchModule
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.LauncherThemeMode
import io.stamethyst.config.RenderSurfaceBackend
import io.stamethyst.backend.render.MobileGluesAngleDepthClearFixMode
import io.stamethyst.backend.render.MobileGluesAnglePolicy
import io.stamethyst.backend.render.MobileGluesCustomGlVersion
import io.stamethyst.backend.render.MobileGluesFsr1QualityPreset
import io.stamethyst.backend.render.MobileGluesGlslCacheSizePreset
import io.stamethyst.backend.render.MobileGluesMultidrawMode
import io.stamethyst.backend.render.MobileGluesNoErrorPolicy
import io.stamethyst.backend.render.RendererBackend
import io.stamethyst.backend.render.RendererSelectionMode

internal data class LauncherSettingsDiagnosticsSection(
    val title: String,
    val entries: List<Pair<String, String>>
)

internal data class LauncherSettingsDiagnosticsSnapshot(
    val sections: List<LauncherSettingsDiagnosticsSection>
)

internal object LauncherSettingsDiagnosticsFormatter {
    fun buildFromContext(context: Context): String {
        return build(capture(context))
    }

    fun build(snapshot: LauncherSettingsDiagnosticsSnapshot): String = buildString {
        append("launcherSettings.formatVersion=2\n")
        append("launcherSettings.snapshotType=resolved_values\n")
        for (section in snapshot.sections) {
            append('\n')
            append('[').append(section.title).append("]\n")
            for ((key, value) in section.entries) {
                append(key).append('=').append(normalizeValue(value)).append('\n')
            }
        }
    }

    private fun capture(context: Context): LauncherSettingsDiagnosticsSnapshot {
        val heapMaxMb = LauncherConfig.readJvmHeapMaxMb(context)
        val mobileGlues = LauncherConfig.readMobileGluesSettings(context)
        val touchscreenInputMode = LauncherConfig.readTouchscreenInputMode(context)
        val nativeTouchscreenAllowlistCompatEnabled =
            CompatibilitySettings.isNativeTouchscreenAllowlistCompatEnabled(context)
        val runtimeDownscalePolicy = CompatibilitySettings.readRuntimeDownscaleMaterialPolicy(context)
        val importDownscalePolicy = CompatibilitySettings.readImportDownscaleMaterialPolicy(context)
        return LauncherSettingsDiagnosticsSnapshot(
            sections = listOf(
                LauncherSettingsDiagnosticsSection(
                    title = "Launcher / Appearance",
                    entries = listOf(
                        "theme.mode" to formatThemeMode(LauncherConfig.readThemeMode(context)),
                        "bootOverlayStyle" to LauncherConfig.readBootOverlayStyle(context).persistedValue,
                        "bootOverlayAnimation" to
                            LauncherConfig.readBootOverlayAnimation(context).persistedValue,
                        "showModFileName" to formatBoolean(LauncherConfig.readShowModFileName(context)),
                    )
                ),
                LauncherSettingsDiagnosticsSection(
                    title = "Launcher / Updates",
                    entries = listOf(
                        "autoCheckUpdatesEnabled" to formatBoolean(
                            LauncherConfig.isAutoCheckUpdatesEnabled(context)
                        ),
                        "preferredUpdateMirrorId" to LauncherConfig.readPreferredUpdateMirrorId(context)
                    )
                ),
                LauncherSettingsDiagnosticsSection(
                    title = "Game / Player name",
                    entries = listOf(
                        "player.name" to LauncherConfig.readPlayerName(context),
                    )
                ),
                LauncherSettingsDiagnosticsSection(
                    title = "Game / Rendering",
                    entries = listOf(
                        "targetFps" to LauncherConfig.readTargetFps(context).toString(),
                        "render.scale" to LauncherConfig.formatRenderScale(
                            LauncherConfig.readRenderScale(context)
                        ),
                        "gameplayFontScale" to LauncherConfig.formatGameplayFontScale(
                            LauncherConfig.readGameplayFontScale(context)
                        ),
                        "largerUiEnabled" to formatBoolean(
                            LauncherConfig.readGameplayLargerUiEnabled(context)
                        ),
                        "avoidDisplayCutout" to formatBoolean(
                            LauncherConfig.isDisplayCutoutAvoidanceEnabled(context)
                        ),
                        "cropScreenBottom" to formatBoolean(
                            LauncherConfig.isScreenBottomCropEnabled(context)
                        ),
                    )
                ),
                LauncherSettingsDiagnosticsSection(
                    title = "Game / Input and interaction",
                    entries = listOf(
                        "back.behavior" to formatBackBehavior(LauncherConfig.readBackBehavior(context)),
                        "specialKeyInputMode" to
                            LauncherConfig.readSpecialKeyInputMode(context).persistedValue,
                        "showFloatingMouseWindow" to formatBoolean(
                            LauncherConfig.readShowFloatingMouseWindow(context)
                        ),
                        "touchMouseInteractionMode" to
                            LauncherConfig.readTouchMouseInteractionMode(context).persistedValue,
                        "builtInSoftKeyboardEnabled" to formatBoolean(
                            LauncherConfig.isBuiltInSoftKeyboardEnabled(context)
                        ),
                        "hapticFeedbackEnabled" to formatBoolean(
                            LauncherConfig.isHapticFeedbackEnabled(context)
                        ),
                        "autoSwitchLeftAfterRightClick" to formatBoolean(
                            LauncherConfig.readAutoSwitchLeftAfterRightClick(context)
                        ),
                        "ignoreLongPressRightClickWhilePlayingCard" to formatBoolean(
                            LauncherConfig.readIgnoreLongPressRightClickWhilePlayingCard(context)
                        ),
                        "touchscreenInputMode" to touchscreenInputMode.persistedValue,
                        "touchscreenEnabled" to formatBoolean(touchscreenInputMode.touchscreenEnabled),
                        "nativeTouchscreenEnabled" to formatBoolean(
                            touchscreenInputMode.touchscreenEnabled
                        ),
                        "touchIndicatorEnabled" to formatBoolean(
                            touchscreenInputMode.touchscreenEnabled &&
                                LauncherConfig.readTouchIndicatorEnabled(context)
                        ),
                        "touchscreenPolicy" to if (touchscreenInputMode.nativeTouchscreenAllowlistEnabled) {
                            "vanilla_allowlist"
                        } else {
                            "global"
                        },
                        "keepScreenOnTimeoutMinutes" to
                            LauncherConfig.readKeepScreenOnTimeoutMinutes(context).toString(),
                        "showGamePerformanceOverlay" to formatBoolean(
                            LauncherConfig.isGamePerformanceOverlayEnabled(context)
                        ),
                    )
                ),
                LauncherSettingsDiagnosticsSection(
                    title = "Developer / Runtime",
                    entries = listOf(
                        "manualDismissBootOverlay" to formatBoolean(
                            LauncherConfig.readManualDismissBootOverlay(context)
                        ),
                        "compendiumUpgradeTouchFixEnabled" to formatBoolean(
                            LauncherConfig.readCompendiumUpgradeTouchFixEnabled(context)
                        ),
                        "sustainedPerformanceModeEnabled" to formatBoolean(
                            LauncherConfig.isSustainedPerformanceModeEnabled(context)
                        )
                    )
                ),
                LauncherSettingsDiagnosticsSection(
                    title = "Developer / Advanced rendering",
                    entries = listOf(
                        "render.surfaceBackend" to formatRenderSurfaceBackend(
                            LauncherConfig.readRenderSurfaceBackend(context)
                        ),
                        "render.selectionMode" to formatRendererSelectionMode(
                            LauncherConfig.readRendererSelectionMode(context)
                        ),
                        "render.manualBackend" to formatRendererBackend(
                            LauncherConfig.readManualRendererBackend(context)
                        ),
                        "gpuResourceGuardianMode" to formatPersistedEnum(
                            LauncherConfig.readGpuResourceGuardianMode(context).name,
                            LauncherConfig.readGpuResourceGuardianMode(context).persistedValue
                        ),
                        "jvm.heapStartMb" to LauncherConfig.resolveJvmHeapStartMb(heapMaxMb).toString(),
                        "jvm.heapMaxMb" to heapMaxMb.toString(),
                        "jvm.compressedPointersEnabled" to formatBoolean(
                            LauncherConfig.isJvmCompressedPointersEnabled(context)
                        ),
                        "jvm.stringDeduplicationEnabled" to formatBoolean(
                            LauncherConfig.isJvmStringDeduplicationEnabled(context)
                        )
                    )
                ),
                LauncherSettingsDiagnosticsSection(
                    title = "Developer / MobileGlues",
                    entries = listOf(
                        "anglePolicy" to formatAnglePolicy(mobileGlues.anglePolicy),
                        "noErrorPolicy" to formatNoErrorPolicy(mobileGlues.noErrorPolicy),
                        "multidrawMode" to formatMultidrawMode(mobileGlues.multidrawMode),
                        "extComputeShaderEnabled" to formatBoolean(
                            mobileGlues.extComputeShaderEnabled
                        ),
                        "extTimerQueryEnabled" to formatBoolean(
                            mobileGlues.extTimerQueryEnabled
                        ),
                        "extDirectStateAccessEnabled" to formatBoolean(
                            mobileGlues.extDirectStateAccessEnabled
                        ),
                        "glslCacheSize" to formatGlslCacheSize(mobileGlues.glslCacheSizePreset),
                        "angleDepthClearFixMode" to formatAngleDepthClearFixMode(
                            mobileGlues.angleDepthClearFixMode
                        ),
                        "customGlVersion" to formatCustomGlVersion(
                            mobileGlues.customGlVersion
                        ),
                        "fsr1QualityPreset" to formatFsr1QualityPreset(
                            mobileGlues.fsr1QualityPreset
                        )
                    )
                ),
                LauncherSettingsDiagnosticsSection(
                    title = "Developer / Status and logs",
                    entries = listOf(
                        "diag.lwjglDebugEnabled" to formatBoolean(
                            LauncherConfig.isLwjglDebugEnabled(context)
                        ),
                        "diag.logcatCaptureEnabled" to formatBoolean(
                            LauncherConfig.isLogcatCaptureEnabled(context)
                        ),
                        "diag.launcherLogcatCaptureEnabled" to formatBoolean(
                            LauncherConfig.isLauncherLogcatCaptureEnabled(context)
                        ),
                        "diag.jvmLogcatMirrorEnabled" to formatBoolean(
                            LauncherConfig.isJvmLogcatMirrorEnabled(context)
                        ),
                        "diag.gpuResourceDiagEnabled" to formatBoolean(
                            LauncherConfig.isGpuResourceDiagEnabled(context)
                        ),
                        "diag.gdxPadCursorDebugEnabled" to formatBoolean(
                            LauncherConfig.isGdxPadCursorDebugEnabled(context)
                        ),
                        "diag.glBridgeSwapHeartbeatDebugEnabled" to formatBoolean(
                            LauncherConfig.isGlBridgeSwapHeartbeatDebugEnabled(context)
                        )
                    )
                ),
                LauncherSettingsDiagnosticsSection(
                    title = "Developer / Compatibility settings",
                    entries = listOf(
                        "globalAtlasFilterCompat" to formatBoolean(
                            ImportPatchRegistry.isEnabled(context, AtlasFilterPatchModule.id)
                        ),
                        "modManifestRootCompat" to formatBoolean(
                            CompatibilitySettings.isModManifestRootCompatEnabled(context)
                        ),
                        "frierenModCompat" to formatBoolean(
                            CompatibilitySettings.isFrierenModCompatEnabled(context)
                        ),
                        "downfallImportCompat" to formatBoolean(
                            CompatibilitySettings.isDownfallImportCompatEnabled(context)
                        ),
                        "vupShionModCompat" to formatBoolean(
                            CompatibilitySettings.isVupShionModCompatEnabled(context)
                        ),
                        "jacketNoAnoKoModCompat" to formatBoolean(
                            CompatibilitySettings.isJacketNoAnoKoModCompatEnabled(context)
                        ),
                        "fragmentShaderPrecisionCompat" to formatBoolean(
                            CompatibilitySettings.isFragmentShaderPrecisionCompatEnabled(context)
                        ),
                        "runtimeTextureCompat" to formatBoolean(
                            CompatibilitySettings.isRuntimeTextureCompatEnabled(context)
                        ),
                        "mainMenuPreviewReuseCompat" to formatBoolean(
                            CompatibilitySettings.isMainMenuPreviewReuseCompatEnabled(context)
                        ),
                        "powerIconRenderRescueCompat" to formatBoolean(
                            CompatibilitySettings.isPowerIconRenderRescueCompatEnabled(context)
                        ),
                        "baseModCustomMonsterRenderRescueCompat" to formatBoolean(
                            CompatibilitySettings.isBaseModCustomMonsterRenderRescueCompatEnabled(context)
                        ),
                        "nonCombatPlayerRenderRescueCompat" to formatBoolean(
                            CompatibilitySettings.isNonCombatPlayerRenderRescueCompatEnabled(context)
                        ),
                        "cardTooltipKeywordRescueCompat" to formatBoolean(
                            CompatibilitySettings.isCardTooltipKeywordRescueCompatEnabled(context)
                        ),
                        "nativeTouchscreenAllowlistCompat" to formatBoolean(
                            nativeTouchscreenAllowlistCompatEnabled
                        ),
                        "largeTextureDownscaleCompat" to formatBoolean(
                            CompatibilitySettings.isLargeTextureDownscaleCompatEnabled(context)
                        ),
                        "texturePressureDownscaleDivisor" to formatTexturePressureDownscaleDivisor(
                            CompatibilitySettings.readTexturePressureDownscaleDivisor(context)
                        ),
                        "forceLinearMipmapFilter" to formatBoolean(
                            CompatibilitySettings.isForceLinearMipmapFilterEnabled(context)
                        ),
                        "hinaCharacterRenderCompat" to formatBoolean(
                            CompatibilitySettings.isHinaCharacterRenderCompatEnabled(context)
                        ),
                        "nonRenderableFboFormatCompat" to formatBoolean(
                            CompatibilitySettings.isNonRenderableFboFormatCompatEnabled(context)
                        ),
                        "fboManagerCompat" to formatBoolean(
                            CompatibilitySettings.isFboManagerCompatEnabled(context)
                        ),
                        "fboIdleReclaimCompat" to formatBoolean(
                            CompatibilitySettings.isFboIdleReclaimCompatEnabled(context)
                        ),
                        "fboPressureDownscaleCompat" to formatBoolean(
                            CompatibilitySettings.isFboPressureDownscaleCompatEnabled(context)
                        ),
                        "runtimeDownscaleOrdinaryTextures" to formatBoolean(
                            runtimeDownscalePolicy.ordinaryTextures
                        ),
                        "runtimeDownscaleTextureAtlasPages" to runtimeDownscalePolicy
                            .textureAtlasPages
                            .prefValue,
                        "runtimeDownscaleSpineTextures" to formatBoolean(
                            runtimeDownscalePolicy.spineTextures
                        ),
                        "runtimeDownscaleOffscreenFrameBuffers" to formatBoolean(
                            runtimeDownscalePolicy.offscreenFrameBuffers
                        ),
                        "importDownscaleSpineAtlasPages" to formatBoolean(
                            importDownscalePolicy.spineAtlasPages
                        ),
                        "importDownscaleOrdinaryAtlasPages" to formatBoolean(
                            importDownscalePolicy.ordinaryAtlasPages
                        )
                    )
                )
            )
        )
    }

    private fun formatBoolean(value: Boolean): String {
        return if (value) "true" else "false"
    }

    private fun formatTexturePressureDownscaleDivisor(value: Int): String {
        return "x$value"
    }

    private fun formatPersistedEnum(name: String, persistedValue: String): String {
        return "$name ($persistedValue)"
    }

    private fun formatThemeMode(themeMode: LauncherThemeMode): String {
        return "${themeMode.name} (${themeMode.persistedValue})"
    }

    private fun formatBackBehavior(backBehavior: BackBehavior): String {
        return "${backBehavior.name} (${backBehavior.persistedValue})"
    }

    private fun formatRenderSurfaceBackend(backend: RenderSurfaceBackend): String {
        return "${backend.name} (${backend.persistedValue})"
    }

    private fun formatRendererSelectionMode(mode: RendererSelectionMode): String {
        return "${mode.name} (${mode.persistedValue})"
    }

    private fun formatRendererBackend(backend: RendererBackend): String {
        return "${backend.displayName} (${backend.rendererId()})"
    }

    private fun formatAnglePolicy(policy: MobileGluesAnglePolicy): String {
        return "${policy.name} (${policy.persistedValue})"
    }

    private fun formatNoErrorPolicy(policy: MobileGluesNoErrorPolicy): String {
        return "${policy.name} (${policy.persistedValue})"
    }

    private fun formatMultidrawMode(mode: MobileGluesMultidrawMode): String {
        return "${mode.name} (${mode.persistedValue})"
    }

    private fun formatGlslCacheSize(preset: MobileGluesGlslCacheSizePreset): String {
        return "${preset.name} (${preset.persistedValue})"
    }

    private fun formatAngleDepthClearFixMode(
        mode: MobileGluesAngleDepthClearFixMode
    ): String {
        return "${mode.name} (${mode.persistedValue})"
    }

    private fun formatCustomGlVersion(version: MobileGluesCustomGlVersion): String {
        return "${version.name} (${version.persistedValue})"
    }

    private fun formatFsr1QualityPreset(preset: MobileGluesFsr1QualityPreset): String {
        return "${preset.name} (${preset.persistedValue})"
    }

    private fun normalizeValue(value: String): String {
        val sanitized = value
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
        return if (sanitized.isEmpty()) {
            "none"
        } else {
            sanitized
        }
    }
}
