package io.stamethyst.backend.presence

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.stamethyst.LauncherActivity
import io.stamethyst.backend.process.AppProcess
import io.stamethyst.config.CloudControlConfig
import io.stamethyst.config.LauncherConfig
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

object GamePresenceReporter {
    private const val DEFAULT_LAUNCH_MODE = "launcher"
    private const val MIN_SEND_INTERVAL_MS = 5_000L

    private val installed = AtomicBoolean(false)

    @JvmStatic
    fun install(application: Application) {
        val appContext = application.applicationContext
        if (!AppProcess.isDefaultProcess(appContext) || !installed.compareAndSet(false, true)) {
            return
        }
        val reporter = MainProcessPresenceReporter(appContext)
        application.registerActivityLifecycleCallbacks(reporter)
        reporter.start()
    }

    private class MainProcessPresenceReporter(
        private val context: Context
    ) : Application.ActivityLifecycleCallbacks {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        private var running = false
        private var connecting = false
        private var currentCall: Call? = null
        private var currentWebSocket: WebSocket? = null
        private var currentWebSocketUrl = ""
        private var webSocketReady = false
        private var webSocketMetadataSent = false
        private var lastMetadataSignature = ""
        private var lastSendAtMs = 0L

        private data class WebSocketHeartbeatFrame(
            val text: String,
            val metadataSignature: String?
        )

        private val heartbeatRunnable = object : Runnable {
            override fun run() {
                if (!running) {
                    return
                }
                sendHeartbeat(force = false)
                scheduleNext()
            }
        }

        fun start() {
            if (running) {
                return
            }
            running = true
            CloudControlConfig.addListener(cloudControlListener)
            if (CloudControlConfig.isStartupRefreshCompleted()) {
                sendHeartbeat(force = true)
            }
            scheduleNext()
        }

        private val cloudControlListener = {
            mainHandler.post {
                if (running) {
                    reconnectWebSocketIfEndpointChanged()
                    sendHeartbeat(force = true)
                    scheduleNext()
                }
            }
            Unit
        }

        private fun scheduleNext() {
            mainHandler.removeCallbacks(heartbeatRunnable)
            if (!running) {
                return
            }
            mainHandler.postDelayed(
                heartbeatRunnable,
                CloudControlConfig.current().heartbeatIntervalMs
            )
        }

        private fun sendHeartbeat(force: Boolean) {
            if (!running) {
                return
            }
            val now = System.currentTimeMillis()
            if (!force && now - lastSendAtMs < MIN_SEND_INTERVAL_MS) {
                return
            }
            val heartbeatEndpoint = CloudControlConfig.heartbeatWsUrl().trim()
            if (heartbeatEndpoint.isEmpty()) {
                return
            }
            val snapshot = GamePresenceStateMarker.readCurrentState(context)
            lastSendAtMs = now
            if (isWebSocketUrl(heartbeatEndpoint)) {
                if (!webSocketReady) {
                    connectWebSocket(heartbeatEndpoint)
                    return
                }
                val frame = try {
                    buildWebSocketFrame(snapshot)
                } catch (error: Throwable) {
                    return
                }
                if (sendWebSocketHeartbeat(frame, heartbeatEndpoint)) {
                    return
                }
            } else {
                closeWebSocket()
            }
            sendHttpHeartbeat(snapshot)
        }

        private fun sendWebSocketHeartbeat(frame: WebSocketHeartbeatFrame, websocketUrl: String): Boolean {
            if (!isWebSocketUrl(websocketUrl)) {
                closeWebSocket()
                return false
            }
            if (!webSocketReady) {
                connectWebSocket(websocketUrl)
                return true
            }
            val webSocket = currentWebSocket ?: return false
            return try {
                if (!webSocket.send(frame.text)) {
                    closeWebSocket()
                    connectWebSocket(websocketUrl)
                    return true
                }
                frame.metadataSignature?.let { signature ->
                    webSocketMetadataSent = true
                    lastMetadataSignature = signature
                }
                true
            } catch (error: Throwable) {
                closeWebSocket()
                true
            }
        }

        private fun sendHttpHeartbeat(snapshot: GamePresenceSnapshot) {
            if (currentCall != null) {
                return
            }
            currentCall = try {
                GamePresenceClient.sendHeartbeatAsync(
                    client = client,
                    context = context,
                    launchMode = snapshot.launchMode.ifBlank { DEFAULT_LAUNCH_MODE },
                    state = snapshot.state,
                    callback = GamePresenceClient.silentCallback {
                        mainHandler.post {
                            currentCall = null
                        }
                    }
                )
            } catch (error: Throwable) {
                currentCall = null
                null
            }
        }

        private fun connectWebSocket(websocketUrl: String) {
            if (connecting || webSocketReady) {
                return
            }
            connecting = true
            currentWebSocketUrl = websocketUrl
            try {
                currentWebSocket = client.newWebSocket(
                    GamePresenceClient.buildHeartbeatWebSocketRequest(websocketUrl),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            mainHandler.post {
                                if (!running || currentWebSocket !== webSocket) {
                                    webSocket.close(1000, null)
                                    return@post
                                }
                                connecting = false
                                webSocketReady = true
                                sendHeartbeat(force = true)
                            }
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            handleWebSocketClosed(webSocket)
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            handleWebSocketClosed(webSocket)
                        }
                    }
                )
            } catch (error: Throwable) {
                connecting = false
                currentWebSocket = null
                currentWebSocketUrl = ""
                webSocketReady = false
                webSocketMetadataSent = false
            }
        }

        private fun handleWebSocketClosed(webSocket: WebSocket) {
            mainHandler.post {
                if (currentWebSocket === webSocket) {
                    currentWebSocket = null
                    currentWebSocketUrl = ""
                    webSocketReady = false
                    webSocketMetadataSent = false
                }
                connecting = false
            }
        }

        private fun reconnectWebSocketIfEndpointChanged() {
            val websocketUrl = CloudControlConfig.heartbeatWsUrl().trim()
            if (currentWebSocketUrl.isNotEmpty() && currentWebSocketUrl != websocketUrl) {
                closeWebSocket()
            }
        }

        private fun closeWebSocket() {
            val webSocket = currentWebSocket
            currentWebSocket = null
            currentWebSocketUrl = ""
            webSocketReady = false
            webSocketMetadataSent = false
            connecting = false
            try {
                webSocket?.close(1000, null)
            } catch (_: Throwable) {
            }
        }

        private fun buildWebSocketFrame(snapshot: GamePresenceSnapshot): WebSocketHeartbeatFrame {
            val launchMode = snapshot.launchMode.ifBlank { DEFAULT_LAUNCH_MODE }
            val identity = GamePresenceClient.resolveIdentityPayload(context)
            val playerName = LauncherConfig.readPlayerName(context)
            val metadataSignature = GamePresenceClient.buildHeartbeatMetadataSignature(
                identity = identity,
                launchMode = launchMode,
                playerName = playerName
            )
            if (!webSocketMetadataSent || lastMetadataSignature != metadataSignature) {
                return WebSocketHeartbeatFrame(
                    text = GamePresenceClient.buildHeartbeatPayload(
                        identity = identity,
                        launchMode = launchMode,
                        state = snapshot.state,
                        playerName = playerName
                    ).toString(),
                    metadataSignature = metadataSignature
                )
            }
            return WebSocketHeartbeatFrame(
                text = GamePresenceClient.buildMinimalHeartbeatPayload(
                    identity = identity,
                    state = snapshot.state
                ).toString(),
                metadataSignature = null
            )
        }

        private fun isWebSocketUrl(value: String): Boolean =
            value.startsWith("ws://", ignoreCase = true) ||
                value.startsWith("wss://", ignoreCase = true)

        override fun onActivityResumed(activity: Activity) {
            if (activity is LauncherActivity) {
                sendHeartbeat(force = true)
            }
        }

        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}
