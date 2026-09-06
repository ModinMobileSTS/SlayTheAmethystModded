package com.badlogic.gdx.graphics;

import com.badlogic.gdx.Gdx;
import optispire.RamSaver;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

import static org.junit.Assert.*;

/** Uses APIs present in the original revision, so the same assertions can run against the baseline. */
public class RamSaverBeforeAfterTest extends RamSaverTestSupport {
    @Test public void repeatedEvictionClearsHandleAndManagedList() {
        for (int i = 0; i < 50; i++) {
            Texture texture = load("cycle", 16, true);
            assertEquals(1, Texture.getNumManagedTextures());
            int handle = texture.getTextureObjectHandle();
            RamSaver.dispose("cycle");
            assertEquals("Eviction must clear the owning object's handle", 0, texture.getTextureObjectHandle());
            assertEquals(0, Texture.getNumManagedTextures());
            assertFalse(live.contains(handle));
        }
        assertEquals(50, deleted.size());
    }

    @Test public void evictionDeletesCurrentHandleAfterContextReload() {
        Texture texture = load("reload", 16, true);
        int old = texture.getTextureObjectHandle();
        live.remove(old); // Context loss, not glDeleteTexture.
        Texture.invalidateAllTextures(Gdx.app);
        int current = texture.getTextureObjectHandle();
        assertNotEquals(old, current);
        RamSaver.dispose("reload");
        assertEquals(Integer.valueOf(current), deleted.get(0));
        assertEquals(0, texture.getTextureObjectHandle());
        assertEquals(0, Texture.getNumManagedTextures());
    }

    @Test public void realBindAloneKeepsTextureFresh() throws Exception {
        Texture texture = load("bound", 16, true);
        for (int i = 0; i < 20; i++) {
            tick();
            texture.bind(i % 3);
        }
        assertEquals(0, deleted.size());
        tick();
        tick();
        assertEquals(0, texture.getTextureObjectHandle());
    }

    @Test public void retainedReferenceBindsCurrentGenerationAfterEviction() {
        Texture retained = load("retained", 16, true);
        int old = retained.getTextureObjectHandle();
        RamSaver.dispose("retained");
        retained.bind(2);
        assertNotEquals(old, bound);
        assertNotEquals(0, bound);
        assertEquals(GL20.GL_TEXTURE0 + 2, activeUnit);
        assertEquals(1, Texture.getNumManagedTextures());
    }

    @Test public void canAgeFalseCannotBypassPinBudget() throws Exception {
        for (int i = 0; i < 8; i++) load("pin" + i, 512, false);
        tick();
        tick();
        assertTrue("1 MiB pin budget must not retain eight 1 MiB textures", live.size() <= 1);
    }
    @Test public void fileConfigurationIsPartOfCacheIdentity() {
        MemoryFile file = new MemoryFile("same.png", new byte[0]);
        String nearest = RamSaver.textureKey(file, Pixmap.Format.RGBA8888, false,
                Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest,
                Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        String linearMipped = RamSaver.textureKey(file, Pixmap.Format.RGB565, true,
                Texture.TextureFilter.Linear, Texture.TextureFilter.Linear,
                Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
        assertNotEquals(nearest, linearMipped);
    }

    @Test public void pngAndKtxHeadersAreAcceptedOnlyWhenStructurallyValid() {
        assertArrayEquals(new int[] {17, 19}, Texture.readHeaderSize(new MemoryFile("valid.png", validPng(17, 19))));
        byte[] badPng = validPng(17, 19);
        badPng[29] ^= 1;
        assertNull(Texture.readHeaderSize(new MemoryFile("bad.png", badPng)));
        assertArrayEquals(new int[] {23, 29}, Texture.readHeaderSize(new MemoryFile("valid.ktx", validKtx(23, 29, false))));
        byte[] badKtx = validKtx(23, 29, false);
        badKtx[12] = 0;
        assertNull(Texture.readHeaderSize(new MemoryFile("bad.ktx", badKtx)));
    }

    @Test public void queuedReferenceCannotDisposeReusedHolder() throws Exception {
        Texture first = load("queued", 8, true);
        Map<String, Object> assets = map("loadedAssets");
        Object oldHolder = assets.get("queued");
        Object oldReference = field(oldHolder, "asset");
        RamSaver.dispose("queued");
        Texture second = load("queued", 8, true);
        assertSame(oldHolder, assets.get("queued"));
        ((java.lang.ref.Reference<?>) oldReference).enqueue();
        int current = second.getTextureObjectHandle();
        tick();
        assertTrue(live.contains(current));
        assertFalse(live.contains(first.getTextureObjectHandle()));
    }

    private static byte[] validPng(int width, int height) {
        byte[] data = new byte[33];
        byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        System.arraycopy(signature, 0, data, 0, signature.length);
        put(data, 8, 13, true);
        data[12] = 'I'; data[13] = 'H'; data[14] = 'D'; data[15] = 'R';
        put(data, 16, width, true); put(data, 20, height, true);
        data[24] = 8; data[25] = 6;
        java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(data, 12, 17);
        put(data, 29, (int) crc.getValue(), true);
        return data;
    }

    private static byte[] validKtx(int width, int height, boolean bigEndian) {
        byte[] data = new byte[64];
        byte[] signature = {(byte) 0xAB, 'K', 'T', 'X', ' ', '1', '1', (byte) 0xBB, 13, 10, 26, 10};
        System.arraycopy(signature, 0, data, 0, signature.length);
        put(data, 12, 0x01020304, true);
        put(data, 16, 0x1401, bigEndian); put(data, 20, 1, bigEndian); put(data, 24, 0x1908, bigEndian);
        put(data, 28, 0x1908, bigEndian); put(data, 32, 0x80e1, bigEndian);
        put(data, 36, width, bigEndian); put(data, 40, height, bigEndian);
        put(data, 44, 0, bigEndian); put(data, 48, 0, bigEndian); put(data, 52, 1, bigEndian);
        put(data, 56, 1, bigEndian); put(data, 60, 0, bigEndian);
        return data;
    }

    private static void put(byte[] data, int index, int value, boolean bigEndian) {
        ByteBuffer.wrap(data, index, 4).order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN).putInt(value);
    }
}
