package io.stamethyst.backend.steamcloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import io.stamethyst.R
import io.stamethyst.backend.notify.ForegroundNotificationIds
import io.stamethyst.backend.presence.GamePresenceState
import io.stamethyst.backend.presence.GamePresenceStateMarker
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import top.apricityx.workshop.steam.protocol.SteamSessionRevokedException

/** Experimental CM presence bridge. It is deliberately separate from Steam Cloud sync. */
class SteamGamePresenceService : Service() {
    companion object {
        private const val TAG = "SteamGamePresenceService"
        private const val CHANNEL_ID = "steam_game_presence"
        private val NOTIFICATION_ID = ForegroundNotificationIds.STEAM_GAME_PRESENCE
        private const val ACTION_START = "io.stamethyst.action.STEAM_GAME_PRESENCE_START"
        private const val STARTUP_TIMEOUT_MS = 60_000L
        private const val SESSION_CHECK_INTERVAL_MS = 30_000L

        fun startIfEnabled(context: Context) {
            val appContext = context.applicationContext
            val enabled = LauncherConfig.isSteamGamePresenceEnabled(appContext)
            val state = GamePresenceStateMarker.readCurrentState(appContext)
            val auth = SteamCloudAuthStore.readAuthMaterial(appContext)
            val skipReason = when {
                !enabled -> "feature_disabled"
                state.state != GamePresenceState.Game -> "game_state_not_active"
                auth == null -> "steam_auth_material_incomplete"
                else -> ""
            }
            if (skipReason.isNotEmpty()) {
                val now = System.currentTimeMillis()
                SteamGamePresenceDiagnosticsStore.writeSummary(
                    appContext,
                    "SKIPPED",
                    auth?.accountName.orEmpty(),
                    now,
                    now,
                    false,
                    false,
                    null,
                    null,
                    skipReason,
                )
                return
            }
            val requestedAtMs = System.currentTimeMillis()
            val intent = Intent(appContext, SteamGamePresenceService::class.java).setAction(ACTION_START)
            runCatching {
                val component = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
                SteamGamePresenceDiagnosticsStore.appendEvent(
                    appContext,
                    "service_start_dispatched",
                    "component=${component?.flattenToShortString().orEmpty()}",
                )
            }.onFailure { error ->
                Log.w(TAG, "Unable to start experimental Steam presence service.", error)
                SteamGamePresenceDiagnosticsStore.writeSummary(
                    appContext,
                    "FAILED",
                    auth?.accountName.orEmpty(),
                    requestedAtMs,
                    System.currentTimeMillis(),
                    false,
                    false,
                    error,
                    null,
                    "foreground_service_start_failed",
                )
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            appContext.stopService(Intent(appContext, SteamGamePresenceService::class.java))
        }
    }

    private val stopRequested = AtomicBoolean(false)
    private val terminalSummaryWritten = AtomicBoolean(false)
    private val clientLock = Any()
    private val presenceChangeLock = ReentrantLock()
    private val presenceChangeCondition = presenceChangeLock.newCondition()
    @Volatile private var workerThread: Thread? = null
    @Volatile private var client: SteamCloudClient? = null
    private var sessionObserver: AutoCloseable? = null
    @Volatile private var loggedOn = false
    @Volatile private var appIdSent = false
    @Volatile private var clearStateSent = false
    @Volatile private var operationStartedAtMs = 0L
    @Volatile private var operationAccountName = ""
    @Volatile private var presenceChangeVersion = 0L
    @Volatile private var recoveryRequestVersion = 0L
    @Volatile private var presenceFileObserver: FileObserver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SteamGamePresenceDiagnosticsStore.appendEvent(
            applicationContext,
            "service_created",
            "process=:game",
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SteamGamePresenceDiagnosticsStore.appendEvent(
            applicationContext,
            "service_start_command",
            "action=${intent?.action.orEmpty()}; startId=$startId; flags=$flags",
        )
        if (intent?.action != ACTION_START) {
            SteamGamePresenceDiagnosticsStore.appendEvent(
                applicationContext,
                "service_start_ignored",
                "reason=unexpected_action",
            )
            return START_NOT_STICKY
        }

        val acceptedAtMs = System.currentTimeMillis()
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to enter foreground for experimental Steam presence service.", error)
            SteamGamePresenceDiagnosticsStore.writeSummary(
                applicationContext,
                "FAILED",
                SteamCloudAuthStore.readAuthMaterial(applicationContext)?.accountName.orEmpty(),
                acceptedAtMs,
                System.currentTimeMillis(),
                false,
                false,
                error,
                null,
                "foreground_notification_failed",
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (workerThread?.isAlive == true) {
            SteamGamePresenceDiagnosticsStore.appendEvent(
                applicationContext,
                "service_recovery_requested",
                "reason=already_running",
            )
            signalPresenceChange(recoveryRequested = true)
            return START_NOT_STICKY
        }
        stopRequested.set(false)
        terminalSummaryWritten.set(false)
        appIdSent = false
        clearStateSent = false
        operationStartedAtMs = acceptedAtMs
        operationAccountName = SteamCloudAuthStore.readAuthMaterial(applicationContext)?.accountName.orEmpty()
        SteamGamePresenceDiagnosticsStore.writeSummary(
            applicationContext,
            "STARTED",
            operationAccountName,
            acceptedAtMs,
            acceptedAtMs,
            false,
            false,
            null,
            null,
            "foreground_service_started_waiting_for_rich_presence",
        )
        val thread = Thread(::runPresence, "STS-SteamGamePresence")
        workerThread = thread
        thread.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRequested.set(true)
        stopPresenceFileObserver()
        signalPresenceChange()
        workerThread?.interrupt()
        closeClient(clearState = true)
        workerThread = null
        super.onDestroy()
    }

    private fun runPresence() {
        if (operationStartedAtMs <= 0L) operationStartedAtMs = System.currentTimeMillis()
        var failure: Throwable? = null
        var steamClient: SteamCloudClient? = null
        var session: SteamGamePresenceSession? = null
        val retryPolicy = SteamGamePresenceRetryPolicy()
        try {
            startPresenceFileObserver()
            var observedPresenceVersion = presenceChangeVersion
            while (!stopRequested.get() && isStillGameActive()) {
                val recoveryVersion = recoveryRequestVersion
                try {
                    // Never retain credentials across retries or resurrect a logged-out account.
                    val auth = SteamCloudAuthStore.readAuthMaterial(applicationContext) ?: break
                    val richPresence = RichPresenceStateFile.read(RuntimePaths.richPresenceFile(applicationContext))
                    if (richPresence != null) {
                        if (session == null) {
                            val activeClient = SteamCloudClient(applicationContext)
                            steamClient = activeClient
                            synchronized(clientLock) {
                                if (stopRequested.get()) {
                                    activeClient.close()
                                    throw InterruptedException("Steam presence service is stopping.")
                                }
                                client = activeClient
                            }
                            operationAccountName = auth.accountName
                            activeClient.beginOperationDiagnostics("game_presence", auth.accountName, auth.guardData.isNotBlank())
                            activeClient.start()
                            session = createPresenceSession(activeClient)
                        }
                        val restored = session.synchronize(richPresence)
                        retryPolicy.reset()
                        if (restored) {
                            SteamGamePresenceDiagnosticsStore.writeSummary(
                                applicationContext,
                                "RUNNING",
                                operationAccountName,
                                operationStartedAtMs,
                                System.currentTimeMillis(),
                                appIdSent,
                                clearStateSent,
                                null,
                                steamClient?.snapshotDiagnostics(),
                                "steam_presence_session_restored",
                            )
                        }
                    }
                } catch (error: Exception) {
                    if (stopRequested.get() || !isStillGameActive() || !SteamGamePresenceRetryPolicy.shouldRetry(error)) {
                        throw error
                    }
                    val retry = retryPolicy.afterFailure(SystemClock.elapsedRealtime())
                    Log.w(TAG, "Steam presence interrupted; retrying while the game remains active.", error)
                    closeClient(clearState = true)
                    session = null
                    SteamGamePresenceDiagnosticsStore.writeSummary(
                        applicationContext,
                        "RETRYING",
                        operationAccountName,
                        operationStartedAtMs,
                        System.currentTimeMillis(),
                        appIdSent,
                        clearStateSent,
                        error,
                        steamClient?.snapshotDiagnostics(),
                        "steam_presence_retry_scheduled",
                    )
                    awaitRetry(retry, recoveryVersion)
                    continue
                }
                // Also check idle sessions: no floor/menu change is needed to notice a disconnect.
                observedPresenceVersion = awaitPresenceChange(observedPresenceVersion, SESSION_CHECK_INTERVAL_MS)
            }
        } catch (error: Throwable) {
            if (error is InterruptedException && stopRequested.get()) {
                Thread.currentThread().interrupt()
            } else if (!stopRequested.get()) {
                failure = error
            }
            if (!stopRequested.get()) Log.w(TAG, "Experimental Steam presence stopped.", error)
        } finally {
            stopPresenceFileObserver()
            closeClient(clearState = true)
            writeTerminalSummary(
                if (failure == null) "STOPPED" else "FAILED",
                operationStartedAtMs,
                System.currentTimeMillis(),
                appIdSent,
                clearStateSent,
                failure,
                steamClient?.snapshotDiagnostics(),
            )
            stopSelf()
        }
    }

    private fun createPresenceSession(
        activeClient: SteamCloudClient,
    ): SteamGamePresenceSession = SteamGamePresenceSession(object : SteamGamePresenceSession.Client {
        override val sessionIdentity: Any?
            get() = activeClient.cmSessionIdentity

        override fun logOn() {
            val auth = SteamCloudAuthStore.readAuthMaterial(applicationContext) ?: throw SteamSessionRevokedException()
            operationAccountName = auth.accountName
            // A timeout cancels this attempt, not the foreground service or its next retry.
            runBlocking {
                withTimeout(STARTUP_TIMEOUT_MS) {
                    runInterruptible(Dispatchers.IO) {
                        activeClient.logOnWithRefreshToken(auth.accountName, auth.refreshToken, auth.steamId64)
                    }
                }
            }
            withActiveClient(activeClient) {
                loggedOn = true
                sessionObserver?.close()
                sessionObserver = activeClient.observeCmSessionChanges { signalPresenceChange() }
            }
        }

        override fun setPersonaOnline() = withActiveClient(activeClient) {
            activeClient.setPersonaOnline()
        }

        override fun setGamePlayedAppId(appId: Long) = withActiveClient(activeClient) {
            activeClient.setGamePlayedAppId(appId)
            appIdSent = true
            clearStateSent = false
        }

        override fun setRichPresence(presence: Map<String, String>) = withActiveClient(activeClient) {
            SteamGamePresenceDiagnosticsStore.appendEvent(
                applicationContext,
                "rich_presence_upload_attempt",
                "emsg=7501; keys=${presence.keys.joinToString(",")}",
            )
            activeClient.setRichPresence(presence)
            SteamGamePresenceDiagnosticsStore.appendEvent(
                applicationContext,
                "rich_presence_upload_queued",
                "emsg=7501; status=${presence["status"].orEmpty()}; " +
                    "steamDisplay=${presence["steam_display"].orEmpty()}",
            )
        }
    })

    private fun withActiveClient(activeClient: SteamCloudClient, action: () -> Unit) {
        synchronized(clientLock) {
            check(!stopRequested.get() && client === activeClient && isStillGameActive()) {
                "Game is no longer active."
            }
            action()
        }
    }

    private fun awaitRetry(retry: SteamGamePresenceRetryPolicy.RetryWindow, recoveryVersion: Long) {
        var observedVersion = presenceChangeVersion
        while (!stopRequested.get() && isStillGameActive()) {
            val remaining = retry.remainingDelayMs(
                SystemClock.elapsedRealtime(),
                recoveryRequested = recoveryRequestVersion != recoveryVersion,
            )
            if (remaining <= 0L) return
            observedVersion = awaitPresenceChange(observedVersion, minOf(remaining, SESSION_CHECK_INTERVAL_MS))
        }
    }

    private fun startPresenceFileObserver() {
        val target = RuntimePaths.richPresenceFile(applicationContext)
        val parent = target.parentFile ?: return
        parent.mkdirs()
        val observer = createPresenceFileObserver(parent, target.name)
        presenceFileObserver = observer
        observer.startWatching()
        SteamGamePresenceDiagnosticsStore.appendEvent(
            applicationContext,
            "rich_presence_observer_started",
            "path=${target.absolutePath}",
        )
    }

    private fun stopPresenceFileObserver() {
        presenceFileObserver?.stopWatching()
        presenceFileObserver = null
    }

    private fun createPresenceFileObserver(parent: File, targetName: String): FileObserver {
        val mask = FileObserver.CREATE or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(parent, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == targetName) signalPresenceChange()
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(parent.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == targetName) signalPresenceChange()
                }
            }
        }
    }

    private fun signalPresenceChange(recoveryRequested: Boolean = false) {
        presenceChangeLock.lock()
        try {
            presenceChangeVersion += 1
            if (recoveryRequested) recoveryRequestVersion += 1
            presenceChangeCondition.signalAll()
        } finally {
            presenceChangeLock.unlock()
        }
    }

    private fun awaitPresenceChange(observedVersion: Long, timeoutMs: Long): Long {
        presenceChangeLock.lock()
        try {
            if (!stopRequested.get() && observedVersion == presenceChangeVersion) {
                if (timeoutMs > 0L) {
                    presenceChangeCondition.await(timeoutMs, TimeUnit.MILLISECONDS)
                } else {
                    presenceChangeCondition.await()
                }
            }
            return presenceChangeVersion
        } finally {
            presenceChangeLock.unlock()
        }
    }

    private fun isStillGameActive(): Boolean {
        return GamePresenceStateMarker.readCurrentState(applicationContext).state == GamePresenceState.Game &&
            LauncherConfig.isSteamGamePresenceEnabled(applicationContext)
    }

    private fun writeTerminalSummary(
        outcome: String,
        startedAtMs: Long,
        completedAtMs: Long,
        appStateMessageSent: Boolean,
        clearStateMessageSent: Boolean,
        failure: Throwable?,
        diagnostics: SteamCloudClient.DiagnosticsSnapshot?,
        detail: String = "",
    ) {
        if (!terminalSummaryWritten.compareAndSet(false, true)) return
        SteamGamePresenceDiagnosticsStore.writeSummary(
            applicationContext,
            outcome,
            operationAccountName,
            startedAtMs,
            completedAtMs,
            appStateMessageSent,
            clearStateMessageSent,
            failure,
            diagnostics,
            detail,
        )
    }

    private fun closeClient(clearState: Boolean) {
        synchronized(clientLock) {
            sessionObserver?.close()
            sessionObserver = null
            val activeClient = client ?: return
            try {
                if (clearState && loggedOn) {
                    activeClient.setGamePlayedAppId(0L)
                    clearStateSent = true
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to clear Steam game state before disconnect.", error)
            } finally {
                loggedOn = false
                client = null
                activeClient.close()
            }
        }
    }

    private fun buildNotification(): android.app.Notification {
        ensureNotificationChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dock_game)
            .setContentTitle(getString(R.string.settings_steam_game_presence_title))
            .setContentText(getString(R.string.settings_steam_game_presence_notification))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Steam 游戏状态", NotificationManager.IMPORTANCE_LOW)
        )
    }
}
