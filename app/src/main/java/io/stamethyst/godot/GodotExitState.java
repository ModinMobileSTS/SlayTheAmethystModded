package io.stamethyst.godot;

/** Main-thread save/exit policy shared by standalone play and the boot minigame. */
final class GodotExitState {
    enum Destination { MAIN_GAME, LAUNCHER }
    private static final long DOUBLE_BACK_WINDOW_MS = 2000L;
    private long backDeadlineMs = -1L;
    private Destination destination;
    private boolean saving, saveFailed, completed;

    boolean pressBack(long nowMs) {
        if (completed || (saving && returnsToLauncher())) return false;
        if (backDeadlineMs >= 0L && nowMs <= backDeadlineMs) {
            resetBack();
            return true;
        }
        backDeadlineMs = nowMs + DOUBLE_BACK_WINDOW_MS;
        return false;
    }

    void resetBack() { backDeadlineMs = -1L; }
    void selectLauncher() { if (!completed) destination = Destination.LAUNCHER; }

    boolean beginSave(Destination requested) {
        if (completed) return false;
        if (requested == Destination.LAUNCHER) selectLauncher();
        if (destination == null) destination = requested;
        if (saving || saveFailed) return false;
        saving = true;
        resetBack();
        return true;
    }

    boolean failSave() {
        if (!saving || completed) return false;
        saving = false;
        saveFailed = true;
        return true;
    }

    boolean retrySave() {
        if (!saveFailed || completed) return false;
        saveFailed = false;
        saving = true;
        return true;
    }

    boolean acknowledgeSave() {
        if (!saving || saveFailed || completed) return false;
        return complete();
    }

    /** Only for an explicit discard confirmation, or before any engine/gameplay exists. */
    boolean complete() {
        if (completed || destination == null) return false;
        completed = true;
        saving = false;
        resetBack();
        return true;
    }

    boolean saving() { return saving; }
    boolean saveFailed() { return saveFailed; }
    boolean completed() { return completed; }
    boolean returnsToLauncher() { return destination == Destination.LAUNCHER; }
}
