package com.badlogic.gdx.graphics;

import optispire.RamSaver;
import optispire.RunTextureResidency;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Run pressure must leave ordinary hot-cache retention and aging independent. No timing/sleeps. */
public class TexturePressurePriorityTest extends RamSaverTestSupport {
    private static final long MIB = 1024L * 1024L;
    private final List<Texture> uploaded = new ArrayList<>();

    private Texture image(String path, int size, boolean slow) {
        MemoryFile file = new MemoryFile(path, new byte[0]);
        String key = RamSaver.textureKey(file, null, false, Texture.TextureFilter.Nearest,
                Texture.TextureFilter.Nearest, Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        RamSaver.registerTexture(key, new RamSaver.FileTextureSupplier(file, null, false) {
            @Override public Texture get() {
                Texture real = new RealTexture(new MockData(size));
                uploaded.add(real);
                RamSaver.markTextureMaterialized(key, real, slow ? 10_000_000L : 1_000_000L);
                return real;
            }
        });
        RamSaver.cacheTextureSize(key, size, size);
        return new Texture(file);
    }

    private long liveBytes() {
        long bytes = 0L;
        for (Texture texture : uploaded) {
            if (texture.getTextureObjectHandle() != 0) bytes += texture.getRamSaverEstimatedBytes();
        }
        return bytes;
    }

    @Test public void pressureWithdrawsRunImagesWithoutTakingUnrelatedHotBudget() throws Exception {
        // Test budgets: run=1 MiB, hot=1 MiB, aggregate pressure=64 MiB.
        // Three 256x256 cards cost 0.75 MiB; history 1 MiB; idle pages 12 MiB.
        Texture hand = image("priority-hand.png", 256, true);
        Texture upcoming = image("priority-upcoming.png", 256, true);
        Texture reserve = image("priority-reserve.png", 256, true);
        Map<String, Long> candidates = new LinkedHashMap<>();
        for (Texture texture : new Texture[] {hand, upcoming, reserve}) {
            candidates.put(texture.getRamSaverKey(), texture.getRamSaverEstimatedBytes());
        }
        RunTextureResidency.replaceCandidates(candidates);
        Texture handReal = hand.getRealTexture();
        Texture upcomingReal = upcoming.getRealTexture();
        Texture reserveReal = reserve.getRealTexture();
        Texture historyReal = image("priority-history.png", 512, true).getRealTexture();
        for (int i = 0; i < 3; i++) image("priority-idle-" + i + ".png", 1024, false).getRealTexture();
        long externalBytes = 50 * MIB + MIB / 2; // Other textures/FBOs cannot be reclaimed by RamSaver.
        long before = liveBytes();
        assertEquals(64 * MIB + MIB / 4, externalBytes + before);
        RunTextureResidency.updatePressure(externalBytes + before, 1, 100, 0L);
        // Two bucket visits age then release; extra visits verify a stable result.
        for (int i = 0; i < 6; i++) tick();
        long after = liveBytes();
        int retained = (handReal.getTextureObjectHandle() != 0 ? 1 : 0)
                + (upcomingReal.getTextureObjectHandle() != 0 ? 1 : 0);
        boolean historyRetained = historyReal.getTextureObjectHandle() != 0;
        boolean reserveRetained = reserveReal.getTextureObjectHandle() != 0;
        int beforeReuse = generated;
        hand.getRealTexture();
        upcoming.getRealTexture();
        int reloads = generated - beforeReuse;
        System.out.println("PRESSURE_BASELINE managedBeforeBytes=" + before + " managedAfterBytes=" + after
                + " aggregateAfterBytes=" + (externalBytes + after) + " criticalRetained=" + retained
                + " criticalReloads=" + reloads + " historyRetained=" + historyRetained
                + " reserveRetained=" + reserveRetained);
        assertEquals("Pressure withdraws all run-owned protection", 0, retained);
        assertEquals("Withdrawn run images use ordinary demand loading", 2, reloads);
        assertTrue("History retains its independent hot protection", historyRetained);
        assertFalse("The reserve card also yields its run protection", reserveRetained);
        assertEquals("Only the independent 1 MiB hot image remains before demand reuse", MIB, after);
    }

    @Test public void severeRunPressureDoesNotDisableUnrelatedHotPinsOrDemandMaterialization() throws Exception {
        Texture history = image("severe-history.png", 256, true);
        Texture real = history.getRealTexture();
        RunTextureResidency.updatePressure(128 * MIB, 1, 100, 0L);
        history.getRealTexture(false); // Fixed-set prewarm/cache-hit path.
        Texture newlyLoaded = image("severe-new.png", 256, true).getRealTexture(false);
        assertTrue("An unrelated cache hit keeps its independent hot protection",
                RamSaver.isHotTexturePinned(history.getRamSaverKey()));
        tick(); tick();
        assertNotEquals(0, real.getTextureObjectHandle());
        assertNotEquals(0, newlyLoaded.getTextureObjectHandle());
    }

    @Test public void intermittentBattleEffectDoesNotReloadDuringSustainedRunPressure() throws Exception {
        Texture effect = image("vfx/intermittent.png", 256, true);
        Texture real = effect.getRealTexture();
        int initialLoads = generated;
        delta = 1f / 60f;
        for (int frame = 0; frame <= 900; frame++) {
            if (frame % 30 == 0) {
                RunTextureResidency.updatePressure(64 * MIB, 1, 100, frame * 1_000_000_000L / 60);
            }
            if (frame % 300 == 0) assertSame(real, effect.getRealTexture());
            RamSaver.update();
        }
        assertEquals("An effect reused every five seconds must not synchronously reload", initialLoads, generated);
        assertTrue(deleted.isEmpty());
    }
}
