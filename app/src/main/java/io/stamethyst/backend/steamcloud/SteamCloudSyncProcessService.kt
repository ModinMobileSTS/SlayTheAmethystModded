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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import io.stamethyst.LauncherActivity
import io.stamethyst.R
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.SteamCloudSaveMode
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Service is an event/foreground adapter only; it never orchestrates separate push/pull engines. */
class SteamCloudSyncProcessService : Service() {
    abstract class OperationReceiver(handler: Handler) : ResultReceiver(handler) {
        val operationId: String = UUID.randomUUID().toString()
    }

    companion object {
        const val ACTION_CHECK_AND_SYNC = "io.stamethyst.action.STEAM_CLOUD_CHECK_AND_SYNC"
        const val ACTION_USE_LOCAL = "io.stamethyst.action.STEAM_CLOUD_USE_LOCAL"
        const val ACTION_USE_CLOUD = "io.stamethyst.action.STEAM_CLOUD_USE_CLOUD"
        const val ACTION_CANCEL = "io.stamethyst.action.STEAM_CLOUD_CANCEL"
        const val ACTION_SYNC_EVENT = "io.stamethyst.action.STEAM_CLOUD_SYNC_EVENT"
        private const val ACTION_QUERY_STATE = "io.stamethyst.action.STEAM_CLOUD_QUERY_STATE"
        const val EXTRA_RESULT_RECEIVER = "io.stamethyst.extra.STEAM_CLOUD_RESULT_RECEIVER"
        const val EXTRA_OPERATION_ID = "io.stamethyst.extra.STEAM_CLOUD_OPERATION_ID"
        const val EXTRA_ACCOUNT_STEAM_ID = "io.stamethyst.extra.STEAM_CLOUD_ACCOUNT_STEAM_ID"
        const val EXTRA_ATTACHMENT_REQUEST_ID = "io.stamethyst.extra.STEAM_CLOUD_ATTACHMENT_REQUEST_ID"
        const val EXTRA_EVENT_RESULT_CODE = "io.stamethyst.extra.STEAM_CLOUD_EVENT_RESULT_CODE"
        const val EXTRA_EVENT_SEQUENCE = "io.stamethyst.extra.STEAM_CLOUD_EVENT_SEQUENCE"
        const val EXTRA_USER_INITIATED = "io.stamethyst.extra.STEAM_CLOUD_USER_INITIATED"
        const val EXTRA_ALLOW_BACKGROUND_UPLOAD = "io.stamethyst.extra.STEAM_CLOUD_ALLOW_BACKGROUND_UPLOAD"
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
        const val EXTRA_FAILURE_CATEGORY = "io.stamethyst.extra.STEAM_CLOUD_FAILURE_CATEGORY"
        const val EXTRA_BACKGROUND_UPLOAD_READY = "io.stamethyst.extra.STEAM_CLOUD_BACKGROUND_UPLOAD_READY"
        const val EXTRA_REQUIRES_RESOLUTION = "io.stamethyst.extra.STEAM_CLOUD_REQUIRES_RESOLUTION"
        const val EXTRA_WARNINGS = "io.stamethyst.extra.STEAM_CLOUD_WARNINGS"
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
        const val RESULT_DEFERRED = 11
        private const val CHANNEL_ID = "steam_cloud_sync"
        private const val NOTIFICATION_ID = 646571
        @Volatile private var running = false
        fun isRunning(): Boolean = running

        fun startCheckAndSync(context: Context, userInitiated: Boolean, allowBackgroundUpload: Boolean = true,
            receiver: ResultReceiver? = null): Boolean = start(context, ACTION_CHECK_AND_SYNC, receiver) {
            putExtra(EXTRA_USER_INITIATED, userInitiated)
            putExtra(EXTRA_ALLOW_BACKGROUND_UPLOAD, allowBackgroundUpload)
        }
        fun startUseLocal(context: Context, receiver: ResultReceiver? = null, expectedPlan: SteamCloudUploadPlan? = null): Boolean =
            start(context, ACTION_USE_LOCAL, receiver) { putExtra(EXTRA_PLAN, expectedPlan) }
        fun startUseCloud(context: Context, receiver: ResultReceiver? = null, expectedPlan: SteamCloudUploadPlan? = null): Boolean =
            start(context, ACTION_USE_CLOUD, receiver) { putExtra(EXTRA_PLAN, expectedPlan) }
        fun cancel(context: Context, receiver: ResultReceiver? = null) {
            context.applicationContext.startService(Intent(context, SteamCloudSyncProcessService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_RESULT_RECEIVER, receiver)
            })
        }
        fun requestBackgroundLaunch(context: Context) {
            LauncherConfig.setSteamCloudBackgroundLaunchRequested(context, true)
        }
        fun queryState(context: Context, receiver: ResultReceiver) {
            context.startService(Intent(context, SteamCloudSyncProcessService::class.java).apply {
                action = ACTION_QUERY_STATE
                putExtra(EXTRA_RESULT_RECEIVER, receiver)
            })
        }
        private fun start(context: Context, action: String, receiver: ResultReceiver?, configure: Intent.() -> Unit = {}): Boolean {
            val id = (receiver as? OperationReceiver)?.operationId ?: UUID.randomUUID().toString()
            val app = context.applicationContext
            if (LauncherConfig.isSteamCloudSyncDisabled(app)) {
                deliver(app, receiver, id, 1, RESULT_CANCELLED, Bundle.EMPTY)
                return false
            }
            val intent = Intent(app, SteamCloudSyncProcessService::class.java).apply {
                this.action = action
                putExtra(EXTRA_RESULT_RECEIVER, receiver)
                putExtra(EXTRA_OPERATION_ID, id)
                configure()
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent) else app.startService(intent)
                true
            } catch (error: IllegalStateException) {
                deliver(app, receiver, id, 1, RESULT_FAILURE, Bundle().apply {
                    putString(EXTRA_ERROR_SUMMARY, app.getString(R.string.main_steam_cloud_service_start_blocked))
                    putString(EXTRA_FAILURE_CATEGORY, SteamCloudFailureCategory.UNKNOWN.name)
                })
                false
            }
        }
        private fun event(id: String, sequence: Long, code: Int, data: Bundle) = Bundle(data).apply {
                putString(EXTRA_OPERATION_ID, id)
                putLong(EXTRA_EVENT_SEQUENCE, sequence)
                putInt(EXTRA_EVENT_RESULT_CODE, code)
            }
        private fun deliver(context: Context, receiver: ResultReceiver?, id: String, sequence: Long, code: Int, data: Bundle) {
            val event = event(id, sequence, code, data)
            receiver?.send(code, Bundle(event))
            context.sendBroadcast(Intent(ACTION_SYNC_EVENT).apply { `package` = context.packageName; putExtras(event) })
        }
        internal fun shouldDeferForLiveSaveLease(error: Throwable): Boolean =
            generateSequence(error) { it.cause?.takeUnless { next -> next === it } }.take(12).any { it is SteamCloudLiveSaveInUseException }
    }

    private val cancelled = AtomicBoolean(false)
    private var worker: Thread? = null
    @Volatile private var latest: Bundle? = null
    @Volatile private var stateObserver: ResultReceiver? = null
    private var lastStartId = 0
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val request = intent ?: return START_NOT_STICKY
        @Suppress("DEPRECATION")
        val receiver = if (Build.VERSION.SDK_INT >= 33) request.getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver::class.java)
            else request.getParcelableExtra(EXTRA_RESULT_RECEIVER)
        if (request.action == ACTION_QUERY_STATE) {
            // Subscribe before reading the snapshot. A terminal event racing this query must
            // still reach the attaching UI, even if its broadcast arrived before it was bound.
            stateObserver = receiver
            val saved = if (latest == null) runCatching { SteamCloudServiceStateStore.forContext(this).read() }.getOrNull() else null
            var snapshot = latest ?: saved?.toBundle()
            val snapshotActive = latest?.let { it.getInt(EXTRA_EVENT_RESULT_CODE) in
                setOf(RESULT_CHECKING, RESULT_SYNC_STARTED, RESULT_PROGRESS) } ?: (saved?.active == true)
            if (!running && snapshotActive && snapshot != null) {
                snapshot = event(snapshot.getString(EXTRA_OPERATION_ID).orEmpty(), snapshot.getLong(EXTRA_EVENT_SEQUENCE) + 1,
                    RESULT_FAILURE, Bundle().apply {
                        putString(EXTRA_ACCOUNT_STEAM_ID, snapshot?.getString(EXTRA_ACCOUNT_STEAM_ID).orEmpty())
                        putString(EXTRA_ERROR_SUMMARY, "Cloud synchronization was interrupted; recheck to recover safely.")
                        putString(EXTRA_FAILURE_CATEGORY, SteamCloudFailureCategory.CLOUD_CONFLICT.name)
                    })
                runCatching { SteamCloudServiceStateStore.forContext(this).write(snapshot, false) }
                    .onFailure { android.util.Log.w("SteamCloudState", "Cannot persist interrupted UI state", it) }
            }
            snapshot?.let { receiver?.send(it.getInt(EXTRA_EVENT_RESULT_CODE), Bundle(it)) }
            if (!running) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (request.action == ACTION_CANCEL) {
            cancelled.set(true)
            worker?.interrupt()
            if (!running) stopSelf(startId)
            // Terminal cancellation is emitted by the worker after unwinding, not prematurely.
            return START_NOT_STICKY
        }
        if (request.action !in setOf(ACTION_CHECK_AND_SYNC, ACTION_USE_LOCAL, ACTION_USE_CLOUD)) return START_NOT_STICKY
        val id = request.getStringExtra(EXTRA_OPERATION_ID) ?: UUID.randomUUID().toString()
        if (running) {
            if (request.action == ACTION_CHECK_AND_SYNC && latest != null) {
                val snapshot = Bundle(latest).apply { putString(EXTRA_ATTACHMENT_REQUEST_ID, id) }
                receiver?.send(snapshot.getInt(EXTRA_EVENT_RESULT_CODE), snapshot)
                return START_NOT_STICKY
            }
            deliver(this, receiver, id, 1, RESULT_FAILURE, Bundle().apply {
                putString(EXTRA_ERROR_SUMMARY, "A cloud operation is already running; retry after it finishes.")
                putString(EXTRA_FAILURE_CATEGORY, SteamCloudFailureCategory.UNKNOWN.name)
            })
            return START_NOT_STICKY
        }
        running = true
        latest = null
        cancelled.set(false)
        startForeground(NOTIFICATION_ID, notification(getString(R.string.main_steam_cloud_progress_preparing_auto_sync)))
        worker = Thread({ runRequest(request, id, receiver) }, "STS-SteamCloudSync").also { it.start() }
        // Never replay a destructive intent after process death; journals drive recovery instead.
        return START_NOT_STICKY
    }
    private fun runRequest(intent: Intent, id: String, receiver: ResultReceiver?) {
        var sequence = 0L
        var accountSteamId = ""
        var backgroundReady = false
        var activePlan: SteamCloudUploadPlan? = null
        var warnings = emptyList<String>()
        val progressPolicy = SteamCloudProgressPublishPolicy()
        var lastNotificationMessage = getString(R.string.main_steam_cloud_progress_preparing_auto_sync)
        fun emit(code: Int, data: Bundle = Bundle.EMPTY) {
            val payload = Bundle(data).apply {
                putBoolean(EXTRA_USER_INITIATED, intent.getBooleanExtra(EXTRA_USER_INITIATED, false) || intent.action != ACTION_CHECK_AND_SYNC)
                putString(EXTRA_ACCOUNT_STEAM_ID, accountSteamId)
                backgroundReady = data.getBoolean(EXTRA_BACKGROUND_UPLOAD_READY, backgroundReady)
                putBoolean(EXTRA_BACKGROUND_UPLOAD_READY, backgroundReady)
                // Reattachment may read a PROGRESS snapshot rather than SYNC_STARTED. Keep the
                // inspected plan so launch readiness never depends on an unproven boolean alone.
                activePlan?.let { putSerializable(EXTRA_PLAN, it) }
                // Strings survive the persisted reattachment snapshot without a list codec.
                putString(EXTRA_WARNINGS, warnings.joinToString("\n"))
            }
            val snapshot = event(id, ++sequence, code, payload)
            latest = snapshot
            val active = code in setOf(RESULT_CHECKING, RESULT_SYNC_STARTED, RESULT_PROGRESS)
            // Finish unwinding cancellation before touching fsynced event state.
            val interrupted = Thread.interrupted()
            try {
                runCatching { SteamCloudServiceStateStore.forContext(this).write(snapshot, active) }
                    .onFailure { android.util.Log.w("SteamCloudState", "Cannot persist UI snapshot", it) }
            }
            finally { if (interrupted) Thread.currentThread().interrupt() }
            deliver(this, receiver, id, sequence, code, payload)
            val notificationMessage = data.getString(EXTRA_PROGRESS_MESSAGE)
                ?: getString(R.string.main_steam_cloud_progress_preparing_auto_sync)
            if (active && notificationMessage != lastNotificationMessage) {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(notificationMessage))
                lastNotificationMessage = notificationMessage
            }
            stateObserver?.takeUnless { it === receiver }?.send(code, Bundle(snapshot))
        }
        val mode = when (intent.action) {
            ACTION_USE_LOCAL -> SteamCloudSyncMode.LOCAL_WINS
            ACTION_USE_CLOUD -> SteamCloudSyncMode.CLOUD_WINS
            else -> SteamCloudSyncMode.MERGE
        }
        try {
            if (LauncherConfig.readSteamCloudSaveMode(this) != SteamCloudSaveMode.STEAM_CLOUD) {
                throw CancellationException("Independent saves are not automatically synchronized")
            }
            val auth = SteamCloudAuthStore.readAuthMaterial(this)
                ?: throw SteamCloudCredentialsMissingException(getString(R.string.settings_steam_cloud_credentials_missing))
            accountSteamId = auth.steamId64
            emit(if (mode == SteamCloudSyncMode.MERGE) RESULT_CHECKING else RESULT_SYNC_STARTED)
            @Suppress("DEPRECATION")
            val expectedPlan = intent.getSerializableExtra(EXTRA_PLAN) as? SteamCloudUploadPlan
            val result = SteamCloudSyncRepository.synchronize(this, auth, mode, expectedPlan = expectedPlan,
                progressCallback = { progress ->
                    if (progressPolicy.shouldPublish(progress, SystemClock.elapsedRealtime())) {
                        emit(RESULT_PROGRESS, Bundle().apply {
                            putString(EXTRA_PROGRESS_DIRECTION, progress.direction.name)
                            putString(EXTRA_PROGRESS_PHASE, progress.phase.name)
                            putInt(EXTRA_PROGRESS_COMPLETED_FILES, progress.completedFiles)
                            putInt(EXTRA_PROGRESS_TOTAL_FILES, progress.totalFiles)
                            if (progress.totalFiles > 0) putInt(EXTRA_PROGRESS_PERCENT,
                                (progress.completedFiles.toLong() * 100 / progress.totalFiles).toInt().coerceIn(0, 100))
                            putString(EXTRA_PROGRESS_CURRENT_PATH, progress.currentPath)
                            putString(EXTRA_PROGRESS_MESSAGE, getString(steamCloudPhaseLabel(progress.phase)))
                        })
                    }
                 }, shouldContinue = { !cancelled.get() }, onPlan = { plan ->
                    activePlan = plan
                    warnings = plan.warnings
                    val noChanges = plan.uploadCandidates.isEmpty() && plan.remoteDeleteCandidates.isEmpty() && plan.remoteOnlyChanges.isEmpty()
                    if (plan.conflicts.isNotEmpty()) {
                        emit(RESULT_PLAN_READY, Bundle().apply {
                            putSerializable(EXTRA_PLAN, plan)
                            putLong(EXTRA_CHECKED_AT_MS, System.currentTimeMillis())
                        })
                     } else if (!noChanges) emit(RESULT_SYNC_STARTED, Bundle().apply {
                        putSerializable(EXTRA_PLAN, plan)
                        putString(EXTRA_SYNC_DIRECTION, if (plan.remoteOnlyChanges.isEmpty())
                            SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD.name else SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL.name)
                        putBoolean(EXTRA_BACKGROUND_UPLOAD_READY, plan.remoteOnlyChanges.isEmpty() &&
                            intent.getBooleanExtra(EXTRA_ALLOW_BACKGROUND_UPLOAD, true))
                    })
                })
            if (result.plan.conflicts.isNotEmpty()) return
            val changed = result.uploaded + result.deleted + result.downloaded > 0 || result.plan.remoteOnlyChanges.isNotEmpty()
            if (!changed && mode == SteamCloudSyncMode.MERGE) emit(RESULT_PLAN_READY, Bundle().apply {
                putSerializable(EXTRA_PLAN, result.plan)
                putLong(EXTRA_CHECKED_AT_MS, System.currentTimeMillis())
            })
            if (changed || mode != SteamCloudSyncMode.MERGE) emit(when (mode) {
                SteamCloudSyncMode.LOCAL_WINS -> RESULT_LOCAL_OVERRIDE_COMPLETED
                SteamCloudSyncMode.CLOUD_WINS -> RESULT_CLOUD_OVERRIDE_COMPLETED
                else -> RESULT_AUTO_SYNC_COMPLETED
            }, Bundle().apply {
                putLong(EXTRA_COMPLETED_AT_MS, System.currentTimeMillis())
                putInt(EXTRA_UPLOADED_FILE_COUNT, result.uploaded)
                putInt(EXTRA_DELETED_REMOTE_FILE_COUNT, result.deleted)
                putInt(EXTRA_APPLIED_FILE_COUNT, result.downloaded)
                putBoolean(EXTRA_USER_INITIATED, intent.getBooleanExtra(EXTRA_USER_INITIATED, false))
            })
        } catch (error: Exception) {
            val category = SteamCloudFailureClassifier.classify(error)
            emit(if (mode == SteamCloudSyncMode.MERGE && shouldDeferForLiveSaveLease(error)) RESULT_DEFERRED
                else if (error is CancellationException || cancelled.get()) RESULT_CANCELLED else RESULT_FAILURE,
                Bundle().apply {
                    putString(EXTRA_ERROR_SUMMARY, error.message ?: error.javaClass.simpleName)
                    putString(EXTRA_FAILURE_CATEGORY, category.name)
                    putBoolean(EXTRA_REQUIRES_RESOLUTION, generateSequence<Throwable>(error) { it.cause }
                        .take(12).any { it is SteamCloudPushReconciliationException })
                    putLong(EXTRA_CHECKED_AT_MS, System.currentTimeMillis())
                })
        } finally {
            Thread.interrupted()
            try { LauncherConfig.setSteamCloudBackgroundLaunchRequested(this, false) }
            finally {
                // onStartCommand and lifecycle cleanup must not interleave on different threads:
                // an old worker must never clear or stop a newly started worker.
                Handler(Looper.getMainLooper()).post {
                    worker = null
                    running = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(lastStartId)
                }
            }
        }
    }
    override fun onDestroy() { cancelled.set(true); worker?.interrupt(); super.onDestroy() }
    private fun notification(message: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Steam Cloud", NotificationManager.IMPORTANCE_LOW))
        val launch = PendingIntent.getActivity(this, 0, Intent(this, LauncherActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_cloud_sync)
            .setContentTitle(getString(R.string.main_steam_cloud_progress_dialog_title)).setContentText(message)
            .setContentIntent(launch).setOngoing(true).setOnlyAlertOnce(true).build()
    }
}
