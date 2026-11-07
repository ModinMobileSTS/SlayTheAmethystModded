package com.badlogic.gdx.graphics;

import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import optispire.RamSaver;
import optispire.RunTextureResidency;
import optispire.patches.HandleRenderingFakes;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class RunTextureResidencyTest extends RamSaverTestSupport {
    private Texture fake(String path, int size, boolean slow) {
        MemoryFile file = new MemoryFile(path, new byte[0]);
        String key = RamSaver.textureKey(file, null, false, Texture.TextureFilter.Nearest,
                Texture.TextureFilter.Nearest, Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        RamSaver.registerTexture(key, new RamSaver.FileTextureSupplier(file, null, false) {
            @Override public Texture get() {
                Texture real = new RealTexture(new MockData(size));
                RamSaver.markTextureMaterialized(key, real, slow ? 10_000_000L : 1_000_000L);
                return real;
            }
        });
        RamSaver.cacheTextureSize(key, size, size);
        return new Texture(file);
    }

    private Map<String, Long> candidates(Texture... textures) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Texture texture : textures) result.put(texture.getRamSaverKey(), texture.getRamSaverEstimatedBytes());
        return result;
    }

    @Test public void retainedDeckPortraitSurvivesAgingWithoutAnyHotPin() throws Exception {
        Texture fake = fake("owned.png", 64, false);
        TextureAtlas.AtlasRegion cardPortrait = new TextureAtlas.AtlasRegion(fake, 0, 0, 64, 64);
        RunTextureResidency.replaceCandidates(candidates(fake));
        RunTextureResidency.prewarmOne(null);
        Texture real = fake.getRealTexture();
        assertFalse(RamSaver.isHotTexturePinned(fake.getRamSaverKey()));
        for (int i = 0; i < 200; i++) tick();
        assertSame(real, HandleRenderingFakes.resolveRegionTexture(cardPortrait.getTexture()));
        assertEquals(1, generated);
        assertTrue(deleted.isEmpty());
    }

    @Test public void runProtectionDoesNotDuplicateGenericHotPinAccounting() throws Exception {
        Texture fake = fake("expired.png", 64, true);
        RunTextureResidency.replaceCandidates(candidates(fake));
        Texture real = fake.getRealTexture();
        assertFalse(RamSaver.isHotTexturePinned(fake.getRamSaverKey()));
        for (int i = 0; i < 20; i++) tick();
        assertNotEquals(0, real.getTextureObjectHandle());
    }

    @Test public void genericHotBudgetCannotEvictRunOwnedPortrait() throws Exception {
        Texture card = fake("owned-budget.png", 512, true);
        RunTextureResidency.replaceCandidates(candidates(card));
        Texture real = card.getRealTexture();
        Texture unrelated = fake("unrelated.png", 512, true);
        unrelated.getRealTexture();
        assertFalse(RamSaver.isHotTexturePinned(card.getRamSaverKey()));
        for (int i = 0; i < 20; i++) tick();
        assertNotEquals(0, real.getTextureObjectHandle());
    }

    @Test public void removedCardAndEndRunReturnImagesToNormalAging() throws Exception {
        Texture removed = fake("removed.png", 64, true);
        Texture kept = fake("kept.png", 64, false);
        RunTextureResidency.replaceCandidates(candidates(removed, kept));
        Texture removedReal = removed.getRealTexture();
        Texture keptReal = kept.getRealTexture();
        RunTextureResidency.replaceCandidates(candidates(kept));
        tick(); tick();
        assertEquals(0, removedReal.getTextureObjectHandle());
        assertNotEquals(0, keptReal.getTextureObjectHandle());
        RunTextureResidency.clear();
        tick(); tick();
        assertEquals(0, keptReal.getTextureObjectHandle());
    }

    @Test public void runBudgetDeniesOversizeAndExtraTexturesWithoutRepeatedPrewarm() throws Exception {
        Texture first = fake("first.png", 512, true);
        Texture second = fake("second.png", 512, true);
        Texture huge = fake("huge.png", 1024, true);
        RunTextureResidency.replaceCandidates(candidates(first, second, huge));
        assertTrue(RunTextureResidency.isProtected(first.getRamSaverKey()));
        assertFalse(RunTextureResidency.isProtected(second.getRamSaverKey()));
        assertFalse(RunTextureResidency.isProtected(huge.getRamSaverKey()));
        RunTextureResidency.prewarmOne(null);
        assertEquals(1, generated);
        assertFalse(RunTextureResidency.hasPending(null));
        Texture denied = second.getRealTexture(false);
        assertFalse(RamSaver.isHotTexturePinned(second.getRamSaverKey()));
        tick(); tick();
        assertEquals(0, denied.getTextureObjectHandle());
        for (int i = 0; i < 10; i++) RunTextureResidency.prewarmOne(null);
        assertEquals("Denied textures are not repeatedly prefetched", 2, generated);
    }

    @Test public void incrementalPrewarmDeduplicatesAndHonorsDrawPriority() {
        Texture first = fake("first-warm.png", 64, false);
        Texture second = fake("second-warm.png", 64, false);
        RunTextureResidency.replaceCandidates(candidates(first, first, second));
        RunTextureResidency.prewarmOne(Collections.singleton(second.getRamSaverKey()));
        assertEquals(1, generated);
        assertNull(RamSaver.getAsset(first.getRamSaverKey(), false));
        assertNotNull(RamSaver.getAsset(second.getRamSaverKey(), false));
        assertFalse(RunTextureResidency.hasPending(Collections.singleton(second.getRamSaverKey())));
        assertTrue(RunTextureResidency.hasPending(null));
        RunTextureResidency.prewarmOne(null);
        RunTextureResidency.prewarmOne(null);
        assertEquals(2, generated);
    }

    @Test public void alreadyLoadedPortraitDoesNotBecomePendingOnEveryScan() {
        Texture fake = fake("cached.png", 64, false);
        fake.getRealTexture();
        RunTextureResidency.replaceCandidates(candidates(fake));
        assertFalse(RunTextureResidency.hasPending(null));
        for (int i = 0; i < 10; i++) RunTextureResidency.replaceCandidates(candidates(fake));
        assertFalse(RunTextureResidency.hasPending(null));
        assertEquals(1, generated);
    }

    @Test public void externalDisposalInvalidatesPendingGeneration() {
        Texture fake = fake("disposed.png", 64, false);
        RunTextureResidency.replaceCandidates(candidates(fake));
        Texture real = fake.getRealTexture();
        real.dispose();
        assertTrue(RunTextureResidency.hasPending(null));
        RunTextureResidency.prewarmOne(null);
        assertEquals(2, generated);
        assertFalse(RunTextureResidency.hasPending(null));
    }

    @Test public void failureDoesNotDeadlockDrawOrRetryEveryFrame() {
        Texture fake = fake("failed.png", 64, false);
        // Replace only this supplier for a deterministic non-native failure.
        try {
            map("textures").put(fake.getRamSaverKey(),
                    new RamSaver.FileTextureSupplier(fake.file, null, false) {
                        @Override public Texture get() { throw new IllegalStateException("test failure"); }
                    });
        } catch (Exception error) { throw new AssertionError(error); }
        RunTextureResidency.replaceCandidates(candidates(fake));
        RunTextureResidency.prewarmOne(null);
        for (int i = 0; i < 10; i++) RunTextureResidency.replaceCandidates(candidates(fake));
        assertFalse(RunTextureResidency.hasPending(null));
        assertEquals(0, generated);
    }

    @Test public void pressureWithdrawsPinsAndRecoversOnlyAfterHysteresis() throws Exception {
        Texture fake = fake("pressure.png", 64, true);
        RunTextureResidency.replaceCandidates(candidates(fake));
        Texture real = fake.getRealTexture();
        RunTextureResidency.updatePressure(Long.MAX_VALUE, 1, 100, 0L);
        assertFalse(RunTextureResidency.isProtected(fake.getRamSaverKey()));
        assertFalse(RamSaver.isHotTexturePinned(fake.getRamSaverKey()));
        assertFalse(RunTextureResidency.hasPending(null));
        tick(); tick();
        assertEquals(0, real.getTextureObjectHandle());
        RunTextureResidency.updatePressure(0L, 1, 100, 29_000_000_000L);
        assertFalse(RunTextureResidency.isProtected(fake.getRamSaverKey()));
        RunTextureResidency.updatePressure(0L, 1, 100, 31_000_000_000L);
        assertTrue(RunTextureResidency.isProtected(fake.getRamSaverKey()));
        assertTrue(RunTextureResidency.hasPending(null));
    }

    @Test public void heapPressureAlsoWithdrawsProtection() {
        Texture fake = fake("heap-pressure.png", 64, false);
        RunTextureResidency.replaceCandidates(candidates(fake));
        RunTextureResidency.updatePressure(0, 91, 100, 0L);
        assertFalse(RunTextureResidency.isProtected(fake.getRamSaverKey()));
    }

    @Test public void pressureCannotBeBypassedByCanAgeFalseOnCacheHit() {
        Texture fake = fake("pressure-cache-hit.png", 64, true);
        RunTextureResidency.replaceCandidates(candidates(fake));
        fake.getRealTexture();
        RunTextureResidency.updatePressure(Long.MAX_VALUE, 1, 100, 0L);
        fake.getRealTexture(false);
        assertFalse(RamSaver.isHotTexturePinned(fake.getRamSaverKey()));
    }

    @Test public void realUploadSizeCorrectsAnUnderestimatedBudget() {
        Texture fake = fake("underestimated.png", 1024, false);
        RunTextureResidency.replaceCandidates(Collections.singletonMap(fake.getRamSaverKey(), 1024L));
        assertTrue(RunTextureResidency.isProtected(fake.getRamSaverKey()));
        RunTextureResidency.prewarmOne(null);
        assertEquals(1, generated);
        assertFalse(RunTextureResidency.isProtected(fake.getRamSaverKey()));
        assertFalse(RunTextureResidency.hasPending(null));
    }

    @Test public void estimatingAnUnsupportedFormatDoesNotMaterializeOrReadEveryScan() {
        final int[] reads = {0};
        MemoryFile file = new MemoryFile("unknown.gif", new byte[0]) {
            @Override public java.io.InputStream read() {
                reads[0]++;
                return super.read();
            }
        };
        Texture texture = new Texture(file);
        for (int i = 0; i < 10; i++) assertEquals(0L, texture.getRamSaverEstimatedBytes());
        assertEquals(0, generated);
        assertEquals(0, reads[0]);
    }

    @Test public void unknownEstimateStaysLazyUntilDemandLoadSuppliesActualSize() {
        Texture fake = fake("unknown.png", 64, false);
        Map<String, Long> candidates = Collections.singletonMap(fake.getRamSaverKey(), 0L);
        RunTextureResidency.replaceCandidates(candidates);
        RunTextureResidency.prewarmOne(null);
        assertEquals(0, generated);
        fake.getRealTexture();
        assertTrue(RunTextureResidency.isProtected(fake.getRamSaverKey()));
    }

    @Test public void healthyAggregateBudgetIsSharedWithHotCacheAndExternalResources() throws Exception {
        long mib = 1024L * 1024L;
        Texture card = fake("shared-card.png", 256, true);
        RunTextureResidency.replaceCandidates(candidates(card));
        Texture cardReal = card.getRealTexture();
        assertEquals(mib / 4, cardReal.getTrackedNativeBytes());
        // 63 MiB of unrelated resources leave 1 MiB total, with 0.25 MiB already reserved for the card.
        RunTextureResidency.updatePressure(63 * mib + mib / 4, 1, 100, 0L);
        Texture history = fake("shared-history.png", 512, true);
        history.getRealTexture();
        assertFalse("A 1 MiB history pin cannot fit in the remaining 0.75 MiB", RamSaver.isHotTexturePinned(history.getRamSaverKey()));
        tick(); tick();
        assertNotEquals(0, cardReal.getTextureObjectHandle());
        assertNull(RamSaver.getAsset(history.getRamSaverKey(), false));
    }

    @Test public void persistentSoftPressureEventuallyWithdrawsEvenRunProtection() {
        long mib = 1024L * 1024L;
        Texture card = fake("persistent-card.png", 256, false);
        RunTextureResidency.replaceCandidates(candidates(card));
        card.getRealTexture();
        fake("persistent-idle.png", 2048, false).getRealTexture();
        RunTextureResidency.updatePressure(64 * mib, 1, 100, 0L);
        assertTrue(RunTextureResidency.isProtected(card.getRamSaverKey()));
        assertFalse("Do not prefetch while reclaiming", RunTextureResidency.hasPending(null));
        RunTextureResidency.updatePressure(64 * mib, 1, 100, 5_000_000_000L);
        assertFalse("Continuing pressure cannot preserve optimistic reclaim credit indefinitely",
                RunTextureResidency.isProtected(card.getRamSaverKey()));
    }

    @Test public void runChangesCannotResetGlobalPressureAndRecoveryRequiresBothLowWatermarks() {
        long mib = 1024L * 1024L;
        RunTextureResidency.updatePressure(128 * mib, 1, 100, 0L);
        RunTextureResidency.clear();
        Texture history = fake("pressure-next-run.png", 256, true);
        history.getRealTexture(false);
        assertFalse(RamSaver.isHotTexturePinned(history.getRamSaverKey()));
        RunTextureResidency.updatePressure(0, 1, 100, 29_000_000_000L);
        assertTrue(RunTextureResidency.isUnderPressure());
        RunTextureResidency.updatePressure(52 * mib, 1, 100, 31_000_000_000L);
        assertTrue(RunTextureResidency.isUnderPressure());
        RunTextureResidency.updatePressure(0, 85, 100, 32_000_000_000L);
        assertTrue(RunTextureResidency.isUnderPressure());
        RunTextureResidency.updatePressure(0, 1, 100, 33_000_000_000L);
        assertFalse(RunTextureResidency.isUnderPressure());
        history.getRealTexture(false);
        assertTrue(RamSaver.isHotTexturePinned(history.getRamSaverKey()));
    }

    @Test public void trackedCreditUsesActualFormatAndDisposalDoesNotLeavePhantomBytes() {
        Texture real = new RealTexture(new MockData(256) {
            @Override public Pixmap.Format getFormat() { return Pixmap.Format.RGB565; }
        });
        assertEquals(256L * 256 * 2, real.getTrackedNativeBytes());
        assertEquals("Run admission stays conservative", 256L * 256 * 4, real.getRamSaverEstimatedBytes());
        real.dispose();
        assertEquals(0, real.getTrackedNativeBytes());
    }

    @Test public void pressureAgingRunsPromptlyButNeverDeletesAnActivelyBoundImage() {
        Texture idle = fake("pressure-idle-cadence.png", 256, true).getRealTexture();
        Texture visible = fake("pressure-visible-cadence.png", 256, true).getRealTexture();
        RunTextureResidency.updatePressure(Long.MAX_VALUE, 1, 100, 0L);
        assertTrue("The pressure sample must not delete GL resources in bulk", deleted.isEmpty());
        delta = 0.25f;
        for (int frame = 0; frame < 12; frame++) {
            visible.bind();
            RamSaver.update(); // Real delta-driven scheduler, not the forced-bucket test helper.
        }
        assertEquals("Idle historical pin must be reclaimed promptly", 0, idle.getTextureObjectHandle());
        assertNotEquals("Normal freshness still protects a visible texture", 0, visible.getTextureObjectHandle());
    }
}
