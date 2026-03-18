package io.stamethyst.backend.render

data class MobileGluesSettings(
    val anglePolicy: MobileGluesAnglePolicy,
    val noErrorPolicy: MobileGluesNoErrorPolicy,
    val multidrawMode: MobileGluesMultidrawMode,
    val extComputeShaderEnabled: Boolean,
    val extTimerQueryEnabled: Boolean,
    val extDirectStateAccessEnabled: Boolean,
    val glslCacheSizePreset: MobileGluesGlslCacheSizePreset,
    val angleDepthClearFixMode: MobileGluesAngleDepthClearFixMode,
    val customGlVersion: MobileGluesCustomGlVersion,
    val fsr1QualityPreset: MobileGluesFsr1QualityPreset,
)

enum class MobileGluesNoErrorPolicy(
    val persistedValue: Int,
    val mobileGluesConfigValue: Int,
    val displayName: String,
    val description: String,
) {
    AUTO(
        persistedValue = 0,
        mobileGluesConfigValue = 0,
        displayName = "自动",
        description = "让 MobileGlues 自己决定是否忽略图形错误。"
    ),
    DO_NOT_IGNORE(
        persistedValue = 1,
        mobileGluesConfigValue = 1,
        displayName = "不忽略",
        description = "保留完整错误检查，兼容性最保守。"
    ),
    IGNORE_SHADER_AND_PROGRAM(
        persistedValue = 2,
        mobileGluesConfigValue = 2,
        displayName = "忽略 Shader / Program 错误",
        description = "会吞掉部分编译和链接错误，仅在明确需要时启用。"
    ),
    IGNORE_SHADER_PROGRAM_AND_FRAMEBUFFER(
        persistedValue = 3,
        mobileGluesConfigValue = 3,
        displayName = "忽略 Shader / Program / FBO 错误",
        description = "最激进，可能掩盖真实问题。"
    );

    companion object {
        @JvmStatic
        fun fromPersistedValue(value: Int): MobileGluesNoErrorPolicy? {
            return entries.firstOrNull { it.persistedValue == value }
        }
    }
}

enum class MobileGluesMultidrawMode(
    val persistedValue: Int,
    val mobileGluesConfigValue: Int,
    val displayName: String,
    val description: String,
) {
    AUTO(
        persistedValue = 0,
        mobileGluesConfigValue = 0,
        displayName = "自动",
        description = "让 MobileGlues 根据驱动能力自动选择。"
    ),
    PREFER_INDIRECT(
        persistedValue = 1,
        mobileGluesConfigValue = 1,
        displayName = "优先 Indirect",
        description = "偏向 glDrawElementsIndirect，失败时自动回退。"
    ),
    PREFER_BASE_VERTEX(
        persistedValue = 2,
        mobileGluesConfigValue = 2,
        displayName = "优先 BaseVertex",
        description = "偏向 BaseVertex 路径，适合部分旧驱动。"
    ),
    PREFER_MULTI_DRAW_INDIRECT(
        persistedValue = 3,
        mobileGluesConfigValue = 3,
        displayName = "优先 MultiDraw Indirect",
        description = "最激进的自动批量提交路径。"
    ),
    FORCE_DRAW_ELEMENTS(
        persistedValue = 4,
        mobileGluesConfigValue = 4,
        displayName = "强制 DrawElements",
        description = "最保守，兼容性最好，但通常最慢。"
    ),
    PREFER_COMPUTE(
        persistedValue = 5,
        mobileGluesConfigValue = 5,
        displayName = "优先 Compute",
        description = "走 compute 路径，属于实验选项。"
    );

    companion object {
        @JvmStatic
        fun fromPersistedValue(value: Int): MobileGluesMultidrawMode? {
            return entries.firstOrNull { it.persistedValue == value }
        }
    }
}

enum class MobileGluesGlslCacheSizePreset(
    val persistedValue: Int,
    val megabytes: Int,
    val displayName: String,
    val description: String,
) {
    DISABLED(
        persistedValue = 0,
        megabytes = 0,
        displayName = "关闭 (0 MB)",
        description = "不保留 GLSL cache，最省空间。"
    ),
    MB_16(
        persistedValue = 16,
        megabytes = 16,
        displayName = "16 MB",
        description = "较小缓存，适合保守试验。"
    ),
    MB_32(
        persistedValue = 32,
        megabytes = 32,
        displayName = "32 MB",
        description = "中等缓存，适合常规使用。"
    ),
    MB_64(
        persistedValue = 64,
        megabytes = 64,
        displayName = "64 MB",
        description = "更积极的 shader 缓存。"
    ),
    MB_128(
        persistedValue = 128,
        megabytes = 128,
        displayName = "128 MB",
        description = "适合大型模组组合。"
    ),
    MB_256(
        persistedValue = 256,
        megabytes = 256,
        displayName = "256 MB",
        description = "最大预设，优先减少重复编译。"
    );

    companion object {
        @JvmStatic
        fun fromPersistedValue(value: Int): MobileGluesGlslCacheSizePreset? {
            return entries.firstOrNull { it.persistedValue == value }
        }
    }
}

enum class MobileGluesAngleDepthClearFixMode(
    val persistedValue: Int,
    val mobileGluesConfigValue: Int,
    val displayName: String,
    val description: String,
) {
    DISABLED(
        persistedValue = 0,
        mobileGluesConfigValue = 0,
        displayName = "关闭",
        description = "默认关闭，不额外改写 ANGLE 深度清除行为。"
    ),
    WORKAROUND_1(
        persistedValue = 1,
        mobileGluesConfigValue = 1,
        displayName = "Workaround #1",
        description = "较保守的 ANGLE 深度清除修正。"
    ),
    WORKAROUND_2(
        persistedValue = 2,
        mobileGluesConfigValue = 2,
        displayName = "Workaround #2",
        description = "更激进的修正，仅在 #1 无效时尝试。"
    );

    companion object {
        @JvmStatic
        fun fromPersistedValue(value: Int): MobileGluesAngleDepthClearFixMode? {
            return entries.firstOrNull { it.persistedValue == value }
        }
    }
}

enum class MobileGluesCustomGlVersion(
    val persistedValue: Int,
    val mobileGluesConfigValue: Int,
    val displayName: String,
    val description: String,
) {
    DEFAULT(
        persistedValue = 0,
        mobileGluesConfigValue = 0,
        displayName = "默认 (4.0)",
        description = "交给 MobileGlues 使用默认 OpenGL 版本。"
    ),
    OPENGL_4_6(
        persistedValue = 46,
        mobileGluesConfigValue = 46,
        displayName = "OpenGL 4.6",
        description = "最高版本，最激进。"
    ),
    OPENGL_4_5(
        persistedValue = 45,
        mobileGluesConfigValue = 45,
        displayName = "OpenGL 4.5",
        description = "接近最高版本。"
    ),
    OPENGL_4_4(
        persistedValue = 44,
        mobileGluesConfigValue = 44,
        displayName = "OpenGL 4.4",
        description = "适合测试较新的桌面 GL 路径。"
    ),
    OPENGL_4_3(
        persistedValue = 43,
        mobileGluesConfigValue = 43,
        displayName = "OpenGL 4.3",
        description = "保留较新特性，但相对更稳。"
    ),
    OPENGL_4_2(
        persistedValue = 42,
        mobileGluesConfigValue = 42,
        displayName = "OpenGL 4.2",
        description = "中间版本。"
    ),
    OPENGL_4_1(
        persistedValue = 41,
        mobileGluesConfigValue = 41,
        displayName = "OpenGL 4.1",
        description = "偏保守的 4.x 路径。"
    ),
    OPENGL_4_0(
        persistedValue = 40,
        mobileGluesConfigValue = 40,
        displayName = "OpenGL 4.0",
        description = "与当前默认体验最接近。"
    ),
    OPENGL_3_3(
        persistedValue = 33,
        mobileGluesConfigValue = 33,
        displayName = "OpenGL 3.3",
        description = "进一步保守，常用于驱动排查。"
    ),
    OPENGL_3_2(
        persistedValue = 32,
        mobileGluesConfigValue = 32,
        displayName = "OpenGL 3.2",
        description = "最保守的桌面 GL 预设。"
    );

    companion object {
        @JvmStatic
        fun fromPersistedValue(value: Int): MobileGluesCustomGlVersion? {
            return entries.firstOrNull { it.persistedValue == value }
        }
    }
}

enum class MobileGluesFsr1QualityPreset(
    val persistedValue: Int,
    val mobileGluesConfigValue: Int,
    val displayName: String,
    val description: String,
) {
    DISABLED(
        persistedValue = 0,
        mobileGluesConfigValue = 0,
        displayName = "关闭",
        description = "不启用内置 FSR1。"
    ),
    ULTRA_QUALITY(
        persistedValue = 1,
        mobileGluesConfigValue = 1,
        displayName = "Ultra Quality",
        description = "画质最好，锐化最轻。"
    ),
    QUALITY(
        persistedValue = 2,
        mobileGluesConfigValue = 2,
        displayName = "Quality",
        description = "画质和性能之间的常规平衡。"
    ),
    BALANCED(
        persistedValue = 3,
        mobileGluesConfigValue = 3,
        displayName = "Balanced",
        description = "更偏性能。"
    ),
    PERFORMANCE(
        persistedValue = 4,
        mobileGluesConfigValue = 4,
        displayName = "Performance",
        description = "最激进，可能出现图像伪影。"
    );

    companion object {
        @JvmStatic
        fun fromPersistedValue(value: Int): MobileGluesFsr1QualityPreset? {
            return entries.firstOrNull { it.persistedValue == value }
        }
    }
}
