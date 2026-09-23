package io.stamethyst

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.stamethyst.backend.diag.WebViewDiagnosticsLogStore
import io.stamethyst.backend.diag.WebViewAudioEnvironment

internal const val SLING_BREAK_GAME_URL = "file:///android_asset/slingbreak/index.html"

internal fun slingBreakGameUrl(audioDebugEnabled: Boolean, launcherMode: Boolean = false): String {
    val query = buildList {
        if (launcherMode) add("launcher=1")
        if (audioDebugEnabled) add("audioDebug=1")
    }
    return if (query.isEmpty()) SLING_BREAK_GAME_URL
    else "$SLING_BREAK_GAME_URL?${query.joinToString("&")}"
}

internal fun slingBreakGameUrl(context: Context, launcherMode: Boolean = false): String {
    return slingBreakGameUrl(
        audioDebugEnabled = io.stamethyst.ui.preferences.LauncherPreferences
            .isSlingBreakAudioDebugModeEnabled(context),
        launcherMode = launcherMode,
    )
}

/** Clears the WebView state used by both the standalone game and the boot overlay. */
internal fun clearSlingBreakWebViewData(context: Context, onComplete: () -> Unit) {
    val clearOnMainThread = Runnable {
        runCatching {
            WebView(context).apply {
                clearCache(true)
                clearHistory()
                clearFormData()
                clearSslPreferences()
                destroy()
            }
        }
        runCatching { WebStorage.getInstance().deleteAllData() }

        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies {
            cookieManager.flush()
            onComplete()
        }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) {
        clearOnMainThread.run()
    } else {
        Handler(Looper.getMainLooper()).post(clearOnMainThread)
    }
}

@SuppressLint("SetJavaScriptEnabled", "DEPRECATION")
@Suppress("DEPRECATION")
internal fun WebView.configureSlingBreakGame() {
    val diagnosticContext = context.applicationContext
    setBackgroundColor(Color.rgb(245, 246, 243))
    overScrollMode = WebView.OVER_SCROLL_NEVER
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        // SlingBreak uses Web Audio rather than HTML media elements. Android WebView can keep its
        // AudioContext suspended when this autoplay restriction is enabled, even after the game
        // receives a touch gesture.
        mediaPlaybackRequiresUserGesture = false
        cacheMode = WebSettings.LOAD_NO_CACHE
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        allowContentAccess = false
        allowFileAccess = true
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        blockNetworkLoads = true
    }
    webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            WebViewDiagnosticsLogStore.appendConsoleMessage(diagnosticContext, consoleMessage)
            return true
        }
    }
    webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            WebViewDiagnosticsLogStore.append(diagnosticContext, "page_started", "url=$url")
            if (io.stamethyst.ui.preferences.LauncherPreferences
                    .isSlingBreakAudioDebugModeEnabled(diagnosticContext)) {
                WebViewAudioEnvironment.record(diagnosticContext)
            }
            super.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            WebViewDiagnosticsLogStore.append(diagnosticContext, "page_finished", "url=$url")
            super.onPageFinished(view, url)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (error != null) {
                WebViewDiagnosticsLogStore.appendWebResourceError(diagnosticContext, request, error)
            }
            super.onReceivedError(view, request, error)
        }
    }
}

/** Hosts the untouched SlingBreak web bundle packaged under assets/slingbreak. */
class SlingBreakActivity : AppCompatActivity() {
    companion object {
        fun launch(context: Context) {
            context.startActivity(Intent(context, SlingBreakActivity::class.java))
        }
    }

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        webView = WebView(this).apply {
            configureSlingBreakGame()
            loadUrl(slingBreakGameUrl(this@SlingBreakActivity))
        }
        setContentView(webView)
        hideSystemBars()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        hideSystemBars()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, webView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
