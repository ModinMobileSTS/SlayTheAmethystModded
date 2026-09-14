package io.stamethyst.backend.diag

import android.content.Context
import io.stamethyst.backend.resources.ResourcePackInspection

/**
 * A single resolved setting. [key] is the stable machine key used by the English export
 * (kept unchanged for existing keys so feedback tooling keeps working), while [labelZh]
 * and [zhValue] carry the Simplified-Chinese, human-readable rendering.
 */
internal data class LauncherSettingsDiagnosticsField(
    val key: String,
    val labelZh: String,
    val enValue: String,
    val zhValue: String,
)

internal data class LauncherSettingsDiagnosticsSection(
    val titleEn: String,
    val titleZh: String,
    val fields: List<LauncherSettingsDiagnosticsField>,
)

internal data class LauncherSettingsDiagnosticsSnapshot(
    val sections: List<LauncherSettingsDiagnosticsSection>,
)

/**
 * Builds the launcher-settings diagnostics text in two languages.
 *
 * Capture once via [capture] and render both [build] (English, machine keys, kept stable for
 * feedback tooling) and [buildChinese] (Chinese labels and values). Both renderings come from
 * the same snapshot, so the resolved settings and raw preferences are only read once.
 */
internal object LauncherSettingsDiagnosticsFormatter {
    fun capture(
        context: Context,
        resourcePackInspection: ResourcePackInspection? = null,
    ): LauncherSettingsDiagnosticsSnapshot {
        return LauncherSettingsDiagnosticsSnapshot(
            sections = captureLauncherSettingsSections(context, resourcePackInspection) +
                captureRawPreferenceSections(context)
        )
    }

    fun buildFromContext(context: Context): String = build(capture(context))

    fun buildChineseFromContext(context: Context): String = buildChinese(capture(context))

    fun build(snapshot: LauncherSettingsDiagnosticsSnapshot): String = buildString {
        append("launcherSettings.formatVersion=2\n")
        append("launcherSettings.snapshotType=resolved_values\n")
        for (section in snapshot.sections) {
            append('\n')
            append('[').append(section.titleEn).append("]\n")
            for (field in section.fields) {
                append(field.key).append('=').append(field.enValue).append('\n')
            }
        }
    }

    fun buildChinese(snapshot: LauncherSettingsDiagnosticsSnapshot): String = buildString {
        append("launcherSettings.formatVersion=2\n")
        append("launcherSettings.snapshotType=resolved_values\n")
        append("launcherSettings.locale=zh-CN\n")
        for (section in snapshot.sections) {
            append('\n')
            append('[').append(section.titleZh).append("]\n")
            for (field in section.fields) {
                append(field.labelZh).append('=').append(field.zhValue).append('\n')
            }
        }
    }
}
