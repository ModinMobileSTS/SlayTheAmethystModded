package io.stamethyst.backend.launch

import android.content.Context
import io.stamethyst.R
import io.stamethyst.backend.mods.StsDesktopJarPatcher

internal object LaunchPreparationFailureMessageResolver {
    private const val PREP_PROCESS_FAILURE_DETAIL_MARKER =
        "[[sts_prep_process_disconnected]]"

    fun resolve(context: Context, throwable: Throwable): String? {
        if (containsPrepProcessFailure(throwable)) {
            return markPrepProcessFailureDetail(
                context.getString(
                    R.string.startup_failure_launch_preparation_process_disconnected
                )
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

    internal fun hasPrepProcessFailureDetailMarker(detail: String?): Boolean {
        val hasMarker = detail
            ?.lineSequence()
            ?.map { it.trim() }
            ?.any { line -> line == PREP_PROCESS_FAILURE_DETAIL_MARKER }
        return hasMarker == true
    }

    internal fun stripInternalDetailMarkers(detail: String?): String? {
        val normalized = detail?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return null
        }
        return normalized.lineSequence()
            .map { it.trimEnd() }
            .filterNot { line -> line.trim() == PREP_PROCESS_FAILURE_DETAIL_MARKER }
            .joinToString("\n")
            .trim()
            .ifEmpty { null }
    }

    internal fun markPrepProcessFailureDetail(message: String): String {
        return buildString {
            append(PREP_PROCESS_FAILURE_DETAIL_MARKER)
            append('\n')
            append(message.trim())
        }
    }
}
