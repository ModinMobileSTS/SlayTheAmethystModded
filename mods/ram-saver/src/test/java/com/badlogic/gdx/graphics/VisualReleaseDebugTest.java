package com.badlogic.gdx.graphics;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import optispire.RamSaver;
import optispire.debug.ReleaseNoticeFeed;
import org.junit.Test;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class VisualReleaseDebugTest extends RamSaverTestSupport {
    @Test public void actualDeletionCarriesIdentityBytesReasonAndMatchingFrameColor() throws Exception {
        assertTrue(RamSaverReleaseEvents.ENABLED);
        Gdx.graphics = proxy(Graphics.class, (name, args) -> name.equals("getFrameId") ? 317L : null);
        Texture texture = load("visual-idle", 16, true);
        int handle = texture.getTextureObjectHandle();
        long before = RamSaverReleaseEvents.latestSequence();
        RamSaver.age("visual-idle");
        assertEquals("Aging is not deletion", before, RamSaverReleaseEvents.latestSequence());
        tick();
        RamSaverReleaseEvents.Event event = RamSaverReleaseEvents.event(before + 1);
        assertEquals(before + 1, RamSaverReleaseEvents.latestSequence());
        assertEquals("visual-idle", event.key);
        assertEquals("visual-idle.png", event.resource);
        assertEquals("idle", event.reason);
        assertEquals(RamSaverReleaseEvents.TEXTURE, event.kind);
        assertEquals(16 * 16 * 4, event.estimatedBytes);
        assertEquals(317, event.frame);
        assertEquals(handle, event.oldHandle);
        assertTrue(deleted.contains(handle));
        assertTrue((RamSaverReleaseEvents.colorsForFrame(317) & (1 << event.color)) != 0);
        assertEquals(0, RamSaverReleaseEvents.colorsForFrame(318));
    }

    @Test public void explicitAndCallerDisposalEmitOnceAndNoFakeWrapperOrStaleNotificationDoes() throws Exception {
        Texture texture = load("caller", 16, true);
        long before = RamSaverReleaseEvents.latestSequence();
        texture.dispose();
        texture.dispose();
        RamSaver.dispose("caller");
        assertEquals(before + 1, RamSaverReleaseEvents.latestSequence());
        assertEquals("caller-dispose", RamSaverReleaseEvents.event(before + 1).reason);

        load("explicit", 16, true);
        RamSaver.dispose("explicit");
        assertEquals("explicit", RamSaverReleaseEvents.event(before + 2).reason);
        Texture fake = new Texture(new MemoryFile("fake-only.png", new byte[0]));
        fake.dispose();
        Texture diagnosticPixel = new Texture(new MockData(1));
        diagnosticPixel.dispose(); // The overlay must never generate notifications about its own pixel.
        assertEquals(before + 2, RamSaverReleaseEvents.latestSequence());
    }

    @Test public void replacementAndRetiredParentCleanupAreReportedSeparately() throws Exception {
        Texture parent = load("replaced-parent", 16, true);
        TextureAtlas.AtlasRegion region = new TextureAtlas.AtlasRegion(parent, 0, 0, 16, 16);
        RamSaver.loadRegion("dependent-region", region, RamSaver.getAssetHolder("replaced-parent"), false);
        long before = RamSaverReleaseEvents.latestSequence();
        RamSaver.loadTexture("replaced-parent", true);
        assertEquals("replacement", RamSaverReleaseEvents.event(before + 1).reason);
        RamSaver.update();
        RamSaverReleaseEvents.Event unlink = RamSaverReleaseEvents.event(before + 2);
        assertEquals("retired-parent", unlink.reason);
        assertEquals(RamSaverReleaseEvents.REGION, unlink.kind);
        assertEquals(0, unlink.estimatedBytes);
        assertEquals(before + 2, RamSaverReleaseEvents.latestSequence());
    }

    @Test public void nullSentinelIsNotARealResourceRelease() {
        RamSaver.registerTexture("null-sentinel", new RamSaver.FileTextureSupplier(
                new MemoryFile("null.png", new byte[0]), null, false) {
            public Texture get() { return null; }
        });
        RamSaver.loadTexture("null-sentinel", true);
        long before = RamSaverReleaseEvents.latestSequence();
        RamSaver.dispose("null-sentinel");
        assertEquals(before, RamSaverReleaseEvents.latestSequence());
    }

    @Test public void regionUnlinkHasNoGpuBytesAndRepeatedQueueNoticeCannotDoubleCount() throws Exception {
        Texture parent = load("region-parent", 16, true);
        TextureAtlas.AtlasRegion region = new TextureAtlas.AtlasRegion(parent, 0, 0, 16, 16);
        RamSaver.loadRegion("visual-region", region, RamSaver.getAssetHolder("region-parent"), false);
        Reference<?> reference = (Reference<?>) field(map("loadedAssets").get("visual-region"), "asset");
        reference.clear();
        reference.enqueue();
        long before = RamSaverReleaseEvents.latestSequence();
        RamSaver.update();
        RamSaver.update();
        RamSaverReleaseEvents.Event event = RamSaverReleaseEvents.event(before + 1);
        assertEquals(before + 1, RamSaverReleaseEvents.latestSequence());
        assertEquals("gc-region", event.reason);
        assertEquals("region-parent.png", event.resource);
        assertEquals(RamSaverReleaseEvents.REGION, event.kind);
        assertEquals(0, event.estimatedBytes);
        assertEquals(0, event.oldHandle);
        assertTrue(live.contains(parent.getTextureObjectHandle()));
    }

    @Test public void enablingVisualizationDoesNotChangeTheSharedReleaseLimit() throws Exception {
        long before = RamSaverReleaseEvents.latestSequence();
        for (int i = 0; i < 7; i++) {
            String key = "budgeted-visual-" + i;
            load(key, 16, true);
            RamSaver.age(key);
        }
        tick();
        assertEquals(4, deleted.size());
        assertEquals(before + 4, RamSaverReleaseEvents.latestSequence());
        RamSaver.update();
        assertEquals(7, deleted.size());
        assertEquals(before + 7, RamSaverReleaseEvents.latestSequence());
    }

    @Test public void burstIsPagedInOrderWithoutSamplingAndOverflowIsExplicit() {
        ReleaseNoticeFeed feed = new ReleaseNoticeFeed();
        // Skip old test history before starting this acceptance burst.
        long now = 1L;
        while (feed.pending() > 0) { feed.update(now); now += 2_000_000_000L; }
        long before = RamSaverReleaseEvents.latestSequence();
        for (int i = 0; i < 19; i++) record("burst-" + i, 900);
        List<Long> shown = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            now += 2_000_000_000L;
            feed.update(now);
            for (int i = 0; i < feed.size; i++) shown.add(feed.page[i].sequence);
        }
        assertEquals(19, shown.size());
        for (int i = 0; i < 19; i++) assertEquals(before + i + 1, (long) shown.get(i));
        long oldOverflow = feed.overwritten;
        for (int i = 0; i < RamSaverReleaseEvents.CAPACITY + 7; i++) record("overflow", 901);
        feed.update(now + 2_000_000_000L);
        assertEquals(oldOverflow + 7, feed.overwritten);
        assertEquals(RamSaverReleaseEvents.CAPACITY - ReleaseNoticeFeed.PAGE_SIZE, feed.pending());
    }

    private static void record(String key, long frame) {
        RamSaverReleaseEvents.record(frame, key, key + ".png", RamSaverReleaseEvents.TEXTURE, "idle", 64, 1);
    }
}
