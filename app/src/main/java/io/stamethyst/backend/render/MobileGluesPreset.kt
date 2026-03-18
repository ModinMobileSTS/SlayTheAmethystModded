package io.stamethyst.backend.render

enum class MobileGluesPreset(
    val shortLabel: String,
    val title: String,
    val description: String,
    val settings: MobileGluesSettings,
) {
    PERFORMANCE_PRIORITY(
        shortLabel = "性能",
        title = "性能优先",
        description = "优先减少转译和绘制开销，适合驱动稳定、主要追求帧率的设备。风险最高，遇到黑屏、闪退或花屏时应继续往右调。",
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
        shortLabel = "偏性能",
        title = "偏性能平衡",
        description = "保留较高性能，同时收回一部分激进设置，适合大多数图形驱动正常的机型。",
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
        shortLabel = "偏兼容",
        title = "偏兼容平衡",
        description = "优先稳定，开始明显收敛图形路径。适合出现偶发闪退、shader 报错或黑屏的设备。",
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
        shortLabel = "兼容",
        title = "兼容优先",
        description = "最大限度收敛到保守路径，适合华为 / Maleoon 或已知不稳定设备。性能最低，但最容易先跑起来。",
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
