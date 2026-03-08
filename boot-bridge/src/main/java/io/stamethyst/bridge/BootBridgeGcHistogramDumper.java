package io.stamethyst.bridge;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class BootBridgeGcHistogramDumper {
    private static final String PROP_OUTPUT_DIR = "amethyst.bridge.gc_histogram_dir";
    private static final String PROP_THRESHOLD_PER_MIN = "amethyst.debug.gc_histogram_threshold_per_min";
    private static final String PROP_SUSTAIN_SECONDS = "amethyst.debug.gc_histogram_sustain_sec";
    private static final String PROP_MIN_INTERVAL_SECONDS = "amethyst.debug.gc_histogram_min_interval_sec";
    private static final long SAMPLE_INTERVAL_MS = 1_000L;
    private static final int DEFAULT_THRESHOLD_PER_MIN = 60;
    private static final int DEFAULT_SUSTAIN_SECONDS = 30;
    private static final int DEFAULT_MIN_INTERVAL_SECONDS = 120;
    private static final long GC_WINDOW_DURATION_MS = 60_000L;
    private static final String DIAGNOSTIC_COMMAND_NAME = "com.sun.management:type=DiagnosticCommand";
    private static final String HISTOGRAM_OPERATION = "gcClassHistogram";
    private static final String[] HISTOGRAM_SIGNATURE = new String[]{"[Ljava.lang.String;"};

    interface HistogramSink {
        void write(
                File outputDir,
                long nowMs,
                int gcEventsPerMinute,
                long heapUsedBytes,
                long heapMaxBytes,
                String scene,
                String histogram
        ) throws Exception;
    }

    interface GcCountSource {
        long readTotalCollectionCount();
    }

    private final File outputDir;
    private final int thresholdPerMinute;
    private final long sustainDurationMs;
    private final long minIntervalMs;
    private final LongSupplier elapsedRealtimeMs;
    private final GcCountSource gcCountSource;
    private final Supplier<String> histogramSupplier;
    private final Supplier<String> sceneSupplier;
    private final HistogramSink histogramSink;
    private final ArrayDeque<Long> gcEventTimestampsMs = new ArrayDeque<Long>();

    private long previousCollectionCount = -1L;
    private long highGcSinceMs = -1L;
    private long lastDumpAtMs = -1L;

    BootBridgeGcHistogramDumper(
            File outputDir,
            int thresholdPerMinute,
            long sustainDurationMs,
            long minIntervalMs,
            LongSupplier elapsedRealtimeMs,
            GcCountSource gcCountSource,
            Supplier<String> histogramSupplier,
            Supplier<String> sceneSupplier,
            HistogramSink histogramSink
    ) {
        this.outputDir = outputDir;
        this.thresholdPerMinute = Math.max(1, thresholdPerMinute);
        this.sustainDurationMs = Math.max(SAMPLE_INTERVAL_MS, sustainDurationMs);
        this.minIntervalMs = Math.max(SAMPLE_INTERVAL_MS, minIntervalMs);
        this.elapsedRealtimeMs = elapsedRealtimeMs;
        this.gcCountSource = gcCountSource;
        this.histogramSupplier = histogramSupplier;
        this.sceneSupplier = sceneSupplier;
        this.histogramSink = histogramSink;
    }

    static Thread startFromSystemProperties() {
        String outputDirPath = System.getProperty(PROP_OUTPUT_DIR, "").trim();
        if (outputDirPath.isEmpty()) {
            return null;
        }
        int thresholdPerMinute = readIntProperty(PROP_THRESHOLD_PER_MIN, DEFAULT_THRESHOLD_PER_MIN);
        long sustainDurationMs = 1_000L * readIntProperty(PROP_SUSTAIN_SECONDS, DEFAULT_SUSTAIN_SECONDS);
        long minIntervalMs = 1_000L * readIntProperty(PROP_MIN_INTERVAL_SECONDS, DEFAULT_MIN_INTERVAL_SECONDS);
        BootBridgeGcHistogramDumper dumper = new BootBridgeGcHistogramDumper(
                new File(outputDirPath),
                thresholdPerMinute,
                sustainDurationMs,
                minIntervalMs,
                System::currentTimeMillis,
                BootBridgeGcHistogramDumper::readTotalCollectionCount,
                BootBridgeGcHistogramDumper::captureHistogram,
                BootBridgeGcHistogramDumper::describeCurrentScene,
                BootBridgeGcHistogramDumper::writeHistogramFile
        );
        return dumper.startThread();
    }

    Thread startThread() {
        Thread watcher = new Thread(this::runLoop, "Amethyst-BootBridge-GcHistogram");
        watcher.setDaemon(true);
        watcher.start();
        return watcher;
    }

    void sampleAndMaybeDump() {
        long nowMs = elapsedRealtimeMs.getAsLong();
        long totalCollectionCount = gcCountSource.readTotalCollectionCount();
        if (previousCollectionCount >= 0L && totalCollectionCount >= previousCollectionCount) {
            long delta = totalCollectionCount - previousCollectionCount;
            for (long index = 0; index < delta; index++) {
                gcEventTimestampsMs.addLast(nowMs);
            }
        }
        previousCollectionCount = totalCollectionCount;
        pruneOldEvents(nowMs);

        int gcEventsPerMinute = gcEventTimestampsMs.size();
        if (gcEventsPerMinute >= thresholdPerMinute) {
            if (highGcSinceMs < 0L) {
                highGcSinceMs = nowMs;
                return;
            }
            if (nowMs - highGcSinceMs < sustainDurationMs) {
                return;
            }
            if (lastDumpAtMs >= 0L && nowMs - lastDumpAtMs < minIntervalMs) {
                return;
            }
            try {
                dumpHistogram(nowMs, gcEventsPerMinute);
            } catch (Exception ignored) {
                return;
            }
            lastDumpAtMs = nowMs;
            highGcSinceMs = nowMs;
            return;
        }
        highGcSinceMs = -1L;
    }

    int currentGcEventsPerMinute() {
        return gcEventTimestampsMs.size();
    }

    private void runLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                sampleAndMaybeDump();
            } catch (Throwable ignored) {
            }
            sleepQuietly(SAMPLE_INTERVAL_MS);
        }
    }

    private void dumpHistogram(long nowMs, int gcEventsPerMinute) throws Exception {
        Runtime runtime = Runtime.getRuntime();
        long heapUsedBytes = runtime.totalMemory() - runtime.freeMemory();
        long heapMaxBytes = runtime.maxMemory();
        String histogram = histogramSupplier.get();
        if (histogram == null || histogram.trim().isEmpty()) {
            return;
        }
        String scene = sceneSupplier.get();
        histogramSink.write(
                outputDir,
                nowMs,
                gcEventsPerMinute,
                heapUsedBytes,
                heapMaxBytes,
                scene == null ? "unknown" : scene,
                histogram
        );
    }

    private void pruneOldEvents(long nowMs) {
        long cutoffMs = nowMs - GC_WINDOW_DURATION_MS;
        while (!gcEventTimestampsMs.isEmpty() && gcEventTimestampsMs.peekFirst() < cutoffMs) {
            gcEventTimestampsMs.removeFirst();
        }
    }

    private static int readIntProperty(String key, int fallback) {
        String raw = System.getProperty(key, "").trim();
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long readTotalCollectionCount() {
        List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
        long total = 0L;
        for (GarbageCollectorMXBean bean : beans) {
            long count = bean.getCollectionCount();
            if (count > 0L) {
                total += count;
            }
        }
        return total;
    }

    private static String captureHistogram() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(DIAGNOSTIC_COMMAND_NAME);
            Object result = server.invoke(
                    name,
                    HISTOGRAM_OPERATION,
                    new Object[]{new String[0]},
                    HISTOGRAM_SIGNATURE
            );
            return result instanceof String ? (String) result : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String describeCurrentScene() {
        try {
            BootBridgeGameStateProbe.Snapshot snapshot = new BootBridgeGameStateProbe().readSnapshot();
            if (snapshot == null) {
                return "unknown";
            }
            String mode = snapshot.modeName == null || snapshot.modeName.isEmpty()
                    ? "unknown"
                    : snapshot.modeName;
            String screen = snapshot.menuScreenName == null || snapshot.menuScreenName.isEmpty()
                    ? "n/a"
                    : snapshot.menuScreenName;
            return "mode=" + mode + ",mainMenu=" + snapshot.hasMainMenuScreen + ",screen=" + screen;
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    static void writeHistogramFile(
            File outputDir,
            long nowMs,
            int gcEventsPerMinute,
            long heapUsedBytes,
            long heapMaxBytes,
            String scene,
            String histogram
    ) throws Exception {
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date(nowMs));
        File outputFile = new File(outputDir, "gc_histo_" + timestamp + "_" + sanitizeScene(scene) + ".txt");
        StringBuilder content = new StringBuilder(histogram.length() + 256);
        content.append("timestamp=").append(timestamp).append('\n');
        content.append("gc_per_min=").append(gcEventsPerMinute).append('\n');
        content.append("heapUsed=").append(Math.max(0L, heapUsedBytes)).append('\n');
        content.append("heapMax=").append(Math.max(0L, heapMaxBytes)).append('\n');
        content.append("scene=").append(scene).append('\n');
        content.append('\n');
        content.append(histogram);
        try (FileOutputStream output = new FileOutputStream(outputFile, false)) {
            output.write(content.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String sanitizeScene(String scene) {
        if (scene == null || scene.trim().isEmpty()) {
            return "unknown";
        }
        String sanitized = scene.replaceAll("[^a-zA-Z0-9._-]+", "_");
        return sanitized.isEmpty() ? "unknown" : sanitized;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
