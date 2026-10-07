package io.stamethyst

import android.content.Context
import android.util.Log
import android.view.View
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
                loadUrlImpl = { requestedUrl ->
                    val url = resolveSlingBreakGeckoUrl(requestedUrl, channel?.token) {
                        SlingAssetServer.url(context, it)
                    }
                    load.invoke(host, url)
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
