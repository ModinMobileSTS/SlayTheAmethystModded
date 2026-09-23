package io.stamethyst.backend.process

import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.WebView

/**
 * Android refuses to let two processes of the same app create a WebView when they share one data
 * directory, and the second process dies while the layout is being inflated:
 *
 * ```
 * java.lang.RuntimeException: Using WebView from more than one process at once with the same data
 * directory is not supported. https://crbug.com/558377
 * ```
 *
 * The launcher default process hosts the workshop YouTube embed, the standalone Sling Break game
 * and the Sling Break data reset, while `:game` hosts the Sling Break boot overlay. Claiming a
 * per-process data directory suffix keeps those WebViews from colliding.
 *
 * The default process deliberately keeps the unsuffixed directory so existing WebView cookies and
 * local storage survive the upgrade.
 */
object WebViewDataDirectory {
    private const val LOGCAT_TAG = "STS-WebView"

    @JvmStatic
    fun configure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return
        }
        val suffix = dataDirectorySuffixFor(
            processName = AppProcess.currentProcessName(context),
            packageName = context.packageName
        ) ?: return
        runCatching { WebView.setDataDirectorySuffix(suffix) }
            .onFailure { Log.w(LOGCAT_TAG, "Unable to set WebView data directory suffix", it) }
    }

    /**
     * Returns the WebView data directory suffix for [processName], or null when that process must
     * keep the legacy unsuffixed directory.
     */
    internal fun dataDirectorySuffixFor(processName: String?, packageName: String): String? {
        if (processName.isNullOrEmpty() || processName == packageName) {
            return null
        }
        val rawSuffix = processName.removePrefix("$packageName:")
        if (rawSuffix.isEmpty() || rawSuffix == processName) {
            return null
        }
        val sanitized = rawSuffix.replace(INVALID_SUFFIX_CHARS, "_")
        return sanitized.ifEmpty { null }
    }

    private val INVALID_SUFFIX_CHARS = Regex("[^A-Za-z0-9_.-]")
}
