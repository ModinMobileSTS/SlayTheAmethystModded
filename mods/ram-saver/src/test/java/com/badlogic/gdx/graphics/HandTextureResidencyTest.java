package com.badlogic.gdx.graphics;

import com.badlogic.gdx.Files.FileType;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import optispire.HandTextureResidency;
import optispire.RamSaver;
import optispire.RamSaverFeatures;
import optispire.RunTextureResidency;
import optispire.TextureDecodeService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

/** Observe GL names, not just pin flags, through both automatic-release implementations. */
public class HandTextureResidencyTest extends RamSaverTestSupport {
    private boolean previousSharedBudget;

    @Before public void rememberReleaseMode() {
        previousSharedBudget = RamSaverFeatures.sharedReleaseBudget();
    }

    @After public void restoreReleaseMode() throws Exception {
        set(RamSaverFeatures.class, "sharedReleaseBudget", previousSharedBudget);
    }

    private void protect(String... keys) {
        HandTextureResidency.beginUpdate();
        for (String key : keys) HandTextureResidency.protect(key);
        HandTextureResidency.endUpdate();
    }

    private void ageCycles() throws Exception {
        for (int i = 0; i < 20; i++) tick();
    }

    @Test public void oversizedHandSurvivesBothReleaseModesAndGpuAndHeapPressure() throws Exception {
        for (boolean shared : new boolean[] {false, true}) {
            set(RamSaverFeatures.class, "sharedReleaseBudget", shared);
            String key = "oversized-hand-" + shared;
            Texture hand = load(key, 1024, true); // 4 MiB exceeds the 1 MiB test run budget.
            int handle = hand.getTextureObjectHandle();
            RunTextureResidency.replaceCandidates(Collections.singletonMap(key, hand.getRamSaverEstimatedBytes()));
            assertFalse(RunTextureResidency.isProtected(key));
            protect(key);
            RunTextureResidency.updatePressure(Long.MAX_VALUE, 95, 100, 0L);
            assertTrue(RunTextureResidency.isUnderPressure());
            assertTrue(HandTextureResidency.isProtected(key));
            assertTrue(RunTextureResidency.shouldPausePrewarm(TextureDecodeService.RUN));
            assertFalse(RamSaver.isHotTexturePinned(key));
            ageCycles();
            assertTrue("Hand must survive without any access or hot pin", live.contains(handle));
            protect();
            ageCycles();
            assertFalse("Leaving the hand restores ordinary aging", live.contains(handle));
        }
    }

    @Test public void handSourceProtectsExistingAndFutureVariantsButNotAnotherFileType() throws Exception {
        MemoryFile file = new MemoryFile("hand-source.png", new byte[0]);
        String first = RamSaver.textureKey(file, null, false, Texture.TextureFilter.Nearest,
                Texture.TextureFilter.Nearest, Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        RamSaver.registerTexture(first, new RamSaver.FileTextureSupplier(file, null, false) {
            @Override public Texture get() { return new RealTexture(new MockData(16)); }
        });
        protect(first);
        String variant = RamSaver.textureKey(file, Pixmap.Format.RGBA8888, true, Texture.TextureFilter.Linear,
                Texture.TextureFilter.Linear, Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
        RamSaver.registerTexture(variant, new RamSaver.FileTextureSupplier(file, Pixmap.Format.RGBA8888, true) {
            @Override public Texture get() { return new RealTexture(new MockData(16)); }
        });
        Texture texture = RamSaver.loadTexture(variant, true);
        int handle = texture.getTextureObjectHandle();
        assertTrue(HandTextureResidency.isProtected(variant));
        MemoryFile external = new MemoryFile(file.path(), new byte[0], FileType.External);
        String other = RamSaver.textureKey(external, null, false, Texture.TextureFilter.Nearest,
                Texture.TextureFilter.Nearest, Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        RamSaver.registerTexture(other, new RamSaver.FileTextureSupplier(external, null, false));
        assertFalse(HandTextureResidency.isProtected(other));
        assertEquals(1, generated); // Protection must not materialize any other variant.
        ageCycles();
        assertTrue(live.contains(handle));
        protect();
        ageCycles();
        assertFalse(live.contains(handle));
    }

    @Test public void parentHandProtectionSurvivesLegacyRegionFirstAgingOrder() throws Exception {
        set(RamSaverFeatures.class, "sharedReleaseBudget", false);
        Texture parent = load("hand-parent", 16, true);
        TextureAtlas.AtlasRegion region = new TextureAtlas.AtlasRegion(parent, 0, 0, 16, 16);
        RamSaver.loadRegion("hand-region", region, RamSaver.getAssetHolder("hand-parent"), false);
        RamSaver.age("hand-parent");
        protect("hand-parent");
        ageCycles();
        assertSame(region, RamSaver.getAsset("hand-region", false));
        assertTrue(deleted.isEmpty());
    }

    @Test public void protectionIsRecheckedForAnAlreadyQueuedSharedRelease() throws Exception {
        set(RamSaverFeatures.class, "sharedReleaseBudget", true);
        for (int i = 0; i < 8; i++) {
            load("queued-hand-" + i, 16, true);
            RamSaver.age("queued-hand-" + i);
        }
        tick();
        assertEquals(4, deleted.size());
        Texture hand = RamSaver.getAsset("queued-hand-0", false);
        assertNotNull(hand);
        int handle = hand.getTextureObjectHandle();
        protect("queued-hand-0");
        RunTextureResidency.updatePressure(Long.MAX_VALUE, 95, 100, 0L);
        RamSaver.update(); // Drain the prior queue without another bucket scan.
        assertTrue(live.contains(handle));
        assertEquals(7, deleted.size());
        ageCycles();
        assertTrue(live.contains(handle));
    }

    @Test public void clearingRunResidencyDoesNotRevokeIndependentHandOwnership() throws Exception {
        Texture hand = load("independent-hand", 16, true);
        protect("independent-hand");
        RunTextureResidency.clear();
        ageCycles();
        assertTrue(live.contains(hand.getTextureObjectHandle()));
        HandTextureResidency.clear();
        ageCycles();
        assertEquals(0, hand.getTextureObjectHandle());
    }

    @Test public void stableHandScansKeepOnlyItsCurrentKeysWithoutUploadingOrRefreshingRunAdmission() throws Exception {
        Texture hand = load("stable-hand", 16, true);
        for (int i = 0; i < 200; i++) {
            protect("stable-hand");
            assertTrue(HandTextureResidency.isProtected("stable-hand"));
            assertFalse(RunTextureResidency.isProtected("stable-hand"));
        }
        assertEquals(1, generated);
        assertEquals(1, ((java.util.Map<?, ?>) field(HandTextureResidency.class, "keys")).size());
        assertEquals(1, ((java.util.Map<?, ?>) field(HandTextureResidency.class, "sources")).size());
        protect();
        assertTrue(((java.util.Map<?, ?>) field(HandTextureResidency.class, "keys")).isEmpty());
        assertTrue(((java.util.Map<?, ?>) field(HandTextureResidency.class, "sources")).isEmpty());
        ageCycles();
        assertEquals(0, hand.getTextureObjectHandle());
    }

    @Test public void handProtectionDoesNotBlockExplicitDisposalOrReplacement() {
        Texture hand = load("hand-lifecycle", 16, true);
        protect("hand-lifecycle");
        Texture next = RamSaver.loadTexture("hand-lifecycle", true);
        assertEquals(0, hand.getTextureObjectHandle());
        assertTrue(HandTextureResidency.isProtected("hand-lifecycle"));
        next.dispose();
        assertEquals(0, next.getTextureObjectHandle());
        assertEquals(2, deleted.size());
    }
}
