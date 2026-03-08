package io.stamethyst.bridge;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BootBridgeGcHistogramDumperTest {
    @Test
    public void dumpsHistogramAfterSustainedHighGcRate() throws Exception {
        File outputDir = Files.createTempDirectory("boot-bridge-histo").toFile();
        AtomicLong nowMs = new AtomicLong(0L);
        AtomicLong gcCount = new AtomicLong(0L);
        AtomicLong dumpCount = new AtomicLong(0L);
        AtomicReference<String> lastScene = new AtomicReference<String>();
        AtomicReference<String> lastHistogram = new AtomicReference<String>();

        BootBridgeGcHistogramDumper dumper = new BootBridgeGcHistogramDumper(
                outputDir,
                1,
                1_000L,
                60_000L,
                nowMs::get,
                gcCount::get,
                () -> "histogram-body",
                () -> "mode=CHAR_SELECT,mainMenu=true,screen=MAIN_MENU",
                (dir, now, gcPerMin, heapUsed, heapMax, scene, histogram) -> {
                    dumpCount.incrementAndGet();
                    lastScene.set(scene);
                    lastHistogram.set(histogram);
                    File marker = new File(dir, "dump.txt");
                    Files.write(marker.toPath(), histogram.getBytes(StandardCharsets.UTF_8));
                }
        );

        for (int second = 0; second < 4; second++) {
            nowMs.set(second * 1_000L);
            gcCount.incrementAndGet();
            dumper.sampleAndMaybeDump();
        }

        File[] files = outputDir.listFiles();
        assertEquals("Expected one histogram dump", 1L, dumpCount.get());
        assertTrue("Expected marker file to be written", files != null && files.length >= 1);
        assertEquals("mode=CHAR_SELECT,mainMenu=true,screen=MAIN_MENU", lastScene.get());
        assertEquals("histogram-body", lastHistogram.get());
        deleteRecursively(outputDir);
    }

    @Test
    public void doesNotDumpBeforeSustainWindowElapses() throws Exception {
        File outputDir = Files.createTempDirectory("boot-bridge-histo").toFile();
        AtomicLong nowMs = new AtomicLong(0L);
        AtomicLong gcCount = new AtomicLong(0L);
        AtomicLong dumpCount = new AtomicLong(0L);

        BootBridgeGcHistogramDumper dumper = new BootBridgeGcHistogramDumper(
                outputDir,
                3,
                10_000L,
                60_000L,
                nowMs::get,
                gcCount::get,
                () -> "histogram-body",
                () -> "unknown",
                (dir, now, gcPerMin, heapUsed, heapMax, scene, histogram) -> dumpCount.incrementAndGet()
        );

        for (int second = 0; second < 5; second++) {
            nowMs.set(second * 1_000L);
            gcCount.incrementAndGet();
            dumper.sampleAndMaybeDump();
        }

        File[] files = outputDir.listFiles();
        assertEquals("Histogram should not dump before sustain window", 0L, dumpCount.get());
        assertEquals("Histogram should not dump before sustain window", 0, files == null ? 0 : files.length);
        deleteRecursively(outputDir);
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
