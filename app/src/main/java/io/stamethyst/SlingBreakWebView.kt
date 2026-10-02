package io.stamethyst

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.tencent.smtt.export.external.interfaces.ConsoleMessage as X5ConsoleMessage
import com.tencent.smtt.export.external.interfaces.WebResourceError as X5WebResourceError
import com.tencent.smtt.export.external.interfaces.WebResourceRequest as X5WebResourceRequest
import com.tencent.smtt.sdk.QbSdk
import com.tencent.smtt.sdk.TbsListener
import com.tencent.smtt.sdk.WebChromeClient as X5WebChromeClient
import com.tencent.smtt.sdk.WebSettings as X5WebSettings
import com.tencent.smtt.sdk.WebView as X5WebView
import com.tencent.smtt.sdk.WebViewClient as X5WebViewClient
import io.stamethyst.backend.audio.SlingNativeAudioBridge
import io.stamethyst.backend.diag.WebViewDiagnosticsLogStore
import io.stamethyst.ui.preferences.LauncherPreferences

private const val LOGCAT_TAG = "STS-X5"

/** Starts the X5 public-network bootstrap without bundling an X5 core in the APK. */
internal object SlingBreakX5 {
    interface InitializationListener {
        fun onProgress(progress: Int)
        fun onReady()
        fun onFailure(reason: String)
    }

    private enum class State { IDLE, INITIALIZING, READY, FAILED }

    @Volatile
    private var state = State.IDLE
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<InitializationListener>()
    private var configured = false

    fun initialize(context: Context, listener: InitializationListener? = null) {
        val appContext = context.applicationContext
        synchronized(this) {
            when (state) {
                State.READY -> {
                    listener?.let { mainHandler.post { it.onProgress(100); it.onReady() } }
                    return
                }
                State.INITIALIZING -> {
                    listener?.let { listeners += it }
                    return
                }
                State.FAILED -> {
                    // A later entry attempt may have network/Wi-Fi available again. Keep the
                    // previous reason for diagnostics, but do not permanently poison retries.
                    listener?.let { listeners += it }
                    state = State.INITIALIZING
                }
                State.IDLE -> {
                    listener?.let { listeners += it }
                    state = State.INITIALIZING
                }
            }
        }

        WebViewDiagnosticsLogStore.append(appContext, "x5_init_started", "source=sling_break_entry")
        runCatching {
            // Public-network X5 must not silently consume mobile data.
            QbSdk.setDownloadWithoutWifi(false)
            synchronized(this) {
                if (!configured) {
                    QbSdk.setTbsListener(object : TbsListener {
                        override fun onDownloadProgress(progress: Int) {
                            val bounded = progress.coerceIn(0, 100)
                            WebViewDiagnosticsLogStore.append(
                                appContext,
                                "x5_download_progress",
                                "progress=$bounded"
                            )
                            notifyProgress(bounded)
                        }

                        override fun onDownloadFinish(errorCode: Int) {
                            WebViewDiagnosticsLogStore.append(
                                appContext,
                                "x5_download_finish",
                                "errorCode=$errorCode"
                            )
                        }

                        override fun onInstallFinish(errorCode: Int) {
                            WebViewDiagnosticsLogStore.append(
                                appContext,
                                "x5_install_finish",
                                "errorCode=$errorCode"
                            )
                        }
                    })
                    configured = true
                }
            }
            QbSdk.initX5Environment(appContext, object : QbSdk.PreInitCallback {
                override fun onViewInitFinished(isX5Core: Boolean) {
                    val reason = if (isX5Core) "" else runCatching {
                        QbSdk.getX5CoreLoadHelp(appContext)
                    }.getOrNull().orEmpty().ifBlank { "X5 内核不可用，可能尚未下载完成或设备不支持。" }
                    WebViewDiagnosticsLogStore.append(
                        appContext,
                        "x5_view_init",
                        "isX5Core=$isX5Core tbsVersion=${runCatching { QbSdk.getTbsVersion(appContext) }.getOrDefault(0)}" +
                            if (reason.isEmpty()) "" else " reason=$reason"
                    )
                    Log.i(LOGCAT_TAG, "X5 view initialized: isX5Core=$isX5Core")
                    synchronized(this@SlingBreakX5) {
                        state = if (isX5Core) State.READY else State.FAILED
                    }
                    if (isX5Core) notifyReady() else notifyFailure(reason)
                }

                override fun onCoreInitFinished() {
                    WebViewDiagnosticsLogStore.append(appContext, "x5_core_init", "completed=true")
                }
            })
        }.onFailure { failure ->
            val reason = "${failure.javaClass.simpleName}: ${failure.message ?: "未知错误"}"
            synchronized(this) {
                state = State.FAILED
            }
            WebViewDiagnosticsLogStore.append(
                appContext,
                "x5_init_error",
                reason
            )
            Log.w(LOGCAT_TAG, "X5 initialization failed; this SlingBreak entry is canceled", failure)
            notifyFailure(reason)
        }
    }

    private fun notifyProgress(progress: Int) {
        val pending = synchronized(this) { listeners.toList() }
        mainHandler.post { pending.forEach { it.onProgress(progress) } }
    }

    private fun notifyReady() {
        val pending = synchronized(this) { listeners.toList().also { listeners.clear() } }
        mainHandler.post { pending.forEach { it.onProgress(100); it.onReady() } }
    }

    private fun notifyFailure(reason: String) {
        val pending = synchronized(this) { listeners.toList().also { listeners.clear() } }
        mainHandler.post { pending.forEach { it.onFailure(reason) } }
    }
}

/**
 * Small lifecycle adapter so the standalone screen and the boot overlay can try X5 while keeping
 * the existing android.webkit.WebView as a last-resort fallback.
 */
internal class SlingBreakWebViewHost private constructor(
    val view: View,
    private val loadUrlImpl: (String) -> Unit,
    private val evaluateJavascriptImpl: (String, ((String?) -> Unit)?) -> Unit,
    private val addJavascriptInterfaceImpl: (Any, String) -> Unit,
    private val onResumeImpl: () -> Unit,
    private val onPauseImpl: () -> Unit,
    private val setActiveImpl: (Boolean) -> Unit,
    private val stopLoadingImpl: () -> Unit,
    private val closeAudioImpl: () -> Unit,
    private val destroyImpl: () -> Unit,
    val engine: String
) {
    fun loadUrl(url: String) = loadUrlImpl(url)

    fun evaluateJavascript(script: String, callback: ((String?) -> Unit)? = null) =
        evaluateJavascriptImpl(script, callback)

    fun addJavascriptInterface(instance: Any, name: String) =
        addJavascriptInterfaceImpl(instance, name)

    fun onResume() = onResumeImpl()
    fun onPause() = onPauseImpl()
    fun setActive(active: Boolean) = setActiveImpl(active)
    fun stopLoading() = stopLoadingImpl()
    fun closeAudio() = closeAudioImpl()
    fun destroy() = destroyImpl()

    companion object {
        fun create(context: Context, allowSystemFallback: Boolean = true): SlingBreakWebViewHost {
            return runCatching {
                X5WebView(context).apply { configureSlingBreakX5Game() }.let { webView ->
                    SlingBreakWebViewHost(
                        view = webView,
                        loadUrlImpl = webView::loadUrl,
                        evaluateJavascriptImpl = { script, callback ->
                            webView.evaluateJavascript(script) { result -> callback?.invoke(result) }
                        },
                        addJavascriptInterfaceImpl = webView::addJavascriptInterface,
                        onResumeImpl = webView::onResume,
                        onPauseImpl = webView::onPause,
                        setActiveImpl = { active -> SlingNativeAudioBridge.setActive(webView, active) },
                        stopLoadingImpl = webView::stopLoading,
                        closeAudioImpl = { SlingNativeAudioBridge.close(webView) },
                        destroyImpl = webView::destroy,
                        engine = if (webView.getIsX5Core()) "x5" else "system-fallback"
                    )
                }
            }.onSuccess { host ->
                WebViewDiagnosticsLogStore.append(context, "x5_view_created", "engine=${host.engine}")
            }.onFailure { failure ->
                WebViewDiagnosticsLogStore.append(
                    context,
                    "x5_view_error",
                    "${failure.javaClass.simpleName}: ${failure.message}"
                )
                Log.w(LOGCAT_TAG, "Unable to create X5 WebView; using system WebView", failure)
            }.getOrElse {
                if (!allowSystemFallback) throw it
                android.webkit.WebView(context).apply { configureSlingBreakGame() }.let { webView ->
                    SlingBreakWebViewHost(
                        view = webView,
                        loadUrlImpl = webView::loadUrl,
                        evaluateJavascriptImpl = { script, callback ->
                            webView.evaluateJavascript(script) { result -> callback?.invoke(result) }
                        },
                        addJavascriptInterfaceImpl = webView::addJavascriptInterface,
                        onResumeImpl = webView::onResume,
                        onPauseImpl = webView::onPause,
                        setActiveImpl = { active -> SlingNativeAudioBridge.setActive(webView, active) },
                        stopLoadingImpl = webView::stopLoading,
                        closeAudioImpl = { SlingNativeAudioBridge.close(webView) },
                        destroyImpl = webView::destroy,
                        engine = "system-fallback"
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "DEPRECATION")
internal fun X5WebView.configureSlingBreakX5Game() {
    val diagnosticContext = context.applicationContext
    SlingNativeAudioBridge.attach(this)
    setBackgroundColor(Color.rgb(245, 246, 243))
    overScrollMode = View.OVER_SCROLL_NEVER
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        cacheMode = X5WebSettings.LOAD_NO_CACHE
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        allowContentAccess = false
        allowFileAccess = true
        blockNetworkLoads = true
    }
    webChromeClient = object : X5WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: X5ConsoleMessage): Boolean {
            WebViewDiagnosticsLogStore.appendX5ConsoleMessage(diagnosticContext, consoleMessage)
            return true
        }
    }
    webViewClient = object : X5WebViewClient() {
        override fun onPageStarted(view: X5WebView?, url: String?, favicon: Bitmap?) {
            view?.let { SlingNativeAudioBridge.pageStarted(it) }
            WebViewDiagnosticsLogStore.append(diagnosticContext, "page_started", "engine=x5 url=$url")
            if (LauncherPreferences.isSlingBreakAudioDebugModeEnabled(diagnosticContext)) {
                WebViewDiagnosticsLogStore.append(diagnosticContext, "x5_audio_environment", "page_started=true")
            }
            super.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: X5WebView?, url: String?) {
            WebViewDiagnosticsLogStore.append(diagnosticContext, "page_finished", "engine=x5 url=$url")
            super.onPageFinished(view, url)
        }

        override fun onReceivedError(
            view: X5WebView?,
            request: X5WebResourceRequest?,
            error: X5WebResourceError?
        ) {
            if (error != null) {
                WebViewDiagnosticsLogStore.appendX5WebResourceError(diagnosticContext, request, error)
            }
            super.onReceivedError(view, request, error)
        }
    }
}
