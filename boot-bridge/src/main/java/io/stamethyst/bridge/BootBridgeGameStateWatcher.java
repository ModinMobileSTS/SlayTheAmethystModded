package io.stamethyst.bridge;

final class BootBridgeGameStateWatcher {
    private static final long WATCHER_POLL_MS = 120L;
    private static final int READY_CONFIRM_TICKS = 3;
    private static final int CONSOLE_FALLBACK_FAIL_TICKS = 90;

    private final BootBridgeReporter reporter;
    private final BootBridgeGameStateProbe probe = new BootBridgeGameStateProbe();

    private BootBridgeGameStateWatcher(BootBridgeReporter reporter) {
        this.reporter = reporter;
    }

    static void start(BootBridgeReporter reporter) {
        new BootBridgeGameStateWatcher(reporter).startThread();
    }

    private void startThread() {
        Thread watcher = new Thread(this::runLoop, "Amethyst-BootBridge-MenuWatcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void runLoop() {
        int readyTicks = 0;
        int reflectionFailureTicks = 0;
        boolean splashSignaled = false;
        String lastMainMenuScreen = "";

        while (!reporter.isReadySent() && !reporter.isFailSent()) {
            try {
                BootBridgeGameStateProbe.Snapshot snapshot = probe.readSnapshot();
                reflectionFailureTicks = 0;
                if (snapshot == null) {
                    readyTicks = 0;
                    splashSignaled = false;
                    lastMainMenuScreen = "";
                    sleepQuietly(WATCHER_POLL_MS);
                    continue;
                }

                if (isReadyGameState(snapshot)) {
                    readyTicks += 1;
                    if (readyTicks >= READY_CONFIRM_TICKS) {
                        reporter.ready("Game state ready: " + describeSnapshot(snapshot));
                        return;
                    }
                } else {
                    readyTicks = 0;
                }

                if ("SPLASH".equals(snapshot.modeName)) {
                    if (!splashSignaled) {
                        reporter.splash("Game splash");
                        splashSignaled = true;
                    }
                } else {
                    splashSignaled = false;
                }

                if ("CHAR_SELECT".equals(snapshot.modeName) && snapshot.hasMainMenuScreen) {
                    String screen = snapshot.menuScreenName.isEmpty() ? "unknown" : snapshot.menuScreenName;
                    if (!screen.equals(lastMainMenuScreen)) {
                        reporter.phase(97, "Main menu scene: " + screen);
                        lastMainMenuScreen = screen;
                    }
                } else {
                    lastMainMenuScreen = "";
                }
            } catch (Throwable ignored) {
                readyTicks = 0;
                reflectionFailureTicks += 1;
                if (reporter.hasConsoleReadyHint() && reflectionFailureTicks >= CONSOLE_FALLBACK_FAIL_TICKS) {
                    reporter.ready("Startup reached interactive phase (console fallback)");
                    return;
                }
            }
            sleepQuietly(WATCHER_POLL_MS);
        }
    }

    private static boolean isReadyGameState(BootBridgeGameStateProbe.Snapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        if ("GAMEPLAY".equals(snapshot.modeName) || "DUNGEON_TRANSITION".equals(snapshot.modeName)) {
            return true;
        }
        if (!"CHAR_SELECT".equals(snapshot.modeName)) {
            return false;
        }
        if (!snapshot.hasMainMenuScreen) {
            return false;
        }
        if (snapshot.menuScreenName == null || snapshot.menuScreenName.isEmpty()) {
            return false;
        }
        return !"NONE".equals(snapshot.menuScreenName);
    }

    private static String describeSnapshot(BootBridgeGameStateProbe.Snapshot snapshot) {
        if (snapshot == null) {
            return "unknown";
        }
        String mode = snapshot.modeName == null || snapshot.modeName.isEmpty()
                ? "unknown"
                : snapshot.modeName;
        String menu = snapshot.menuScreenName == null || snapshot.menuScreenName.isEmpty()
                ? "n/a"
                : snapshot.menuScreenName;
        return "mode=" + mode + ", mainMenu=" + snapshot.hasMainMenuScreen + ", screen=" + menu;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
