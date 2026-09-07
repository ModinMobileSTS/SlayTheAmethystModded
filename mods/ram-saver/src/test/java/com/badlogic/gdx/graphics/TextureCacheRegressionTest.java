package com.badlogic.gdx.graphics;

import com.badlogic.gdx.graphics.g3d.utils.TextureDescriptor;
import optispire.patches.TextureDescriptorFakeTexture;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/** Focused contracts for cache identity and descriptor-local sampler overrides. */
public class TextureCacheRegressionTest extends RamSaverTestSupport {
    @Test public void descriptorHashDoesNotFollowMutableSamplerCacheKey() {
        Texture fake = new Texture(new MemoryFile("stable.png", new byte[0]), null, false);
        TextureDescriptor<Texture> descriptor = new TextureDescriptor<>(fake,
                Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest,
                Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        int before = TextureDescriptorFakeTexture.HashCode.fakeSafeHash(descriptor).get();

        fake.unsafeSetFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear, false);
        int after = TextureDescriptorFakeTexture.HashCode.fakeSafeHash(descriptor).get();

        assertEquals("descriptor hash must not change when wrapper sampler changes", before, after);
        fake.dispose();
    }

    @Test public void descriptorSamplerOverrideLeavesSharedFakeWrapperUnchanged() {
        Texture fake = new Texture(new MemoryFile("shared.png", new byte[0]), null, false);
        Texture.TextureFilter originalMin = fake.getMinFilter();
        Texture.TextureFilter originalMag = fake.getMagFilter();
        Texture.TextureWrap originalU = fake.getUWrap();
        Texture.TextureWrap originalV = fake.getVWrap();
        TextureDescriptor<Texture> descriptor = new TextureDescriptor<>(fake,
                Texture.TextureFilter.Linear, Texture.TextureFilter.Linear,
                Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);

        assertSame(fake, descriptor.texture);
        assertEquals(originalMin, fake.getMinFilter());
        assertEquals(originalMag, fake.getMagFilter());
        assertEquals(originalU, fake.getUWrap());
        assertEquals(originalV, fake.getVWrap());
        fake.dispose();
    }
}
