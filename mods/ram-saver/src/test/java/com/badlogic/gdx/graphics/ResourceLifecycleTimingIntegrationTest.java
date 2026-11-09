package com.badlogic.gdx.graphics;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import optispire.RamSaver;
import org.junit.Test;
import org.junit.Before;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Real lifecycle entry points with mock GL, independent of visual and verbose diagnostics. */
public class ResourceLifecycleTimingIntegrationTest extends RamSaverTestSupport {
    private long firstId;

    @Before public void rememberSessionPosition() throws Exception {
        assertTrue(ResourceLifecycleTimings.ENABLED);
        for (JsonObject event : snapshot()) firstId = Math.max(firstId, event.get("id").getAsLong());
    }

    private List<JsonObject> snapshot() throws Exception {
        File directory = new File(System.getProperty(ResourceLifecycleTimings.PROPERTY + ".dir"));
        directory.mkdirs();
        String request = "test-" + System.nanoTime();
        Files.write(new File(directory, "flush.request").toPath(), request.getBytes(StandardCharsets.UTF_8));
        File acknowledgement = new File(directory, "flush.ack");
        long deadline = System.nanoTime() + 5000000000L;
        while (System.nanoTime() < deadline) {
            try {
                if (acknowledgement.isFile() && new String(Files.readAllBytes(acknowledgement.toPath()), StandardCharsets.UTF_8).startsWith(request + "\n")) break;
            } catch (java.nio.file.NoSuchFileException startupRotation) { }
            Thread.yield();
        }
        assertTrue(acknowledgement.isFile());
        assertTrue(new String(Files.readAllBytes(acknowledgement.toPath()), StandardCharsets.UTF_8).startsWith(request + "\n"));
        List<JsonObject> result = new ArrayList<>();
        for (String line : Files.readAllLines(new File(directory, "events.jsonl").toPath(), StandardCharsets.UTF_8)) {
            JsonObject json = new JsonParser().parse(line).getAsJsonObject();
            if (json.get("type").getAsString().equals("event") && json.get("id").getAsLong() > firstId) result.add(json);
        }
        return result;
    }

    private int count(List<JsonObject> events, String kind, String action, String key) {
        int count = 0;
        for (JsonObject event : events) {
            if (kind.equals(event.get("kind").getAsString()) && action.equals(event.get("action").getAsString())
                    && (key == null || (!event.get("key").isJsonNull() && key.equals(event.get("key").getAsString())))) count++;
        }
        return count;
    }

    @Test public void allRepetitionsAndActualDeletesAreRecordedWithNoDuplicateGpuDeletion() throws Exception {
        for (int i = 0; i < 8; i++) {
            Texture texture = load("timing-repeat", 16, true);
            texture.dispose(); texture.dispose();
        }
        List<JsonObject> events = snapshot();
        assertEquals(8, count(events, "managed_texture", "create", "timing-repeat"));
        assertEquals(8, count(events, "texture", "release", null));
        assertEquals(8, count(events, "texture", "create_handle", null));
        assertEquals(8, count(events, "texture", "prepare_upload", null));
        assertEquals(8, count(events, "texture", "upload", null));
        assertEquals(8, deleted.size());
        for (JsonObject event : events) {
            assertTrue(event.get("blocking_ns").getAsLong() >= 0L);
            if ("upload".equals(event.get("action").getAsString())) {
                assertTrue(event.get("handle").getAsInt() > 0);
                assertEquals(1024L, event.get("estimated_bytes").getAsLong());
            }
        }
    }

    @Test public void failedUploadAndCleanupAreRecorded() throws Exception {
        RamSaver.registerTexture("timing-failure", new RamSaver.FileTextureSupplier(new MemoryFile("failure.png", new byte[0]), null, false) {
            @Override public Texture get() {
                return new RealTexture(new MockData(16) {
                    @Override public void consumeCustomData(int target) { throw new IllegalStateException("test upload failure"); }
                });
            }
        });
        try { RamSaver.loadTexture("timing-failure", true); fail(); }
        catch (IllegalStateException expected) { assertEquals("test upload failure", expected.getMessage()); }
        List<JsonObject> events = snapshot();
        int failures = 0;
        for (JsonObject event : events) if (!event.get("success").getAsBoolean()) failures++;
        assertEquals(3, failures); // upload, prepare/upload, managed creation
        assertEquals(1, count(events, "texture", "release", null));
        assertEquals(1, deleted.size());
    }

    @Test public void regionsAndReplacementHaveSeparateZeroByteBookkeepingRecords() throws Exception {
        Texture texture = load("timing-replacement", 16, true);
        RamSaver.loadRegion("timing-region", new TextureAtlas.AtlasRegion(texture, 0, 0, 16, 16),
                RamSaver.getAssetHolder("timing-replacement"), true);
        RamSaver.dispose("timing-region");
        RamSaver.loadTexture("timing-replacement", true);
        RamSaver.dispose("timing-replacement");
        List<JsonObject> events = snapshot();
        assertEquals(1, count(events, "region", "create", "timing-region"));
        assertEquals(1, count(events, "region", "release", "timing-region"));
        assertEquals(2, count(events, "texture", "release", null));
        for (JsonObject event : events) if ("region".equals(event.get("kind").getAsString()))
            assertEquals(0L, event.get("estimated_bytes").getAsLong());
    }

    private static final class TestFrameBuffer extends GLFrameBuffer<Texture> {
        TestFrameBuffer() { super(Pixmap.Format.RGBA8888, 16, 16, false, false); }
        @Override protected Texture createColorTexture() { return new RealTexture(new MockData(16)); }
        @Override protected void disposeColorTexture(Texture texture) { texture.dispose(); }
        @Override protected void attachFrameBufferColorTexture() { }
    }

    @Test public void frameBufferCreateReclaimRebuildAndDisposeAreRecorded() throws Exception {
        Gdx.gl = Gdx.gl20 = proxy(GL20.class, (name, args) -> {
            if (name.equals("glGenTexture")) { live.add(++generated); return generated; }
            if (name.equals("glDeleteTexture")) { int id = (Integer) args[0]; assertTrue(live.remove(id)); deleted.add(id); }
            if (name.equals("glBindTexture")) bound = (Integer) args[1];
            if (name.equals("glGetIntegerv")) ((java.nio.IntBuffer) args[1]).put(0, (Integer) args[0] == GL20.GL_TEXTURE_BINDING_2D ? bound : 0);
            if (name.equals("glGenFramebuffer")) return 100;
            if (name.equals("glCheckFramebufferStatus")) return GL20.GL_FRAMEBUFFER_COMPLETE;
            return null;
        });
        TestFrameBuffer buffer = new TestFrameBuffer();
        Method release = GLFrameBuffer.class.getDeclaredMethod("releaseNativeResources", String.class, boolean.class, boolean.class);
        release.setAccessible(true);
        assertEquals(true, release.invoke(buffer, "idle_reclaim", false, true));
        buffer.bind();
        buffer.dispose(); buffer.dispose();
        List<JsonObject> events = snapshot();
        assertEquals(2, count(events, "framebuffer", "create", TestFrameBuffer.class.getName()));
        assertEquals(2, count(events, "framebuffer", "release", TestFrameBuffer.class.getName()));
        assertEquals(2, count(events, "texture", "release", null));
    }
}
