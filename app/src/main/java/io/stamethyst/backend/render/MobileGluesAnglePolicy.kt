package io.stamethyst.backend.render

enum class MobileGluesAnglePolicy(
    val persistedValue: Int,
    val mobileGluesConfigValue: Int,
    val displayName: String,
    val description: String,
) {
    PREFER_DISABLED(
        persistedValue = 0,
        mobileGluesConfigValue = 0,
        displayName = "尽量关闭 (Prefer Disabled)",
        description = "优先不用 ANGLE，仅在兼容性更好时才自动启用。"
    ),
    PREFER_ENABLED(
        persistedValue = 1,
        mobileGluesConfigValue = 1,
        displayName = "尽量开启 (Prefer Enabled)",
        description = "默认推荐，优先走 ANGLE，但仍允许按设备情况自动回退。"
    ),
    DISABLE(
        persistedValue = 2,
        mobileGluesConfigValue = 2,
        displayName = "强制关闭 (Disable)",
        description = "无论设备表现如何，都不使用 ANGLE。"
    ),
    ENABLE(
        persistedValue = 3,
        mobileGluesConfigValue = 3,
        displayName = "强制开启 (Enable)",
        description = "无论设备表现如何，都强制使用 ANGLE。"
    );

    companion object {
        @JvmStatic
        fun fromPersistedValue(value: Int): MobileGluesAnglePolicy? {
            return entries.firstOrNull { it.persistedValue == value }
        }
    }
}
