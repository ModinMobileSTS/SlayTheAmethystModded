package io.stamethyst.backend.diag

import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import com.tencent.smtt.export.external.interfaces.ConsoleMessage as X5ConsoleMessage
import com.tencent.smtt.export.external.interfaces.WebResourceError as X5WebResourceError
import com.tencent.smtt.export.external.interfaces.WebResourceRequest as X5WebResourceRequest
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persists the embedded Sling Break WebView console and lifecycle diagnostics. */
internal object WebViewDiagnosticsLogStore {
    const val MAX_LOG_SLOTS = 5

    private const val MAX_BYTES_PER_FILE = 512L * 1024L
    private val lock = Any()
    private var writer: RollingTextLogWriter? = null
    private var writerFile: File? = null

    fun append(context: Context, event: String, message: String) {
        runCatching {
            synchronized(lock) {
                val file = RuntimePaths.webViewDiagnosticsLog(context.applicationContext)
                val activeWriter = if (writerFile == file) {
                    writer
                } else {
                    writer?.close()
                    RollingTextLogWriter(
                        baseFile = file,
                        maxBytesPerFile = MAX_BYTES_PER_FILE,
                        maxFiles = MAX_LOG_SLOTS,
                        appendExisting = true
                    ).also {
                        writer = it
                        writerFile = file
                    }
                } ?: return
                activeWriter.appendLine("${timestamp()} [$event] ${message.trim()}")
                activeWriter.flush()
            }
        }
    }

    fun appendConsoleMessage(context: Context, consoleMessage: ConsoleMessage) {
        append(
            context = context,
            event = "console.${consoleMessage.messageLevel().name.lowercase(Locale.US)}",
            message = "${consoleMessage.message()} " +
                "source=${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
        )
    }

    fun appendX5ConsoleMessage(context: Context, consoleMessage: X5ConsoleMessage) {
        append(
            context = context,
            event = "x5.console.${consoleMessage.messageLevel().name.lowercase(Locale.US)}",
            message = "${consoleMessage.message()} source=${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
        )
    }

    fun appendWebResourceError(
        context: Context,
        request: WebResourceRequest?,
        error: WebResourceError
    ) {
        append(
            context = context,
            event = "web_resource_error",
            message = "url=${request?.url} code=${error.errorCode} description=${error.description}"
        )
    }

    fun appendX5WebResourceError(
        context: Context,
        request: X5WebResourceRequest?,
        error: X5WebResourceError
    ) {
        append(
            context = context,
            event = "x5.web_resource_error",
            message = "url=${request?.url} code=${error.errorCode} description=${error.description}"
        )
    }

    fun listLogFiles(context: Context): List<File> = RuntimePaths.listWebViewDiagnosticsFiles(context)

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
}
