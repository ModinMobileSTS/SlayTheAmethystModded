package io.stamethyst;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import io.stamethyst.backend.CrashReportStore;
import io.stamethyst.backend.LaunchPreparationService;
import io.stamethyst.backend.RendererBackend;
import io.stamethyst.backend.RendererConfig;
import io.stamethyst.backend.RuntimePaths;
import io.stamethyst.backend.StsLaunchSpec;

import java.io.File;

public class LaunchLoadingActivity extends AppCompatActivity {
    private static final String TAG = "LaunchLoadingActivity";
    private static final long MIN_VISIBLE_MS = 900L;

    public static final String EXTRA_LAUNCH_MODE = "io.stamethyst.loading_launch_mode";
    public static final String EXTRA_RENDERER_BACKEND = "io.stamethyst.loading_renderer_backend";
    public static final String EXTRA_BACK_IMMEDIATE_EXIT = "io.stamethyst.loading_back_immediate_exit";
    public static final String EXTRA_MANUAL_DISMISS_BOOT_OVERLAY = "io.stamethyst.loading_manual_dismiss_boot_overlay";

    private final java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private TextView statusText;
    private ProgressBar progressBar;
    private long visibleSinceMs = -1L;
    private volatile boolean destroyed = false;
    private String launchMode = StsLaunchSpec.LAUNCH_MODE_VANILLA;
    private RendererBackend requestedRenderer = RendererBackend.OPENGL_ES2;
    private boolean backImmediateExit = true;
    private boolean manualDismissBootOverlay = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launch_loading);

        statusText = findViewById(R.id.loadingStatusText);
        progressBar = findViewById(R.id.loadingProgressBar);
        visibleSinceMs = SystemClock.uptimeMillis();

        Intent sourceIntent = getIntent();
        String mode = sourceIntent.getStringExtra(EXTRA_LAUNCH_MODE);
        if (StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD.equals(mode)) {
            launchMode = StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD;
        }
        requestedRenderer = RendererBackend.fromRendererId(
                sourceIntent.getStringExtra(EXTRA_RENDERER_BACKEND)
        );
        backImmediateExit = sourceIntent.getBooleanExtra(EXTRA_BACK_IMMEDIATE_EXIT, true);
        manualDismissBootOverlay = sourceIntent.getBooleanExtra(EXTRA_MANUAL_DISMISS_BOOT_OVERLAY, false);

        postProgress(0, "Preparing runtime/components...");
        startPrepare();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        executor.shutdownNow();
        super.onDestroy();
    }

    private void startPrepare() {
        CrashReportStore.clear(this);
        executor.execute(() -> {
            try {
                Log.i(TAG, "startPrepare begin, mode=" + launchMode + ", rendererRequest=" + requestedRenderer.rendererId());
                postProgress(2, "Resolving renderer backend...");
                RendererConfig.ResolutionResult rendererDecision =
                        RendererConfig.resolveEffectiveBackend(this, requestedRenderer);
                appendRendererDecisionLog("loading", rendererDecision);
                Log.i(TAG, "Renderer decision: " + rendererDecision.toLogText());

                LaunchPreparationService.prepare(this, launchMode, this::postProgress);
                continueLaunchAfterMinimumVisible(rendererDecision);
            } catch (Throwable error) {
                CrashReportStore.recordThrowable(this, "loading_prepare", error);
                Log.e(TAG, "startPrepare failed", error);
                runOnUiThread(() -> {
                    if (destroyed) {
                        return;
                    }
                    String detail = "Launch preparation failed: "
                            + error.getClass().getSimpleName()
                            + ": " + error.getMessage();
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.sts_crash_dialog_title)
                            .setMessage(getString(R.string.sts_crash_detail_format, detail))
                            .setPositiveButton(android.R.string.ok, (dialog, which) -> finish())
                            .setOnCancelListener(dialog -> finish())
                            .show();
                });
            }
        });
    }

    private void continueLaunchAfterMinimumVisible(RendererConfig.ResolutionResult rendererDecision) {
        long now = SystemClock.uptimeMillis();
        long elapsed = visibleSinceMs <= 0L ? MIN_VISIBLE_MS : (now - visibleSinceMs);
        long delay = Math.max(0L, MIN_VISIBLE_MS - elapsed);

        runOnUiThread(() -> {
            if (destroyed) {
                return;
            }
            Runnable launchAction = () -> {
                if (destroyed) {
                    return;
                }
                if (rendererDecision.isFallback()) {
                    Toast.makeText(
                            this,
                            getString(R.string.renderer_fallback_toast, rendererDecision.reason),
                            Toast.LENGTH_LONG
                    ).show();
                }
                Log.i(TAG, "Starting StsGameActivity, mode=" + launchMode + ", delayMs=" + delay);
                StsGameActivity.launch(
                        this,
                        launchMode,
                        rendererDecision.effective,
                        true,
                        backImmediateExit,
                        manualDismissBootOverlay
                );
                finish();
            };

            if (delay > 0L) {
                statusText.postDelayed(launchAction, delay);
            } else {
                launchAction.run();
            }
        });
    }

    private int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private void postProgress(int percent, @Nullable String message) {
        int bounded = clampPercent(percent);
        runOnUiThread(() -> {
            if (destroyed) {
                return;
            }
            progressBar.setProgress(bounded);
            if (message != null && !message.trim().isEmpty()) {
                statusText.setText(message + " (" + bounded + "%)");
            }
        });
    }

    private void appendRendererDecisionLog(String stage, RendererConfig.ResolutionResult decision) {
        String line = "[LaunchLoading/" + stage + "] " + decision.toLogText() + "\n";
        try {
            RuntimePaths.ensureBaseDirs(this);
            File logFile = RuntimePaths.latestLog(this);
            File parent = logFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return;
            }
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(logFile, true)) {
                output.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable error) {
            Log.w(TAG, "Failed to append renderer decision log", error);
        }
    }
}
