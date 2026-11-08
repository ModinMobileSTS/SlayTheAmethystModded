package com.badlogic.gdx.graphics;

import optispire.BoundedTextureDecoder;
import optispire.PngFixtures;
import optispire.RamSaver;
import optispire.RamSaverFeatures;
import optispire.TextureDecodeService;
import org.junit.Test;

import static org.junit.Assert.*;

/** Runs in a separate test JVM with neither optimization property supplied. */
public class RamSaverOptimizationDefaultsTest extends RamSaverTestSupport {
    @Test public void missingSharedBudgetPropertyUsesLegacyAging() throws Exception {
        assertNull(System.getProperty("ramsaver.release.shared_budget_enabled"));
        assertFalse(RamSaverFeatures.sharedReleaseBudget());
        for (int i = 0; i < 12; i++) load("default-off-age-" + i, 16, true);
        tick(); tick();
        assertEquals("Default aging must not use the shared four-operation test budget", 12, deleted.size());
        assertTrue(live.isEmpty());
    }

    @Test public void missingDecodePropertyDoesNotCreateBackgroundWork() throws Exception {
        assertNull(System.getProperty("ramsaver.decode.enabled"));
        MemoryFile file = new MemoryFile("default-off.png", PngFixtures.image(2, 2, 4, new byte[16], 0));
        RamSaver.FileTextureSupplier supplier = new RamSaver.FileTextureSupplier(file, null, false);
        assertEquals(BoundedTextureDecoder.State.FALLBACK,
                TextureDecodeService.request("default-off", supplier, TextureDecodeService.FIRST_COMBAT));
        assertNull(field(TextureDecodeService.class, "decoder"));
        assertEquals(0, TextureDecodeService.reservedBytes());
    }
}
