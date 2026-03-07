package io.stamethyst.backend.launch

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import io.stamethyst.LauncherActivity

object LauncherReturnCoordinator {
    private const val REQUEST_CODE_LAUNCHER_RESTART = 0x71A7
    private const val RESTART_PENDING_INTENT_FLAGS =
        PendingIntent.FLAG_CANCEL_CURRENT or
            PendingIntent.FLAG_ONE_SHOT or
            PendingIntent.FLAG_IMMUTABLE

    @JvmStatic
    fun returnToLauncher(activity: Activity) {
        activity.startActivity(createReturnIntent(activity))
        activity.finish()
    }

    @JvmStatic
    fun createReturnIntent(context: Context): Intent {
        return Intent(context, LauncherActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
    }

    @JvmStatic
    fun createCrashIntent(context: Context, code: Int, isSignal: Boolean, detail: String?): Intent {
        return Intent(context, LauncherActivity::class.java).apply {
            putExtra(LauncherActivity.EXTRA_CRASH_OCCURRED, true)
            putExtra(LauncherActivity.EXTRA_CRASH_CODE, code)
            putExtra(LauncherActivity.EXTRA_CRASH_IS_SIGNAL, isSignal)
            appendCrashDetail(detail)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
    }

    @JvmStatic
    fun showCrashAndFinish(activity: Activity, code: Int, isSignal: Boolean, detail: String?) {
        activity.startActivity(createCrashIntent(activity, code, isSignal, detail))
        activity.finish()
    }

    @JvmStatic
    fun scheduleLauncherRestart(
        context: Context,
        delayMs: Long,
        markExpectedBackExitRestart: Boolean
    ): Boolean {
        if (markExpectedBackExitRestart) {
            BackExitNotice.markExpectedBackExitRestartScheduled(context)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_LAUNCHER_RESTART,
            createRestartIntent(context),
            RESTART_PENDING_INTENT_FLAGS
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager?
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        if (alarmManager != null && scheduleExactLauncherRestart(alarmManager, triggerAt, pendingIntent)) {
            return true
        }
        return sendLauncherRestart(pendingIntent)
    }

    private fun createRestartIntent(context: Context): Intent {
        return Intent(context, LauncherActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
    }

    private fun Intent.appendCrashDetail(detail: String?) {
        val trimmed = detail?.trim().orEmpty()
        if (trimmed.isNotEmpty()) {
            putExtra(LauncherActivity.EXTRA_CRASH_DETAIL, trimmed)
        }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleExactLauncherRestart(
        alarmManager: AlarmManager,
        triggerAt: Long,
        pendingIntent: PendingIntent
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
            }
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun sendLauncherRestart(pendingIntent: PendingIntent): Boolean {
        return try {
            pendingIntent.send()
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        }
    }
}
