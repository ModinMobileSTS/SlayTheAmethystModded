package io.stamethyst.backend.render

import android.content.Context
import androidx.annotation.StringRes
import io.stamethyst.R

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
    @StringRes val displayNameResId: Int,
    @StringRes val descriptionResId: Int,
) {
    AUTO(
        persistedValue = 0,
        mobileGluesConfigValue = 0,
        displayNameResId = R.string.mobileglues_no_error_auto_title,
        descriptionResId = R.string.mobileglues_no_error_auto_desc
    ),
    DO_NOT_IGNORE(
        persistedValue = 1,
        mobileGluesConfigValue = 1,
        displayNameResId = R.string.mobileglues_no_error_do_not_ignore_title,
        descriptionResId = R.string.mobileglues_no_error_do_not_ignore_desc
    ),
    IGNORE_SHADER_AND_PROGRAM(
        persistedValue = 2,
        mobileGluesConfigValue = 2,
        displayNameResId = R.string.mobileglues_no_error_ignore_shader_program_title,
        descriptionResId = R.string.mobileglues_no_error_ignore_shader_program_desc
    ),
    IGNORE_SHADER_PROGRAM_AND_FRAMEBUFFER(
        persistedValue = 3,
        mobileGluesConfigValue = 3,
        displayNameResId = R.string.mobileglues_no_error_ignore_shader_program_fbo_title,
        descriptionResId = R.string.mobileglues_no_error_ignore_shader_program_fbo_desc
    );

    fun displayName(context: Context): String = context.getString(displayNameResId)

    fun description(context: Context): String = context.getString(descriptionResId)

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
    @StringRes val displayNameResId: Int,
    @StringRes val descriptionResId: Int,
) {
    AUTO(
        persistedValue = 0,
        mobileGluesConfigValue = 0,
        displayNameResId = R.string.mobileglues_multidraw_auto_title,
        descriptionResId = R.string.mobileglues_multidraw_auto_desc
    ),
    PREFER_INDIRECT(
        persistedValue = 1,
        mobileGluesConfigValue = 1,
        displayNameResId = R.string.mobileglues_multidraw_prefer_indirect_title,
        descriptionResId = R.string.mobileglues_multidraw_prefer_indirect_desc
    ),
    PREFER_BASE_VERTEX(
        persistedValue = 2,
        mobileGluesConfigValue = 2,
        displayNameResId = R.string.mobileglues_multidraw_prefer_base_vertex_title,
        descriptionResId = R.string.mobileglues_multidraw_prefer_base_vertex_desc
    ),
    PREFER_MULTI_DRAW_INDIRECT(
        persistedValue = 3,
        mobileGluesConfigValue = 3,
        displayNameResId = R.string.mobileglues_multidraw_prefer_multi_draw_indirect_title,
        descriptionResId = R.string.mobileglues_multidraw_prefer_multi_draw_indirect_desc
    ),
    FORCE_DRAW_ELEMENTS(
        persistedValue = 4,
        mobileGluesConfigValue = 4,
        displayNameResId = R.string.mobileglues_multidraw_force_draw_elements_title,
        descriptionResId = R.string.mobileglues_multidraw_force_draw_elements_desc
    ),
    PREFER_COMPUTE(
        persistedValue = 5,
        mobileGluesConfigValue = 5,
        displayNameResId = R.string.mobileglues_multidraw_prefer_compute_title,
        descriptionResId = R.string.mobileglues_multidraw_prefer_compute_desc
    );

    fun displayName(context: Context): String = context.getString(displayNameResId)

    fun description(context: Context): String = context.getString(descriptionResId)

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
    @StringRes val displayNameResId: Int,
    @StringRes val descriptionResId: Int,
) {
    DISABLED(
        persistedValue = 0,
        megabytes = 0,
        displayNameResId = R.string.mobileglues_glsl_cache_disabled_title,
        descriptionResId = R.string.mobileglues_glsl_cache_disabled_desc
    ),
    MB_16(
        persistedValue = 16,
        megabytes = 16,
        displayNameResId = R.string.mobileglues_glsl_cache_16_title,
        descriptionResId = R.string.mobileglues_glsl_cache_16_desc
    ),
    MB_32(
        persistedValue = 32,
        megabytes = 32,
        displayNameResId = R.string.mobileglues_glsl_cache_32_title,
        descriptionResId = R.string.mobileglues_glsl_cache_32_desc
    ),
    MB_64(
        persistedValue = 64,
        megabytes = 64,
        displayNameResId = R.string.mobileglues_glsl_cache_64_title,
        descriptionResId = R.string.mobileglues_glsl_cache_64_desc
    ),
    MB_128(
        persistedValue = 128,
        megabytes = 128,
        displayNameResId = R.string.mobileglues_glsl_cache_128_title,
        descriptionResId = R.string.mobileglues_glsl_cache_128_desc
    ),
    MB_256(
        persistedValue = 256,
        megabytes = 256,
        displayNameResId = R.string.mobileglues_glsl_cache_256_title,
        descriptionResId = R.string.mobileglues_glsl_cache_256_desc
    );

    fun displayName(context: Context): String = context.getString(displayNameResId)

    fun description(context: Context): String = context.getString(descriptionResId)

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
    @StringRes val displayNameResId: Int,
    @StringRes val descriptionResId: Int,
) {
    DISABLED(
        persistedValue = 0,
        mobileGluesConfigValue = 0,
        displayNameResId = R.string.mobileglues_angle_depth_clear_disabled_title,
        descriptionResId = R.string.mobileglues_angle_depth_clear_disabled_desc
    ),
    WORKAROUND_1(
        persistedValue = 1,
        mobileGluesConfigValue = 1,
        displayNameResId = R.string.mobileglues_angle_depth_clear_workaround1_title,
        descriptionResId = R.string.mobileglues_angle_depth_clear_workaround1_desc
    ),
    WORKAROUND_2(
        persistedValue = 2,
        mobileGluesConfigValue = 2,
        displayNameResId = R.string.mobileglues_angle_depth_clear_workaround2_title,
        descriptionResId = R.string.mobileglues_angle_depth_clear_workaround2_desc
    );

    fun displayName(context: Context): String = context.getString(displayNameResId)

    fun description(context: Context): String = context.getString(descriptionResId)

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
    @StringRes val displayNameResId: Int,
    @StringRes val descriptionResId: Int,
) {
    DEFAULT(
        persistedValue = 0,
        mobileGluesConfigValue = 0,
        displayNameResId = R.string.mobileglues_custom_gl_default_title,
        descriptionResId = R.string.mobileglues_custom_gl_default_desc
    ),
    OPENGL_4_6(
        persistedValue = 46,
        mobileGluesConfigValue = 46,
        displayNameResId = R.string.mobileglues_custom_gl_46_title,
        descriptionResId = R.string.mobileglues_custom_gl_46_desc
    ),
    OPENGL_4_5(
        persistedValue = 45,
        mobileGluesConfigValue = 45,
        displayNameResId = R.string.mobileglues_custom_gl_45_title,
        descriptionResId = R.string.mobileglues_custom_gl_45_desc
    ),
    OPENGL_4_4(
        persistedValue = 44,
        mobileGluesConfigValue = 44,
        displayNameResId = R.string.mobileglues_custom_gl_44_title,
        descriptionResId = R.string.mobileglues_custom_gl_44_desc
    ),
    OPENGL_4_3(
        persistedValue = 43,
        mobileGluesConfigValue = 43,
        displayNameResId = R.string.mobileglues_custom_gl_43_title,
        descriptionResId = R.string.mobileglues_custom_gl_43_desc
    ),
    OPENGL_4_2(
        persistedValue = 42,
        mobileGluesConfigValue = 42,
        displayNameResId = R.string.mobileglues_custom_gl_42_title,
        descriptionResId = R.string.mobileglues_custom_gl_42_desc
    ),
    OPENGL_4_1(
        persistedValue = 41,
        mobileGluesConfigValue = 41,
        displayNameResId = R.string.mobileglues_custom_gl_41_title,
        descriptionResId = R.string.mobileglues_custom_gl_41_desc
    ),
    OPENGL_4_0(
        persistedValue = 40,
        mobileGluesConfigValue = 40,
        displayNameResId = R.string.mobileglues_custom_gl_40_title,
        descriptionResId = R.string.mobileglues_custom_gl_40_desc
    ),
    OPENGL_3_3(
        persistedValue = 33,
        mobileGluesConfigValue = 33,
        displayNameResId = R.string.mobileglues_custom_gl_33_title,
        descriptionResId = R.string.mobileglues_custom_gl_33_desc
    ),
    OPENGL_3_2(
        persistedValue = 32,
        mobileGluesConfigValue = 32,
        displayNameResId = R.string.mobileglues_custom_gl_32_title,
        descriptionResId = R.string.mobileglues_custom_gl_32_desc
    );

    fun displayName(context: Context): String = context.getString(displayNameResId)

    fun description(context: Context): String = context.getString(descriptionResId)

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
    @StringRes val displayNameResId: Int,
    @StringRes val descriptionResId: Int,
) {
    DISABLED(
        persistedValue = 0,
        mobileGluesConfigValue = 0,
        displayNameResId = R.string.mobileglues_fsr1_disabled_title,
        descriptionResId = R.string.mobileglues_fsr1_disabled_desc
    ),
    ULTRA_QUALITY(
        persistedValue = 1,
        mobileGluesConfigValue = 1,
        displayNameResId = R.string.mobileglues_fsr1_ultra_quality_title,
        descriptionResId = R.string.mobileglues_fsr1_ultra_quality_desc
    ),
    QUALITY(
        persistedValue = 2,
        mobileGluesConfigValue = 2,
        displayNameResId = R.string.mobileglues_fsr1_quality_title,
        descriptionResId = R.string.mobileglues_fsr1_quality_desc
    ),
    BALANCED(
        persistedValue = 3,
        mobileGluesConfigValue = 3,
        displayNameResId = R.string.mobileglues_fsr1_balanced_title,
        descriptionResId = R.string.mobileglues_fsr1_balanced_desc
    ),
    PERFORMANCE(
        persistedValue = 4,
        mobileGluesConfigValue = 4,
        displayNameResId = R.string.mobileglues_fsr1_performance_title,
        descriptionResId = R.string.mobileglues_fsr1_performance_desc
    );

    fun displayName(context: Context): String = context.getString(displayNameResId)

    fun description(context: Context): String = context.getString(descriptionResId)

    companion object {
        @JvmStatic
        fun fromPersistedValue(value: Int): MobileGluesFsr1QualityPreset? {
            return entries.firstOrNull { it.persistedValue == value }
        }
    }
}
