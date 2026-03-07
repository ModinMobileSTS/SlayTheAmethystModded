package io.stamethyst.bridge;

final class BootBridgeJvmMemoryWatcher {
    private static final long SAMPLE_INTERVAL_MS = 1000L;

    private final BootBridgeReporter reporter;

    private BootBridgeJvmMemoryWatcher(BootBridgeReporter reporter) {
        this.reporter = reporter;
    }

    static void start(BootBridgeReporter reporter) {
        new BootBridgeJvmMemoryWatcher(reporter).startThread();
    }

    private void startThread() {
        Thread watcher = new Thread(this::runLoop, "Amethyst-BootBridge-MemoryWatcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void runLoop() {
        while (!reporter.isFailSent() && !Thread.currentThread().isInterrupted()) {
            try {
                Runtime runtime = Runtime.getRuntime();
                long heapUsedBytes = runtime.totalMemory() - runtime.freeMemory();
                long heapMaxBytes = runtime.maxMemory();
                reporter.memory(heapUsedBytes, heapMaxBytes);
            } catch (Throwable ignored) {
            }
            sleepQuietly(SAMPLE_INTERVAL_MS);
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
