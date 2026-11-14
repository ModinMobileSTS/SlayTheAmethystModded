package io.stamethyst

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

/** Explicit developer action for legacy WebView data; never called by engine retirement. */
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
