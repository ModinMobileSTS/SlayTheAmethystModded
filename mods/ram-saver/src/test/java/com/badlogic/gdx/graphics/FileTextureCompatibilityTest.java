package com.badlogic.gdx.graphics;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.glutils.FileTextureData;
import com.badlogic.gdx.utils.GdxNativesLoader;
import com.badlogic.gdx.utils.GdxRuntimeException;
import optispire.PngFixtures;
import optispire.patches.HandleRenderingFakes;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.InputStream;

import static org.junit.Assert.*;

/** Uses the bundled native image decoder, with GL calls recorded by RamSaverTestSupport. */
public class FileTextureCompatibilityTest extends RamSaverTestSupport {
    @BeforeClass public static void loadNativeDecoder() {
        GdxNativesLoader.load();
    }

    @Test public void extensionlessPngUsesRealTextureAndReloadsAfterContextLoss() throws Exception {
        Texture texture = new Texture(new MemoryFile("extensionless", png()), Pixmap.Format.RGB565, true);
        try {
            assertFalse(texture.isFake);
            assertTrue(texture.isManaged());
            assertEquals(1, Texture.getNumManagedTextures());
            assertEquals(Pixmap.Format.RGB565, texture.getTextureData().getFormat());
            assertTrue(texture.getTextureData().useMipMaps());
            assertEquals(2, texture.getWidth());
            assertEquals(2, texture.getHeight());
            int original = texture.peekTextureObjectHandle();
            assertTrue(live.contains(original));
            live.clear(); // Context loss invalidates names without glDeleteTexture.
            Texture.invalidateAllTextures(Gdx.app);
            int restored = texture.peekTextureObjectHandle();
            assertNotEquals(original, restored);
            assertTrue(live.contains(restored));
        } finally {
            texture.dispose();
        }
        assertEquals(0, Texture.getNumManagedTextures());
        assertTrue(live.isEmpty());
    }

    @Test public void tgaReachesTheDrawHookAsARealTexture() {
        byte[] tga = new byte[18 + 2 * 2 * 3];
        tga[2] = 2; // Uncompressed true color.
        tga[12] = 2; // Width.
        tga[14] = 2; // Height.
        tga[16] = 24; // Bits per pixel.
        Texture texture = new Texture(new MemoryFile("mod-image.tga", tga));
        try {
            assertFalse(texture.isFake);
            assertTrue(texture.getTextureData() instanceof FileTextureData);
            assertEquals(2, texture.getWidth());
            assertEquals(2, texture.getHeight());
            Texture[] drawArgument = { texture };
            HandleRenderingFakes.FakeTextures.handleFakeTexture(drawArgument);
            assertSame(texture, drawArgument[0]);
            assertSame(texture, HandleRenderingFakes.resolveRegionTexture(texture));
        } finally {
            texture.dispose();
        }
        assertTrue(live.isEmpty());
    }

    @Test public void regularPngStillDefersDecodeAndGlAllocation() throws Exception {
        int[] reads = {0};
        Texture texture = new Texture(new MemoryFile("regular.png", png()) {
            @Override public InputStream read() {
                reads[0]++;
                return super.read();
            }
        });
        assertTrue(texture.isFake);
        assertEquals(0, reads[0]);
        assertEquals(0, generated);
        Texture real = texture.getRealTexture();
        assertFalse(real.isFake);
        assertTrue(reads[0] > 0);
        assertTrue(live.contains(real.peekTextureObjectHandle()));
    }

    @Test public void missingFilesStillFailBeforeDecodeOrGlAllocation() {
        for (String path : new String[] {"missing.png", "missing"}) {
            MemoryFile missing = new MemoryFile(path, new byte[0]) {
                @Override public boolean exists() { return false; }
                @Override public InputStream read() { throw new AssertionError("Missing file read"); }
            };
            GdxRuntimeException failure = assertThrows(GdxRuntimeException.class, () -> new Texture(missing));
            assertTrue(failure.getMessage().contains(path));
        }
        assertEquals(0, generated);
    }

    @Test public void undecodableUnknownFileStillFailsRatherThanDrawingAPlaceholder() {
        int[] reads = {0};
        MemoryFile invalid = new MemoryFile("invalid.data", new byte[] {1, 2, 3}) {
            @Override public InputStream read() {
                reads[0]++;
                return super.read();
            }
        };
        GdxRuntimeException failure = assertThrows(GdxRuntimeException.class, () -> new Texture(invalid));
        assertTrue(failure.getMessage().contains(invalid.path()));
        assertTrue("The original decoder must decide whether the file is valid", reads[0] > 0);
        assertEquals(0, generated);
        assertEquals(0, Texture.getNumManagedTextures());
    }

    private static byte[] png() throws Exception {
        return PngFixtures.image(2, 2, 4, new byte[] {
                1, 2, 3, 4, 11, 12, 13, 14, 21, 22, 23, 24, 31, 32, 33, 34
        }, 0);
    }
}
