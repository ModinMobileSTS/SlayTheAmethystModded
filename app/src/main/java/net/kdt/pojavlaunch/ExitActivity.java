/*
 * Derived from PojavLauncher project sources.
 * Source: https://github.com/AngelAuraMC/Amethyst-Android (branch: v3_openjdk)
 * License: LGPL-3.0
 * Modifications: adapted for the SlayTheAmethystModded Android integration.
 */

package net.kdt.pojavlaunch;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import io.stamethyst.LauncherActivity;
import io.stamethyst.backend.launch.BackExitNotice;

public class ExitActivity extends AppCompatActivity {
    private static final String EXTRA_CODE = "code";
    private static final String EXTRA_IS_SIGNAL = "isSignal";
    private static final String EXTRA_DETAIL = "detail";
    private static final int REQUEST_CODE_LAUNCHER_RESTART = 0x71A7;
    private static final long LAUNCHER_RESTART_DELAY_MS = 180L;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int code = getIntent().getIntExtra(EXTRA_CODE, -1);
        boolean isSignal = getIntent().getBooleanExtra(EXTRA_IS_SIGNAL, false);
        String detail = getIntent().getStringExtra(EXTRA_DETAIL);

        Intent launcherIntent = new Intent(this, LauncherActivity.class);
        launcherIntent.putExtra(LauncherActivity.EXTRA_CRASH_OCCURRED, true);
        launcherIntent.putExtra(LauncherActivity.EXTRA_CRASH_CODE, code);
        launcherIntent.putExtra(LauncherActivity.EXTRA_CRASH_IS_SIGNAL, isSignal);
        if (detail != null) {
            String trimmed = detail.trim();
            if (!trimmed.isEmpty()) {
                launcherIntent.putExtra(LauncherActivity.EXTRA_CRASH_DETAIL, trimmed);
            }
        }
        launcherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(launcherIntent);
        finish();
    }

    public static void showExitMessage(Context context, int code, boolean isSignal, @Nullable String detail) {
        if (BackExitNotice.isExpectedBackExitRecent(context)) {
            if (BackExitNotice.isExpectedBackExitRestartScheduledRecent(context)) {
                return;
            }
            scheduleLauncherRestart(context);
            return;
        }
        if (code == 0) {
            return;
        }
        Intent intent = new Intent(context, ExitActivity.class);
        intent.putExtra(EXTRA_CODE, code);
        intent.putExtra(EXTRA_IS_SIGNAL, isSignal);
        if (detail != null) {
            String trimmed = detail.trim();
            if (!trimmed.isEmpty()) {
                intent.putExtra(EXTRA_DETAIL, trimmed);
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
    }

    private static void scheduleLauncherRestart(Context context) {
        BackExitNotice.markExpectedBackExitRestartScheduled(context);
        Intent launcherIntent = new Intent(context, LauncherActivity.class);
        launcherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        int flags = PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                REQUEST_CODE_LAUNCHER_RESTART,
                launcherIntent,
                flags
        );
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        long triggerAt = SystemClock.elapsedRealtime() + LAUNCHER_RESTART_DELAY_MS;
        boolean scheduled = false;
        if (alarmManager != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
                } else {
                    alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
                }
                scheduled = true;
            } catch (SecurityException ignored) {
            } catch (Throwable ignored) {
            }
        }
        if (!scheduled) {
            try {
                pendingIntent.send();
            } catch (PendingIntent.CanceledException ignored) {
            }
        }
    }
}
