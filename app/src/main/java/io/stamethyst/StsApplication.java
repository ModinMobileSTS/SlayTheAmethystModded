package io.stamethyst;

import android.app.Application;

import io.stamethyst.backend.crash.LauncherCrashReporter;
import io.stamethyst.backend.diag.MemoryDiagnosticsLogger;
import io.stamethyst.backend.feedback.StreamChatPreviewInitializer;
import io.stamethyst.backend.presence.GamePresenceReporter;
import io.stamethyst.backend.process.AppProcess;
import io.stamethyst.backend.steamcloud.SteamCloudLegacySensitiveDataCleanup;
import io.stamethyst.config.CloudControlConfig;
import io.stamethyst.config.LauncherIconController;
import io.stamethyst.config.LauncherThemeController;
import net.kdt.pojavlaunch.MainActivity;

public class StsApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        LauncherCrashReporter.install(getApplicationContext());
        LauncherCrashReporter.recordLatestLauncherProcessExitIfNeeded(getApplicationContext());
        LauncherThemeController.applySavedThemeMode(getApplicationContext());
        MemoryDiagnosticsLogger.install(getApplicationContext());
        MainActivity.init(getApplicationContext());
        if (AppProcess.isDefaultProcess(getApplicationContext())) {
            SteamCloudLegacySensitiveDataCleanup.clear(getApplicationContext());
            LauncherIconController.applySavedIconMode(getApplicationContext());
            StreamChatPreviewInitializer.initialize(getApplicationContext());
            CloudControlConfig.refreshOnAppStart(getApplicationContext());
            GamePresenceReporter.install(this);
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        MemoryDiagnosticsLogger.logLowMemory(getApplicationContext(), "application");
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        MemoryDiagnosticsLogger.logTrimMemory(
                getApplicationContext(),
                "application",
                level
        );
    }
}
