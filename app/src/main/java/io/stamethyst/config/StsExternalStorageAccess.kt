package io.stamethyst.config

import android.content.Context
import io.stamethyst.R
import java.io.File

object StsExternalStorageAccess {
    enum class Reason {
        EXTERNAL_ROOT_UNAVAILABLE,
        STS_ROOT_NOT_DIRECTORY,
        MODS_DIR_NOT_DIRECTORY,
        MODS_DIR_ENUMERATION_FAILED
    }

    data class Issue(
        val reason: Reason,
        val stsRootPath: String,
        val detailPath: String
    )

    data class UiModel(
        val title: String,
        val message: String,
        val recovery: String
    )

    @JvmStatic
    fun detect(context: Context): Issue? {
        val externalStsRoot = RuntimePaths.externalAppStsRoot(context)
        if (externalStsRoot == null) {
            val fallbackPath = preferredExternalStsRootPath(context.packageName)
            return Issue(
                reason = Reason.EXTERNAL_ROOT_UNAVAILABLE,
                stsRootPath = fallbackPath,
                detailPath = fallbackPath
            )
        }
        if (externalStsRoot.exists() && !externalStsRoot.isDirectory) {
            return Issue(
                reason = Reason.STS_ROOT_NOT_DIRECTORY,
                stsRootPath = externalStsRoot.absolutePath,
                detailPath = externalStsRoot.absolutePath
            )
        }

        val modsDir = File(externalStsRoot, "mods")
        if (modsDir.exists()) {
            if (!modsDir.isDirectory) {
                return Issue(
                    reason = Reason.MODS_DIR_NOT_DIRECTORY,
                    stsRootPath = externalStsRoot.absolutePath,
                    detailPath = modsDir.absolutePath
                )
            }
            if (modsDir.listFiles() == null) {
                return Issue(
                    reason = Reason.MODS_DIR_ENUMERATION_FAILED,
                    stsRootPath = externalStsRoot.absolutePath,
                    detailPath = modsDir.absolutePath
                )
            }
        }
        return null
    }

    @JvmStatic
    fun buildUiModel(context: Context): UiModel? {
        val issue = detect(context) ?: return null
        return UiModel(
            title = context.getString(R.string.main_storage_issue_title),
            message = context.getString(R.string.main_storage_issue_message, issue.stsRootPath),
            recovery = context.getString(R.string.storage_issue_recovery_steps)
        )
    }

    @JvmStatic
    fun buildFailureMessage(
        context: Context,
        prefix: String,
        error: Throwable
    ): String {
        val detail = error.message?.trim().takeUnless { it.isNullOrEmpty() }
            ?: error.javaClass.simpleName
        if (!shouldSuggestRecovery(context, error)) {
            return "$prefix: $detail"
        }
        return buildString {
            append(prefix)
            append(": ")
            append(detail)
            append('\n')
            append(context.getString(R.string.storage_issue_recovery_short))
        }
    }

    @JvmStatic
    fun shouldSuggestRecovery(context: Context, error: Throwable?): Boolean {
        if (detect(context) != null) {
            return true
        }
        if (error == null) {
            return false
        }
        val packageMarker = "/Android/data/${context.packageName}/"
        return generateSequence(error) { it.cause }
            .mapNotNull { it.message?.trim() }
            .any { message -> message.contains(packageMarker) }
    }

    private fun preferredExternalStsRootPath(packageName: String): String {
        return RuntimePaths.legacyExternalStsRootCandidates(packageName)
            .firstOrNull { it.startsWith("/storage/emulated/0/") }
            ?: "/storage/emulated/0/Android/data/$packageName/files/sts"
    }
}
