package io.stamethyst.godot;

/** Main-thread state retained across asynchronous component verification and UI handoff. */
final class GodotLauncherState {
    private int progress;
    private String message = "正在启动主游戏…";
    private boolean ready;
    private boolean uiReady;
    private boolean disconnected;

    void update(int progress, String message, boolean ready) {
        if (disconnected) return;
        this.progress = Math.max(0, Math.min(100, progress));
        this.message = message == null ? "" : message.trim();
        this.ready = ready;
    }

    void attachUi() { uiReady = true; }
    void disconnect() {
        disconnected = true;
        ready = false;
        progress = 0;
        message = "主游戏进程已退出";
    }

    int progress() { return progress; }
    String message() { return message; }
    boolean ready() { return ready; }
    boolean uiReady() { return uiReady; }
    boolean disconnected() { return disconnected; }
    boolean canEnter() { return ready && uiReady && !disconnected; }
}
