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
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.stamethyst.backend.diag.WebViewDiagnosticsLogStore
import io.stamethyst.backend.diag.WebViewAudioEnvironment
import io.stamethyst.backend.audio.SlingNativeAudioBridge
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
        // SlingBreak's fallback uses Web Audio rather than HTML media elements. WebView can keep its
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

/** Consent/install gate. Gameplay runs only in the isolated, download-only Godot Activity. */
class SlingBreakActivity : AppCompatActivity() {
    companion object {
        fun launch(context: Context) {
            context.startActivity(Intent(context, SlingBreakActivity::class.java))
        }
    }

    private val preparation by viewModels<io.stamethyst.ui.settings.core.SlingBreakPreparationViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            io.stamethyst.ui.theme.LauncherTheme(
                io.stamethyst.config.LauncherConfig.readThemeMode(this),
                io.stamethyst.config.LauncherConfig.readThemeColor(this),
            ) {
                val ready = preparation.installed || io.stamethyst.backend.minigame.SlingBreakInstallation.read(this) != null
                androidx.compose.runtime.LaunchedEffect(ready) {
                    if (ready) {
                        startActivity(Intent(this@SlingBreakActivity, io.stamethyst.godot.GodotMinigameActivity::class.java))
                        finish()
                    }
                }
                if (!ready && preparation.progress == null && preparation.failure == null) {
                    io.stamethyst.ui.settings.sections.SlingBreakDownloadConfirmation(
                        onConfirm = { preparation.prepare(applicationContext) }, onDismiss = ::finish,
                    )
                }
                io.stamethyst.ui.settings.sections.SlingBreakDependencyDialogs(
                    preparation.progress, preparation.message, preparation.failure, preparation.slow,
                    onRetry = { preparation.prepare(applicationContext) },
                    onSwitchMirror = preparation::switchMirror, onDismissFailure = ::finish,
                )
            }
        }
    }
}
