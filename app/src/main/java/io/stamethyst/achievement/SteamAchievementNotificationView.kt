package io.stamethyst.achievement

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.stamethyst.backend.steamcloud.SteamAchievementCatalog
import java.io.ByteArrayInputStream

/**
 * Steam uses Chromium to draw its desktop notifications. Use the same CSS/font/mask pipeline
 * instead of approximating its conic-gradient and nested rotating masks with Compose effects.
 * This is a 283 x 210dp local-only stack, not a full-screen browser or an interactive overlay.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class SteamAchievementNotificationView(
    context: Context,
    private val onReady: () -> Unit,
) : WebView(context) {
    private val assets = SteamAchievementNotificationAssets(context)
    private var token = 0L
    private var destroyed = false
    private data class Callbacks(var onShown: (() -> Unit)?, val onFinished: () -> Unit)
    private val callbacks = mutableMapOf<Long, Callbacks>()
    var ready = false
        private set

    init {
        setBackgroundColor(Color.TRANSPARENT)
        visibility = View.INVISIBLE
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            textZoom = 100
            setSupportZoom(false)
        }
        addJavascriptInterface(Bridge(), "SteamAchievementHost")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val name = request.url.toString().removePrefix(SteamAchievementNotificationAssets.ORIGIN)
                val mime = SteamAchievementNotificationAssets.resources[name]
                if (request.method != "GET" ||
                    !request.url.toString().startsWith(SteamAchievementNotificationAssets.ORIGIN) || mime == null
                ) {
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
                return WebResourceResponse(mime, "UTF-8", context.assets.open("steam-achievement/$name"))
            }
        }
        val html = context.assets.open("steam-achievement/notification.html").bufferedReader().use { it.readText() }
        loadDataWithBaseURL(SteamAchievementNotificationAssets.ORIGIN, html, "text/html", "UTF-8", null)
    }

    // Returning false at dispatch, rather than only onTouchEvent, keeps game gestures underneath
    // working even when they start inside the notification rectangle.
    override fun dispatchTouchEvent(event: MotionEvent?): Boolean = false

    fun show(entry: SteamAchievementCatalog.Entry, onShown: () -> Unit, onFinished: () -> Unit) {
        check(ready && !destroyed)
        check(callbacks.size < SteamAchievementNotificationSpec.MAX_VISIBLE)
        token++
        callbacks[token] = Callbacks(onShown, onFinished)
        visibility = View.VISIBLE
        evaluateJavascript("window.setAchievement(${assets.payload(entry, token)})", null)
    }

    override fun destroy() {
        destroyed = true
        token++
        ready = false
        callbacks.clear()
        removeJavascriptInterface("SteamAchievementHost")
        stopLoading()
        super.destroy()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun ready() {
            post {
                if (!destroyed && !ready) {
                    ready = true
                    onReady()
                }
            }
        }

        @JavascriptInterface
        fun prepared(preparedToken: Long) {
            post {
                if (!destroyed) {
                    val callback = callbacks[preparedToken] ?: return@post
                    callback.onShown?.invoke()
                    callback.onShown = null
                }
            }
        }

        @JavascriptInterface
        fun finished(finishedToken: Long) {
            post {
                if (!destroyed) {
                    val callback = callbacks.remove(finishedToken) ?: return@post
                    if (callbacks.isEmpty()) visibility = View.INVISIBLE
                    callback.onFinished()
                }
            }
        }
    }
}
