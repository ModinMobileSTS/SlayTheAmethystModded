package io.stamethyst.backend.mods

import android.content.Context
import io.stamethyst.config.LauncherConfig

object CompatibilitySettings {
    @JvmStatic
    fun isVirtualFboPocEnabled(context: Context): Boolean {
        return LauncherConfig.isVirtualFboPocEnabled(context)
    }

    @JvmStatic
    fun setVirtualFboPocEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setVirtualFboPocEnabled(context, enabled)
    }

    @JvmStatic
    fun isGlobalAtlasFilterCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isGlobalAtlasFilterCompatEnabled(context)
    }

    @JvmStatic
    fun setGlobalAtlasFilterCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setGlobalAtlasFilterCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isModManifestRootCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isModManifestRootCompatEnabled(context)
    }

    @JvmStatic
    fun setModManifestRootCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setModManifestRootCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isFrierenModCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isFrierenModCompatEnabled(context)
    }

    @JvmStatic
    fun setFrierenModCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setFrierenModCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isDownfallImportCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isDownfallImportCompatEnabled(context)
    }

    @JvmStatic
    fun setDownfallImportCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setDownfallImportCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isVupShionModCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isVupShionModCompatEnabled(context)
    }

    @JvmStatic
    fun setVupShionModCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setVupShionModCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isFragmentShaderPrecisionCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isFragmentShaderPrecisionCompatEnabled(context)
    }

    @JvmStatic
    fun setFragmentShaderPrecisionCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setFragmentShaderPrecisionCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isRuntimeTextureCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isRuntimeTextureCompatEnabled(context)
    }

    @JvmStatic
    fun setRuntimeTextureCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setRuntimeTextureCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isMainMenuPreviewReuseCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isMainMenuPreviewReuseCompatEnabled(context)
    }

    @JvmStatic
    fun setMainMenuPreviewReuseCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setMainMenuPreviewReuseCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isLargeTextureDownscaleCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isLargeTextureDownscaleCompatEnabled(context)
    }

    @JvmStatic
    fun setLargeTextureDownscaleCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setLargeTextureDownscaleCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isTextureResidencyManagerCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isTextureResidencyManagerCompatEnabled(context)
    }

    @JvmStatic
    fun setTextureResidencyManagerCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setTextureResidencyManagerCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun readTexturePressureDownscaleDivisor(context: Context): Int {
        return LauncherConfig.readTexturePressureDownscaleDivisor(context)
    }

    @JvmStatic
    fun saveTexturePressureDownscaleDivisor(context: Context, divisor: Int) {
        LauncherConfig.saveTexturePressureDownscaleDivisor(context, divisor)
    }

    @JvmStatic
    fun isForceLinearMipmapFilterEnabled(context: Context): Boolean {
        return LauncherConfig.isForceLinearMipmapFilterEnabled(context)
    }

    @JvmStatic
    fun setForceLinearMipmapFilterEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setForceLinearMipmapFilterEnabled(context, enabled)
    }

    @JvmStatic
    fun isNonRenderableFboFormatCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isNonRenderableFboFormatCompatEnabled(context)
    }

    @JvmStatic
    fun setNonRenderableFboFormatCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setNonRenderableFboFormatCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isFboManagerCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isFboManagerCompatEnabled(context)
    }

    @JvmStatic
    fun setFboManagerCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setFboManagerCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isFboIdleReclaimCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isFboIdleReclaimCompatEnabled(context)
    }

    @JvmStatic
    fun setFboIdleReclaimCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setFboIdleReclaimCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isFboPressureDownscaleCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isFboPressureDownscaleCompatEnabled(context)
    }

    @JvmStatic
    fun setFboPressureDownscaleCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setFboPressureDownscaleCompatEnabled(context, enabled)
    }
}
