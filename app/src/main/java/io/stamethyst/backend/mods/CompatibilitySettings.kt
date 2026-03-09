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
    fun isRuntimeTextureCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isRuntimeTextureCompatEnabled(context)
    }

    @JvmStatic
    fun setRuntimeTextureCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setRuntimeTextureCompatEnabled(context, enabled)
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
}
