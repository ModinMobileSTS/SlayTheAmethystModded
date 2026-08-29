package io.stamethyst.backend.easytier

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ResultReceiver
import android.util.Log
import io.stamethyst.R
import io.stamethyst.config.CloudControlConfig
import io.stamethyst.config.LauncherConfig

class EasyTierProcessService : Service() {
    private val priorityBinder = Binder()
    private lateinit var statusPollThread: HandlerThread
    private lateinit var statusPollHandler: Handler
    private var statusPollRunnable: Runnable? = null
    @Volatile
    private var workerThread: Thread? = null
    @Volatile
    private var currentSessionId: String = ""
    @Volatile
    private var currentRuntimeInstanceName: String = ""
    @Volatile
    private var currentVpnRouteSignature: String = ""
    @Volatile
    private var runtimeReportUnsupported: Boolean = false

    companion object {
        private const val TAG = "EasyTierProcessService"

        const val ACTION_CONNECT = "io.stamethyst.action.EASYTIER_CONNECT"
        const val ACTION_DISCONNECT = "io.stamethyst.action.EASYTIER_DISCONNECT"
        const val ACTION_REFRESH_STATE = "io.stamethyst.action.EASYTIER_REFRESH_STATE"
        const val ACTION_CONNECTION_EVENT = "io.stamethyst.action.EASYTIER_CONNECTION_EVENT"

        const val EXTRA_RESULT_RECEIVER = "io.stamethyst.extra.EASYTIER_RESULT_RECEIVER"
        const val EXTRA_EVENT_RESULT_CODE = "io.stamethyst.extra.EASYTIER_EVENT_RESULT_CODE"
        const val EXTRA_STATE_SNAPSHOT = "io.stamethyst.extra.EASYTIER_STATE_SNAPSHOT"
        const val EXTRA_ERROR_SUMMARY = "io.stamethyst.extra.EASYTIER_ERROR_SUMMARY"
        const val EXTRA_USER_INITIATED = "io.stamethyst.extra.EASYTIER_USER_INITIATED"
        const val EXTRA_CONNECT_MODE = "io.stamethyst.extra.EASYTIER_CONNECT_MODE"
        const val EXTRA_ROOM_ID = "io.stamethyst.extra.EASYTIER_ROOM_ID"
        const val EXTRA_ROOM_DESCRIPTION = "io.stamethyst.extra.EASYTIER_ROOM_DESCRIPTION"
        const val EXTRA_ALLOW_NEW_JOINS = "io.stamethyst.extra.EASYTIER_ALLOW_NEW_JOINS"
        const val EXTRA_CREATE_ONLY = "io.stamethyst.extra.EASYTIER_CREATE_ONLY"
        const val EXTRA_ROOM_PASSWORD = "io.stamethyst.extra.EASYTIER_ROOM_PASSWORD"

        const val RESULT_CONNECTING = 1
        const val RESULT_CONNECTED = 2
        const val RESULT_RECONNECTING = 3
        const val RESULT_DISCONNECTED = 4
        const val RESULT_FAILURE = 5
        const val RESULT_PERMISSION_REQUIRED = 6
        const val RESULT_SESSION_READY = 7

        @Volatile
        private var running = false
        @Volatile
        private var activeService: EasyTierProcessService? = null

        fun isRunning(context: Context): Boolean {
            if (running) {
                return true
            }
            val manager = context.getSystemService(ActivityManager::class.java) ?: return false
            @Suppress("DEPRECATION")
            return manager.getRunningServices(Int.MAX_VALUE).any { service ->
                service.service.className == EasyTierProcessService::class.java.name
            }
        }

        fun releaseConnectForeground() {
            activeService?.releaseConnectForegroundInternal()
        }

        fun startConnect(
            context: Context,
            mode: EasyTierNetworkMode? = null,
            roomId: String = "",
            userInitiated: Boolean,
            receiver: ResultReceiver? = null,
            roomDescriptionWhenCreating: String = "",
            allowNewJoinsWhenCreating: Boolean? = null,
            createOnly: Boolean = false,
            password: String = "",
        ): Boolean {
            val appContext = context.applicationContext
            val intent = Intent(appContext, EasyTierProcessService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_RESULT_RECEIVER, receiver)
                putExtra(EXTRA_USER_INITIATED, userInitiated)
                mode?.let { putExtra(EXTRA_CONNECT_MODE, it.cloudControlValue) }
                if (roomId.isNotBlank()) {
                    putExtra(EXTRA_ROOM_ID, roomId)
                }
                if (roomDescriptionWhenCreating.isNotBlank()) {
                    putExtra(EXTRA_ROOM_DESCRIPTION, roomDescriptionWhenCreating)
                }
                allowNewJoinsWhenCreating?.let {
                    putExtra(EXTRA_ALLOW_NEW_JOINS, it)
                }
                if (createOnly) {
                    putExtra(EXTRA_CREATE_ONLY, true)
                }
                if (password.isNotEmpty()) {
                    putExtra(EXTRA_ROOM_PASSWORD, password)
                }
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
                true
            } catch (error: IllegalStateException) {
                if (!isForegroundServiceStartRejected(error)) {
                    throw error
                }
                reportServiceStartRejected(appContext, receiver, error)
                false
            }
        }

        fun disconnect(
            context: Context,
            receiver: ResultReceiver? = null,
        ) {
            val appContext = context.applicationContext
            appContext.startService(
                Intent(appContext, EasyTierProcessService::class.java).apply {
                    action = ACTION_DISCONNECT
                    putExtra(EXTRA_RESULT_RECEIVER, receiver)
                }
            )
        }

        fun requestStateSync(
            context: Context,
            receiver: ResultReceiver? = null,
        ) {
            val appContext = context.applicationContext
            appContext.startService(
                Intent(appContext, EasyTierProcessService::class.java).apply {
                    action = ACTION_REFRESH_STATE
                    putExtra(EXTRA_RESULT_RECEIVER, receiver)
                }
            )
        }

        internal fun broadcastSnapshot(
            context: Context,
            resultCode: Int,
            snapshot: EasyTierConnectionSnapshot,
            errorSummary: String = snapshot.lastErrorSummary,
        ) {
            context.sendBroadcast(
                Intent(ACTION_CONNECTION_EVENT).apply {
                    `package` = context.packageName
                    putExtra(EXTRA_EVENT_RESULT_CODE, resultCode)
                    putExtra(EXTRA_STATE_SNAPSHOT, snapshot)
                    if (errorSummary.isNotBlank()) {
                        putExtra(EXTRA_ERROR_SUMMARY, errorSummary)
                    }
                }
            )
        }

        private fun isForegroundServiceStartRejected(error: IllegalStateException): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                error.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
        }

        private fun reportServiceStartRejected(
            context: Context,
            receiver: ResultReceiver?,
            error: IllegalStateException,
        ) {
            val summary = context.getString(R.string.main_easytier_service_start_blocked)
            Log.w(TAG, summary, error)
            val snapshot = EasyTierSessionController.persistSnapshot(
                context = context,
                snapshot = EasyTierSessionController.buildFailureSnapshot(
                    previous = EasyTierSessionController.currentSnapshot(context),
                    summary = summary,
                    failureCategory = EasyTierFailureCategory.BackgroundStartBlocked,
                ),
                extraLines = listOf("foreground_service_start_rejected=true"),
                error = error,
            )
            deliverSnapshot(context, receiver, RESULT_FAILURE, snapshot)
        }

        private fun deliverSnapshot(
            context: Context,
            receiver: ResultReceiver?,
            resultCode: Int,
            snapshot: EasyTierConnectionSnapshot,
        ) {
            val data = Bundle().apply {
                putSerializable(EXTRA_STATE_SNAPSHOT, snapshot)
                if (snapshot.lastErrorSummary.isNotBlank()) {
                    putString(EXTRA_ERROR_SUMMARY, snapshot.lastErrorSummary)
                }
            }
            receiver?.send(resultCode, Bundle(data))
            broadcastSnapshot(context, resultCode, snapshot)
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeService = this
        CloudControlConfig.refreshOnAppStart(applicationContext)
        statusPollThread = HandlerThread("STS-EasyTierStatusPoll").also(HandlerThread::start)
        statusPollHandler = Handler(statusPollThread.looper)
    }

    /**
     * Exists only so the `:game` process can hold a `BIND_IMPORTANT` binding and pull this process
     * into the game's LMK priority band; see [EasyTierGameProcessPriorityBinding]. The binder
     * intentionally exposes no interface — session control stays on the start-command path.
     */
    override fun onBind(intent: Intent?): IBinder = priorityBinder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val safeIntent = intent ?: return START_NOT_STICKY
        val receiver = extractResultReceiver(safeIntent)
        when (safeIntent.action) {
            ACTION_REFRESH_STATE -> {
                val snapshot = EasyTierSessionController.currentSnapshot(applicationContext)
                deliverSnapshot(applicationContext, receiver, resultCodeFor(snapshot), snapshot)
                return START_NOT_STICKY
            }

            ACTION_DISCONNECT -> {
                running = false
                workerThread?.interrupt()
                workerThread = null
                stopStatusPolling()
                Thread(
                    { runDisconnect(startId, receiver) },
                    "STS-EasyTierDisconnect"
                ).start()
                return START_NOT_STICKY
            }

            ACTION_CONNECT -> {
                startForeground(
                    EasyTierForegroundNotification.CONNECT_NOTIFICATION_ID,
                    EasyTierForegroundNotification.build(
                        this,
                        getString(R.string.main_easytier_notification_connecting),
                    ),
                )
                if (running) {
                    val snapshot = EasyTierSessionController.currentSnapshot(applicationContext)
                    deliverSnapshot(applicationContext, receiver, resultCodeFor(snapshot), snapshot)
                    return START_REDELIVER_INTENT
                }
                running = true
                val taskIntent = Intent(safeIntent)
                val thread = Thread(
                    { runConnect(startId, taskIntent, receiver) },
                    "STS-EasyTierConnect"
                )
                workerThread = thread
                thread.start()
                return START_REDELIVER_INTENT
            }

            else -> return START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        if (activeService === this) {
            activeService = null
        }
        val previous = EasyTierStateStore.readSnapshot(applicationContext)
        running = false
        workerThread?.interrupt()
        workerThread = null
        stopStatusPolling()
        statusPollThread.quitSafely()
        StsEasyTierVpnService.stopSession(applicationContext)
        EasyTierJniBridge.stopAllInstances(applicationContext)
        if (previous?.isConnectionActive == true) {
            EasyTierSessionController.persistSnapshot(
                context = applicationContext,
                snapshot = EasyTierSessionController.buildDisconnectedSnapshot(previous),
                extraLines = listOf("process_service_destroyed=true"),
            )
        }
        super.onDestroy()
    }

    private fun runConnect(
        startId: Int,
        intent: Intent,
        receiver: ResultReceiver?,
    ) {
        try {
            val config = EasyTierConfigRepository.current()
            val currentPlayerId = buildStablePlayerId(applicationContext)
            val mode = intent.getStringExtra(EXTRA_CONNECT_MODE)
                ?.let(EasyTierNetworkMode::fromCloudControl)
                ?: config.defaultMode
            val roomId = EasyTierSessionController.resolveRequestedRoomId(
                mode = mode,
                requestedRoomId = intent.getStringExtra(EXTRA_ROOM_ID).orEmpty(),
            )
            val userInitiated = intent.getBooleanExtra(EXTRA_USER_INITIATED, false)
            val roomDescriptionWhenCreating =
                intent.getStringExtra(EXTRA_ROOM_DESCRIPTION).orEmpty()
            val allowNewJoinsWhenCreating = if (intent.hasExtra(EXTRA_ALLOW_NEW_JOINS)) {
                intent.getBooleanExtra(EXTRA_ALLOW_NEW_JOINS, true)
            } else {
                null
            }
            val createOnly = intent.getBooleanExtra(EXTRA_CREATE_ONLY, false)
            val roomPassword = intent.getStringExtra(EXTRA_ROOM_PASSWORD).orEmpty()

            if (!config.canConnect) {
                val snapshot = EasyTierSessionController.persistSnapshot(
                    context = applicationContext,
                    snapshot = EasyTierSessionController.buildFailureSnapshot(
                        previous = EasyTierSessionController.buildInitialSnapshot(
                            applicationContext,
                            config,
                        ).copy(
                            mode = mode,
                            roomId = roomId,
                            currentPlayerId = currentPlayerId,
                            userInitiated = userInitiated,
                        ),
                        summary = getString(R.string.main_easytier_config_missing),
                        failureCategory = EasyTierFailureCategory.ConfigMissing,
                    ),
                    extraLines = listOf("connect_blocked=config_unavailable"),
                )
                deliverSnapshot(applicationContext, receiver, RESULT_FAILURE, snapshot)
                updateNotification(getString(R.string.main_easytier_config_missing))
                return
            }

            val connectingSnapshot = EasyTierSessionController.persistSnapshot(
                context = applicationContext,
                snapshot = EasyTierSessionController.buildConnectingSnapshot(
                    config = config,
                    mode = mode,
                    roomId = roomId,
                    userInitiated = userInitiated,
                    currentPlayerId = currentPlayerId,
                ),
                extraLines = listOf("connect_requested=true"),
            )

            if (VpnService.prepare(applicationContext) != null) {
                val permissionSnapshot = EasyTierSessionController.persistSnapshot(
                    context = applicationContext,
                    snapshot = EasyTierSessionController.buildPermissionRequiredSnapshot(
                        applicationContext,
                        connectingSnapshot,
                    ),
                    extraLines = listOf("vpn_prepare_required=true"),
                )
                deliverSnapshot(applicationContext, receiver, RESULT_PERMISSION_REQUIRED, permissionSnapshot)
                updateNotification(getString(R.string.main_easytier_notification_permission_required))
                return
            }

            deliverSnapshot(applicationContext, receiver, RESULT_CONNECTING, connectingSnapshot)
            updateNotification(getString(R.string.main_easytier_notification_connecting))

            var startedSessionConfig: EasyTierRoomSessionConfig? = null
            val resolvedSnapshot = if (roomId.isNotBlank()) {
                runCatching {
                    val playerName = LauncherConfig.readPlayerName(applicationContext)
                        .trim()
                        .ifEmpty { LauncherConfig.DEFAULT_PLAYER_NAME }
                    val sessionConfig = EasyTierRoomApiClient(applicationContext).startSession(
                        roomId = roomId,
                        playerId = currentPlayerId,
                        displayName = playerName,
                        roomDescriptionWhenCreating = roomDescriptionWhenCreating,
                        allowNewJoinsWhenCreating = allowNewJoinsWhenCreating,
                        createOnly = createOnly,
                        password = roomPassword,
                        sessionToken = EasyTierCredentialStore.sessionToken(
                            applicationContext,
                            roomId,
                            currentPlayerId,
                        ),
                        ownerToken = EasyTierCredentialStore.ownerToken(applicationContext, roomId),
                        macAddress = EasyTierVirtualMacAddress.fromDeviceId(currentPlayerId),
                        mods = EasyTierModInventory.collect(applicationContext),
                    )
                    EasyTierCredentialStore.save(
                        context = applicationContext,
                        roomId = sessionConfig.roomId,
                        playerId = currentPlayerId,
                        sessionToken = sessionConfig.sessionToken,
                        ownerToken = sessionConfig.ownerToken,
                    )
                    if (roomPassword.isNotEmpty()) {
                        // The server accepted it, so remember it and stop prompting on re-entry.
                        EasyTierCredentialStore.saveRoomPassword(
                            context = applicationContext,
                            roomId = sessionConfig.roomId,
                            password = roomPassword,
                        )
                    }
                    startedSessionConfig = sessionConfig
                    val sessionStatus = runCatching {
                        EasyTierRoomApiClient(applicationContext).fetchSessionStatus(
                            sessionConfig.sessionId,
                            sessionConfig.sessionToken,
                        )
                    }.getOrNull()
                    val roomInfo = fetchRoomInfoOrNull(sessionConfig.roomId)
                    applyEasyTierRoomInfo(
                        snapshot = EasyTierSessionController.buildSessionReadySnapshot(
                            previous = connectingSnapshot,
                            sessionConfig = sessionConfig,
                            sessionStatus = sessionStatus,
                            currentPlayerId = currentPlayerId,
                        ),
                        roomInfo = roomInfo,
                    )
                }.getOrElse { error ->
                    val failureSnapshot = EasyTierSessionController.persistSnapshot(
                        context = applicationContext,
                        snapshot = EasyTierSessionController.buildFailureSnapshot(
                            previous = connectingSnapshot,
                            summary = localizedRoomSessionStartFailure(error),
                        ),
                        extraLines = listOf("room_session_start_failed=true"),
                        error = error,
                    )
                    deliverSnapshot(applicationContext, receiver, RESULT_FAILURE, failureSnapshot)
                    updateNotification(failureSnapshot.lastErrorSummary)
                    return
                }
            } else {
                connectingSnapshot
            }

            val sessionReadySnapshot = EasyTierSessionController.persistSnapshot(
                context = applicationContext,
                snapshot = resolvedSnapshot,
                extraLines = buildList {
                    add("runtime_bridge_integrated=true")
                    add("runtime_bridge_type=android_jni")
                    if (resolvedSnapshot.sessionId.isNotBlank()) {
                        add("room_session_started=true")
                    }
                },
            )
            deliverSnapshot(applicationContext, receiver, RESULT_SESSION_READY, sessionReadySnapshot)
            notificationMessageForSnapshot(sessionReadySnapshot)?.let(::updateNotification)

            val runtimeSessionConfig = startedSessionConfig ?: EasyTierRoomSessionConfig(
                sessionId = sessionReadySnapshot.sessionId,
                roomId = sessionReadySnapshot.roomId.ifBlank { roomId },
                mode = sessionReadySnapshot.mode,
                entryNodeUrl = sessionReadySnapshot.entryNodeUrl,
                configServerUrl = sessionReadySnapshot.configServerUrl,
                aclGroup = sessionReadySnapshot.aclGroup,
                networkSecret = "",
                expiresAtEpochSeconds = sessionReadySnapshot.expiresAtEpochSeconds,
            )
            val runtimeStart = EasyTierRuntimeBridge.startNetworkInstance(
                context = applicationContext,
                sessionConfig = runtimeSessionConfig,
                playerId = currentPlayerId,
            )
            when (runtimeStart) {
                is EasyTierRuntimeStartResult.Failed -> {
                    if (runtimeSessionConfig.sessionId.isNotBlank()) {
                        runCatching {
                            EasyTierRoomApiClient(applicationContext).stopSession(
                                runtimeSessionConfig.sessionId,
                                EasyTierCredentialStore.sessionToken(
                                    applicationContext,
                                    runtimeSessionConfig.roomId,
                                    currentPlayerId,
                                ),
                            )
                        }.onFailure { error ->
                            Log.w(TAG, "Failed to stop EasyTier room session ${runtimeSessionConfig.sessionId}", error)
                        }
                    }
                    val summary = localizedRuntimeFailureSummary(runtimeStart)
                    val failureSnapshot = EasyTierSessionController.persistSnapshot(
                        context = applicationContext,
                        snapshot = EasyTierSessionController.buildFailureSnapshot(
                            previous = sessionReadySnapshot,
                            summary = summary,
                            failureCategory = runtimeStart.failureCategory,
                        ),
                        extraLines = buildList {
                            add("runtime_instance_start_failed=true")
                            add("runtime_failure_category=${runtimeStart.failureCategory.name}")
                        },
                        error = runtimeStart.error,
                    )
                    deliverSnapshot(applicationContext, receiver, RESULT_FAILURE, failureSnapshot)
                    updateNotification(summary)
                    return
                }
                is EasyTierRuntimeStartResult.Started -> {
                    currentSessionId = sessionReadySnapshot.sessionId
                    currentRuntimeInstanceName = runtimeStart.config.instanceName
                    currentVpnRouteSignature = ""
                    runtimeReportUnsupported = false
                    EasyTierSessionController.persistSnapshot(
                        context = applicationContext,
                        snapshot = sessionReadySnapshot,
                        extraLines = listOf(
                            "runtime_instance_started=true",
                            "runtime_instance_name=${runtimeStart.config.instanceName}",
                            "runtime_network_name=${runtimeStart.config.networkName}",
                            "runtime_peer_urls=${runtimeStart.config.peerUrls.joinToString(",")}",
                        ),
                    )
                }
            }
            startStatusPolling(receiver)
            return
        } finally {
            if (currentSessionId.isBlank()) {
                running = false
                stopForegroundCompat()
                stopSelf(startId)
            }
        }
    }

    private fun runDisconnect(
        startId: Int,
        receiver: ResultReceiver?,
    ) {
        val previous = EasyTierSessionController.currentSnapshot(applicationContext)
        val stopResult = previous.sessionId.takeIf { it.isNotBlank() }?.let { sessionId ->
            runCatching {
                EasyTierRoomApiClient(applicationContext).stopSession(
                    sessionId,
                    EasyTierCredentialStore.sessionToken(
                        applicationContext,
                        previous.roomId,
                        previous.currentPlayerId,
                    ),
                )
            }.onFailure { error ->
                Log.w(TAG, "Failed to stop EasyTier room session $sessionId", error)
            }
        }
        val clearSessionCredential = shouldClearEasyTierSessionCredential(
            stopSucceeded = stopResult?.isSuccess == true,
            stopFailureStatusCode = (stopResult?.exceptionOrNull() as? EasyTierRoomApiHttpException)
                ?.statusCode,
        )
        StsEasyTierVpnService.stopSession(applicationContext)
        EasyTierJniBridge.stopAllInstances(applicationContext).exceptionOrNull()?.let { error ->
            Log.w(TAG, "Failed to stop EasyTier runtime instances", error)
        }
        val snapshot = EasyTierSessionController.persistSnapshot(
            context = applicationContext,
            snapshot = EasyTierSessionController.buildDisconnectedSnapshot(
                previous = previous,
            ),
            extraLines = buildList {
                add("disconnect_requested=true")
                add("runtime_stop_requested=true")
                if (previous.sessionId.isNotBlank()) {
                    add("room_session_stop_requested=true")
                }
                add("session_credential_cleared=$clearSessionCredential")
            },
        )
        if (clearSessionCredential &&
            previous.roomId.isNotBlank() &&
            previous.currentPlayerId.isNotBlank()
        ) {
            EasyTierCredentialStore.clearSession(
                applicationContext,
                previous.roomId,
                previous.currentPlayerId,
            )
        }
        deliverSnapshot(applicationContext, receiver, RESULT_DISCONNECTED, snapshot)
        stopForegroundCompat()
        stopSelf(startId)
    }

    private fun resultCodeFor(snapshot: EasyTierConnectionSnapshot): Int = when (snapshot.status) {
        EasyTierConnectionStatus.CONNECTING -> RESULT_CONNECTING
        EasyTierConnectionStatus.SESSION_READY -> RESULT_SESSION_READY
        EasyTierConnectionStatus.CONNECTED -> RESULT_CONNECTED
        EasyTierConnectionStatus.RECONNECTING -> RESULT_RECONNECTING
        EasyTierConnectionStatus.PERMISSION_REQUIRED -> RESULT_PERMISSION_REQUIRED
        EasyTierConnectionStatus.IDLE,
        EasyTierConnectionStatus.DISCONNECTING,
        EasyTierConnectionStatus.DISCONNECTED -> RESULT_DISCONNECTED
        EasyTierConnectionStatus.FAILED -> RESULT_FAILURE
    }

    private fun updateNotification(message: String) {
        val notificationId = if (StsEasyTierVpnService.isForegroundSessionActive()) {
            EasyTierForegroundNotification.VPN_NOTIFICATION_ID
        } else {
            EasyTierForegroundNotification.CONNECT_NOTIFICATION_ID
        }
        EasyTierForegroundNotification.notify(this, notificationId, message)
    }

    private fun notificationMessageForSnapshot(snapshot: EasyTierConnectionSnapshot): String? {
        return easyTierNotificationMessage(
            snapshot = snapshot,
            resolveString = ::getString,
            unknownErrorMessage = getString(R.string.main_easytier_unknown_error),
        )
    }

    private fun localizedRuntimeFailureSummary(result: EasyTierRuntimeStartResult.Failed): String {
        return when (result.failureCategory) {
            EasyTierFailureCategory.RuntimeBridgeUnavailable ->
                result.summary.ifBlank { getString(R.string.main_easytier_runtime_bridge_unavailable) }
            else -> result.summary.ifBlank { getString(R.string.main_easytier_unknown_error) }
        }
    }

    private fun localizedRoomSessionStartFailure(error: Throwable): String {
        val apiError = error as? EasyTierRoomApiHttpException
        return when {
            // Prefer the server's stable error code over matching prose, which varies by locale.
            apiError?.errorCode == "lan_room_password_required" ->
                getString(R.string.main_easytier_error_room_password_required)
            apiError?.errorCode == "lan_room_password_invalid" ->
                getString(R.string.main_easytier_error_room_password_invalid)
            apiError?.errorCode == "lan_room_password_throttled" ->
                getString(R.string.main_easytier_error_room_password_throttled)
            apiError?.errorCode == "lan_client_version_unsupported" || apiError?.statusCode == 426 ->
                getString(R.string.main_easytier_error_client_version_unsupported)
            apiError?.statusCode == 409 &&
                apiError.message.orEmpty().contains("room already exists", ignoreCase = true) ->
                getString(R.string.main_easytier_error_room_exists)
            apiError?.statusCode == 403 &&
                apiError.message.orEmpty().contains("Existing player credential", ignoreCase = true) ->
                getString(R.string.main_easytier_error_existing_session)
            apiError?.statusCode == 403 &&
                apiError.message.orEmpty().contains("not accepting new joins", ignoreCase = true) ->
                getString(R.string.main_easytier_error_room_locked)
            apiError?.statusCode == 404 || apiError?.statusCode == 410 ->
                getString(R.string.main_easytier_error_room_unavailable)
            else -> error.message?.trim().takeUnless { it.isNullOrEmpty() }
                ?: getString(R.string.main_easytier_unknown_error)
        }
    }

    private fun releaseConnectForegroundInternal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun extractResultReceiver(intent: Intent): ResultReceiver? {
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_RECEIVER)
        }
    }

    private fun startStatusPolling(receiver: ResultReceiver?) {
        stopStatusPolling(clearRuntimeState = false)
        val intervalMs = EasyTierConfigRepository.current().statusPollIntervalSeconds.coerceAtLeast(1) * 1000L
        val runnable = object : Runnable {
            override fun run() {
                val sessionId = currentSessionId
                if (!running || sessionId.isBlank()) {
                    return
                }
                runStatusPollIteration(sessionId, receiver)
                if (running && currentSessionId == sessionId) {
                    statusPollHandler.postDelayed(this, intervalMs)
                }
            }
        }
        statusPollRunnable = runnable
        // Lease renewals must keep running after the launcher Activity is destroyed for game launch.
        statusPollHandler.post(runnable)
    }

    private fun runStatusPollIteration(
        sessionId: String,
        receiver: ResultReceiver?,
    ) {
        val current = EasyTierSessionController.currentSnapshot(applicationContext)
        if (hasEasyTierConnectionTimedOut(current, EasyTierConfigRepository.current())) {
            handleConnectionTimeout(current, receiver)
            return
        }
        runCatching {
            EasyTierRoomApiClient(applicationContext).fetchSessionStatus(
                sessionId,
                EasyTierCredentialStore.sessionToken(
                    applicationContext,
                    current.roomId,
                    current.currentPlayerId,
                ),
            )
        }.onSuccess { sessionStatus ->
            val normalizedSessionState = sessionStatus.sessionState.trim().lowercase()
            val normalizedRoomState = sessionStatus.roomState.trim().lowercase()
            val terminalSession = isTerminalSessionState(
                sessionState = normalizedSessionState,
                roomState = normalizedRoomState,
            )
            if (terminalSession) {
                handleTerminalSessionState(
                    current = current,
                    sessionStatus = sessionStatus,
                    receiver = receiver,
                )
                return@onSuccess
            }

            val nextStatus = resolveEasyTierPollSuccessStatus(
                sessionState = normalizedSessionState,
                current = current,
            )
            val roomInfo = fetchRoomInfoOrNull(current.roomId.ifBlank { sessionStatus.roomId })
            val updatedSnapshot = EasyTierSessionController.persistSnapshot(
                context = applicationContext,
                snapshot = applyEasyTierRoomInfo(
                    snapshot = current.copy(
                        status = nextStatus,
                        // Older servers do not report the runtime IP. Do not erase the local
                        // EasyTier address between runtime polls, or the launcher card flickers.
                        assignedIpv4Cidr = resolveEasyTierAssignedIpv4Cidr(
                            currentValue = current.assignedIpv4Cidr,
                            reportedValue = sessionStatus.assignedIpv4Cidr,
                        ),
                        currentPlayerId = current.currentPlayerId.ifBlank {
                            buildStablePlayerId(applicationContext)
                        },
                        peerCount = sessionStatus.peerCount,
                        relayServerDescription = sessionStatus.relayServerDescription,
                        lastSessionState = sessionStatus.sessionState.trim(),
                        lastRoomState = sessionStatus.roomState.trim(),
                        lastUpdatedAtMs = System.currentTimeMillis(),
                        lastErrorSummary = "",
                    ),
                    roomInfo = roomInfo,
                ),
                extraLines = listOf(
                    "session_state=${sessionStatus.sessionState}",
                    "room_state=${sessionStatus.roomState}",
                ),
            )
            deliverSnapshot(
                applicationContext,
                receiver,
                resultCodeFor(updatedSnapshot),
                updatedSnapshot,
            )
            notificationMessageForSnapshot(updatedSnapshot)?.let(::updateNotification)
            pollRuntimeInfo(updatedSnapshot, receiver)
        }.onFailure { error ->
            // Only tear the session down when the server actually said the session (or its room)
            // is gone. Any other 404 is treated as a transient failure so the poll loop can retry
            // instead of ending a session that may still be alive.
            if (error is EasyTierRoomApiHttpException && isEasyTierSessionGone(error)) {
                handleTerminalSessionState(
                    current = current,
                    sessionStatus = null,
                    receiver = receiver,
                )
                return@onFailure
            }
            val failedSnapshot = EasyTierSessionController.persistSnapshot(
                context = applicationContext,
                snapshot = EasyTierSessionController.buildFailureSnapshot(
                    previous = current,
                    summary = error.message?.trim().takeUnless { it.isNullOrEmpty() }
                        ?: getString(R.string.main_easytier_unknown_error),
                ),
                extraLines = listOf("session_status_poll_failed=true"),
                error = error,
            )
            deliverSnapshot(applicationContext, receiver, RESULT_FAILURE, failedSnapshot)
            notificationMessageForSnapshot(failedSnapshot)?.let(::updateNotification)
        }
    }

    private fun handleTerminalSessionState(
        current: EasyTierConnectionSnapshot,
        sessionStatus: EasyTierSessionStatusSnapshot?,
        receiver: ResultReceiver?,
    ) {
        val terminalSessionState = sessionStatus?.sessionState?.trim()?.lowercase().orEmpty()
        val kicked = terminalSessionState == "kicked"
        val summary = if (kicked) {
            easyTierKickedSummary(applicationContext, sessionStatus?.kickMessage.orEmpty())
        } else {
            ""
        }
        running = false
        stopStatusPolling(clearRuntimeState = false)
        StsEasyTierVpnService.stopSession(applicationContext)
        EasyTierJniBridge.stopAllInstances(applicationContext).exceptionOrNull()?.let { error ->
            Log.w(TAG, "Failed to stop EasyTier runtime instances after terminal session state", error)
        }
        if (current.roomId.isNotBlank() && current.currentPlayerId.isNotBlank()) {
            EasyTierCredentialStore.clearSession(
                applicationContext,
                current.roomId,
                current.currentPlayerId,
            )
        }
        val snapshot = EasyTierSessionController.persistSnapshot(
            context = applicationContext,
            snapshot = EasyTierSessionController.buildDisconnectedSnapshot(
                previous = current,
                summary = summary,
                failureCategory = if (kicked) {
                    EasyTierFailureCategory.SessionKicked
                } else {
                    EasyTierFailureCategory.None
                },
                terminalSessionState = terminalSessionState,
            ),
            extraLines = listOf(
                "terminal_session_state=true",
                // Distinguishes the two ways this path is reached. Without it, the Room API 404
                // branch (sessionStatus == null) and a genuinely blank server state both render as
                // an empty value, which makes a disconnect report unattributable after the fact.
                "terminal_session_state_source=${if (sessionStatus == null) "room_api_session_missing" else "server_reported"}",
                "terminal_session_state_value=${terminalSessionState.ifBlank { "<blank>" }}",
                "removed_by_room_owner=$kicked",
            ),
        )
        deliverSnapshot(applicationContext, receiver, RESULT_DISCONNECTED, snapshot)
        stopForegroundCompat()
        stopSelf()
    }

    private fun pollRuntimeInfo(
        baseSnapshot: EasyTierConnectionSnapshot,
        receiver: ResultReceiver?,
    ) {
        val instanceName = currentRuntimeInstanceName
        if (instanceName.isBlank()) {
            return
        }
        val runtimeResult = EasyTierJniBridge.collectNetworkInfo(applicationContext, instanceName)
        runtimeResult.exceptionOrNull()?.let { error ->
            // The lease must outlive a transient runtime fault. Dropping it here is what turned a
            // brief JNI hiccup into a permanent disconnect: the server swept the session after its
            // TTL and every later request came back 404.
            renewSessionLease(baseSnapshot)
            val summary = EasyTierJniBridge.failureSummary(error)
            val failedSnapshot = EasyTierSessionController.persistSnapshot(
                context = applicationContext,
                snapshot = EasyTierSessionController.buildFailureSnapshot(
                    previous = baseSnapshot,
                    summary = summary,
                    failureCategory = EasyTierJniBridge.failureCategory(error),
                ),
                extraLines = listOf(
                    "runtime_info_poll_failed=true",
                    "runtime_instance_name=$instanceName",
                ),
                error = error,
            )
            deliverSnapshot(applicationContext, receiver, RESULT_FAILURE, failedSnapshot)
            notificationMessageForSnapshot(failedSnapshot)?.let(::updateNotification)
            return
        }

        val runtimeInfo = runtimeResult.getOrNull() ?: run {
            // A success result with no payload still means the session is in use, so hold the lease
            // rather than letting it lapse on an empty read.
            renewSessionLease(baseSnapshot)
            return
        }
        if (!runtimeInfo.running) {
            // Keep the server-side session alive while the runtime restarts itself. Without this
            // the session expired after its TTL and the reconnect could never succeed.
            renewSessionLease(baseSnapshot)
            val runtimeError = runtimeInfo.errorMessage.ifBlank {
                getString(R.string.main_easytier_notification_reconnecting)
            }
            val reconnectingSnapshot = EasyTierSessionController.persistSnapshot(
                context = applicationContext,
                snapshot = baseSnapshot.copy(
                    status = EasyTierConnectionStatus.RECONNECTING,
                    lastUpdatedAtMs = System.currentTimeMillis(),
                    lastErrorSummary = runtimeInfo.errorMessage,
                ),
                extraLines = listOf(
                    "runtime_instance_running=false",
                    "runtime_instance_name=$instanceName",
                    // The Rust runtime reporting "not running" while this process is alive means the
                    // instance died in-process rather than the whole process being reclaimed. That
                    // distinction is what separates a runtime fault from an LMK kill.
                    "runtime_stopped_while_service_alive=true",
                    "runtime_error=${runtimeInfo.errorMessage.ifBlank { "<empty>" }}",
                ),
            )
            deliverSnapshot(applicationContext, receiver, RESULT_RECONNECTING, reconnectingSnapshot)
            updateNotification(runtimeError)
            return
        }

        if (runtimeInfo.virtualIpv4Cidr.isBlank()) {
            // No virtual IP yet. The session is still legitimately in use, so hold the lease while
            // the runtime finishes negotiating an address.
            renewSessionLease(baseSnapshot)
            if (hasEasyTierConnectionTimedOut(baseSnapshot, EasyTierConfigRepository.current())) {
                handleConnectionTimeout(baseSnapshot, receiver)
            }
            return
        }
        val routeCidrs = runtimeInfo.routeCidrs.ifEmpty {
            normalizeEasyTierIpv4Route(runtimeInfo.virtualIpv4Cidr)
                ?.cidr
                ?.let(::listOf)
                .orEmpty()
        }
        val routeSignature = routeCidrs.joinToString("|")
        if (routeSignature.isNotBlank() && routeSignature != currentVpnRouteSignature) {
            currentVpnRouteSignature = routeSignature
            StsEasyTierVpnService.startSession(
                context = applicationContext,
                instanceName = instanceName,
                ipv4Cidr = runtimeInfo.virtualIpv4Cidr,
                routeCidrs = routeCidrs,
            )
        }
        val runtimeStatus = reportSessionRuntimeOrNull(
            snapshot = baseSnapshot,
            assignedIpv4Cidr = runtimeInfo.virtualIpv4Cidr,
        )
        // The VPN worker can finish while this poll is blocked on the Room API. Preserve its
        // CONNECTED snapshot instead of overwriting it with the stale SESSION_READY snapshot.
        val snapshotBeforePersist = selectRuntimePollBaseSnapshot(
            polledSnapshot = baseSnapshot,
            latestSnapshot = EasyTierSessionController.currentSnapshot(applicationContext),
        )
        val updatedSnapshot = EasyTierSessionController.persistSnapshot(
            context = applicationContext,
            snapshot = applyLocalEasyTierOwnerIpv4(
                snapshotBeforePersist.copy(
                    assignedIpv4Cidr = runtimeStatus?.assignedIpv4Cidr ?: runtimeInfo.virtualIpv4Cidr,
                    peerCount = runtimeInfo.peerCount ?: runtimeStatus?.peerCount ?: snapshotBeforePersist.peerCount,
                    relayServerDescription = runtimeStatus?.relayServerDescription
                        ?: snapshotBeforePersist.relayServerDescription,
                    lastSessionState = runtimeStatus?.sessionState?.trim()
                        ?: snapshotBeforePersist.lastSessionState,
                    lastRoomState = runtimeStatus?.roomState?.trim()
                        ?: snapshotBeforePersist.lastRoomState,
                    lastUpdatedAtMs = System.currentTimeMillis(),
                    lastErrorSummary = "",
                ),
                assignedIpv4Cidr = runtimeInfo.virtualIpv4Cidr,
            ),
            extraLines = buildList {
                add("runtime_info_poll_success=true")
                add("runtime_instance_name=$instanceName")
                add("runtime_virtual_ipv4_cidr=${runtimeInfo.virtualIpv4Cidr}")
                add("runtime_routes=${routeCidrs.joinToString(",")}")
            },
        )
        deliverSnapshot(applicationContext, receiver, resultCodeFor(updatedSnapshot), updatedSnapshot)
        notificationMessageForSnapshot(updatedSnapshot)?.let(::updateNotification)
    }

    private fun fetchRoomInfoOrNull(roomId: String): EasyTierRoomInfo? {
        if (roomId.isBlank()) {
            return null
        }
        return runCatching {
            EasyTierRoomApiClient(applicationContext).fetchRoomInfo(roomId)
        }.onFailure { error ->
            Log.w(TAG, "Failed to fetch EasyTier room info for $roomId", error)
        }.getOrNull()
    }

    /**
     * Renews the server-side session lease without requiring a healthy runtime.
     *
     * The Room API treats runtime reports as the only lease heartbeat, so a client that stops
     * reporting loses its session once the TTL elapses. Reporting only on the happy path meant the
     * lease lapsed exactly when the runtime was struggling, and the resulting 404 turned a
     * recoverable hiccup into a permanent disconnect.
     *
     * The last known address is reused because the server rejects a mismatched CIDR for sessions
     * with a static assignment; when nothing is known yet there is nothing to keep alive.
     */
    private fun renewSessionLease(snapshot: EasyTierConnectionSnapshot) {
        val assignedIpv4Cidr = snapshot.assignedIpv4Cidr.trim()
        if (assignedIpv4Cidr.isBlank()) {
            return
        }
        reportSessionRuntimeOrNull(
            snapshot = snapshot,
            assignedIpv4Cidr = assignedIpv4Cidr,
        )
    }

    private fun reportSessionRuntimeOrNull(
        snapshot: EasyTierConnectionSnapshot,
        assignedIpv4Cidr: String,
    ): EasyTierSessionStatusSnapshot? {
        if (runtimeReportUnsupported) {
            return null
        }
        if (!shouldReportEasyTierRuntime(
                snapshot = snapshot,
                assignedIpv4Cidr = assignedIpv4Cidr,
            )
        ) {
            return null
        }
        return try {
            EasyTierRoomApiClient(applicationContext).reportSessionRuntime(
                sessionId = snapshot.sessionId,
                sessionToken = EasyTierCredentialStore.sessionToken(
                    applicationContext,
                    snapshot.roomId,
                    snapshot.currentPlayerId,
                ),
                assignedIpv4Cidr = assignedIpv4Cidr,
                relayServerDescription = snapshot.relayServerDescription,
            )
        } catch (error: EasyTierRoomApiHttpException) {
            if (error.isPossiblyUnimplementedEndpoint) {
                // A 404 with no server error code is the only response that still looks like an
                // older server without this endpoint. A labelled "session not found" must not land
                // here: disabling renewal on it guaranteed the session stayed dead.
                runtimeReportUnsupported = true
                Log.i(TAG, "Room API does not support runtime reports; continuing with local connection state")
            } else if (error.isSessionMissing) {
                Log.w(
                    TAG,
                    "EasyTier session ${snapshot.sessionId} no longer exists on the server",
                    error,
                )
            } else {
                Log.w(TAG, "Failed to report EasyTier runtime for session ${snapshot.sessionId}", error)
            }
            null
        } catch (error: Exception) {
            Log.w(TAG, "Failed to report EasyTier runtime for session ${snapshot.sessionId}", error)
            null
        }
    }

    private fun stopStatusPolling(clearRuntimeState: Boolean = true) {
        statusPollRunnable?.let(statusPollHandler::removeCallbacks)
        statusPollRunnable = null
        if (clearRuntimeState) {
            currentSessionId = ""
            currentRuntimeInstanceName = ""
            currentVpnRouteSignature = ""
            runtimeReportUnsupported = false
        }
    }

    private fun handleConnectionTimeout(
        current: EasyTierConnectionSnapshot,
        receiver: ResultReceiver?,
    ) {
        running = false
        stopStatusPolling(clearRuntimeState = false)
        val stopResult = current.sessionId.takeIf { it.isNotBlank() }?.let { sessionId ->
            runCatching {
                EasyTierRoomApiClient(applicationContext).stopSession(
                    sessionId,
                    EasyTierCredentialStore.sessionToken(
                        applicationContext,
                        current.roomId,
                        current.currentPlayerId,
                    ),
                )
            }.onFailure { error ->
                Log.w(TAG, "Failed to release timed-out EasyTier session $sessionId", error)
            }
        }
        val clearSessionCredential = shouldClearEasyTierSessionCredential(
            stopSucceeded = stopResult?.isSuccess == true,
            stopFailureStatusCode = (stopResult?.exceptionOrNull() as? EasyTierRoomApiHttpException)
                ?.statusCode,
        )
        StsEasyTierVpnService.stopSession(applicationContext)
        EasyTierJniBridge.stopAllInstances(applicationContext)
        if (clearSessionCredential &&
            current.roomId.isNotBlank() &&
            current.currentPlayerId.isNotBlank()
        ) {
            EasyTierCredentialStore.clearSession(
                applicationContext,
                current.roomId,
                current.currentPlayerId,
            )
        }
        val summary = "EasyTier did not receive a virtual IP before the connection timeout."
        val failedSnapshot = EasyTierSessionController.persistSnapshot(
            context = applicationContext,
            snapshot = EasyTierSessionController.buildFailureSnapshot(current, summary),
            extraLines = listOf(
                "connection_timeout=true",
                "session_credential_cleared=$clearSessionCredential",
            ),
        )
        deliverSnapshot(applicationContext, receiver, RESULT_FAILURE, failedSnapshot)
        updateNotification(summary)
        stopForegroundCompat()
        stopSelf()
    }

    private fun buildStablePlayerId(context: Context): String {
        return io.stamethyst.backend.presence.GamePresenceClient.resolveIdentityPayload(context).deviceId
    }
}

/**
 * True when this session's VPN tunnel is up, so a healthy poll may report it as connected.
 *
 * The status poll cannot establish a tunnel, so it must not be the thing that decides a session is
 * connected from scratch — that stays [StsEasyTierVpnService]'s job. What it does need to do is
 * recognise an existing tunnel, because the poll rewrites the status on every iteration and the
 * previous rule only preserved [EasyTierConnectionStatus.CONNECTED] when the status already was
 * `CONNECTED`. That rule is self-referential: after any dip into
 * [EasyTierConnectionStatus.FAILED] the session could never be reported as connected again, since
 * the only other writer of `CONNECTED` is the VPN service and it is skipped while the routes are
 * unchanged. A session that had merely timed out once therefore recovered every part of its state
 * except its status, and stayed permanently stuck on [EasyTierConnectionStatus.SESSION_READY].
 *
 * That was not only cosmetic. `CONNECTED` gates the Together in Spire launch properties, the in-game
 * room state reports and the mod inventory reports, so the downgrade silently withheld the host
 * address from the game and stopped the server hearing about this member.
 *
 * Both fields are required. [EasyTierConnectionSnapshot.connectedAtMs] proves the tunnel was
 * established at some point, and a non-blank [EasyTierConnectionSnapshot.assignedIpv4Cidr] proves it
 * still holds an address — the revoke and disconnect paths clear the address precisely so that a
 * torn-down tunnel cannot be mistaken for a live one here.
 */
internal fun hasEasyTierEstablishedTunnel(
    snapshot: EasyTierConnectionSnapshot,
): Boolean = snapshot.connectedAtMs != null && snapshot.assignedIpv4Cidr.isNotBlank()

/**
 * Maps a non-terminal server session state onto the status a successful poll should publish.
 *
 * Terminal states are handled before this is reached, so only the live states are mapped here. An
 * unrecognised state is treated as [EasyTierConnectionStatus.RECONNECTING] rather than connected,
 * because the server is reporting something this client does not model.
 */
internal fun resolveEasyTierPollSuccessStatus(
    sessionState: String,
    current: EasyTierConnectionSnapshot,
): EasyTierConnectionStatus = when (sessionState) {
    "issued", "connected" -> if (hasEasyTierEstablishedTunnel(current)) {
        EasyTierConnectionStatus.CONNECTED
    } else {
        EasyTierConnectionStatus.SESSION_READY
    }
    else -> EasyTierConnectionStatus.RECONNECTING
}

/**
 * True when a session that has never finished connecting has exhausted its connect budget.
 *
 * The budget only applies to the initial handshake. A session that already reached a working tunnel
 * is never timed out here, no matter how long ago it started: [EasyTierConnectionSnapshot.startedAtMs]
 * is set once when connecting begins and is never refreshed, so for an established session the
 * elapsed time is the age of the session rather than the age of the current problem. Treating
 * [EasyTierConnectionStatus.FAILED] as timeout-eligible on that stale clock meant a single request
 * failure after the budget had passed — which is every failure on a session older than a few
 * seconds — tore the tunnel down on the very next poll with no grace period at all.
 *
 * Recovery from a broken established session is the retry loop's job, not this check's: the poll loop
 * keeps renewing the lease and the server's own TTL is the backstop if the client is truly gone.
 */
internal fun hasEasyTierConnectionTimedOut(
    snapshot: EasyTierConnectionSnapshot,
    config: EasyTierResolvedConfig,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    val startedAtMs = snapshot.startedAtMs ?: return false
    if (snapshot.status == EasyTierConnectionStatus.CONNECTED &&
        snapshot.assignedIpv4Cidr.isNotBlank()
    ) {
        return false
    }
    // A tunnel that was established at any point takes this check out of play. Without it the
    // connect budget doubles as a kill switch for established sessions.
    if (snapshot.connectedAtMs != null) {
        return false
    }
    return snapshot.status in setOf(
        EasyTierConnectionStatus.CONNECTING,
        EasyTierConnectionStatus.SESSION_READY,
        EasyTierConnectionStatus.RECONNECTING,
        EasyTierConnectionStatus.FAILED,
    ) && nowMs - startedAtMs >= config.connectTimeoutSeconds.coerceAtLeast(1) * 1000L
}

internal fun shouldClearEasyTierSessionCredential(
    stopSucceeded: Boolean,
    stopFailureStatusCode: Int? = null,
): Boolean = stopSucceeded || stopFailureStatusCode == 404

internal fun applyEasyTierRoomInfo(
    snapshot: EasyTierConnectionSnapshot,
    roomInfo: EasyTierRoomInfo?,
): EasyTierConnectionSnapshot {
    if (roomInfo == null) {
        return snapshot
    }
    val ownerPlayerId = roomInfo.ownerPlayerId.trim()
    val ownerMember = roomInfo.members.firstOrNull { it.playerId.trim() == ownerPlayerId }
        ?: roomInfo.members.firstOrNull { it.role.trim().equals("owner", ignoreCase = true) }
    val ownerIpv4Cidr = when {
        ownerPlayerId.isBlank() -> ""
        ownerPlayerId == snapshot.currentPlayerId && snapshot.assignedIpv4Cidr.isNotBlank() ->
            snapshot.assignedIpv4Cidr
        else -> ownerMember?.assignedIpv4Cidr?.trim().orEmpty()
    }
    return snapshot.copy(
        roomOwnerPlayerId = ownerPlayerId,
        roomOwnerIpv4Cidr = ownerIpv4Cidr,
    )
}

internal fun applyLocalEasyTierOwnerIpv4(
    snapshot: EasyTierConnectionSnapshot,
    assignedIpv4Cidr: String,
): EasyTierConnectionSnapshot {
    val normalizedIpv4Cidr = assignedIpv4Cidr.trim()
    if (normalizedIpv4Cidr.isBlank()) {
        return snapshot
    }
    return if (
        snapshot.roomOwnerPlayerId.isNotBlank() &&
        snapshot.roomOwnerPlayerId == snapshot.currentPlayerId
    ) {
        snapshot.copy(roomOwnerIpv4Cidr = normalizedIpv4Cidr)
    } else {
        snapshot
    }
}

internal fun selectRuntimePollBaseSnapshot(
    polledSnapshot: EasyTierConnectionSnapshot,
    latestSnapshot: EasyTierConnectionSnapshot,
): EasyTierConnectionSnapshot {
    val isSameSession = polledSnapshot.sessionId.isNotBlank() &&
        polledSnapshot.sessionId == latestSnapshot.sessionId
    return if (isSameSession && latestSnapshot.status == EasyTierConnectionStatus.CONNECTED) {
        latestSnapshot
    } else {
        polledSnapshot
    }
}

internal fun resolveEasyTierAssignedIpv4Cidr(
    currentValue: String,
    reportedValue: String,
): String = reportedValue.trim().ifBlank { currentValue.trim() }

internal fun shouldReportEasyTierRuntime(
    snapshot: EasyTierConnectionSnapshot,
    assignedIpv4Cidr: String,
): Boolean = snapshot.sessionId.isNotBlank() && assignedIpv4Cidr.isNotBlank()

/**
 * True when the server told us this session (or the room behind it) no longer exists.
 *
 * A bare 404 is deliberately not enough. The status alone cannot distinguish "your session is
 * gone" from a proxy error or a server that never implemented the route, and tearing the session
 * down on the latter is what made a recoverable stall look like a permanent disconnect. Older
 * servers that predate the error codes still send an unlabelled 404, which is why that case is
 * kept as a session-gone signal for the status endpoint.
 */
internal fun isEasyTierSessionGone(error: EasyTierRoomApiHttpException): Boolean {
    if (error.statusCode != 404) {
        return false
    }
    return error.errorCode.isBlank() ||
        error.errorCode == EasyTierRoomApiHttpException.ERROR_CODE_SESSION_NOT_FOUND ||
        error.errorCode == "lan_room_not_found"
}

internal fun easyTierNotificationMessage(
    snapshot: EasyTierConnectionSnapshot,
    resolveString: (Int) -> String,
    unknownErrorMessage: String,
): String? {
    return when (snapshot.status) {
        EasyTierConnectionStatus.DISCONNECTED ->
            snapshot.lastErrorSummary.takeIf { it.isNotBlank() }
        EasyTierConnectionStatus.FAILED -> snapshot.lastErrorSummary.ifBlank { unknownErrorMessage }
        else -> easyTierNotificationMessageResIdForStatus(snapshot.status)?.let(resolveString)
    }
}

internal fun easyTierNotificationMessageResIdForStatus(status: EasyTierConnectionStatus): Int? {
    return when (status) {
        EasyTierConnectionStatus.PERMISSION_REQUIRED -> R.string.main_easytier_notification_permission_required
        EasyTierConnectionStatus.CONNECTING -> R.string.main_easytier_notification_connecting
        EasyTierConnectionStatus.SESSION_READY -> R.string.main_easytier_notification_runtime_starting
        EasyTierConnectionStatus.CONNECTED -> R.string.main_easytier_notification_connected
        EasyTierConnectionStatus.RECONNECTING -> R.string.main_easytier_notification_reconnecting
        EasyTierConnectionStatus.IDLE,
        EasyTierConnectionStatus.DISCONNECTING,
        EasyTierConnectionStatus.DISCONNECTED,
        EasyTierConnectionStatus.FAILED -> null
    }
}

internal fun isTerminalSessionState(
    sessionState: String,
    roomState: String,
): Boolean = roomState == "closed" ||
    sessionState == "expired" ||
    sessionState == "stopped" ||
    sessionState == "superseded" ||
    sessionState == "kicked"

internal fun easyTierKickedSummary(context: Context, message: String): String {
    val normalizedMessage = message.trim().take(EASY_TIER_KICK_MESSAGE_MAX_LENGTH)
    return if (normalizedMessage.isEmpty()) {
        context.getString(R.string.main_easytier_summary_session_kicked)
    } else {
        context.getString(R.string.main_easytier_summary_session_kicked_with_message, normalizedMessage)
    }
}
