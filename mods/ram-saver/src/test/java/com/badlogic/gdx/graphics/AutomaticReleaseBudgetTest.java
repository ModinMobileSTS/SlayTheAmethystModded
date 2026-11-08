package com.badlogic.gdx.graphics;

import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import optispire.RamSaver;
import optispire.RunTextureResidency;
import org.junit.Test;

import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import static org.junit.Assert.*;

/** Observable GL deletion/reuse contracts; no sleeps, GC timing, or native allocation. */
public class AutomaticReleaseBudgetTest extends RamSaverTestSupport {
    private final List<TextureAtlas.AtlasRegion> regions = new ArrayList<>();
    private static final long MIB = 1024L * 1024L;

    @Test public void byteLimitStopsBeforeTheNextTextureAndDrainsWithoutAnotherBucketVisit() throws Exception {
        Map<Integer, Long> bytes = new HashMap<>();
        for (int i = 0; i < 6; i++) {
            Texture texture = load("bytes" + i, 1024, true);
            bytes.put(texture.getTextureObjectHandle(), texture.getRamSaverEstimatedBytes());
            RamSaver.age("bytes" + i);
        }
        tick();
        assertEquals(2, deleted.size());
        for (int frame = 0; frame < 3; frame++) {
            int before = deleted.size();
            RamSaver.update(); // No aging tick required to drain candidates.
            long released = 0L;
            for (int i = before; i < deleted.size(); i++) released += bytes.get(deleted.get(i));
            assertTrue("Actual deleted names must fit the 8 MiB estimate budget", released <= 8 * MIB);
        }
        assertTrue(live.isEmpty());
        assertEquals(6, deleted.size());
    }

    @Test public void oversizedHeadWaitsThenMakesProgressAlone() throws Exception {
        Texture huge = load("huge", 2048, true); // 16 MiB > 8 MiB/frame.
        load("small1", 1024, true);
        load("small2", 1024, true);
        for (String id : new String[] {"huge", "small1", "small2"}) RamSaver.age(id);
        tick(); // Reverse bucket order places the huge candidate after two 4 MiB releases.
        assertEquals(2, deleted.size());
        assertNotEquals(0, huge.getTextureObjectHandle());
        load("later", 16, true);
        RamSaver.age("later");
        tick();
        assertEquals("An oversized head must progress before newer small arrivals", 0, huge.getTextureObjectHandle());
        assertEquals("The oversized release is this frame's only texture deletion", 3, deleted.size());
        RamSaver.update();
        assertEquals(4, deleted.size());
    }

    @Test public void elapsedBudgetStopsBetweenDeletesWithoutInterruptingOneGlCall() throws Exception {
        releaseDeleteNanos = 750_000L;
        for (int i = 0; i < 8; i++) { load("timed" + i, 16, true); RamSaver.age("timed" + i); }
        tick();
        assertEquals("Three calls reach/exceed 2 ms; the fourth must wait", 3, deleted.size());
        assertEquals(2_250_000L, releaseNow);
        releaseDeleteNanos = 3_000_000L;
        int before = deleted.size();
        RamSaver.update();
        assertEquals("One indivisible slow GL call may exceed the cap, then scheduling stops", before + 1, deleted.size());
        releaseDeleteNanos = 0L;
        RamSaver.update();
        assertTrue(live.isEmpty());
    }

    @Test public void timeAndPersistentRoundRobinCoverAllSources() throws Exception {
        Texture parent = load("parent", 16, true);
        Texture retired = load("retired", 16, true);
        for (int i = 0; i < 12; i++) {
            region("gc" + i, "parent", parent);
            region("child" + i, "retired", retired);
            Reference<?> reference = (Reference<?>) field(map("loadedAssets").get("gc" + i), "asset");
            reference.clear();
            reference.enqueue();
        }
        RamSaver.dispose("retired");
        for (int i = 0; i < 8; i++) { load("idle" + i, 16, true); RamSaver.age("idle" + i); }
        // Advance only at scheduler clock checks, modelling costly unlink/check work without sleeps.
        set(RamSaver.class, "releaseClock", (LongSupplier) () -> { releaseNow += 2_000_000L; return releaseNow; });
        int initialDeletes = deleted.size();
        for (int frame = 0; frame < 3; frame++) {
            int before = map("loadedAssets").size();
            tick();
            assertEquals("The shared time budget must stop even zero-byte unlink work", 1,
                    before - map("loadedAssets").size());
        }
        assertEquals("Even one operation/frame must give aging a turn despite both backlogs", initialDeletes + 1, deleted.size());
        assertFalse(map("loadedAssets").containsKey("child11"));
        set(RamSaver.class, "releaseClock", (LongSupplier) () -> releaseNow);
        for (int i = 0; i < 50; i++) tick();
        assertTrue(map("loadedAssets").isEmpty());
        assertTrue(live.isEmpty());
    }

    @Test public void realBindCancelsPendingEvictionEvenIfNextBucketAgesItAgain() throws Exception {
        Texture kept = pendingTextures();
        int handle = kept.getTextureObjectHandle();
        int beforeLoads = generated;
        kept.bind();
        tick(); // Refresh must invalidate the old candidate, not just temporarily set fresh=true.
        assertTrue(live.contains(handle));
        assertSame(kept, RamSaver.getTexture(null, "pending0", true));
        assertEquals(beforeLoads, generated);
        assertEquals(7, deleted.size());
    }

    @Test public void newHotAndRunProtectionAreCheckedAtExecution() throws Exception {
        Texture run = pendingTextures();
        Texture hot = RamSaver.getAsset("pending1", false);
        RunTextureResidency.replaceCandidates(Collections.singletonMap("pending0", run.getRamSaverEstimatedBytes()));
        RamSaver.markTextureMaterialized("pending1", hot, 10_000_000L);
        int beforeLoads = generated;
        RamSaver.update(); // No bucket visit: protection must be rechecked by the release scheduler itself.
        assertSame(run, RamSaver.getTexture(null, "pending0", true));
        assertSame(hot, RamSaver.getTexture(null, "pending1", true));
        assertEquals(beforeLoads, generated);
        assertEquals(6, deleted.size());
    }

    @Test public void explicitDisposalAndReplacementCannotLeaveStaleCandidates() throws Exception {
        Texture old = pendingTextures();
        int before = deleted.size();
        old.dispose(); // External API remains immediate even after this frame's four automatic deletions.
        assertEquals(before + 1, deleted.size());
        assertEquals(0, old.getTextureObjectHandle());
        Texture replacement = RamSaver.getTexture(null, "pending0", true);
        for (int i = 0; i < 100; i++) {
            // Replace a candidate while four earlier entries consume the automatic budget.
            RamSaver.age("pending0");
            for (int j = 0; j < 4; j++) { load("block" + j, 16, true); RamSaver.age("block" + j); }
            tick();
            replacement = RamSaver.loadTexture("pending0", true);
        }
        int beforeLoads = generated;
        RamSaver.update();
        assertSame(replacement, RamSaver.getTexture(null, "pending0", true));
        replacement.bind();
        assertEquals(beforeLoads, generated);
        assertEquals("Only the live replacement remains; stale candidates cannot consume future work indefinitely", 1, live.size());
        for (int i = 0; i < 4; i++) { load("probe" + i, 16, true); RamSaver.age("probe" + i); }
        before = deleted.size();
        tick();
        assertEquals("Replacement churn must leave no obsolete work ahead of new candidates", 4, deleted.size() - before);
        assertNotEquals(0, replacement.getTextureObjectHandle());
    }

    @Test public void runPressureDoesNotBypassAutomaticReleaseCaps() throws Exception {
        for (int i = 0; i < 16; i++) load("pressure" + i, 1024, true);
        int before = deleted.size();
        RunTextureResidency.updatePressure(128 * MIB, 1, 100, 0L);
        assertEquals("Pressure sampling itself must not delete in bulk", before, deleted.size());
        for (int frame = 0; frame < 12; frame++) {
            before = deleted.size();
            tick();
            assertTrue("Pressure retains the byte/count caps", deleted.size() - before <= 2);
        }
        assertTrue(live.isEmpty());
    }

    private Texture pendingTextures() throws Exception {
        Texture first = null;
        for (int i = 0; i < 8; i++) {
            Texture t = load("pending" + i, 16, true);
            if (i == 0) first = t;
            RamSaver.age("pending" + i);
        }
        tick(); // Four releases, four waiting candidates (including pending0).
        assertEquals(4, deleted.size());
        assertNotEquals(0, first.getTextureObjectHandle());
        return first;
    }

    @Test public void multipleAgingBucketsDrainWithinSharedBudgetWhileVisibleTextureStaysResident() throws Exception {
        Texture visible = load("multi-visible", 16, true);
        int visibleHandle = visible.getTextureObjectHandle();
        // More than two 48-entry buckets: discovery and deferred deletion overlap across rotations.
        for (int i = 0; i < 120; i++) load("multi-idle" + i, 16, true);
        int initialLoads = generated;
        for (int frame = 0; frame < 160; frame++) {
            visible.bind();
            int before = deleted.size();
            tick();
            assertTrue("Each rotation shares the same four-operation allowance", deleted.size() - before <= 4);
            assertTrue("Visible handle survives all bucket rotations", live.contains(visibleHandle));
        }
        assertEquals("All 120 idle textures eventually drain", 120, deleted.size());
        assertEquals(1, live.size());
        assertEquals("No needless materialization while aging across buckets", initialLoads, generated);
        assertSame(visible, RamSaver.getTexture(null, "multi-visible", true));
    }

    @Test public void agingCannotBypassTheSharedCountLimit() throws Exception {
        for (int i = 0; i < 12; i++) load("idle" + i, 16, true);
        tick();
        tick();
        assertTrue("Aging must share the configured four operations per frame: " + deleted.size(),
                deleted.size() > 0 && deleted.size() <= 4);
        for (int frame = 0; frame < 12 && !live.isEmpty(); frame++) {
            int before = deleted.size();
            tick();
            assertTrue(deleted.size() - before <= 4);
        }
        assertTrue("Finite idle work must drain", live.isEmpty());
    }

    @Test public void referenceRetirementAndAgingShareOneLimit() throws Exception {
        Texture parent = load("parent", 16, true);
        Texture retired = load("retired", 16, true);
        for (int i = 0; i < 8; i++) {
            region("gc" + i, "parent", parent);
            region("child" + i, "retired", retired);
        }
        RamSaver.dispose("retired");
        for (int i = 0; i < 8; i++) {
            Reference<?> ref = (Reference<?>) field(map("loadedAssets").get("gc" + i), "asset");
            ref.clear();
            ref.enqueue();
        }
        for (int i = 0; i < 12; i++) {
            load("idle" + i, 16, true);
            RamSaver.age("idle" + i);
        }
        int beforeAssets = map("loadedAssets").size();
        int beforeDeletes = deleted.size();
        tick();
        assertTrue("All three automatic sources must share four cleanup operations",
                beforeAssets - map("loadedAssets").size() <= 4);
        assertTrue("A GC backlog must not starve actual texture release", deleted.size() > beforeDeletes);
        for (int i = 0; i < 40; i++) {
            int before = deleted.size();
            tick();
            assertTrue(deleted.size() - before <= 4);
        }
        assertTrue(live.isEmpty());
        assertTrue(map("loadedAssets").isEmpty());
    }

    private void region(String id, String parentId, Texture parent) {
        TextureAtlas.AtlasRegion region = new TextureAtlas.AtlasRegion(parent, 0, 0, 16, 16);
        regions.add(region);
        RamSaver.loadRegion(id, region, RamSaver.getAssetHolder(parentId), false);
    }
}
