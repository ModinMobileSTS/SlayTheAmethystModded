package io.stamethyst;

import android.app.Application;

import io.stamethyst.backend.diag.MemoryDiagnosticsLogger;
import io.stamethyst.config.LauncherThemeController;
import net.kdt.pojavlaunch.MainActivity;

public class StsApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        LauncherThemeController.applySavedThemeMode(getApplicationContext());
        MemoryDiagnosticsLogger.install(getApplicationContext());
        MainActivity.init(getApplicationContext());
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
