package io.stamethyst.bridge;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

final class BootBridgeJvmHeapSnapshotWriter {
    private static final String PROP_HEAP_SNAPSHOT = "amethyst.bridge.heap_snapshot";
    private static final long SAMPLE_INTERVAL_MS = 1000L;

    private final File snapshotFile;
    private final long sampleIntervalMs;

    private BootBridgeJvmHeapSnapshotWriter(File snapshotFile, long sampleIntervalMs) {
        this.snapshotFile = snapshotFile;
        this.sampleIntervalMs = Math.max(1L, sampleIntervalMs);
    }

    static Thread startFromSystemProperty() {
        String rawPath = System.getProperty(PROP_HEAP_SNAPSHOT, "").trim();
        if (rawPath.isEmpty()) {
            return null;
        }
        return new BootBridgeJvmHeapSnapshotWriter(new File(rawPath), SAMPLE_INTERVAL_MS).startThread();
    }

    private Thread startThread() {
        Thread watcher = new Thread(this::runLoop, "Amethyst-BootBridge-HeapSnapshot");
        watcher.setDaemon(true);
        watcher.start();
        return watcher;
    }

    private void runLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Runtime runtime = Runtime.getRuntime();
                long heapUsedBytes = runtime.totalMemory() - runtime.freeMemory();
                long heapMaxBytes = runtime.maxMemory();
                writeSnapshot(heapUsedBytes, heapMaxBytes);
            } catch (Throwable ignored) {
            }
            sleepQuietly(sampleIntervalMs);
        }
    }

    private void writeSnapshot(long heapUsedBytes, long heapMaxBytes) throws Exception {
        File parent = snapshotFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        String payload =
                "heapUsed=" + Math.max(0L, heapUsedBytes) +
                        ";heapMax=" + Math.max(0L, heapMaxBytes);
        try (FileOutputStream output = new FileOutputStream(snapshotFile, false)) {
            output.write(payload.getBytes(StandardCharsets.UTF_8));
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
