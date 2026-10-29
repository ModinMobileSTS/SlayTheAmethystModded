package io.stamethyst.backend.steamcloud

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.stamethyst.LauncherActivity
import io.stamethyst.R
import io.stamethyst.backend.notify.ForegroundNotificationIds

internal enum class SteamCloudSyncResultNotice(@get:StringRes val title: Int, @get:StringRes val message: Int) {
    COMPLETED(R.string.cloud_sync_result_completed_title, R.string.cloud_sync_result_completed_message),
    UP_TO_DATE(R.string.cloud_sync_result_up_to_date_title, R.string.cloud_sync_result_up_to_date_message),
    FAILED(R.string.cloud_sync_result_failed_title, R.string.cloud_sync_result_failed_message),
    CONFLICT(R.string.cloud_sync_result_conflict_title, R.string.cloud_sync_result_conflict_message),
    RECOVERY_REQUIRED(R.string.cloud_sync_result_recovery_title, R.string.cloud_sync_result_recovery_message),
    DEFERRED(R.string.cloud_sync_result_deferred_title, R.string.cloud_sync_result_deferred_message),
    CANCELLED(R.string.cloud_sync_result_cancelled_title, R.string.cloud_sync_result_cancelled_message),
}

/** PLAN_READY is terminal only for a conflict or a verified no-change result, not upload readiness. */
internal fun steamCloudSyncResultNotice(
    resultCode: Int,
    plan: SteamCloudUploadPlan? = null,
    requiresResolution: Boolean = false,
): SteamCloudSyncResultNotice? = when (resultCode) {
    SteamCloudSyncProcessService.RESULT_AUTO_SYNC_COMPLETED,
    SteamCloudSyncProcessService.RESULT_LOCAL_OVERRIDE_COMPLETED,
    SteamCloudSyncProcessService.RESULT_CLOUD_OVERRIDE_COMPLETED -> SteamCloudSyncResultNotice.COMPLETED
    SteamCloudSyncProcessService.RESULT_UP_TO_DATE -> SteamCloudSyncResultNotice.UP_TO_DATE
    SteamCloudSyncProcessService.RESULT_PLAN_READY -> when {
        plan == null -> null
        plan.conflicts.isNotEmpty() -> SteamCloudSyncResultNotice.CONFLICT
        plan.uploadCandidates.isEmpty() && plan.remoteDeleteCandidates.isEmpty() && plan.remoteOnlyChanges.isEmpty() ->
            SteamCloudSyncResultNotice.UP_TO_DATE
        else -> null
    }
    SteamCloudSyncProcessService.RESULT_FAILURE -> if (requiresResolution) SteamCloudSyncResultNotice.RECOVERY_REQUIRED
        else SteamCloudSyncResultNotice.FAILED
    SteamCloudSyncProcessService.RESULT_DEFERRED -> SteamCloudSyncResultNotice.DEFERRED
    SteamCloudSyncProcessService.RESULT_CANCELLED -> SteamCloudSyncResultNotice.CANCELLED
    else -> null
}

internal fun shouldShowSteamCloudSyncResultNotice(
    notice: SteamCloudSyncResultNotice,
    userInitiated: Boolean,
    backgroundLaunchRequested: Boolean,
): Boolean = notice != SteamCloudSyncResultNotice.UP_TO_DATE || userInitiated || backgroundLaunchRequested

/** Results belong to the worker, not the launcher Activity (which finishes when the game opens). */
internal object SteamCloudSyncResultNotification {
    const val CHANNEL_ID = "steam_cloud_sync_results"
    const val NOTIFICATION_ID = ForegroundNotificationIds.STEAM_CLOUD_SYNC_RESULT

    fun show(
        context: Context,
        notice: SteamCloudSyncResultNotice,
        userInitiated: Boolean,
        backgroundLaunchRequested: Boolean,
        errorSummary: String = "",
    ) {
        if (!shouldShowSteamCloudSyncResultNotice(notice, userInitiated, backgroundLaunchRequested)) return
        val app = context.applicationContext
        // Text toasts do not require POST_NOTIFICATIONS. Keep an immediate, short result for
        // background launch even when the user declines notifications; no game/launcher receiver
        // or Activity lifecycle is involved. Never label deferred work as a successful sync.
        if (backgroundLaunchRequested) {
            Handler(Looper.getMainLooper()).post {
                runCatching { Toast.makeText(app, app.getString(notice.title), Toast.LENGTH_LONG).show() }
                    .onFailure { Log.w("SteamCloudNotice", "Cannot show synchronization result toast", it) }
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) return@runCatching
            val manager = app.getSystemService(NotificationManager::class.java)
            if (manager.areNotificationsEnabled()) manager.notify(NOTIFICATION_ID, build(app, notice, errorSummary))
        }.onFailure { Log.w("SteamCloudNotice", "Cannot post synchronization result notification", it) }
    }

    fun clear(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
            .onFailure { Log.w("SteamCloudNotice", "Cannot clear previous synchronization result", it) }
    }

    internal fun build(context: Context, notice: SteamCloudSyncResultNotice, errorSummary: String = ""): Notification {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.cloud_sync_result_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val launch = PendingIntent.getActivity(context, NOTIFICATION_ID,
            Intent(context, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val message = buildString {
            append(context.getString(notice.message))
            if (notice == SteamCloudSyncResultNotice.FAILED && errorSummary.isNotBlank()) {
                append('\n')
                append(errorSummary)
            }
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud_sync)
            .setContentTitle(context.getString(notice.title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(launch)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }
}
