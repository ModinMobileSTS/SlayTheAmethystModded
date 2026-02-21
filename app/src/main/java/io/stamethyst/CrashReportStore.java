package io.stamethyst;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CrashReportStore {
    private static final String TAG = "CrashReportStore";

    private CrashReportStore() {
    }

    public static void clear(Context context) {
        try {
            File file = RuntimePaths.lastCrashReport(context);
            if (file.exists() && !file.delete() && file.exists()) {
                Log.w(TAG, "Failed to delete stale crash report: " + file.getAbsolutePath());
            }
        } catch (Throwable error) {
            Log.w(TAG, "Failed to clear crash report", error);
        }
    }

    public static void recordLaunchResult(Context context,
                                          String stage,
                                          int code,
                                          boolean isSignal,
                                          String detail) {
        StringBuilder out = new StringBuilder(256);
        out.append("time=").append(nowString()).append('\n');
        out.append("stage=").append(stage).append('\n');
        out.append("type=launch_result").append('\n');
        out.append("code=").append(code).append('\n');
        out.append("isSignal=").append(isSignal).append('\n');
        out.append("detail=").append(detail == null ? "" : detail.trim()).append('\n');
        out.append('\n');
        append(context, out.toString());
    }

    public static void recordThrowable(Context context, String stage, Throwable error) {
        StringBuilder out = new StringBuilder(1024);
        out.append("time=").append(nowString()).append('\n');
        out.append("stage=").append(stage).append('\n');
        out.append("type=throwable").append('\n');
        out.append("class=").append(error.getClass().getName()).append('\n');
        out.append("message=").append(String.valueOf(error.getMessage())).append('\n');
        out.append("stacktrace:\n");
        StringWriter stackBuffer = new StringWriter(2048);
        PrintWriter writer = new PrintWriter(stackBuffer);
        error.printStackTrace(writer);
        writer.flush();
        out.append(stackBuffer);
        out.append('\n');
        append(context, out.toString());
    }

    private static void append(Context context, String message) {
        try {
            File file = RuntimePaths.lastCrashReport(context);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Log.w(TAG, "Failed to create crash report directory: " + parent.getAbsolutePath());
                return;
            }
            try (FileOutputStream output = new FileOutputStream(file, true)) {
                output.write(message.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable error) {
            Log.w(TAG, "Failed to write crash report", error);
        }
    }

    private static String nowString() {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        return formatter.format(new Date());
    }
}
