package com.badlogic.gdx.backends.lwjgl;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class RuntimeStageDiagnosticsTest {
    @Test public void onlyActiveStagesAtOrBeyondThresholdAreStalls() {
        assertFalse(RuntimeStageDiagnostics.isStalled(4999, true));
        assertTrue(RuntimeStageDiagnostics.isStalled(5000, true));
        assertFalse(RuntimeStageDiagnostics.isStalled(100000, false));
    }

    @Test public void nestedStagesLogTheirOwnBeginAndEndWithProcessAndSessionIdentity() throws Exception {
        PrintStream old = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final CountDownLatch observed = new CountDownLatch(1);
        try {
            System.setOut(new PrintStream(bytes, true, "UTF-8") {
                @Override public void println(String value) {
                    super.println(value);
                    if (value.contains("event=stalled_observation") && value.contains("stage=test_blocked")) {
                        observed.countDown();
                    }
                }
            });
            try (RuntimeStageDiagnostics.Stage outer = RuntimeStageDiagnostics.begin("test_display")) {
                try (RuntimeStageDiagnostics.Stage inner = RuntimeStageDiagnostics.begin("test_extensions")) {
                    assertNotNull(inner.previous);
                    assertSame(outer, inner.previous);
                }
            }
            try (RuntimeStageDiagnostics.Stage blocked = RuntimeStageDiagnostics.begin("test_blocked")) {
                assertTrue("Independent observer did not report a blocked stage", observed.await(8, TimeUnit.SECONDS));
                assertTrue(Thread.currentThread().isAlive()); // Observer must not terminate the owner.
            }
        } finally { System.setOut(old); }
        String log = bytes.toString("UTF-8");
        assertTrue(log.contains("event=begin"));
        assertTrue(log.contains("event=end"));
        assertTrue(log.contains("process="));
        assertTrue(log.contains("processStartMs="));
        assertTrue(log.contains("session="));
        assertTrue(log.contains("stage=test_display"));
        assertTrue(log.contains("stage=test_extensions"));
        assertTrue(log.contains("event=stalled_observation"));
    }
}
