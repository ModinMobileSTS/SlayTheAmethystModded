package io.stamethyst.backend.render

import android.content.Context
import androidx.annotation.StringRes
import io.stamethyst.R

enum class MobileGluesAnglePolicy(
    val persistedValue: Int,
    val mobileGluesConfigValue: Int,
    @StringRes val displayNameResId: Int,
    @StringRes val descriptionResId: Int,
) {
    PREFER_DISABLED(
        persistedValue = 0,
        mobileGluesConfigValue = 0,
        displayNameResId = R.string.mobileglues_angle_policy_prefer_disabled_title,
        descriptionResId = R.string.mobileglues_angle_policy_prefer_disabled_desc
    ),
    PREFER_ENABLED(
        persistedValue = 1,
        mobileGluesConfigValue = 1,
        displayNameResId = R.string.mobileglues_angle_policy_prefer_enabled_title,
        descriptionResId = R.string.mobileglues_angle_policy_prefer_enabled_desc
    ),
    DISABLE(
        persistedValue = 2,
        mobileGluesConfigValue = 2,
        displayNameResId = R.string.mobileglues_angle_policy_disable_title,
        descriptionResId = R.string.mobileglues_angle_policy_disable_desc
    ),
    ENABLE(
        persistedValue = 3,
        mobileGluesConfigValue = 3,
        displayNameResId = R.string.mobileglues_angle_policy_enable_title,
        descriptionResId = R.string.mobileglues_angle_policy_enable_desc
    );

    fun displayName(context: Context): String = context.getString(displayNameResId)

    fun description(context: Context): String = context.getString(descriptionResId)

    companion object {
        @JvmStatic
        fun fromPersistedValue(value: Int): MobileGluesAnglePolicy? {
            return entries.firstOrNull { it.persistedValue == value }
        }
    }
}
