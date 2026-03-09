package io.stamethyst;

import android.app.Application;

import io.stamethyst.config.LauncherThemeController;
import net.kdt.pojavlaunch.MainActivity;

public class StsApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        LauncherThemeController.applySavedThemeMode(getApplicationContext());
        MainActivity.init(getApplicationContext());
    }
}
