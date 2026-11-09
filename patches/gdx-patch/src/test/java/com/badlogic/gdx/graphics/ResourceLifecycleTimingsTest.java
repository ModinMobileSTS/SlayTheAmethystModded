package com.badlogic.gdx.graphics;

import org.junit.Test;
import java.io.File;
import java.lang.reflect.Field;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class ResourceLifecycleTimingsTest {
    private File directory() throws Exception {
        File parent = new File("../../agent-tmp/resource-timings-tests");
        assertTrue(parent.isDirectory() || parent.mkdirs());
        return Files.createTempDirectory(parent.toPath(), "trace-").toFile();
    }

    private String text(File directory, String name) throws Exception {
        return new String(Files.readAllBytes(new File(directory, name).toPath()), StandardCharsets.UTF_8);
    }

    @Test public void disabledAllocatesNoRecorderThreadOrHistory() throws Exception {
        String old = System.getProperty(ResourceLifecycleTimings.PROPERTY);
        System.setProperty(ResourceLifecycleTimings.PROPERTY, "false");
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {
                ResourceLifecycleTimings.class.getProtectionDomain().getCodeSource().getLocation()}, null)) {
            Class<?> type = Class.forName(ResourceLifecycleTimings.class.getName(), true, loader);
            Field recorder = type.getDeclaredField("RECORDER"); recorder.setAccessible(true);
            assertNull(recorder.get(null));
            assertEquals(0L, type.getMethod("begin", String.class, String.class, String.class, String.class)
                    .invoke(null, "texture", "create", "key", "test"));
        } finally {
            if (old == null) System.clearProperty(ResourceLifecycleTimings.PROPERTY);
            else System.setProperty(ResourceLifecycleTimings.PROPERTY, old);
        }
    }

    @Test public void everyRepeatedEventAndNestedFailureIsWrittenAndEscaped() throws Exception {
        File directory = directory();
        ResourceLifecycleTimings.Recorder recorder = new ResourceLifecycleTimings.Recorder(directory, 4);
        recorder.start();
        long parent = recorder.begin("managed_texture", "create", "卡\"\n\\图", "test");
        for (int i = 0; i < 1000; i++) {
            long child = recorder.begin("texture", "upload", "same", "repeat");
            recorder.end(child, 42, 4096, i != 999);
        }
        recorder.end(parent, 42, 4096, true);
        recorder.close();
        List<String> lines = Files.readAllLines(new File(directory, "events.jsonl").toPath(), StandardCharsets.UTF_8);
        assertEquals(1002, lines.size());
        assertTrue(lines.get(1).contains("\"parent_id\":" + parent));
        assertTrue(lines.get(1000).contains("\"success\":false"));
        assertTrue(lines.get(1001).contains("卡\\\"\\u000a\\\\图"));
        String summary = text(directory, "summary.json");
        assertTrue(summary, summary.contains("\"written\":1001"));
        assertTrue(summary, summary.contains("\"unsaved\":0"));
        assertTrue(summary, summary.contains("\"closed_cleanly\":true"));
        assertTrue(summary, summary.contains("\"failures\":1"));
    }

    @Test public void saturatedQueueWaitsWithoutDroppingAndSeparatesDiagnosticWait() throws Exception {
        File directory = directory();
        ResourceLifecycleTimings.Recorder recorder = new ResourceLifecycleTimings.Recorder(directory, 1);
        long first = recorder.begin("texture", "release", "first", "test");
        recorder.end(first, 1, 1, true);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean();
        Thread producer = new Thread(() -> {
            long outer = recorder.begin("managed_texture", "release", "outer", "test");
            long child = recorder.begin("texture", "release", "second", "test");
            entered.countDown(); recorder.end(child, 2, 2, true);
            recorder.end(outer, 2, 2, true); completed.set(true);
        });
        producer.start(); assertTrue(entered.await(2, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (producer.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield();
        assertEquals(Thread.State.WAITING, producer.getState());
        assertFalse(completed.get());
        recorder.start(); producer.join(5000);
        assertFalse(producer.isAlive()); recorder.close();
        String summary = text(directory, "summary.json");
        assertTrue(summary, summary.contains("\"written\":3"));
        assertTrue(summary, summary.contains("\"unsaved\":0"));
        assertTrue(recorder.totalWait > 0);
        List<String> lines = Files.readAllLines(new File(directory, "events.jsonl").toPath(), StandardCharsets.UTF_8);
        assertFalse(lines.get(3).contains("\"nested_diagnostic_wait_ns\":0,"));
    }

    @Test public void concurrentProducersAreLosslessAndIdsRemainUnique() throws Exception {
        File directory = directory();
        ResourceLifecycleTimings.Recorder recorder = new ResourceLifecycleTimings.Recorder(directory, 8); recorder.start();
        Thread[] producers = new Thread[4];
        for (int p = 0; p < producers.length; p++) {
            producers[p] = new Thread(() -> {
                for (int i = 0; i < 500; i++) {
                    long token = recorder.begin("texture", "create", "same", "test");
                    recorder.end(token, i, i, true);
                }
            }); producers[p].start();
        }
        for (Thread producer : producers) { producer.join(10000); assertFalse(producer.isAlive()); }
        recorder.close();
        assertEquals(2000, recorder.written);
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (String line : Files.readAllLines(new File(directory, "events.jsonl").toPath(), StandardCharsets.UTF_8)) {
            if (!line.contains("\"type\":\"event\"")) continue;
            assertTrue(ids.add(line.split("\"id\":")[1].split(",")[0]));
        }
        assertEquals(2000, ids.size());
    }

    @Test public void writeFailureWakesProducersAndReportsIncompleteWithoutThrowingIntoGameplay() throws Exception {
        File root = directory();
        File impossible = new File(root, "file"); Files.write(impossible.toPath(), new byte[] {1});
        ResourceLifecycleTimings.Recorder recorder = new ResourceLifecycleTimings.Recorder(impossible, 1);
        long first = recorder.begin("texture", "create", "a", "test"); recorder.end(first, 1, 1, true);
        Thread producer = new Thread(() -> {
            long token = recorder.begin("texture", "create", "blocked", "test");
            recorder.end(token, 2, 2, true);
        });
        producer.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (producer.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield();
        assertEquals(Thread.State.WAITING, producer.getState());
        recorder.start(); recorder.writer.join(5000); assertFalse(recorder.writer.isAlive());
        producer.join(5000); assertFalse(producer.isAlive());
        long second = recorder.begin("texture", "create", "b", "test"); recorder.end(second, 3, 3, true);
        assertNotNull(recorder.error); assertEquals(3, recorder.failed);
    }

    @Test public void rotatesOnlyAtSessionBoundaryAndAcknowledgesFlushedExportPrefix() throws Exception {
        File directory = directory();
        ResourceLifecycleTimings.Recorder first = new ResourceLifecycleTimings.Recorder(directory, 4);
        first.start(); long token = first.begin("texture", "release", "old", "test"); first.end(token, 1, 1, true); first.close();
        ResourceLifecycleTimings.Recorder next = new ResourceLifecycleTimings.Recorder(directory, 4); next.start();
        token = next.begin("texture", "release", "new", "test"); next.end(token, 2, 2, true);
        Files.write(new File(directory, "flush.request").toPath(), "export-test".getBytes(StandardCharsets.UTF_8));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        File ack = new File(directory, "flush.ack");
        while (!ack.isFile() && System.nanoTime() < deadline) Thread.yield();
        assertTrue(ack.isFile());
        assertTrue(text(directory, "flush.ack").startsWith("export-test\n"));
        assertTrue(text(directory, "previous-events.jsonl").contains("\"key\":\"old\""));
        next.close(); assertTrue(text(directory, "events.jsonl").contains("\"key\":\"new\""));
    }

    @Test public void cleanShutdownWaitsForAlreadyActiveSpanAndDrainsItsEvent() throws Exception {
        File directory = directory();
        ResourceLifecycleTimings.Recorder recorder = new ResourceLifecycleTimings.Recorder(directory, 1); recorder.start();
        long token = recorder.begin("texture", "release", "shutdown", "test");
        Thread shutdown = new Thread(recorder::close); shutdown.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!recorder.closing && System.nanoTime() < deadline) Thread.yield();
        assertTrue(recorder.closing);
        recorder.end(token, 1, 1, true);
        shutdown.join(5000); assertFalse(shutdown.isAlive());
        assertEquals(1, recorder.written);
        assertEquals(0L, recorder.begin("texture", "release", "too-late", "test"));
    }
}
