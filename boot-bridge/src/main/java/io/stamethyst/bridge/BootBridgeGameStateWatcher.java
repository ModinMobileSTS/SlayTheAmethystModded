package io.stamethyst.bridge;

final class BootBridgeGameStateWatcher {
    private static final long WATCHER_POLL_MS = 120L;
    private static final int READY_CONFIRM_TICKS = 3;
    private static final int CONSOLE_FALLBACK_FAIL_TICKS = 90;
    private static final float SPLASH_VISIBLE_ALPHA_THRESHOLD = 0.06f;

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
                        reporter.ready(BootBridgeStartupMessage.key("game_ready"));
                        return;
                    }
                } else {
                    readyTicks = 0;
                }

                if ("SPLASH".equals(snapshot.modeName)) {
                    if (!splashSignaled && isSplashLogoVisible(snapshot)) {
                        reporter.splash(BootBridgeStartupMessage.key("game_splash"));
                        splashSignaled = true;
                    }
                } else {
                    splashSignaled = false;
                }

                if ("CHAR_SELECT".equals(snapshot.modeName) && snapshot.hasMainMenuScreen) {
                    String screen = snapshot.menuScreenName == null ? "" : snapshot.menuScreenName;
                    if (!screen.equals(lastMainMenuScreen)) {
                        reporter.phase(97, BootBridgeStartupMessage.key("main_menu_ready"));
                        lastMainMenuScreen = screen;
                    }
                } else {
                    lastMainMenuScreen = "";
                }
            } catch (Throwable ignored) {
                readyTicks = 0;
                reflectionFailureTicks += 1;
                if (reporter.hasConsoleReadyHint() && reflectionFailureTicks >= CONSOLE_FALLBACK_FAIL_TICKS) {
                    reporter.ready(BootBridgeStartupMessage.key("game_ready"));
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

    private static boolean isSplashLogoVisible(BootBridgeGameStateProbe.Snapshot snapshot) {
        if (snapshot == null || !"SPLASH".equals(snapshot.modeName)) {
            return false;
        }
        String phase = snapshot.splashPhaseName == null ? "" : snapshot.splashPhaseName;
        if ("INIT".equals(phase)) {
            return false;
        }
        if (Float.isNaN(snapshot.splashLogoAlpha)) {
            // If alpha introspection is unavailable, use phase-only detection.
            return true;
        }
        return snapshot.splashLogoAlpha >= SPLASH_VISIBLE_ALPHA_THRESHOLD;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
