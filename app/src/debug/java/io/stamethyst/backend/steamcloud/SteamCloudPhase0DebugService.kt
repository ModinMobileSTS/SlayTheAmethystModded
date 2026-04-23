package io.stamethyst.backend.steamcloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import io.stamethyst.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SteamCloudPhase0DebugService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Steam Cloud Debug",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Runs Steam Cloud Phase 0 diagnostics from adb."
                }
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestIntent = Intent(intent)
        if (!RUNNING.compareAndSet(false, true)) {
            Log.w(TAG, "Steam Cloud Phase 0 is already running; ignoring duplicate service start.")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        startDebugForeground()
        val appContext = applicationContext
        EXECUTOR.execute {
            try {
                maybeSaveCredentials(appContext, requestIntent)
                val credentials = SteamCloudPhase0Store.readCredentials(appContext)
                if (credentials == null) {
                    val message = "Phase 0 debug service is missing saved credentials."
                    Log.e(TAG, message)
                    SteamCloudPhase0Store.recordFailure(appContext, message)
                    return@execute
                }

                Log.i(
                    TAG,
                    "Running Steam Cloud Phase 0 from debug service. account="
                        + credentials.accountName
                        + ", proxy="
                        + if (credentials.proxyUrl.isBlank()) "direct" else credentials.proxyUrl
                )
                val result = SteamCloudPhase0ManifestProbe.run(
                    appContext,
                    credentials.accountName,
                    credentials.refreshToken,
                    credentials.proxyUrl
                )
                SteamCloudPhase0Store.recordSuccess(appContext, result)
                Log.i(
                    TAG,
                    "Steam Cloud Phase 0 completed from debug service. files="
                        + result.fileCount
                        + ", summary="
                        + result.summaryFile.absolutePath
                )
            } catch (error: Throwable) {
                val probeFailure = error as? SteamCloudPhase0ManifestProbe.ProbeFailureException
                val summary = probeFailure?.message?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: error.message?.trim()?.takeIf { it.isNotEmpty() }
                    ?: error.javaClass.simpleName
                SteamCloudPhase0Store.recordFailure(
                    appContext,
                    summary,
                    probeFailure?.summaryFile?.absolutePath
                )
                Log.e(TAG, "Steam Cloud Phase 0 debug service failed: $summary", error)
            } finally {
                RUNNING.set(false)
                MAIN_HANDLER.post {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lock_open)
            .setContentTitle("Steam Cloud Phase 0")
            .setContentText("Running manifest probe for debug diagnostics.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun startDebugForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            return
        }
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun maybeSaveCredentials(context: Context, intent: Intent) {
        val accountName = intent.getStringExtra(EXTRA_ACCOUNT_NAME)?.trim().orEmpty()
        val refreshToken = intent.getStringExtra(EXTRA_REFRESH_TOKEN)?.trim().orEmpty()
        val proxyUrl = intent.getStringExtra(EXTRA_PROXY_URL)?.trim().orEmpty()
        if (accountName.isEmpty() && refreshToken.isEmpty() && proxyUrl.isEmpty()) {
            return
        }

        val existing = SteamCloudPhase0Store.readSnapshot(context)
        if (accountName.isEmpty() && existing.accountName.isNotBlank()) {
            SteamCloudPhase0Store.saveCredentials(
                context,
                existing.accountName,
                refreshToken.ifEmpty { null },
                proxyUrl.ifEmpty { existing.proxyUrl }
            )
            return
        }

        if (accountName.isEmpty()) {
            throw IllegalArgumentException("Missing Steam account name for Phase 0 debug service.")
        }

        SteamCloudPhase0Store.saveCredentials(
            context,
            accountName,
            refreshToken.ifEmpty { null },
            proxyUrl
        )
    }

    private companion object {
        private const val TAG = "SteamCloudPhase0Rx"
        private const val EXTRA_ACCOUNT_NAME = "account_name"
        private const val EXTRA_REFRESH_TOKEN = "refresh_token"
        private const val EXTRA_PROXY_URL = "proxy_url"
        private const val NOTIFICATION_CHANNEL_ID = "steam_cloud_phase0_debug"
        private const val NOTIFICATION_ID = 42010
        private val EXECUTOR = Executors.newSingleThreadExecutor()
        private val MAIN_HANDLER = Handler(Looper.getMainLooper())
        private val RUNNING = AtomicBoolean(false)
    }
}
