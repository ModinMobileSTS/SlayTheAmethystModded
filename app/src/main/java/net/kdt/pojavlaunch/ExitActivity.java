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

import io.stamethyst.LauncherActivity;

public class ExitActivity extends AppCompatActivity {
    private static final String EXTRA_CODE = "code";
    private static final String EXTRA_IS_SIGNAL = "isSignal";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int code = getIntent().getIntExtra(EXTRA_CODE, -1);
        boolean isSignal = getIntent().getBooleanExtra(EXTRA_IS_SIGNAL, false);

        Intent launcherIntent = new Intent(this, LauncherActivity.class);
        launcherIntent.putExtra(LauncherActivity.EXTRA_CRASH_OCCURRED, true);
        launcherIntent.putExtra(LauncherActivity.EXTRA_CRASH_CODE, code);
        launcherIntent.putExtra(LauncherActivity.EXTRA_CRASH_IS_SIGNAL, isSignal);
        launcherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(launcherIntent);
        finish();
    }

    public static void showExitMessage(Context context, int code, boolean isSignal) {
        Intent intent = new Intent(context, ExitActivity.class);
        intent.putExtra(EXTRA_CODE, code);
        intent.putExtra(EXTRA_IS_SIGNAL, isSignal);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
    }
}
