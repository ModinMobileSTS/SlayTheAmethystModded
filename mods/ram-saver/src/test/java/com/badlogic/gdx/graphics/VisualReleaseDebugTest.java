package com.badlogic.gdx.graphics;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;
import optispire.RamSaver;
import optispire.debug.ReleaseNoticeFeed;
import org.junit.Test;
import java.lang.ref.Reference;
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
        assertEquals(RamSaverReleaseEvents.CREATE, RamSaverReleaseEvents.event(before + 2).action);
        assertEquals("explicit", RamSaverReleaseEvents.event(before + 3).reason);
        Texture fake = new Texture(new MemoryFile("fake-only.png", new byte[0]));
        fake.dispose();
        RamSaverReleaseEvents.beginInternalResources();
        try {
            Texture diagnosticPixel = new Texture(new MockData(1));
            diagnosticPixel.dispose();
        } finally { RamSaverReleaseEvents.endInternalResources(); }
        assertEquals(before + 3, RamSaverReleaseEvents.latestSequence());
    }

    @Test public void replacementAndRetiredParentCleanupAreReportedSeparately() throws Exception {
        Texture parent = load("replaced-parent", 16, true);
        TextureAtlas.AtlasRegion region = new TextureAtlas.AtlasRegion(parent, 0, 0, 16, 16);
        RamSaver.loadRegion("dependent-region", region, RamSaver.getAssetHolder("replaced-parent"), false);
        long before = RamSaverReleaseEvents.latestSequence();
        RamSaver.loadTexture("replaced-parent", true);
        assertEquals(RamSaverReleaseEvents.CREATE, RamSaverReleaseEvents.event(before + 1).action);
        assertEquals("replacement", RamSaverReleaseEvents.event(before + 2).reason);
        RamSaver.update();
        RamSaverReleaseEvents.Event unlink = RamSaverReleaseEvents.event(before + 3);
        assertEquals("retired-parent", unlink.reason);
        assertEquals(RamSaverReleaseEvents.REGION, unlink.kind);
        assertEquals(0, unlink.estimatedBytes);
        assertEquals(before + 3, RamSaverReleaseEvents.latestSequence());
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
        RamSaverReleaseEvents.Event created = RamSaverReleaseEvents.event(RamSaverReleaseEvents.latestSequence());
        assertEquals(RamSaverReleaseEvents.REGION, created.kind);
        assertEquals(RamSaverReleaseEvents.CREATE, created.action);
        assertEquals(0L, created.estimatedBytes);
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
        for (int i = 0; i < 7; i++) {
            String key = "budgeted-visual-" + i;
            load(key, 16, true);
            RamSaver.age(key);
        }
        long before = RamSaverReleaseEvents.latestSequence();
        tick();
        assertEquals(4, deleted.size());
        assertEquals(before + 4, RamSaverReleaseEvents.latestSequence());
        RamSaver.update();
        assertEquals(7, deleted.size());
        assertEquals(before + 7, RamSaverReleaseEvents.latestSequence());
    }

    @Test public void latestEventsReplaceOldRowsImmediatelyAndNeverFadeDuringIdle() {
        ReleaseNoticeFeed feed = new ReleaseNoticeFeed();
        long before = RamSaverReleaseEvents.latestSequence();
        for (int i = 0; i < 19; i++) record("burst-" + i, 900);
        feed.update(1L);
        assertEquals(ReleaseNoticeFeed.PAGE_SIZE, feed.size);
        for (int i = 0; i < feed.size; i++) assertEquals(before + 19 - i, feed.page[i].sequence);
        feed.update(Long.MAX_VALUE);
        assertEquals(before + 19, feed.page[0].sequence);
        record("newest", 900);
        feed.update(Long.MAX_VALUE);
        assertEquals(before + 20, feed.page[0].sequence);
        assertEquals(before + 19, feed.page[1].sequence);
    }

    @Test public void scrollKeepsViewedEventsAnchoredUntilHistoryIsOverwrittenAndCanReturnToLatest() {
        ReleaseNoticeFeed feed = new ReleaseNoticeFeed();
        for (int i = 0; i < 19; i++) record("scroll-" + i, 901);
        feed.update(1L); feed.scroll(8); feed.update(2L);
        long viewed = feed.page[0].sequence;
        record("new-while-reading", 901);
        feed.update(3L);
        assertEquals(9, feed.offset());
        assertEquals(viewed, feed.page[0].sequence);
        for (int i = 0; i < RamSaverReleaseEvents.CAPACITY + 7; i++) record("overflow", 901);
        feed.update(4L);
        assertEquals(RamSaverReleaseEvents.firstSequence() - 1, feed.overwritten);
        assertEquals(RamSaverReleaseEvents.CAPACITY, feed.available());
        assertEquals(feed.maxOffset(), feed.offset());
        assertEquals(0, feed.pending());
        feed.scroll(-Integer.MAX_VALUE); feed.update(5L);
        assertEquals(0, feed.offset());
        assertEquals(RamSaverReleaseEvents.latestSequence(), feed.page[0].sequence);
        feed.scroll(Integer.MAX_VALUE); feed.update(6L);
        assertEquals(feed.maxOffset(), feed.offset());
        feed.newest(); feed.update(7L);
        assertEquals(RamSaverReleaseEvents.latestSequence(), feed.page[0].sequence);
    }

    @Test public void successfulImageUploadAndRawTextureDeletionAreBothVisibleButLazyWrappersAreNot() {
        long before = RamSaverReleaseEvents.latestSequence();
        Texture fake = new Texture(new MemoryFile("lazy-only.png", new byte[0]));
        assertEquals(before, RamSaverReleaseEvents.latestSequence());
        Texture image = new Texture(new MockData(16));
        RamSaverReleaseEvents.Event create = RamSaverReleaseEvents.event(before + 1);
        assertEquals(RamSaverReleaseEvents.CREATE, create.action);
        assertEquals(image.peekTextureObjectHandle(), create.handle);
        assertEquals(1024L, create.estimatedBytes);
        image.dispose(); image.dispose(); fake.dispose();
        RamSaverReleaseEvents.Event destroy = RamSaverReleaseEvents.event(before + 2);
        assertEquals(RamSaverReleaseEvents.DESTROY, destroy.action);
        assertNotEquals(create.color, destroy.color);
        assertEquals(before + 2, RamSaverReleaseEvents.latestSequence());
    }

    @Test public void failedUploadNeverClaimsSuccessfulImageCreation() {
        long before = RamSaverReleaseEvents.latestSequence();
        try {
            new RealTexture(new MockData(16) {
                @Override public void consumeCustomData(int target) { throw new IllegalStateException("failed upload"); }
            });
            fail("expected upload failure");
        } catch (IllegalStateException expected) { }
        for (long sequence = before + 1; sequence <= RamSaverReleaseEvents.latestSequence(); sequence++)
            assertNotEquals(RamSaverReleaseEvents.CREATE, RamSaverReleaseEvents.event(sequence).action);
    }

    private static void record(String key, long frame) {
        RamSaverReleaseEvents.record(frame, key, key + ".png", RamSaverReleaseEvents.TEXTURE, "idle", 64, 1);
    }

    private static final class VisualFrameBuffer extends GLFrameBuffer<Texture> {
        VisualFrameBuffer() { super(Pixmap.Format.RGBA8888, 16, 16, false, false); }
        @Override protected Texture createColorTexture() { return new RealTexture(new MockData(16)); }
        @Override protected void disposeColorTexture(Texture texture) { texture.dispose(); }
        @Override protected void attachFrameBufferColorTexture() { }
    }

    @Test public void framebufferBuildAndDeleteAreVisibleAlongsideAttachmentLifecycle() {
        Gdx.gl = Gdx.gl20 = proxy(GL20.class, (name, args) -> {
            if (name.equals("glGenTexture")) { live.add(++generated); return generated; }
            if (name.equals("glDeleteTexture")) { int id = (Integer) args[0]; assertTrue(live.remove(id)); deleted.add(id); }
            if (name.equals("glBindTexture")) bound = (Integer) args[1];
            if (name.equals("glGetIntegerv")) ((java.nio.IntBuffer) args[1]).put(0, (Integer) args[0] == GL20.GL_TEXTURE_BINDING_2D ? bound : 0);
            if (name.equals("glGenFramebuffer")) return 100;
            if (name.equals("glCheckFramebufferStatus")) return GL20.GL_FRAMEBUFFER_COMPLETE;
            return null;
        });
        long before = RamSaverReleaseEvents.latestSequence();
        VisualFrameBuffer buffer = new VisualFrameBuffer();
        RamSaverReleaseEvents.Event created = RamSaverReleaseEvents.event(before + 2);
        assertEquals(RamSaverReleaseEvents.FRAMEBUFFER, created.kind);
        assertEquals(RamSaverReleaseEvents.CREATE, created.action);
        assertEquals(100, created.handle);
        buffer.dispose(); buffer.dispose();
        RamSaverReleaseEvents.Event destroyed = RamSaverReleaseEvents.event(before + 4);
        assertEquals(RamSaverReleaseEvents.FRAMEBUFFER, destroyed.kind);
        assertEquals(RamSaverReleaseEvents.DESTROY, destroyed.action);
        assertNotEquals(created.color, destroyed.color);
        assertEquals(before + 4, RamSaverReleaseEvents.latestSequence());
    }
}
