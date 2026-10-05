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
import io.stamethyst.web.GeckoDependencyLoader
import io.stamethyst.web.SlingAssetServer
import io.stamethyst.web.SlingLauncherChannel

private const val LOGCAT_TAG = "STS-Compatible"

/**
 * Shared lifecycle adapter over the compatibility engine. WebView mode uses the platform WebView;
 * compatibility mode uses downloaded GeckoView and a loopback launcher bridge for the overlay.
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
        /**
         * Builds the host for the requested engine.
         *
         * @param useCompatibilityEngine selects the external GeckoView compatibility engine
         *   for either entry point. The overlay uses the scoped loopback launcher bridge.
         */
        fun create(
            context: Context,
            allowSystemFallback: Boolean = true,
            useCompatibilityEngine: Boolean =
                LauncherPreferences.readSlingBreakEngineMode(context) == SlingBreakEngineMode.COMPATIBILITY,
        ): SlingBreakWebViewHost {
            if (!useCompatibilityEngine) return createSystem(context)
            return runCatching { createCompatibility(context) }
                .onSuccess { host ->
                    WebViewDiagnosticsLogStore.append(
                        context, "compatibility_view_created", "engine=${host.engine}"
                    )
                }
                .onFailure { failure ->
                    WebViewDiagnosticsLogStore.append(
                        context,
                        "compatibility_view_error",
                        "${failure.javaClass.simpleName}: ${failure.message}"
                    )
                    Log.w(LOGCAT_TAG, "Unable to create the compatibility engine; using system WebView", failure)
                }
                .getOrElse {
                    if (!allowSystemFallback) throw it
                    createSystem(context)
                }
        }

        /**
         * External GeckoView with a narrowly scoped launcher bridge and Web Audio fallback.
         */
        private fun createCompatibility(context: Context): SlingBreakWebViewHost {
            val host = GeckoDependencyLoader.createHost(context)
            val type = host.javaClass
            val load = type.getMethod("loadUrl", String::class.java)
            val active = type.getMethod("setActive", Boolean::class.javaPrimitiveType)
            val stop = type.getMethod("stop")
            val close = type.getMethod("close")
            var channel: SlingLauncherChannel? = null
            return SlingBreakWebViewHost(
                view = type.getMethod("getView").invoke(host) as View,
                loadUrlImpl = {
                    val url = SlingAssetServer.url(context, it)
                    load.invoke(host, url + (channel?.let { bridge ->
                        (if (url.contains('?')) "&" else "?") + "geckoLauncher=" + bridge.token
                    } ?: ""))
                },
                evaluateJavascriptImpl = { script, callback ->
                    channel?.evaluate(script)
                    callback?.invoke(null)
                },
                addJavascriptInterfaceImpl = { instance, name ->
                    require(name == "AndroidSlingBreakLauncher") { "Unsupported Gecko JS interface: $name" }
                    channel?.let(SlingAssetServer::unregister)
                    channel = SlingLauncherChannel(instance).also { SlingAssetServer.register(context, it) }
                },
                onResumeImpl = { },
                onPauseImpl = { },
                setActiveImpl = { active.invoke(host, it); Unit },
                stopLoadingImpl = { stop.invoke(host); Unit },
                closeAudioImpl = { },
                destroyImpl = {
                    channel?.let(SlingAssetServer::unregister)
                    channel = null
                    runCatching { close.invoke(host) }
                },
                engine = "gecko"
            )
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
