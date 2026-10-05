package io.stamethyst

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tencent.smtt.sdk.QbSdk
import com.tencent.smtt.sdk.TbsDownloader
import com.tencent.smtt.sdk.TbsListener
import com.tencent.smtt.utils.TbsLog
import com.tencent.smtt.utils.TbsLogClient
import io.stamethyst.backend.diag.WebViewDiagnosticsLogStore

/** A preInit request consumes the SDK's process-wide one-shot even if loading later fails. */
internal class SlingBreakX5PreInitGate {
    enum class Action { START, ALREADY_READY, RESTART_REQUIRED }
    private var requested = false

    fun nextAction(isX5Ready: Boolean): Action = when {
        isX5Ready -> Action.ALREADY_READY.also { requested = true }
        requested -> Action.RESTART_REQUIRED
        else -> Action.START.also { requested = true }
    }
}

/** needDownload automatically starts downloading; SDK 44286 allows only one start per process. */
internal class SlingBreakX5DownloadGate {
    private var requested = false

    fun tryRequest(): Boolean {
        if (requested) return false
        requested = true
        return true
    }
}

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
    // SDK 44286 cannot reinitialize X5 without restarting the process. Keep this across attempts.
    private val preInitGate = SlingBreakX5PreInitGate()
    private val downloadGate = SlingBreakX5DownloadGate()

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
            if (QbSdk.getTbsVersion(appContext) > 0) {
                loadInstalledCore(appContext, attempt)
            } else if (!allowDownload) {
                fail(appContext, appContext.getString(R.string.settings_sling_break_x5_missing))
            } else {
                if (!downloadGate.tryRequest()) {
                    fail(appContext, appContext.getString(R.string.settings_sling_break_x5_download_restart_required))
                    return@runCatching
                }
                // The user has accepted the ~50MB download, including on a mobile connection.
                installDownloadDiagnostics(appContext)
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
                            else -> fail(
                                appContext,
                                appContext.getString(
                                    SlingBreakX5DownloadDiagnostics.failureMessageResource(errorCode), errorCode
                                )
                            )
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
                // The foreground needDownload query automatically calls startDownload for third-
                // party apps. Do not start it again in the callback: the SDK reports 127 and the
                // error cleanup would stop the original download. PreInit only after installation.
                TbsDownloader.needDownload(appContext, false, true) { needed, version ->
                    dispatch(appContext, attempt) {
                        WebViewDiagnosticsLogStore.append(
                            appContext, "x5_download_query", "needed=$needed version=$version"
                        )
                        if (needed) {
                            // SDK 44286's query can return true/version=0 without sending HTTP.
                            // Stay in "preparing" until the actual downloader reports progress.
                            armTimeout(appContext, attempt)
                        } else if (QbSdk.getTbsVersion(appContext) > 0) {
                            loadInstalledCore(appContext, attempt)
                        } else {
                            // No core was offered. Do not consume preInit's one-shot on a missing core.
                            fail(appContext, appContext.getString(R.string.settings_sling_break_x5_missing))
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

    private fun installDownloadDiagnostics(context: Context) {
        // The SDK's normal log file can live outside our feedback bundle. Preserve its logging
        // while mirroring config/download status without full request or response payloads.
        TbsLog.setTbsLogClient(object : TbsLogClient(context.applicationContext) {
            override fun i(tag: String?, message: String?) {
                super.i(tag, message)
                SlingBreakX5DownloadDiagnostics.sdkLogDetail(tag, message)?.let { detail ->
                    WebViewDiagnosticsLogStore.append(context, "x5_download_sdk", detail)
                }
            }
        })
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
        when (preInitGate.nextAction(QbSdk.isX5Core())) {
            SlingBreakX5PreInitGate.Action.ALREADY_READY -> {
                notifyReady()
                return
            }
            SlingBreakX5PreInitGate.Action.RESTART_REQUIRED -> {
                fail(context, context.getString(R.string.settings_sling_break_x5_restart_required))
                return
            }
            SlingBreakX5PreInitGate.Action.START -> Unit
        }
        // Use the supported, one-shot entry point. enableX5WithoutRestart throws unconditionally
        // in SDK 44286, and forced preInit silently returns after an earlier initialization.
        QbSdk.preInit(context, object : QbSdk.PreInitCallback {
            override fun onViewInitFinished(isX5Core: Boolean) = dispatch(context, attempt) {
                WebViewDiagnosticsLogStore.append(context, "x5_view_init", "isX5Core=$isX5Core")
                if (isX5Core) {
                    notifyReady()
                } else {
                    val reason = runCatching { QbSdk.getX5CoreLoadHelp(context) }.getOrNull()
                        .orEmpty().ifBlank { context.getString(R.string.settings_sling_break_x5_missing) }
                    fail(context, "$reason\n${context.getString(R.string.settings_sling_break_x5_restart_required)}")
                }
            }

            override fun onCoreInitFinished() {
                WebViewDiagnosticsLogStore.append(context, "x5_core_init", "completed=true")
            }
        })
    }

    private fun notifyReady() {
        state = State.READY
        clearTimeout()
        QbSdk.setDownloadWithoutWifi(false)
        val pending = listeners.toList()
        listeners.clear()
        pending.forEach { it.onReady() }
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
