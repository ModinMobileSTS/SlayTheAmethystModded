package io.stamethyst.backend.diag

import android.content.Context
import io.stamethyst.backend.mods.CompatibilitySettings
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
        append("launcherSettings.formatVersion=1\n")
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
        return LauncherSettingsDiagnosticsSnapshot(
            sections = listOf(
                LauncherSettingsDiagnosticsSection(
                    title = "General",
                    entries = listOf(
                        "player.name" to LauncherConfig.readPlayerName(context),
                        "theme.mode" to formatThemeMode(LauncherConfig.readThemeMode(context)),
                        "back.behavior" to formatBackBehavior(LauncherConfig.readBackBehavior(context)),
                        "targetFps" to LauncherConfig.readTargetFps(context).toString(),
                        "render.scale" to LauncherConfig.formatRenderScale(
                            LauncherConfig.readRenderScale(context)
                        ),
                        "manualDismissBootOverlay" to formatBoolean(
                            LauncherConfig.readManualDismissBootOverlay(context)
                        ),
                        "showModFileName" to formatBoolean(
                            LauncherConfig.readShowModFileName(context)
                        ),
                        "autoCheckUpdatesEnabled" to formatBoolean(
                            LauncherConfig.isAutoCheckUpdatesEnabled(context)
                        ),
                        "preferredUpdateMirrorId" to LauncherConfig.readPreferredUpdateMirrorId(context)
                    )
                ),
                LauncherSettingsDiagnosticsSection(
                    title = "InputAndUi",
                    entries = listOf(
                        "showFloatingMouseWindow" to formatBoolean(
                            LauncherConfig.readShowFloatingMouseWindow(context)
                        ),
                        "touchMouseDoubleTapLockEnabled" to formatBoolean(
                            LauncherConfig.readTouchMouseDoubleTapLockEnabled(context)
                        ),
                        "longPressMouseShowsKeyboard" to formatBoolean(
                            LauncherConfig.readLongPressMouseShowsKeyboard(context)
                        ),
                        "autoSwitchLeftAfterRightClick" to formatBoolean(
                            LauncherConfig.readAutoSwitchLeftAfterRightClick(context)
                        ),
                        "touchscreenEnabled" to formatBoolean(
                            LauncherConfig.readTouchscreenEnabled(context)
                        ),
                        "mobileHudEnabled" to formatBoolean(
                            LauncherConfig.readMobileHudEnabled(context)
                        ),
                        "compendiumUpgradeTouchFixEnabled" to formatBoolean(
                            LauncherConfig.readCompendiumUpgradeTouchFixEnabled(context)
                        ),
                        "avoidDisplayCutout" to formatBoolean(
                            LauncherConfig.isDisplayCutoutAvoidanceEnabled(context)
                        ),
                        "cropScreenBottom" to formatBoolean(
                            LauncherConfig.isScreenBottomCropEnabled(context)
                        ),
                        "showGamePerformanceOverlay" to formatBoolean(
                            LauncherConfig.isGamePerformanceOverlayEnabled(context)
                        )
                    )
                ),
                LauncherSettingsDiagnosticsSection(
                    title = "Renderer",
                    entries = listOf(
                        "render.surfaceBackend" to formatRenderSurfaceBackend(
                            LauncherConfig.readRenderSurfaceBackend(context)
                        ),
                        "render.selectionMode" to formatRendererSelectionMode(
                            LauncherConfig.readRendererSelectionMode(context)
                        ),
                        "render.manualBackend" to formatRendererBackend(
                            LauncherConfig.readManualRendererBackend(context)
                        )
                    )
                ),
                LauncherSettingsDiagnosticsSection(
                    title = "MobileGlues",
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
                    title = "JvmAndDiagnostics",
                    entries = listOf(
                        "jvm.heapStartMb" to LauncherConfig.resolveJvmHeapStartMb(heapMaxMb).toString(),
                        "jvm.heapMaxMb" to heapMaxMb.toString(),
                        "jvm.compressedPointersEnabled" to formatBoolean(
                            LauncherConfig.isJvmCompressedPointersEnabled(context)
                        ),
                        "jvm.stringDeduplicationEnabled" to formatBoolean(
                            LauncherConfig.isJvmStringDeduplicationEnabled(context)
                        ),
                        "diag.lwjglDebugEnabled" to formatBoolean(
                            LauncherConfig.isLwjglDebugEnabled(context)
                        ),
                        "diag.logcatCaptureEnabled" to formatBoolean(
                            LauncherConfig.isLogcatCaptureEnabled(context)
                        ),
                        "diag.jvmLogcatMirrorEnabled" to formatBoolean(
                            LauncherConfig.isJvmLogcatMirrorEnabled(context)
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
                    title = "Compatibility",
                    entries = listOf(
                        "virtualFboPoc" to formatBoolean(
                            CompatibilitySettings.isVirtualFboPocEnabled(context)
                        ),
                        "globalAtlasFilterCompat" to formatBoolean(
                            CompatibilitySettings.isGlobalAtlasFilterCompatEnabled(context)
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
                        "runtimeTextureCompat" to formatBoolean(
                            CompatibilitySettings.isRuntimeTextureCompatEnabled(context)
                        ),
                        "forceLinearMipmapFilter" to formatBoolean(
                            CompatibilitySettings.isForceLinearMipmapFilterEnabled(context)
                        ),
                        "nonRenderableFboFormatCompat" to formatBoolean(
                            CompatibilitySettings.isNonRenderableFboFormatCompatEnabled(context)
                        )
                    )
                )
            )
        )
    }

    private fun formatBoolean(value: Boolean): String {
        return if (value) "true" else "false"
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
        return "${policy.displayName} (${policy.persistedValue})"
    }

    private fun formatNoErrorPolicy(policy: MobileGluesNoErrorPolicy): String {
        return "${policy.displayName} (${policy.persistedValue})"
    }

    private fun formatMultidrawMode(mode: MobileGluesMultidrawMode): String {
        return "${mode.displayName} (${mode.persistedValue})"
    }

    private fun formatGlslCacheSize(preset: MobileGluesGlslCacheSizePreset): String {
        return "${preset.displayName} (${preset.persistedValue})"
    }

    private fun formatAngleDepthClearFixMode(
        mode: MobileGluesAngleDepthClearFixMode
    ): String {
        return "${mode.displayName} (${mode.persistedValue})"
    }

    private fun formatCustomGlVersion(version: MobileGluesCustomGlVersion): String {
        return "${version.displayName} (${version.persistedValue})"
    }

    private fun formatFsr1QualityPreset(preset: MobileGluesFsr1QualityPreset): String {
        return "${preset.displayName} (${preset.persistedValue})"
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
