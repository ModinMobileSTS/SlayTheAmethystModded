package io.stamethyst

import android.annotation.SuppressLint
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import com.tencent.smtt.sdk.CookieManager as X5CookieManager
import com.tencent.smtt.sdk.QbSdk
import com.tencent.smtt.sdk.WebStorage as X5WebStorage
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.stamethyst.backend.diag.WebViewDiagnosticsLogStore
import io.stamethyst.backend.diag.WebViewAudioEnvironment
import io.stamethyst.backend.audio.SlingNativeAudioBridge
import io.stamethyst.config.SlingBreakEngineMode
import io.stamethyst.ui.preferences.LauncherPreferences

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

        runCatching {
            X5WebStorage.getInstance().deleteAllData()
            X5CookieManager.getInstance().removeAllCookies(null)
            X5CookieManager.getInstance().flush()
            QbSdk.clearAllWebViewCache(context.applicationContext, true)
        }

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
    SlingNativeAudioBridge.attach(this)
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
            view?.let { SlingNativeAudioBridge.pageStarted(it) }
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

    private var webView: SlingBreakWebViewHost? = null
    private var x5InitializationDialog: AlertDialog? = null
    private var x5InitializationListener: SlingBreakX5.InitializationListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (LauncherPreferences.readSlingBreakEngineMode(this) == SlingBreakEngineMode.COMPATIBILITY) {
            beginX5Initialization()
        } else {
            showGame()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })
    }

    override fun onResume() {
        super.onResume()
        webView?.let {
            it.setActive(true)
            it.onResume()
            hideSystemBars()
        }
    }

    override fun onPause() {
        webView?.let {
            it.setActive(false)
            it.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        x5InitializationListener?.let(SlingBreakX5::removeListener)
        x5InitializationListener = null
        x5InitializationDialog?.dismiss()
        x5InitializationDialog = null
        webView?.let {
            it.closeAudio()
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun hideSystemBars() {
        val content = webView?.view ?: return
        WindowInsetsControllerCompat(window, content).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun beginX5Initialization() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(8))
        }
        val status = TextView(this).apply {
            text = getString(R.string.settings_sling_break_x5_preparing)
            gravity = Gravity.CENTER_VERTICAL
        }
        val spinner = ProgressBar(this).apply { isIndeterminate = true }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = android.view.View.INVISIBLE
        }
        root.addView(
            spinner,
            LinearLayout.LayoutParams(dp(32), dp(32)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        )
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)))

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_sling_break_x5_initializing)
            .setView(root)
            .create()
        dialog.setCancelable(false)
        dialog.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
        dialog.show()
        x5InitializationDialog = dialog

        val listener = object : SlingBreakX5.InitializationListener {
            override fun onProgress(value: Int) {
                if (isFinishing || isDestroyed) return
                if (value < 0) return
                spinner.visibility = android.view.View.GONE
                progress.visibility = android.view.View.VISIBLE
                status.text = if (value >= 100) getString(R.string.settings_sling_break_x5_installing)
                else getString(R.string.settings_sling_break_x5_downloading, value)
                val old = progress.progress
                ObjectAnimator.ofInt(progress, "progress", old, value.coerceIn(old, 100)).apply {
                    duration = 240L
                    start()
                }
            }

            override fun onReady() {
                if (isFinishing || isDestroyed) return
                x5InitializationListener = null
                x5InitializationDialog?.dismiss()
                x5InitializationDialog = null
                showGame()
            }

            override fun onFailure(reason: String) {
                if (isFinishing || isDestroyed) return
                x5InitializationListener = null
                showX5Failure(reason)
            }
        }
        x5InitializationListener = listener
        // Runtime entry only loads an installed core; new downloads require the settings prompt.
        SlingBreakX5.initialize(this, listener)
    }

    private fun showGame() {
        runCatching {
            val host = SlingBreakWebViewHost.create(this, allowSystemFallback = false)
            webView = host
            host.loadUrl(slingBreakGameUrl(this))
            setContentView(host.view)
            hideSystemBars()
        }.onFailure { failure ->
            showX5Failure(failure.message ?: failure.javaClass.simpleName)
        }
    }

    private fun showX5Failure(reason: String) {
        x5InitializationDialog?.dismiss()
        x5InitializationDialog = null
        WebViewDiagnosticsLogStore.append(this, "x5_switch_cancelled", "reason=$reason")
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_sling_break_x5_failed)
            .setMessage(reason)
            .setPositiveButton(R.string.common_action_close) { _, _ -> finish() }
            .setOnDismissListener { finish() }
            .show()
    }
}
