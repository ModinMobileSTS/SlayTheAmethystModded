package com.badlogic.gdx.graphics;

import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.Method;
import org.junit.Test;
import static org.junit.Assert.*;

/** Isolated loaders verify both immutable startup switch states in one test JVM. */
public class RamSaverReleaseEventsTest {
    @Test public void disabledDoesNotAllocateHistoryOrRecordEvents() throws Exception {
        withBridge(false, bridge -> {
            java.lang.reflect.Field history = bridge.getDeclaredField("HISTORY");
            history.setAccessible(true);
            assertNull(history.get(null));
            record(bridge, 3L, "ignored", 1);
            assertEquals(0L, bridge.getMethod("latestSequence").invoke(null));
        });
    }

    @Test public void enabledBoundsHistoryButPreservesActionFrameMarkers() throws Exception {
        withBridge(true, bridge -> {
            Method color = bridge.getMethod("colorIndex", int.class);
            int expected = (Integer) color.invoke(null, 2);
            record(bridge, 3L, "same.png", 1);
            for (int i = 0; i < 520; i++) record(bridge, 4L, "other-" + i, 1);
            assertEquals(521L, bridge.getMethod("latestSequence").invoke(null));
            assertNull(bridge.getMethod("event", long.class).invoke(null, 1L));
            assertEquals(10L, bridge.getMethod("firstSequence").invoke(null));
            assertEquals(1 << expected, bridge.getMethod("colorsForFrame", long.class).invoke(null, 3L));
            assertEquals(expected, color.invoke(null, 2));
            record(bridge, 5L, "region", 2);
            Object region = bridge.getMethod("event", long.class).invoke(null, 522L);
            assertEquals("Region unlink must never report GPU bytes", 0L,
                    region.getClass().getField("estimatedBytes").getLong(region));
        });
    }

    @Test public void creationAndDestructionShareFrameButNeverShareActionColor() throws Exception {
        withBridge(true, bridge -> {
            bridge.getMethod("recordCreated", long.class, String.class, String.class, int.class, String.class, long.class, int.class)
                    .invoke(null, 70L, "same.png", "same.png", 1, "upload", 128L, 17);
            record(bridge, 70L, "same.png", 1);
            Object create = bridge.getMethod("event", long.class).invoke(null, 1L);
            Object destroy = bridge.getMethod("event", long.class).invoke(null, 2L);
            Class<?> type = create.getClass();
            assertEquals(1, type.getField("action").getInt(create));
            assertEquals(17, type.getField("handle").getInt(create));
            assertEquals(0, type.getField("oldHandle").getInt(create));
            assertEquals(2, type.getField("action").getInt(destroy));
            assertNotEquals(type.getField("color").getInt(create), type.getField("color").getInt(destroy));
            assertEquals(3, bridge.getMethod("colorsForFrame", long.class).invoke(null, 70L));
        });
    }

    @Test public void nestedInternalPixelsProduceNeitherHistoryNorFrameMarkers() throws Exception {
        withBridge(true, bridge -> {
            bridge.getMethod("beginInternalResources").invoke(null);
            bridge.getMethod("beginInternalResources").invoke(null);
            record(bridge, 9L, "internal", 1);
            bridge.getMethod("endInternalResources").invoke(null);
            record(bridge, 9L, "still-internal", 1);
            assertEquals(0L, bridge.getMethod("latestSequence").invoke(null));
            bridge.getMethod("endInternalResources").invoke(null);
            record(bridge, 9L, "real", 1);
            assertEquals(1L, bridge.getMethod("latestSequence").invoke(null));
        });
    }

    private interface Check { void run(Class<?> bridge) throws Exception; }

    private static void withBridge(boolean enabled, Check check) throws Exception {
        String old = System.getProperty("ramsaver.debug.visual");
        System.setProperty("ramsaver.debug.visual", Boolean.toString(enabled));
        URL classes = RamSaverReleaseEvents.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes}, null)) {
            check.run(Class.forName(RamSaverReleaseEvents.class.getName(), true, loader));
        } finally {
            if (old == null) System.clearProperty("ramsaver.debug.visual");
            else System.setProperty("ramsaver.debug.visual", old);
        }
    }

    private static void record(Class<?> bridge, long frame, String key, int kind) throws Exception {
        bridge.getMethod("record", long.class, String.class, String.class, int.class, String.class, long.class, int.class)
                .invoke(null, frame, key, key, kind, "idle", 128L, 1);
    }
}
