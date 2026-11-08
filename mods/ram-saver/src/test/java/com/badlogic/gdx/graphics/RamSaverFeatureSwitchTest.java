package com.badlogic.gdx.graphics;

import optispire.RamSaver;
import optispire.RamSaverFeatures;
import optispire.RunTextureResidency;
import optispire.TextureDecodeService;
import org.junit.After;
import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class RamSaverFeatureSwitchTest extends RamSaverTestSupport {
    @Test public void visualDebugDefaultOffDoesNotRetainReleaseHistory() {
        assertFalse(RamSaverReleaseEvents.ENABLED);
        load("debug-off", 16, true);
        RamSaver.dispose("debug-off");
        assertEquals(1, deleted.size());
        assertEquals(0L, RamSaverReleaseEvents.latestSequence());
    }

    @After public void restoreFlags() throws Exception {
        set(RamSaverFeatures.class, "sharedReleaseBudget", true);
    }

    @Test public void disablingSharedBudgetRestoresImmediateAgingDisposal() throws Exception {
        set(RamSaverFeatures.class, "sharedReleaseBudget", false);
        for (int i = 0; i < 12; i++) load("legacy-age-" + i, 16, true);
        tick(); tick();
        assertEquals("Legacy aging bypasses the four-operation queue budget", 12, deleted.size());
        assertTrue(live.isEmpty());
    }

    @Test public void legacyAgingAlsoBypassesNewByteAndTimeLimits() throws Exception {
        set(RamSaverFeatures.class, "sharedReleaseBudget", false);
        releaseDeleteNanos = 3_000_000L;
        for (int i = 0; i < 6; i++) {
            load("legacy-large-" + i, 1024, true);
            RamSaver.age("legacy-large-" + i);
        }
        tick();
        assertEquals("All 24 MiB release in the bucket despite the 8 MiB/2 ms shared limits", 6, deleted.size());
        assertTrue(live.isEmpty());
    }

    @Test public void pressureKeepsOrdinaryAgingCadenceAndRunOnlyPrewarmPause() throws Exception {
        RunTextureResidency.updatePressure(128L * 1024 * 1024, 1, 100, 0L);
        assertEquals("Pressure must not request a faster bucket visit", 100f, (Float) field(RamSaver.class, "timer"), 0f);
        assertTrue(RunTextureResidency.shouldPausePrewarm(TextureDecodeService.RUN));
        assertFalse(RunTextureResidency.shouldPausePrewarm(TextureDecodeService.FIRST_COMBAT));
        tick();
        assertEquals(field(RamSaver.class, "TICK"), field(RamSaver.class, "timer"));
        RunTextureResidency.updatePressure(0L, 1, 100, 31_000_000_000L);
        assertFalse(RunTextureResidency.shouldPausePrewarm(TextureDecodeService.RUN));
    }

    @Test public void unrelatedHotPinsRemainIndependentOfRunPressure() throws Exception {
        Texture card = load("legacy-card", 256, true);
        RunTextureResidency.replaceCandidates(Collections.singletonMap("legacy-card", card.getRamSaverEstimatedBytes()));
        Texture history = load("legacy-history", 256, false);
        assertTrue(RamSaver.isHotTexturePinned("legacy-history"));
        RunTextureResidency.updatePressure(128L * 1024 * 1024, 1, 100, 0L);
        assertFalse(RunTextureResidency.isProtected("legacy-card"));
        RamSaver.getTexture(null, "legacy-history", false);
        assertTrue("Unrelated cache hit can retain its legacy hot pin", RamSaver.isHotTexturePinned("legacy-history"));
        tick(); tick();
        assertEquals(0, card.getTextureObjectHandle());
        assertNotEquals(0, history.getTextureObjectHandle());
    }

}
