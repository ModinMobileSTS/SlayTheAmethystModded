package io.stamethyst;

import android.app.Application;

import net.kdt.pojavlaunch.MainActivity;

public class StsApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        MainActivity.init(getApplicationContext());
    }
}
