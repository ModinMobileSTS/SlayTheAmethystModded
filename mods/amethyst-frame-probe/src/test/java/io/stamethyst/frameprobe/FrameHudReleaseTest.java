package io.stamethyst.frameprobe;

import com.badlogic.gdx.backends.lwjgl.FrameRingBuffer;
import com.badlogic.gdx.graphics.RamSaverReleaseEvents;
import java.lang.reflect.Field;
import org.junit.Test;
import static org.junit.Assert.*;

public class FrameHudReleaseTest {
    @Test public void markersUseCompletedFrameNotDrainTimeAndKeepEveryColorInOneFrame() throws Exception {
        FrameHud hud = new FrameHud(null, Long.MAX_VALUE, null, false);
        String first = "first.png";
        String second = "second.png";
        while (RamSaverReleaseEvents.colorIndex(first) == RamSaverReleaseEvents.colorIndex(second)) second += "x";
        release(701L, first);
        release(701L, second);
        recordFrame(700L);
        recordFrame(701L);
        recordFrame(702L);
        hud.update();
        int[] colors = (int[]) field(hud, "releaseColors");
        long[] times = (long[]) field(hud, "ring");
        assertEquals(0, colors[0]);
        assertEquals((1 << RamSaverReleaseEvents.colorIndex(first)) | (1 << RamSaverReleaseEvents.colorIndex(second)), colors[1]);
        assertEquals(0, colors[2]);
        assertEquals("Timing values must not be replaced by release data", 42L, times[1]);
    }

    @Test public void frameSlotsCannotMistakeOldEventsForNewFramesAfterWrap() throws Exception {
        release(1001L, "old");
        assertNotEquals(0, RamSaverReleaseEvents.colorsForFrame(1001L));
        assertEquals(0, RamSaverReleaseEvents.colorsForFrame(2801L));
        release(2801L, "new");
        assertEquals(0, RamSaverReleaseEvents.colorsForFrame(1001L));
        assertNotEquals(0, RamSaverReleaseEvents.colorsForFrame(2801L));
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void recordFrame(long frame) {
        FrameRingBuffer.record(frame, 0L, 42L, 0L, 0L, 0L, 0L, 0L, 0, 0);
    }

    private static void release(long frame, String key) {
        RamSaverReleaseEvents.record(frame, key, key, RamSaverReleaseEvents.TEXTURE, "idle", 64, 1);
    }
}
