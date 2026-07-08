package io.stamethyst.backend.steamcloud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import io.stamethyst.LauncherActivity
import io.stamethyst.R
import io.stamethyst.backend.launch.GameLaunchReturnTracker
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class SteamCloudSyncProcessService : Service() {
    companion object {
        private const val TAG = "SteamCloudSyncProcessService"

        const val ACTION_CHECK_AND_SYNC = "io.stamethyst.action.STEAM_CLOUD_CHECK_AND_SYNC"
        const val ACTION_USE_LOCAL = "io.stamethyst.action.STEAM_CLOUD_USE_LOCAL"
        const val ACTION_USE_CLOUD = "io.stamethyst.action.STEAM_CLOUD_USE_CLOUD"
        const val ACTION_CANCEL = "io.stamethyst.action.STEAM_CLOUD_CANCEL"
        const val ACTION_SYNC_EVENT = "io.stamethyst.action.STEAM_CLOUD_SYNC_EVENT"

        const val EXTRA_RESULT_RECEIVER = "io.stamethyst.extra.STEAM_CLOUD_RESULT_RECEIVER"
        const val EXTRA_EVENT_RESULT_CODE = "io.stamethyst.extra.STEAM_CLOUD_EVENT_RESULT_CODE"
        const val EXTRA_USER_INITIATED = "io.stamethyst.extra.STEAM_CLOUD_USER_INITIATED"
        const val EXTRA_PLAN = "io.stamethyst.extra.STEAM_CLOUD_PLAN"
        const val EXTRA_PROGRESS_DIRECTION = "io.stamethyst.extra.STEAM_CLOUD_PROGRESS_DIRECTION"
        const val EXTRA_PROGRESS_PHASE = "io.stamethyst.extra.STEAM_CLOUD_PROGRESS_PHASE"
        const val EXTRA_PROGRESS_PERCENT = "io.stamethyst.extra.STEAM_CLOUD_PROGRESS_PERCENT"
        const val EXTRA_PROGRESS_COMPLETED_FILES = "io.stamethyst.extra.STEAM_CLOUD_PROGRESS_COMPLETED_FILES"
        const val EXTRA_PROGRESS_TOTAL_FILES = "io.stamethyst.extra.STEAM_CLOUD_PROGRESS_TOTAL_FILES"
        const val EXTRA_PROGRESS_CURRENT_PATH = "io.stamethyst.extra.STEAM_CLOUD_PROGRESS_CURRENT_PATH"
        const val EXTRA_PROGRESS_MESSAGE = "io.stamethyst.extra.STEAM_CLOUD_PROGRESS_MESSAGE"
        const val EXTRA_SYNC_DIRECTION = "io.stamethyst.extra.STEAM_CLOUD_SYNC_DIRECTION"
        const val EXTRA_CHECKED_AT_MS = "io.stamethyst.extra.STEAM_CLOUD_CHECKED_AT_MS"
        const val EXTRA_COMPLETED_AT_MS = "io.stamethyst.extra.STEAM_CLOUD_COMPLETED_AT_MS"
        const val EXTRA_UPLOADED_FILE_COUNT = "io.stamethyst.extra.STEAM_CLOUD_UPLOADED_FILE_COUNT"
        const val EXTRA_DELETED_REMOTE_FILE_COUNT = "io.stamethyst.extra.STEAM_CLOUD_DELETED_REMOTE_FILE_COUNT"
        const val EXTRA_APPLIED_FILE_COUNT = "io.stamethyst.extra.STEAM_CLOUD_APPLIED_FILE_COUNT"
        const val EXTRA_ERROR_SUMMARY = "io.stamethyst.extra.STEAM_CLOUD_ERROR_SUMMARY"

        const val RESULT_CHECKING = 1
        const val RESULT_PLAN_READY = 2
        const val RESULT_SYNC_STARTED = 3
        const val RESULT_PROGRESS = 4
        const val RESULT_UP_TO_DATE = 5
        const val RESULT_LOCAL_OVERRIDE_COMPLETED = 6
        const val RESULT_CLOUD_OVERRIDE_COMPLETED = 7
        const val RESULT_AUTO_SYNC_COMPLETED = 8
        const val RESULT_FAILURE = 9
        const val RESULT_CANCELLED = 10

        private const val CHANNEL_ID = "steam_cloud_sync"
        private const val NOTIFICATION_ID = 646571

        @Volatile
        private var running = false

        fun isRunning(): Boolean = running

        fun startCheckAndSync(
            context: Context,
            userInitiated: Boolean,
            receiver: ResultReceiver? = null,
        ): Boolean {
            return start(context, ACTION_CHECK_AND_SYNC, receiver) {
                putExtra(EXTRA_USER_INITIATED, userInitiated)
            }
        }

        fun startUseLocal(context: Context, receiver: ResultReceiver? = null): Boolean {
            return start(context, ACTION_USE_LOCAL, receiver)
        }

        fun startUseCloud(context: Context, receiver: ResultReceiver? = null): Boolean {
            return start(context, ACTION_USE_CLOUD, receiver)
        }

        fun cancel(context: Context, receiver: ResultReceiver? = null) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, SteamCloudSyncProcessService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_RESULT_RECEIVER, receiver)
            }
            appContext.startService(intent)
        }

        private fun start(
            context: Context,
            action: String,
            receiver: ResultReceiver?,
            configure: Intent.() -> Unit = {},
        ): Boolean {
            val appContext = context.applicationContext
            val intent = Intent(appContext, SteamCloudSyncProcessService::class.java).apply {
                this.action = action
                putExtra(EXTRA_RESULT_RECEIVER, receiver)
                configure()
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
                return true
            } catch (error: IllegalStateException) {
                if (!isForegroundServiceStartRejected(error)) {
                    throw error
                }
                reportServiceStartRejected(appContext, receiver, error)
                return false
            }
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
            val summary = context.getString(R.string.main_steam_cloud_service_start_blocked)
            Log.w(TAG, summary, error)
            SteamCloudAuthStore.recordFailure(context, summary)
            deliverResult(context, receiver, RESULT_FAILURE, Bundle().apply {
                putString(EXTRA_ERROR_SUMMARY, summary)
                putLong(EXTRA_CHECKED_AT_MS, System.currentTimeMillis())
            })
        }

        private fun deliverResult(
            context: Context,
            receiver: ResultReceiver?,
            resultCode: Int,
            data: Bundle = Bundle.EMPTY,
        ) {
            receiver?.send(resultCode, Bundle(data))
            context.sendBroadcast(
                Intent(ACTION_SYNC_EVENT).apply {
                    `package` = context.packageName
                    putExtra(EXTRA_EVENT_RESULT_CODE, resultCode)
                    putExtras(Bundle(data))
                }
            )
        }
    }

    private val cancelRequested = AtomicBoolean(false)
    @Volatile
    private var workerThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val safeIntent = intent ?: return START_NOT_STICKY
        if (safeIntent.action == ACTION_CANCEL) {
            cancelRequested.set(true)
            workerThread?.interrupt()
            deliverResult(applicationContext, extractResultReceiver(safeIntent), RESULT_CANCELLED, Bundle().apply {
                putString(EXTRA_ERROR_SUMMARY, getString(R.string.main_steam_cloud_sync_cancelled_summary))
            })
            updateNotification(getString(R.string.main_steam_cloud_sync_cancelled_summary))
            return START_NOT_STICKY
        }

        val action = safeIntent.action ?: return START_NOT_STICKY
        if (action !in setOf(ACTION_CHECK_AND_SYNC, ACTION_USE_LOCAL, ACTION_USE_CLOUD)) {
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.main_steam_cloud_progress_preparing_auto_sync)))
        if (running) {
            deliverResult(applicationContext, extractResultReceiver(safeIntent), RESULT_SYNC_STARTED, Bundle().apply {
                putString(EXTRA_PROGRESS_MESSAGE, getString(R.string.main_steam_cloud_bar_summary_syncing))
            })
            return START_REDELIVER_INTENT
        }

        cancelRequested.set(false)
        running = true
        val receiver = extractResultReceiver(safeIntent)
        val taskIntent = Intent(safeIntent)
        val thread = Thread(
            { runOperation(startId, action, taskIntent, receiver) },
            "STS-SteamCloudSync"
        )
        workerThread = thread
        thread.start()
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        cancelRequested.set(true)
        workerThread?.interrupt()
        workerThread = null
        running = false
        super.onDestroy()
    }

    private fun runOperation(
        startId: Int,
        action: String,
        intent: Intent,
        receiver: ResultReceiver?,
    ) {
        try {
            val authMaterial = SteamCloudAuthStore.readAuthMaterial(applicationContext)
                ?: throw IllegalStateException(getString(R.string.settings_steam_cloud_credentials_missing))
            when (action) {
                ACTION_CHECK_AND_SYNC -> runCheckAndSync(intent, authMaterial, receiver)
                ACTION_USE_LOCAL -> runUseLocal(authMaterial, receiver)
                ACTION_USE_CLOUD -> runUseCloud(authMaterial, receiver)
            }
        } catch (error: Throwable) {
            val summary = summarizeError(error)
            SteamCloudAuthStore.recordFailure(applicationContext, summary)
            val resultCode = if (error is CancellationException || cancelRequested.get()) {
                RESULT_CANCELLED
            } else {
                RESULT_FAILURE
            }
            deliverResult(applicationContext, receiver, resultCode, Bundle().apply {
                putString(EXTRA_ERROR_SUMMARY, summary)
                putLong(EXTRA_CHECKED_AT_MS, System.currentTimeMillis())
            })
            updateNotification(summary)
        } finally {
            running = false
            workerThread = null
            stopForegroundCompat()
            stopSelf(startId)
        }
    }

    private fun runCheckAndSync(
        intent: Intent,
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        receiver: ResultReceiver?,
    ) {
        deliverResult(applicationContext, receiver, RESULT_CHECKING)
        updateNotification(getString(R.string.main_steam_cloud_bar_title_checking))
        val autoSynced = SteamCloudOperationMutex.runExclusive {
            val plan = SteamCloudPushCoordinator.buildUploadPlan(
                applicationContext,
                authMaterial,
                shouldContinue = ::shouldContinue,
            )
            val checkedAtMs = System.currentTimeMillis()
            ensureNotCancelled()
            if (plan.conflicts.isNotEmpty() || plan.isAlreadySynced()) {
                deliverResult(applicationContext, receiver, RESULT_PLAN_READY, Bundle().apply {
                    putSerializable(EXTRA_PLAN, plan)
                    putLong(EXTRA_CHECKED_AT_MS, checkedAtMs)
                })
                updateNotification(
                    if (plan.conflicts.isNotEmpty()) {
                        getString(R.string.main_steam_cloud_bar_title_conflict)
                    } else {
                        getString(R.string.main_steam_cloud_bar_title_up_to_date)
                    }
                )
                false
            } else {
                val direction = resolveAutomaticSyncDirection(plan)
                deliverResult(applicationContext, receiver, RESULT_SYNC_STARTED, Bundle().apply {
                    putString(EXTRA_SYNC_DIRECTION, direction.name)
                    putLong(EXTRA_CHECKED_AT_MS, checkedAtMs)
                })
                performAutomaticSync(authMaterial, plan, receiver)
                true
            }
        }
        if (!autoSynced) return
        val completedAtMs = System.currentTimeMillis()
        deliverResult(applicationContext, receiver, RESULT_AUTO_SYNC_COMPLETED, Bundle().apply {
            putLong(EXTRA_COMPLETED_AT_MS, completedAtMs)
            putBoolean(EXTRA_USER_INITIATED, intent.getBooleanExtra(EXTRA_USER_INITIATED, false))
        })
        updateNotification(getString(R.string.main_steam_cloud_bar_title_up_to_date))
        maybeShowCompletionToastInGame()
    }

    private fun runUseLocal(
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        receiver: ResultReceiver?,
    ) {
        deliverResult(applicationContext, receiver, RESULT_SYNC_STARTED, Bundle().apply {
            putString(EXTRA_SYNC_DIRECTION, SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD.name)
        })
        val result = SteamCloudOperationMutex.runExclusive {
            SteamCloudPushCoordinator.overwriteRemoteWithLocal(
                applicationContext,
                authMaterial,
                progressCallback = { progress -> reportProgress(receiver, progress) },
                shouldContinue = ::shouldContinue,
            )
        }
        deliverResult(applicationContext, receiver, RESULT_LOCAL_OVERRIDE_COMPLETED, Bundle().apply {
            putLong(EXTRA_COMPLETED_AT_MS, result.completedAtMs)
            putInt(EXTRA_UPLOADED_FILE_COUNT, result.uploadedFileCount)
            putInt(EXTRA_DELETED_REMOTE_FILE_COUNT, result.deletedRemoteFileCount)
        })
        updateNotification(getString(R.string.main_steam_cloud_bar_title_up_to_date))
        maybeShowCompletionToastInGame()
    }

    private fun runUseCloud(
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        receiver: ResultReceiver?,
    ) {
        deliverResult(applicationContext, receiver, RESULT_SYNC_STARTED, Bundle().apply {
            putString(EXTRA_SYNC_DIRECTION, SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL.name)
        })
        val result = SteamCloudOperationMutex.runExclusive {
            SteamCloudPullCoordinator.pullAll(
                applicationContext,
                authMaterial,
                progressCallback = { progress -> reportProgress(receiver, progress) },
            )
        }
        deliverResult(applicationContext, receiver, RESULT_CLOUD_OVERRIDE_COMPLETED, Bundle().apply {
            putLong(EXTRA_COMPLETED_AT_MS, result.completedAtMs)
            putInt(EXTRA_APPLIED_FILE_COUNT, result.appliedFileCount)
        })
        updateNotification(getString(R.string.main_steam_cloud_bar_title_up_to_date))
        maybeShowCompletionToastInGame()
    }

    private fun performAutomaticSync(
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        plan: SteamCloudUploadPlan,
        receiver: ResultReceiver?,
    ) {
        if (plan.remoteOnlyChanges.isNotEmpty()) {
            SteamCloudPullCoordinator.mergeRemoteOnlyChanges(
                applicationContext,
                authMaterial,
                plan,
                progressCallback = { progress -> reportProgress(receiver, progress) },
            )
        }
        ensureNotCancelled()
        if (plan.uploadCandidates.isNotEmpty() || plan.remoteDeleteCandidates.isNotEmpty()) {
            SteamCloudPushCoordinator.pushLocalChanges(
                applicationContext,
                authMaterial,
                plan,
                progressCallback = { progress -> reportProgress(receiver, progress) },
                shouldContinue = ::shouldContinue,
            )
        }
    }

    private fun reportProgress(receiver: ResultReceiver?, progress: SteamCloudSyncProgress) {
        updateNotification(buildProgressMessage(progress))
        deliverResult(applicationContext, receiver, RESULT_PROGRESS, Bundle().apply {
            putString(EXTRA_PROGRESS_DIRECTION, progress.direction.name)
            putString(EXTRA_PROGRESS_PHASE, progress.phase.name)
            putInt(EXTRA_PROGRESS_COMPLETED_FILES, progress.completedFiles)
            putInt(EXTRA_PROGRESS_TOTAL_FILES, progress.totalFiles)
            putString(EXTRA_PROGRESS_CURRENT_PATH, progress.currentPath)
            progress.progressPercent?.let { putInt(EXTRA_PROGRESS_PERCENT, it) }
            putString(EXTRA_PROGRESS_MESSAGE, buildProgressMessage(progress))
        })
    }

    private fun shouldContinue(): Boolean {
        return !cancelRequested.get() && !Thread.currentThread().isInterrupted
    }

    private fun ensureNotCancelled() {
        if (!shouldContinue()) {
            throw CancellationException("Steam Cloud sync cancelled by user.")
        }
    }

    private fun buildProgressMessage(progress: SteamCloudSyncProgress): String {
        return when (progress.phase) {
            SteamCloudSyncPhase.CONNECTING -> getString(R.string.main_steam_cloud_progress_connecting)
            SteamCloudSyncPhase.LOGGING_ON -> getString(R.string.main_steam_cloud_progress_logging_on)
            SteamCloudSyncPhase.REFRESHING_MANIFEST -> getString(R.string.main_steam_cloud_progress_refreshing_manifest)
            SteamCloudSyncPhase.PREPARING_UPLOAD -> getString(R.string.main_steam_cloud_progress_preparing_upload)
            SteamCloudSyncPhase.CREATING_UPLOAD_BATCH -> getString(R.string.main_steam_cloud_progress_creating_upload_batch)
            SteamCloudSyncPhase.REQUESTING_UPLOAD_SLOT -> getString(R.string.main_steam_cloud_progress_requesting_upload_slot)
            SteamCloudSyncPhase.UPLOADING -> getString(
                R.string.main_steam_cloud_progress_uploading,
                progress.completedFiles,
                progress.totalFiles.coerceAtLeast(progress.completedFiles),
            )
            SteamCloudSyncPhase.DOWNLOADING -> getString(
                R.string.main_steam_cloud_progress_downloading,
                progress.completedFiles,
                progress.totalFiles.coerceAtLeast(progress.completedFiles),
            )
            SteamCloudSyncPhase.BACKING_UP_LOCAL -> getString(R.string.main_steam_cloud_progress_backing_up_local)
            SteamCloudSyncPhase.APPLYING_TO_LOCAL -> getString(R.string.main_steam_cloud_progress_applying_to_local)
            SteamCloudSyncPhase.FINALIZING -> when (progress.direction) {
                SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD ->
                    getString(R.string.main_steam_cloud_progress_finalizing_upload)
                SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL ->
                    getString(R.string.main_steam_cloud_progress_finalizing_pull)
            }
        }
    }

    private fun buildNotification(message: String): Notification {
        ensureNotificationChannel()
        val intent = Intent(this, LauncherActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud_sync)
            .setContentTitle(getString(R.string.main_steam_cloud_progress_dialog_title))
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun maybeShowCompletionToastInGame() {
        if (!GameLaunchReturnTracker.isGameProcessRunning(applicationContext)) {
            return
        }
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                applicationContext,
                getString(R.string.main_steam_cloud_sync_completed_toast),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Steam Cloud 同步", NotificationManager.IMPORTANCE_LOW)
        )
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

    private fun SteamCloudUploadPlan.isAlreadySynced(): Boolean =
        conflicts.isEmpty() &&
            uploadCandidates.isEmpty() &&
            remoteDeleteCandidates.isEmpty() &&
            remoteOnlyChanges.isEmpty()

    private fun resolveAutomaticSyncDirection(plan: SteamCloudUploadPlan): SteamCloudSyncDirection {
        return if (plan.remoteOnlyChanges.isNotEmpty()) {
            SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL
        } else {
            SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD
        }
    }

    private fun summarizeError(error: Throwable): String {
        val cause = meaningfulCause(error)
        val message = cause.message?.trim().orEmpty()
        return when {
            error is CancellationException || cancelRequested.get() ->
                getString(R.string.main_steam_cloud_sync_cancelled_summary)
            message.contains("InvalidPassword", ignoreCase = true) ||
                message.contains("invalid password", ignoreCase = true) ->
                getString(R.string.settings_steam_cloud_login_invalid_credentials_summary)
            message.contains("beginhttpupload", ignoreCase = true) &&
                (message.contains("steam disconnected", ignoreCase = true) ||
                    message.contains("client or session is no longer active", ignoreCase = true)) ->
                getString(R.string.settings_steam_cloud_upload_disconnect_summary)
            message.isNotEmpty() -> message
            else -> cause.javaClass.simpleName
        }
    }

    private fun meaningfulCause(error: Throwable): Throwable {
        var current = error
        while (true) {
            if (!current.message.isNullOrBlank()) {
                return current
            }
            val next = current.cause?.takeUnless { it === current } ?: return current
            current = next
        }
    }
}
