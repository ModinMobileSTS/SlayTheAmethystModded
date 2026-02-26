package io.stamethyst.backend;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.IOException;

public final class LaunchPreparationService {
    private LaunchPreparationService() {
    }

    public static void prepare(Context context, String launchMode) throws IOException {
        prepare(context, launchMode, null);
    }

    public static void prepare(
            Context context,
            String launchMode,
            @Nullable StartupProgressCallback progressCallback
    ) throws IOException {
        reportProgress(progressCallback, 3, "Installing launcher components...");
        ComponentInstaller.ensureInstalled(context, mapProgressRange(progressCallback, 5, 35));

        reportProgress(progressCallback, 36, "Preparing Java runtime...");
        RuntimePackInstaller.ensureInstalled(context, mapProgressRange(progressCallback, 36, 76));

        reportProgress(progressCallback, 78, "Ensuring runtime directories...");
        RuntimePaths.ensureBaseDirs(context);

        reportProgress(progressCallback, 86, "Validating desktop-1.0.jar...");
        StsJarValidator.validate(RuntimePaths.importedStsJar(context));

        if (StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD.equals(launchMode)) {
            reportProgress(progressCallback, 90, "Validating required mod jars...");
            ModJarSupport.validateMtsJar(RuntimePaths.importedMtsJar(context));
            ModJarSupport.validateBaseModJar(RuntimePaths.importedBaseModJar(context));
            ModJarSupport.validateStsLibJar(RuntimePaths.importedStsLibJar(context));

            reportProgress(progressCallback, 95, "Preparing MTS classpath...");
            ModJarSupport.prepareMtsClasspath(context);
            ModManager.resolveLaunchModIds(context);
        }

        reportProgress(progressCallback, 100, "Launch preparation complete");
    }

    @Nullable
    private static StartupProgressCallback mapProgressRange(
            @Nullable StartupProgressCallback callback,
            int startPercent,
            int endPercent
    ) {
        if (callback == null) {
            return null;
        }
        int safeStart = clampPercent(startPercent);
        int safeEnd = clampPercent(endPercent);
        return (percent, message) -> callback.onProgress(
                mapRangeProgress(percent, safeStart, safeEnd),
                message
        );
    }

    private static int mapRangeProgress(int percent, int startPercent, int endPercent) {
        int bounded = clampPercent(percent);
        float ratio = bounded / 100f;
        return startPercent + Math.round((endPercent - startPercent) * ratio);
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static void reportProgress(
            @Nullable StartupProgressCallback callback,
            int percent,
            String message
    ) {
        if (callback != null) {
            callback.onProgress(clampPercent(percent), message);
        }
    }
}

