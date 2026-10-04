package io.stamethyst

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tencent.smtt.sdk.QbSdk
import com.tencent.smtt.sdk.TbsDownloader
import com.tencent.smtt.sdk.TbsListener
import io.stamethyst.backend.diag.WebViewDiagnosticsLogStore

/** Downloads only after explicit consent, then loads the installed core. All state lives on main. */
internal object SlingBreakX5 {
    interface InitializationListener {
        fun onProgress(progress: Int)
        fun onReady()
        fun onFailure(reason: String)
    }

    private enum class State { IDLE, INITIALIZING, READY, FAILED }
    private var state = State.IDLE
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<InitializationListener>()
    private var progress = -1
    private var generation = 0
    private var timeout: Runnable? = null
    private var loadingCore = false

    fun initialize(
        context: Context,
        listener: InitializationListener? = null,
        allowDownload: Boolean = false,
    ) {
        val appContext = context.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { initialize(appContext, listener, allowDownload) }
            return
        }
        if (state == State.READY) {
            listener?.onProgress(100)
            listener?.onReady()
            return
        }
        listener?.let { listeners += it }
        if (state == State.INITIALIZING) {
            listener?.onProgress(progress)
            return
        }
        state = State.INITIALIZING
        progress = -1
        loadingCore = false
        val attempt = ++generation
        WebViewDiagnosticsLogStore.append(appContext, "x5_init_started", "allowDownload=$allowDownload")
        armTimeout(appContext, attempt)
        runCatching {
            QbSdk.enableX5WithoutRestart()
            if (QbSdk.getTbsVersion(appContext) > 0) {
                loadInstalledCore(appContext, attempt)
            } else if (!allowDownload) {
                fail(appContext, appContext.getString(R.string.settings_sling_break_x5_missing))
            } else {
                // The user has accepted the ~50MB download, including on a mobile connection.
                QbSdk.setDownloadWithoutWifi(true)
                QbSdk.setTbsListener(object : TbsListener {
                    override fun onDownloadProgress(value: Int) = dispatch(appContext, attempt) {
                        updateProgress(appContext, attempt, value.coerceIn(0, 100))
                    }

                    override fun onDownloadFinish(errorCode: Int) = dispatch(appContext, attempt) {
                        WebViewDiagnosticsLogStore.append(appContext, "x5_download_finish", "errorCode=$errorCode")
                        when (errorCode) {
                            TbsListener.ErrorCode.DOWNLOAD_SUCCESS -> updateProgress(appContext, attempt, 100)
                            TbsListener.ErrorCode.DOWNLOAD_HAS_LOCAL_TBS_ERROR,
                            TbsListener.ErrorCode.DOWNLOAD_HAS_COPY_TBS_ERROR,
                            TbsListener.ErrorCode.NONEEDTODOWN_ERROR -> loadInstalledCore(appContext, attempt)
                            else -> fail(appContext, appContext.getString(R.string.settings_sling_break_x5_download_error, errorCode))
                        }
                    }

                    override fun onInstallFinish(errorCode: Int) = dispatch(appContext, attempt) {
                        WebViewDiagnosticsLogStore.append(appContext, "x5_install_finish", "errorCode=$errorCode")
                        when (errorCode) {
                            TbsListener.ErrorCode.DOWNLOAD_INSTALL_SUCCESS,
                            TbsListener.ErrorCode.COPY_INSTALL_SUCCESS,
                            TbsListener.ErrorCode.INCRUPDATE_INSTALL_SUCCESS,
                            TbsListener.ErrorCode.RENAME_SUCCESS,
                            TbsListener.ErrorCode.INSTALL_SUCCESS_AND_RELEASE_LOCK -> loadInstalledCore(appContext, attempt)
                            else -> fail(appContext, appContext.getString(R.string.settings_sling_break_x5_install_error, errorCode))
                        }
                    }
                })
                // initX5Environment can report a system core before its background download finishes.
                // Query/download explicitly and preInit only after installation, so the progress
                // dialog stays open until X5 is actually usable.
                TbsDownloader.needDownload(appContext, false, true) { needed, _ ->
                    dispatch(appContext, attempt) {
                        if (needed) {
                            updateProgress(appContext, attempt, 0)
                            TbsDownloader.startDownload(appContext, true)
                        } else {
                            loadInstalledCore(appContext, attempt)
                        }
                    }
                }
            }
        }.onFailure { fail(appContext, "${it.javaClass.simpleName}: ${it.message.orEmpty()}") }
    }

    fun removeListener(listener: InitializationListener) {
        if (Looper.myLooper() == Looper.getMainLooper()) listeners.remove(listener)
        else mainHandler.post { listeners.remove(listener) }
    }

    private fun dispatch(context: Context, attempt: Int, action: () -> Unit) {
        mainHandler.post {
            if (generation == attempt && state == State.INITIALIZING) {
                runCatching(action).onFailure {
                    fail(context, "${it.javaClass.simpleName}: ${it.message.orEmpty()}")
                }
            }
        }
    }

    private fun loadInstalledCore(context: Context, attempt: Int) {
        if (loadingCore) return
        loadingCore = true
        updateProgress(context, attempt, 100)
        // Force a fresh callback on retries; ordinary preInit is a process-wide one-shot.
        QbSdk.preInit(context, true, object : QbSdk.PreInitCallback {
            override fun onViewInitFinished(isX5Core: Boolean) = dispatch(context, attempt) {
                WebViewDiagnosticsLogStore.append(context, "x5_view_init", "isX5Core=$isX5Core")
                if (isX5Core) {
                    state = State.READY
                    clearTimeout()
                    QbSdk.setDownloadWithoutWifi(false)
                    val pending = listeners.toList()
                    listeners.clear()
                    pending.forEach { it.onReady() }
                } else {
                    val reason = runCatching { QbSdk.getX5CoreLoadHelp(context) }.getOrNull()
                        .orEmpty().ifBlank { context.getString(R.string.settings_sling_break_x5_missing) }
                    fail(context, reason)
                }
            }

            override fun onCoreInitFinished() {
                WebViewDiagnosticsLogStore.append(context, "x5_core_init", "completed=true")
            }
        })
    }

    private fun updateProgress(context: Context, attempt: Int, value: Int) {
        progress = value.coerceAtLeast(progress)
        WebViewDiagnosticsLogStore.append(context, "x5_download_progress", "progress=$progress")
        armTimeout(context, attempt)
        listeners.toList().forEach { it.onProgress(progress) }
    }

    private fun armTimeout(context: Context, attempt: Int) {
        clearTimeout()
        timeout = Runnable {
            if (generation == attempt && state == State.INITIALIZING) {
                fail(context, context.getString(R.string.settings_sling_break_x5_timeout))
            }
        }.also { mainHandler.postDelayed(it, 180_000L) }
    }

    private fun clearTimeout() {
        timeout?.let(mainHandler::removeCallbacks)
        timeout = null
    }

    private fun fail(context: Context, reason: String) {
        state = State.FAILED
        clearTimeout()
        runCatching { TbsDownloader.stopDownload() }
        runCatching { QbSdk.setDownloadWithoutWifi(false) }
        WebViewDiagnosticsLogStore.append(context, "x5_init_error", reason)
        Log.w("STS-X5", "X5 initialization failed: $reason")
        val pending = listeners.toList()
        listeners.clear()
        pending.forEach { it.onFailure(reason) }
    }
}
