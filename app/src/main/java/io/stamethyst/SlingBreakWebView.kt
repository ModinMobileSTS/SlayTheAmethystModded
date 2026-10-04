package io.stamethyst

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.view.View
import com.tencent.smtt.export.external.interfaces.ConsoleMessage as X5ConsoleMessage
import com.tencent.smtt.export.external.interfaces.WebResourceError as X5WebResourceError
import com.tencent.smtt.export.external.interfaces.WebResourceRequest as X5WebResourceRequest
import com.tencent.smtt.sdk.WebChromeClient as X5WebChromeClient
import com.tencent.smtt.sdk.WebSettings as X5WebSettings
import com.tencent.smtt.sdk.WebView as X5WebView
import com.tencent.smtt.sdk.WebViewClient as X5WebViewClient
import io.stamethyst.backend.audio.SlingNativeAudioBridge
import io.stamethyst.backend.diag.WebViewDiagnosticsLogStore
import io.stamethyst.config.SlingBreakEngineMode
import io.stamethyst.ui.preferences.LauncherPreferences

private const val LOGCAT_TAG = "STS-X5"

/**
 * Shared lifecycle adapter. WebView mode never creates an X5 view; compatibility mode uses the
 * installed X5 core, with a system fallback for the boot overlay if the core becomes unavailable.
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
            if (LauncherPreferences.readSlingBreakEngineMode(context) == SlingBreakEngineMode.WEBVIEW) {
                return createSystem(context)
            }
            return runCatching {
                X5WebView(context).apply { configureSlingBreakX5Game() }.let { webView ->
                    if (!webView.getIsX5Core() && !allowSystemFallback) {
                        SlingNativeAudioBridge.close(webView)
                        webView.destroy()
                        error(context.getString(R.string.settings_sling_break_x5_missing))
                    }
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
                createSystem(context)
            }
        }

        private fun createSystem(context: Context): SlingBreakWebViewHost =
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
                    engine = "system"
                )
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
