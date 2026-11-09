package io.stamethyst.frameprobe;

import com.badlogic.gdx.backends.lwjgl.FrameRingBuffer;
import com.badlogic.gdx.graphics.RamSaverReleaseEvents;
import java.lang.reflect.Field;
import org.junit.Test;
import static org.junit.Assert.*;

public class FrameHudReleaseTest {
    @Test public void markersUseCompletedFrameNotDrainTimeAndKeepBothActionsInOneFrame() throws Exception {
        FrameHud hud = new FrameHud(null, Long.MAX_VALUE, null, false);
        String first = "first.png";
        String second = "second.png";
        release(701L, first);
        RamSaverReleaseEvents.recordCreated(701L, second, second, RamSaverReleaseEvents.TEXTURE, "upload", 64, 1);
        recordFrame(700L);
        recordFrame(701L);
        recordFrame(702L);
        hud.update();
        int[] colors = (int[]) field(hud, "releaseColors");
        long[] times = (long[]) field(hud, "ring");
        assertEquals(0, colors[0]);
        assertEquals((1 << RamSaverReleaseEvents.colorIndex(RamSaverReleaseEvents.CREATE))
                | (1 << RamSaverReleaseEvents.colorIndex(RamSaverReleaseEvents.DESTROY)), colors[1]);
        assertEquals(0, colors[2]);
        assertEquals("Timing values must not be replaced by release data", 42L, times[1]);
    }

    @Test public void creationOnlyAndDestructionOnlyHaveDifferentMarkersWithoutChangingTiming() throws Exception {
        FrameHud hud = new FrameHud(null, Long.MAX_VALUE, null, false);
        RamSaverReleaseEvents.recordCreated(713L, "image", "image.png", RamSaverReleaseEvents.TEXTURE, "upload", 64, 2);
        release(714L, "image");
        recordFrame(713L); recordFrame(714L);
        hud.update();
        int[] colors = (int[]) field(hud, "releaseColors");
        assertEquals(1, colors[0]);
        assertEquals(2, colors[1]);
        long[] times = (long[]) field(hud, "ring");
        assertEquals(42L, times[0]); assertEquals(42L, times[1]);
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
