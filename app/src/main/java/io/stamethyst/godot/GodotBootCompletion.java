package io.stamethyst.godot;

/** Cancellation must win over delayed entry events and minigame Binder death recovery. */
final class GodotBootCompletion {
    private boolean launcherRequested, completed;

    void requestLauncher() { if (!completed) launcherRequested = true; }
    boolean launcherRequested() { return launcherRequested; }

    boolean claimEntry() {
        if (completed || launcherRequested) return false;
        completed = true;
        return true;
    }

    boolean claimLauncherReturn() {
        if (completed) return false;
        launcherRequested = true;
        completed = true;
        return true;
    }
}
