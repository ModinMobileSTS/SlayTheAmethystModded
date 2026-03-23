package io.stamethyst.backend.render

import android.content.Context
import androidx.annotation.StringRes
import io.stamethyst.R

enum class MobileGluesPreset(
    @StringRes val shortLabelResId: Int,
    @StringRes val titleResId: Int,
    @StringRes val descriptionResId: Int,
    val settings: MobileGluesSettings,
) {
    PERFORMANCE_PRIORITY(
        shortLabelResId = R.string.mobileglues_preset_performance_short,
        titleResId = R.string.mobileglues_preset_performance_title,
        descriptionResId = R.string.mobileglues_preset_performance_desc,
        settings = MobileGluesSettings(
            anglePolicy = MobileGluesAnglePolicy.PREFER_DISABLED,
            noErrorPolicy = MobileGluesNoErrorPolicy.AUTO,
            multidrawMode = MobileGluesMultidrawMode.PREFER_MULTI_DRAW_INDIRECT,
            extComputeShaderEnabled = false,
            extTimerQueryEnabled = false,
            extDirectStateAccessEnabled = true,
            glslCacheSizePreset = MobileGluesGlslCacheSizePreset.MB_64,
            angleDepthClearFixMode = MobileGluesAngleDepthClearFixMode.DISABLED,
            customGlVersion = MobileGluesCustomGlVersion.OPENGL_4_6,
            fsr1QualityPreset = MobileGluesFsr1QualityPreset.DISABLED,
        )
    ),
    BALANCED_PERFORMANCE(
        shortLabelResId = R.string.mobileglues_preset_balanced_performance_short,
        titleResId = R.string.mobileglues_preset_balanced_performance_title,
        descriptionResId = R.string.mobileglues_preset_balanced_performance_desc,
        settings = MobileGluesSettings(
            anglePolicy = MobileGluesAnglePolicy.PREFER_DISABLED,
            noErrorPolicy = MobileGluesNoErrorPolicy.AUTO,
            multidrawMode = MobileGluesMultidrawMode.AUTO,
            extComputeShaderEnabled = false,
            extTimerQueryEnabled = false,
            extDirectStateAccessEnabled = true,
            glslCacheSizePreset = MobileGluesGlslCacheSizePreset.MB_32,
            angleDepthClearFixMode = MobileGluesAngleDepthClearFixMode.DISABLED,
            customGlVersion = MobileGluesCustomGlVersion.DEFAULT,
            fsr1QualityPreset = MobileGluesFsr1QualityPreset.DISABLED,
        )
    ),
    BALANCED_COMPATIBILITY(
        shortLabelResId = R.string.mobileglues_preset_balanced_compatibility_short,
        titleResId = R.string.mobileglues_preset_balanced_compatibility_title,
        descriptionResId = R.string.mobileglues_preset_balanced_compatibility_desc,
        settings = MobileGluesSettings(
            anglePolicy = MobileGluesAnglePolicy.PREFER_ENABLED,
            noErrorPolicy = MobileGluesNoErrorPolicy.DO_NOT_IGNORE,
            multidrawMode = MobileGluesMultidrawMode.FORCE_DRAW_ELEMENTS,
            extComputeShaderEnabled = false,
            extTimerQueryEnabled = false,
            extDirectStateAccessEnabled = false,
            glslCacheSizePreset = MobileGluesGlslCacheSizePreset.MB_32,
            angleDepthClearFixMode = MobileGluesAngleDepthClearFixMode.DISABLED,
            customGlVersion = MobileGluesCustomGlVersion.DEFAULT,
            fsr1QualityPreset = MobileGluesFsr1QualityPreset.DISABLED,
        )
    ),
    COMPATIBILITY_PRIORITY(
        shortLabelResId = R.string.mobileglues_preset_compatibility_short,
        titleResId = R.string.mobileglues_preset_compatibility_title,
        descriptionResId = R.string.mobileglues_preset_compatibility_desc,
        settings = MobileGluesSettings(
            anglePolicy = MobileGluesAnglePolicy.ENABLE,
            noErrorPolicy = MobileGluesNoErrorPolicy.DO_NOT_IGNORE,
            multidrawMode = MobileGluesMultidrawMode.FORCE_DRAW_ELEMENTS,
            extComputeShaderEnabled = false,
            extTimerQueryEnabled = false,
            extDirectStateAccessEnabled = false,
            glslCacheSizePreset = MobileGluesGlslCacheSizePreset.MB_16,
            angleDepthClearFixMode = MobileGluesAngleDepthClearFixMode.DISABLED,
            customGlVersion = MobileGluesCustomGlVersion.OPENGL_3_2,
            fsr1QualityPreset = MobileGluesFsr1QualityPreset.DISABLED,
        )
    );

    fun shortLabel(context: Context): String = context.getString(shortLabelResId)

    fun title(context: Context): String = context.getString(titleResId)

    fun description(context: Context): String = context.getString(descriptionResId)

    companion object {
        fun fromSliderIndex(index: Int): MobileGluesPreset {
            return entries[index.coerceIn(0, entries.lastIndex)]
        }

        fun closestTo(settings: MobileGluesSettings): MobileGluesPreset {
            return entries.maxByOrNull { preset -> score(settings, preset.settings) }
                ?: BALANCED_COMPATIBILITY
        }

        private fun score(current: MobileGluesSettings, target: MobileGluesSettings): Int {
            var value = 0
            if (current.anglePolicy == target.anglePolicy) value += 3
            if (current.noErrorPolicy == target.noErrorPolicy) value += 3
            if (current.multidrawMode == target.multidrawMode) value += 4
            if (current.extComputeShaderEnabled == target.extComputeShaderEnabled) value += 1
            if (current.extTimerQueryEnabled == target.extTimerQueryEnabled) value += 1
            if (current.extDirectStateAccessEnabled == target.extDirectStateAccessEnabled) value += 2
            if (current.glslCacheSizePreset == target.glslCacheSizePreset) value += 1
            if (current.angleDepthClearFixMode == target.angleDepthClearFixMode) value += 1
            if (current.customGlVersion == target.customGlVersion) value += 3
            if (current.fsr1QualityPreset == target.fsr1QualityPreset) value += 1
            return value
        }
    }
}
