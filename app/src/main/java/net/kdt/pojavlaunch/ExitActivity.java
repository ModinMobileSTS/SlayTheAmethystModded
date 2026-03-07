/*
 * Derived from PojavLauncher project sources.
 * Source: https://github.com/AngelAuraMC/Amethyst-Android (branch: v3_openjdk)
 * License: LGPL-3.0
 * Modifications: adapted for the SlayTheAmethystModded Android integration.
 */

package net.kdt.pojavlaunch;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import io.stamethyst.backend.launch.BackExitNotice;
import io.stamethyst.backend.launch.LauncherReturnCoordinator;

public class ExitActivity extends AppCompatActivity {
    private static final String EXTRA_CODE = "code";
    private static final String EXTRA_IS_SIGNAL = "isSignal";
    private static final String EXTRA_DETAIL = "detail";
    private static final long LAUNCHER_RESTART_DELAY_MS = 180L;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int code = getIntent().getIntExtra(EXTRA_CODE, -1);
        boolean isSignal = getIntent().getBooleanExtra(EXTRA_IS_SIGNAL, false);
        String detail = getIntent().getStringExtra(EXTRA_DETAIL);

        LauncherReturnCoordinator.showCrashAndFinish(this, code, isSignal, detail);
    }

    public static void showExitMessage(Context context, int code, boolean isSignal, @Nullable String detail) {
        if (BackExitNotice.isExpectedBackExitRecent(context)) {
            if (BackExitNotice.isExpectedBackExitRestartScheduledRecent(context)) {
                return;
            }
            LauncherReturnCoordinator.scheduleLauncherRestart(
                    context,
                    LAUNCHER_RESTART_DELAY_MS,
                    true
            );
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
}
