package io.stamethyst.bridge;

final class BootBridgeJvmMemoryWatcher {
    private static final long SAMPLE_INTERVAL_MS = 1000L;

    private final BootBridgeReporter reporter;
    private final long sampleIntervalMs;

    private BootBridgeJvmMemoryWatcher(BootBridgeReporter reporter, long sampleIntervalMs) {
        this.reporter = reporter;
        this.sampleIntervalMs = Math.max(1L, sampleIntervalMs);
    }

    static Thread start(BootBridgeReporter reporter) {
        return start(reporter, SAMPLE_INTERVAL_MS);
    }

    static Thread start(BootBridgeReporter reporter, long sampleIntervalMs) {
        return new BootBridgeJvmMemoryWatcher(reporter, sampleIntervalMs).startThread();
    }

    private Thread startThread() {
        Thread watcher = new Thread(this::runLoop, "Amethyst-BootBridge-MemoryWatcher");
        watcher.setDaemon(true);
        watcher.start();
        return watcher;
    }

    private void runLoop() {
        while (!reporter.isReadySent() && !reporter.isFailSent() && !Thread.currentThread().isInterrupted()) {
            try {
                Runtime runtime = Runtime.getRuntime();
                long heapUsedBytes = runtime.totalMemory() - runtime.freeMemory();
                long heapMaxBytes = runtime.maxMemory();
                reporter.memory(heapUsedBytes, heapMaxBytes);
            } catch (Throwable ignored) {
            }
            sleepQuietly(sampleIntervalMs);
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
