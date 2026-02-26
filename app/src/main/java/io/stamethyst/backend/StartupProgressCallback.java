package io.stamethyst.backend;

@FunctionalInterface
public interface StartupProgressCallback {
    void onProgress(int percent, String message);
}
