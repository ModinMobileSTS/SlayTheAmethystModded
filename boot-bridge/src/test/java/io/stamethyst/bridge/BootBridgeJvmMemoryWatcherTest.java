package io.stamethyst.bridge;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BootBridgeJvmMemoryWatcherTest {
    private static final String PROP_EVENTS = "amethyst.bridge.events";
    private static final long JOIN_TIMEOUT_MS = 1_000L;
    private static final long AWAIT_TIMEOUT_MS = 500L;
    private static final long SAMPLE_INTERVAL_MS = 5L;

    @Test
    public void memoryWatcherStopsAfterReporterReady() throws Exception {
        WatcherHarness harness = createHarness();
        try {
            Thread watcher = BootBridgeJvmMemoryWatcher.start(harness.reporter, SAMPLE_INTERVAL_MS);
            awaitCondition(() -> countEventLines(harness.eventsFile, "MEM") >= 1, AWAIT_TIMEOUT_MS);

            harness.reporter.ready("ready");
            watcher.join(JOIN_TIMEOUT_MS);

            assertFalse("Watcher should stop after READY", watcher.isAlive());
        } finally {
            harness.close();
        }
    }

    @Test
    public void memoryWatcherStopsAfterReporterFail() throws Exception {
        WatcherHarness harness = createHarness();
        try {
            Thread watcher = BootBridgeJvmMemoryWatcher.start(harness.reporter, SAMPLE_INTERVAL_MS);
            awaitCondition(() -> countEventLines(harness.eventsFile, "MEM") >= 1, AWAIT_TIMEOUT_MS);

            harness.reporter.fail("boom");
            watcher.join(JOIN_TIMEOUT_MS);

            assertFalse("Watcher should stop after FAIL", watcher.isAlive());
        } finally {
            harness.close();
        }
    }

    @Test
    public void memoryWatcherDoesNotAppendAfterReporterReady() throws Exception {
        WatcherHarness harness = createHarness();
        try {
            Thread watcher = BootBridgeJvmMemoryWatcher.start(harness.reporter, SAMPLE_INTERVAL_MS);
            awaitCondition(() -> countEventLines(harness.eventsFile, "MEM") >= 1, AWAIT_TIMEOUT_MS);

            int beforeReadyMemCount = countEventLines(harness.eventsFile, "MEM");
            harness.reporter.ready("ready");
            watcher.join(JOIN_TIMEOUT_MS);
            int afterReadyMemCount = countEventLines(harness.eventsFile, "MEM");

            Thread.sleep(50L);
            int stableMemCount = countEventLines(harness.eventsFile, "MEM");

            assertTrue("Expected at least one MEM event before READY", beforeReadyMemCount >= 1);
            assertTrue("Watcher should finish after READY", !watcher.isAlive());
            assertTrue("No additional MEM events should be written after READY", afterReadyMemCount == stableMemCount);
        } finally {
            harness.close();
        }
    }

    private static WatcherHarness createHarness() throws Exception {
        File tempDir = Files.createTempDirectory("boot-bridge-memory-watcher").toFile();
        File eventsFile = new File(tempDir, "boot_bridge_events.log");
        String previousPath = System.getProperty(PROP_EVENTS);
        System.setProperty(PROP_EVENTS, eventsFile.getAbsolutePath());
        BootBridgeReporter reporter = new BootBridgeReporter(BootBridgeEventSink.fromSystemProperty());
        return new WatcherHarness(tempDir, eventsFile, previousPath, reporter);
    }

    private static int countEventLines(File file, String type) {
        if (!file.isFile()) {
            return 0;
        }
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            return lines.stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .filter(line -> line.startsWith(type + "\t"))
                    .collect(Collectors.toList())
                    .size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static void awaitCondition(BooleanSupplier condition, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10L);
        }
        assertTrue("Condition was not met within timeout", condition.getAsBoolean());
    }

    private static final class WatcherHarness implements AutoCloseable {
        private final File tempDir;
        private final File eventsFile;
        private final String previousPath;
        private final BootBridgeReporter reporter;

        private WatcherHarness(File tempDir, File eventsFile, String previousPath, BootBridgeReporter reporter) {
            this.tempDir = tempDir;
            this.eventsFile = eventsFile;
            this.previousPath = previousPath;
            this.reporter = reporter;
        }

        @Override
        public void close() throws Exception {
            if (previousPath == null) {
                System.clearProperty(PROP_EVENTS);
            } else {
                System.setProperty(PROP_EVENTS, previousPath);
            }
            deleteRecursively(tempDir);
        }

        private static void deleteRecursively(File file) throws Exception {
            if (file == null || !file.exists()) {
                return;
            }
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteRecursively(child);
                    }
                }
            }
            Files.deleteIfExists(file.toPath());
        }
    }
}
