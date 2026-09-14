package io.stamethyst.backend.diag

import android.content.Context
import java.io.File

/**
 * Dumps every stored SharedPreferences entry from the app's credential-protected and
 * device-protected storage. Iterating the `shared_prefs` directories (instead of a
 * hand-maintained key list) means no new preference is silently missing from the export;
 * each file is parsed once and both language renderings reuse the same snapshot.
 *
 * Raw keys are language-neutral identifiers, so the Chinese rendering keeps the key as the
 * label and only localizes the section title.
 */
internal fun captureRawPreferenceSections(
    context: Context
): List<LauncherSettingsDiagnosticsSection> {
    val sections = mutableListOf<LauncherSettingsDiagnosticsSection>()
    for ((storageContext, dataDir) in preferenceStorages(context)) {
        val prefsDir = File(dataDir, "shared_prefs")
        val files = prefsDir
            .listFiles { file -> file.isFile && file.name.endsWith(".xml", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        for (file in files) {
            val preferencesName = file.name.removeSuffix(".xml").removeSuffix(".XML")
            val values = runCatching {
                storageContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).all
            }.getOrNull()
            if (values.isNullOrEmpty()) {
                continue
            }
            val fields = values.entries
                .sortedBy { it.key }
                .map { (key, value) ->
                    val text = normalizeRawValue(formatRawValue(value))
                    LauncherSettingsDiagnosticsField(
                        key = key,
                        labelZh = key,
                        enValue = text,
                        zhValue = text,
                    )
                }
            sections += LauncherSettingsDiagnosticsSection(
                titleEn = "Raw preferences / $preferencesName",
                titleZh = "原始偏好 / $preferencesName",
                fields = fields,
            )
        }
    }
    return sections
}

private fun preferenceStorages(context: Context): List<Pair<Context, String>> {
    val storages = mutableListOf<Pair<Context, String>>()
    context.applicationInfo?.dataDir?.let { storages += context to it }
    runCatching { context.createDeviceProtectedStorageContext() }.getOrNull()?.let { deviceContext ->
        val dataDir = deviceContext.applicationInfo?.dataDir
        if (dataDir != null) {
            storages += deviceContext to dataDir
        }
    }
    return storages.distinctBy { it.second }
}

private fun formatRawValue(value: Any?): String = when (value) {
    null -> "none"
    is Boolean, is Number -> value.toString()
    is String -> value
    is Set<*> -> value.joinToString(",")
    else -> value.toString()
}

private fun normalizeRawValue(value: String): String {
    val sanitized = value.replace('\r', ' ').replace('\n', ' ').trim()
    return sanitized.ifEmpty { "none" }
}
