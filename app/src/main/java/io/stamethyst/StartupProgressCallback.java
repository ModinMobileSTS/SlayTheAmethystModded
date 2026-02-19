package io.stamethyst;

@FunctionalInterface
public interface StartupProgressCallback {
    void onProgress(int percent, String message);
}
