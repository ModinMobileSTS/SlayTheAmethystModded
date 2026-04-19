package io.stamethyst.backend.launch

import android.content.Context
import io.stamethyst.R
import io.stamethyst.backend.mods.StsDesktopJarPatcher

internal object LaunchPreparationFailureMessageResolver {
    fun resolve(context: Context, throwable: Throwable): String? {
        if (containsPrepProcessFailure(throwable)) {
            return context.getString(
                R.string.startup_failure_launch_preparation_process_disconnected
            )
        }
        val requiresJarReimport = generateSequence(throwable) { it.cause }
            .mapNotNull { error ->
                StsDesktopJarPatcher.extractLegacyWholeClassUiPatchClass(error.message)
            }
            .firstOrNull() != null
        if (!requiresJarReimport) {
            return null
        }
        return context.getString(
            R.string.startup_failure_legacy_patched_desktop_jar_requires_reimport
        )
    }

    internal fun containsPrepProcessFailure(throwable: Throwable): Boolean {
        return generateSequence(throwable) { it.cause }
            .map { error -> error.message }
            .any(LaunchPreparationProcessClient::isPrepProcessFailureMessage)
    }
}
