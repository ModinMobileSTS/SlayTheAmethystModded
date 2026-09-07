package optispire;

/*
Why does modded sts take so much more ram?
Probably just due to recompiling the game and stuff


CURRENT METHOD UTILIZING MANY DYNAMIC PATCHES:
unnacceptable.
takes too much ram just through the patching process.
need to handle things more like ManagedAtlas, changing behavior in just a few places.



notes:
MTS no mods: 550-600mb
MTS+ ANY MOD: 750-800mb
MTS basemod stslib memlogger: 1460 mb
1160, 1400mb allocated (increased after garbage collection)
MTS basemod stslib memlogger optispire: 1500 mb
A larger amount of memory was allocated for the heap, though.
+optimize the spire: 1600 mb
note: basicmod adds effectively no usage. What causes more ram usage?

mts is just not very efficient...



SPRITER ANIMATIONS??!?!?!?
I think every instance loads a LOT of textures. And there's multiple instances made of pretty much every creature and player and stuff that uses them.
process:
load images as pixmaps
create textures from pixmaps, texture -> textureregion -> sprite
store sprites in resources map
if pack (default true)
    combine into larger pixmaps

After loading all:
If pack: generate a TextureAtlas from the combined pixmap
    Dispose all existing sprites
    Set resource entries to entries of the atlas

dispose loaded pixmaps after

Then, create textures from pixmaps, convert to textureregions
later, convert to textureregions




Packmaster without opti: 3000mb
With no card images: 2600mb
Without registering anything other than loading strings: 2150mb
No packmaster (given time to settle): 1500mb
Basicmod: also around 1500mb



chunky List: 10000mb




ConstructorConstructor line 33:
Gson ends up using LinkedTreeMap for all the localization text
*/


import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.RealTexture;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.glutils.PixmapTextureData;
import com.badlogic.gdx.utils.Pool;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.core.CardCrawlGame;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.*;
import java.util.function.Supplier;

@SpirePatch(
        clz = CardCrawlGame.class,
        method = "render"
)
public class RamSaver {
    private static final float TICK = readFloat("ramsaver.age.tick_seconds", 15f, 1f, 120f);
    private static final int SET_LIMIT = 48;
    private static final int REFERENCE_QUEUE_DISPOSALS_PER_FRAME = readInt("ramsaver.release.max_per_frame", 16, 1, 256);
    private static final int HOT_LOAD_REPEAT_THRESHOLD = readInt("ramsaver.hot.repeat_loads", 2, 2, 16);
    private static final long HOT_LOAD_WINDOW_NANOS = readLong("ramsaver.hot.repeat_window_seconds", 120L, 10L, 1800L) * 1000000000L;
    private static final long HOT_SLOW_LOAD_NANOS = readLong("ramsaver.hot.slow_ms", 8L, 1L, 60000L) * 1000000L;
    private static final long HOT_PIN_NANOS = readLong("ramsaver.hot.pin_seconds", 120L, 10L, 1800L) * 1000000000L;
    private static final long HOT_PIN_BUDGET_BYTES = readLong("ramsaver.hot.budget_mb", 384L, 0L, 2048L) * 1024L * 1024L;
    private static int nextSet = 0;
    private static float timer = TICK;

    //public static Texture blank = new Texture(1, 1, Pixmap.Format.RGBA8888);

    // Textures are strongly owned until idle eviction; only regions depend on GC notifications.
    // Retired parents stay out of the pool until their children have been unlinked.




    @SpirePostfixPatch
    public static void update() {
        boolean diag = RamSaverDiag.enabled();
        long updateStarted = diag ? System.nanoTime() : 0L;
        int queuedReferences = 0;
        int disposedQueuedReferences = 0;
        ManagedAsset.ManagedAssetReference o;
        while (queuedReferences < REFERENCE_QUEUE_DISPOSALS_PER_FRAME
                && (o = (ManagedAsset.ManagedAssetReference) referenceQueue.poll()) != null) {
            queuedReferences++;
            if (o.holder.asset != o) {
                // The holder may already have been explicitly disposed and
                // returned to the pool. Never operate on a reused holder.
                continue;
            }
            if (loadedAssets.get(o.holder.ID) == o.holder) {
                disposedQueuedReferences++;
                if (diag) {
                    RamSaverDiag.logRepeat("reference_queue_dispose", o.holder.ID, o.holder.describe() + " " + inventoryDetails());
                }
                dispose(o.holder);
            }
        }

        while (queuedReferences < REFERENCE_QUEUE_DISPOSALS_PER_FRAME && !retiredAssets.isEmpty()) {
            queuedReferences++;
            ManagedAsset parent = retiredAssets.peek();
            if (!parent.dependent.isEmpty()) dispose(parent.dependent.get(parent.dependent.size() - 1));
            if (parent.dependent.isEmpty()) {
                retiredAssets.remove();
                managedAssetPool.free(parent);
            }
        }

        timer -= Gdx.graphics.getRawDeltaTime();
        if (timer <= 0) {
            long bucketStarted = diag ? System.nanoTime() : 0L;
            int setIndex = nextSet;
            timer = TICK / loadedSets.size();

            ArrayList<String> set = loadedSets.get(nextSet);
            int sizeBefore = set.size();
            int missingAssets = 0;
            int disposedOldAssets = 0;
            int agedAssets = 0;
            int keptFreshAssets = 0;
            // Child disposal can remove other entries in this bucket. Reuse a fixed snapshot.
            int count = set.size();
            for (int i = 0; i < count; i++) agingKeys[i] = set.get(i);
            for (int i = count - 1; i >= 0; --i) {
                String id = agingKeys[i];
                agingKeys[i] = null;
                ManagedAsset asset = loadedAssets.get(id);
                if (asset == null) {
                    missingAssets++;
                    loadedAssets.remove(id);
                    set.remove(id);
                }
                else if (asset.isHotPinned()) {
                    keptFreshAssets++;
                    asset.refresh();
                }
                else if (!asset.isFresh()) {
                    disposedOldAssets++;
                    dispose(asset);
                }
                else if (asset.canAge()) {
                    agedAssets++;
                    asset.age();
                }
                else {
                    keptFreshAssets++;
                }
            }

            nextSet = (nextSet + 1) % loadedSets.size();
            if (diag) {
                RamSaverDiag.logDuration(
                        "update_bucket",
                        "set-" + setIndex,
                        bucketStarted,
                        "setIndex=" + setIndex
                                + " sizeBefore=" + sizeBefore
                                + " sizeAfter=" + set.size()
                                + " missing=" + missingAssets
                                + " disposedOld=" + disposedOldAssets
                                + " aged=" + agedAssets
                                + " keptFresh=" + keptFreshAssets
                                + " queuedReferences=" + queuedReferences
                                + " disposedQueuedReferences=" + disposedQueuedReferences
                                + " nextSet=" + nextSet
                                + " " + inventoryDetails(),
                        false
                );
            }

            /*if (nextSet == 0) {
                SystemStats.logMemoryStats();
            }*/
        }
        else if (diag && queuedReferences > 0) {
            RamSaverDiag.logDuration(
                    "update_reference_queue",
                    "queue",
                    updateStarted,
                    "queuedReferences=" + queuedReferences
                            + " disposedQueuedReferences=" + disposedQueuedReferences
                            + " timer=" + timer
                            + " " + inventoryDetails(),
                    false
            );
        }
    }

    private static final ReferenceQueue<Object> referenceQueue = new ReferenceQueue<>();
    private static final Map<String, ManagedAsset> loadedAssets = new HashMap<>();
    private static final ArrayDeque<ManagedAsset> retiredAssets = new ArrayDeque<>();
    private static final Set<String> nullAssets = new HashSet<>();
    private static final Set<String> rejectedTextures = new HashSet<>();
    private static final Map<String, FakeTextureState> fakeTextureStates = new HashMap<>();
    // Insertion order is also expiry order: every refresh uses the same pin lifetime.
    private static final LinkedHashMap<FakeTextureState, Long> hotPins = new LinkedHashMap<>();
    private static long hotPinBytes;
    private static final ArrayList<ArrayList<String>> loadedSets = new ArrayList<>();
    private static final String[] agingKeys = new String[SET_LIMIT];
    private static final int RENDER_CREATE_REPEAT_THRESHOLD = 25;
    static {
        loadedSets.add(new ArrayList<>());
        RamSaverDiag.log(
                "init",
                "tickSeconds=" + TICK
                        + " setLimit=" + SET_LIMIT
                        + " hotRepeatLoads=" + HOT_LOAD_REPEAT_THRESHOLD
                        + " hotRepeatWindowSeconds=" + (HOT_LOAD_WINDOW_NANOS / 1000000000L)
                        + " hotSlowMs=" + (HOT_SLOW_LOAD_NANOS / 1000000L)
                        + " hotPinSeconds=" + (HOT_PIN_NANOS / 1000000000L)
                        + " hotBudgetMb=" + (HOT_PIN_BUDGET_BYTES / 1024L / 1024L)
                        + " " + inventoryDetails()
        );
    }

    private static final Map<String, FileTextureSupplier> textures = new HashMap<>(256);

    public static String textureKey(FileHandle file, Pixmap.Format format, boolean mipMaps,
                                    Texture.TextureFilter min, Texture.TextureFilter mag,
                                    Texture.TextureWrap u, Texture.TextureWrap v) {
        // Length-prefix the path so separators in legal filenames cannot alias configuration fields.
        String path = file.path();
        return file.type() + ":" + path.length() + ":" + path + ":" + format + ":" + mipMaps
                + ":" + min + ":" + mag + ":" + u + ":" + v;
    }

    public static String prewarmKey(FileHandle file) {
        return textureKey(file, null, false, Texture.TextureFilter.Linear, Texture.TextureFilter.Linear,
                Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
    }

    public static void registerPrewarmTexture(FileHandle file) {
        FileTextureSupplier supplier = new FileTextureSupplier(file, null, false);
        supplier.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        registerTexture(prewarmKey(file), supplier);
    }
    public static class FileTextureSupplier implements Supplier<Texture> {
        final FileHandle file;
        final Pixmap.Format format;
        final boolean useMipMaps;
        private String cacheKey;

        protected Texture.TextureFilter minFilter = Texture.TextureFilter.Nearest;
        protected Texture.TextureFilter magFilter = Texture.TextureFilter.Nearest;
        protected Texture.TextureWrap uWrap = Texture.TextureWrap.ClampToEdge;
        protected Texture.TextureWrap vWrap = Texture.TextureWrap.ClampToEdge;

        public FileTextureSupplier(FileHandle file, Pixmap.Format format, boolean useMipMaps) {
            this.file = file;
            this.format = format;
            this.useMipMaps = useMipMaps;
        }

        @Override
        public Texture get() {
            boolean diag = RamSaverDiag.enabled();
            long started = diag ? System.nanoTime() : 0L;
            long loadStarted = System.nanoTime();
            try {
                RealTexture real = new RealTexture(file, format, useMipMaps);
                real.setFilter(this.minFilter, this.magFilter);
                real.setWrap(this.uWrap, this.vWrap);
                markTextureMaterialized(cacheKey, real, Math.max(0L, System.nanoTime() - loadStarted));
                if (diag) {
                    RamSaverDiag.logDuration(
                            "supplier_get_real_texture",
                            file.path(),
                            started,
                            "format=" + format
                                    + " useMipMaps=" + useMipMaps
                                    + " minFilter=" + minFilter
                                    + " magFilter=" + magFilter
                                    + " uWrap=" + uWrap
                                    + " vWrap=" + vWrap
                                    + " " + textureDetails(real),
                            true
                    );
                }
                return real;
            }
            catch (RuntimeException e) {
                RamSaverDiag.logStackRepeat(
                        "supplier_get_failed",
                        file.path(),
                        "format=" + format + " useMipMaps=" + useMipMaps + " error=" + e.getClass().getName() + ":" + e.getMessage()
                );
                return getOrCreateMaterializationFallback(this, e);
            }
        }

        public void setFilter(Texture.TextureFilter minFilter, Texture.TextureFilter magFilter) {
            if (minFilter != null) this.minFilter = minFilter;
            if (magFilter != null) this.magFilter = magFilter;
        }

        public void setWrap(Texture.TextureWrap u, Texture.TextureWrap v) {
            if (u != null) this.uWrap = u;
            if (v != null) this.vWrap = v;
        }
    }


    public static boolean textureExists(String ID) {
        return textures.containsKey(ID);
    }

    public static boolean isTextureRejected(String textureID) {
        if (textureID == null) {
            return false;
        }
        FakeTextureState state = getFakeTextureState(textureID);
        return rejectedTextures.contains(textureID) || (state != null && state.rejected);
    }

    public static void markTextureRejected(String textureID, String details) {
        if (textureID == null) {
            return;
        }
        FakeTextureState state = getOrCreateFakeTextureState(textureID);
        boolean first = rejectedTextures.add(textureID) || !state.rejected;
        state.rejected = true;
        state.rejectionDetails = details;
        if (RamSaverDiag.enabled()) {
            RamSaverDiag.logRepeat(
                    first ? "texture_rejected_cached" : "texture_rejected_cache_hit",
                    textureID,
                    details + " " + inventoryDetails()
            );
        }
    }

    public static int recordFakeTextureCreate(String textureID, boolean rejected) {
        if (textureID == null) {
            return 0;
        }
        FakeTextureState state = getOrCreateFakeTextureState(textureID);
        int createCount;
        String knownRenderSignature;
        synchronized (state) {
            createCount = ++state.createCount;
            if (rejected) {
                state.rejected = true;
            }
            knownRenderSignature = state.renderSignature;
        }
        if (!RamSaverDiag.enabled()) {
            return 0;
        }
        String renderSignature = knownRenderSignature;
        if (renderSignature == null && shouldProbeRenderCreateStack(createCount)) {
            renderSignature = findRenderTextureCreationSignature();
        }
        if (renderSignature == null) {
            return 0;
        }
        int next;
        synchronized (state) {
            if (state.renderSignature == null) {
                state.renderSignature = renderSignature;
            }
            else {
                renderSignature = state.renderSignature;
            }
            next = ++state.renderCreateCount;
            if (next >= RENDER_CREATE_REPEAT_THRESHOLD) {
                state.repeatedRenderCreate = true;
            }
        }
        if (isRenderCreateMilestone(next)) {
            RamSaverDiag.logRepeat(
                    "repeated_render_texture_create",
                    textureID,
                    "count=" + next
                            + " createCount=" + createCount
                            + " rejected=" + rejected
                            + " renderStack=" + renderSignature
                            + " " + inventoryDetails()
            );
        }
        return next;
    }

    public static boolean isRepeatedRenderTexture(String textureID) {
        FakeTextureState state = getFakeTextureState(textureID);
        return state != null && state.repeatedRenderCreate;
    }

    public static int[] getCachedTextureSize(String textureID) {
        FakeTextureState state = getFakeTextureState(textureID);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            return state.knowSize ? new int[] { state.width, state.height } : null;
        }
    }

    public static void cacheTextureSize(String textureID, int width, int height) {
        if (textureID == null || width <= 0 || height <= 0) {
            return;
        }
        FakeTextureState state = getOrCreateFakeTextureState(textureID);
        synchronized (state) {
            state.width = width;
            state.height = height;
            state.knowSize = true;
        }
    }

    public static boolean isHotTexturePinned(String textureID) {
        FakeTextureState state = getFakeTextureState(textureID);
        if (state == null) {
            return false;
        }
        synchronized (hotPins) {
            if (state.hotPinned && System.nanoTime() >= state.hotPinnedUntilNanos) unpin(state);
            return state.hotPinned;
        }
    }

    public static void markTextureMaterialized(String textureID, Texture texture, long elapsedNanos) {
        if (textureID == null || texture == null) {
            return;
        }
        FakeTextureState state = getOrCreateFakeTextureState(textureID);
        long now = System.nanoTime();
        int width = Math.max(0, texture.getWidth());
        int height = Math.max(0, texture.getHeight());
        long estimatedBytes = estimateTextureBytes(width, height);
        if (texture.getTextureData().useMipMaps()) estimatedBytes += estimatedBytes / 3L;
        boolean becameHot = false;
        boolean repeatedHot = false;
        synchronized (state) {
            state.materializeCount++;
            if (state.lastMaterializedNanos > 0L && now - state.lastMaterializedNanos <= HOT_LOAD_WINDOW_NANOS) {
                state.recentMaterializeCount++;
            }
            else {
                state.recentMaterializeCount = 1;
            }
            state.lastMaterializedNanos = now;
            state.lastMaterializeElapsedNanos = elapsedNanos;
            state.estimatedBytes = estimatedBytes;
            state.width = width;
            state.height = height;
            state.knowSize = width > 0 && height > 0;
            state.materializationFailed = false;
            boolean shouldPin = HOT_PIN_BUDGET_BYTES > 0L
                    && (elapsedNanos >= HOT_SLOW_LOAD_NANOS || state.recentMaterializeCount >= HOT_LOAD_REPEAT_THRESHOLD);
            if (shouldPin) {
                repeatedHot = state.hotPinned;
                becameHot = !repeatedHot;
            }
        }
        if (becameHot || repeatedHot) {
            pin(state, now);
            if (RamSaverDiag.enabled()) {
                RamSaverDiag.logRepeat(
                        becameHot ? "hot_texture_pinned" : "hot_texture_refreshed",
                        textureID,
                        "elapsedMs=" + RamSaverDiag.formatElapsedMillis(elapsedNanos)
                                + " estimatedBytes=" + estimatedBytes
                                + " size=" + width + "x" + height
                                + " " + hotPinInventoryDetails(now)
                );
            }
        }
    }

    private static Texture getOrCreateMaterializationFallback(FileTextureSupplier supplier, RuntimeException error) {
        String textureID = supplier.cacheKey;
        int failureCount = recordTextureMaterializationFailure(textureID, error);
        Texture fallback = getMaterializationFallback(textureID);
        if (fallback == null) {
            fallback = createMaterializationFallback(supplier, error);
            setMaterializationFallback(textureID, fallback);
            if (RamSaverDiag.enabled()) {
                RamSaverDiag.logStackRepeat(
                        "supplier_get_fallback_created",
                        textureID,
                        "failureCount=" + failureCount
                                + " format=" + supplier.format
                                + " useMipMaps=" + supplier.useMipMaps
                                + " error=" + exceptionDetails(error)
                                + " fallback=" + textureDetails(fallback)
                );
            }
        }
        else {
            if (RamSaverDiag.enabled()) {
                RamSaverDiag.logRepeat(
                        "supplier_get_fallback_reuse",
                        textureID,
                        "failureCount=" + failureCount
                                + " error=" + exceptionDetails(error)
                                + " fallback=" + textureDetails(fallback)
                );
            }
        }
        logMaterializationFallback(textureID, supplier, error, failureCount, fallback);
        return fallback;
    }

    private static int recordTextureMaterializationFailure(String textureID, RuntimeException error) {
        FakeTextureState state = getOrCreateFakeTextureState(textureID);
        synchronized (state) {
            state.materializationFailed = true;
            state.materializationFailureCount++;
            state.materializationFailureDetails = exceptionDetails(error);
            return state.materializationFailureCount;
        }
    }

    private static Texture getMaterializationFallback(String textureID) {
        FakeTextureState state = getFakeTextureState(textureID);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            Texture fallback = state.materializationFallback;
            if (fallback != null && fallback.getTextureObjectHandle() != 0) {
                return fallback;
            }
            state.materializationFallback = null;
            return null;
        }
    }

    private static void setMaterializationFallback(String textureID, Texture fallback) {
        FakeTextureState state = getOrCreateFakeTextureState(textureID);
        synchronized (state) {
            state.materializationFallback = fallback;
        }
    }

    private static Texture createMaterializationFallback(FileTextureSupplier supplier, RuntimeException originalError) {
        Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixmap.setColor(0f, 0f, 0f, 0f);
        pixmap.fill();
        try {
            RealTexture fallback = new RealTexture(new PixmapTextureData(pixmap, Pixmap.Format.RGBA8888, false, true));
            fallback.setFilter(supplier.minFilter, supplier.magFilter);
            fallback.setWrap(supplier.uWrap, supplier.vWrap);
            return fallback;
        }
        catch (RuntimeException fallbackError) {
            System.out.println("[ram-saver] texture materialization fallback failed path="
                    + RamSaverDiag.safe(supplier.file.path())
                    + " originalError=" + exceptionDetails(originalError)
                    + " fallbackError=" + exceptionDetails(fallbackError));
            RamSaverDiag.logStackRepeat(
                    "supplier_get_fallback_failed",
                    supplier.file.path(),
                    "originalError=" + exceptionDetails(originalError)
                            + " fallbackError=" + exceptionDetails(fallbackError)
            );
            throw originalError;
        }
    }

    private static boolean isMaterializationFallback(String textureID) {
        FakeTextureState state = getFakeTextureState(textureID);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return state.materializationFailed && state.materializationFallback != null;
        }
    }

    private static void logMaterializationFallback(String textureID, FileTextureSupplier supplier, RuntimeException error, int failureCount, Texture fallback) {
        if (!shouldLogTextureMaterializationFailure(failureCount)) {
            return;
        }
        System.out.println("[ram-saver] texture materialization failed; using transparent fallback"
                + " path=" + RamSaverDiag.safe(textureID)
                + " failureCount=" + failureCount
                + " format=" + supplier.format
                + " useMipMaps=" + supplier.useMipMaps
                + " error=" + exceptionDetails(error)
                + " fallbackHandle=" + fallback.getTextureObjectHandle()
                + " fallbackSize=" + fallback.getWidth() + "x" + fallback.getHeight());
    }

    private static boolean shouldLogTextureMaterializationFailure(int count) {
        return count <= 3
                || count == 5
                || count == 10
                || count == 25
                || count == 50
                || count == 100
                || (count <= 1000 && count % 100 == 0)
                || count % 1000 == 0;
    }

    private static String exceptionDetails(Throwable error) {
        if (error == null) {
            return "null";
        }
        return error.getClass().getName() + ":" + RamSaverDiag.safe(error.getMessage());
    }

    public static void registerTexture(String textureID, FileTextureSupplier texSupplier) {
        if (textureID == null) {
            return;
        }
        FakeTextureState state = getOrCreateFakeTextureState(textureID);
        boolean replacing = textures.containsKey(textureID);
        synchronized (state) {
            if (state.supplier != null && replacing) {
                if (RamSaverDiag.enabled()) {
                    RamSaverDiag.logRepeat(
                            "register_texture_reuse",
                            textureID,
                            "supplier=" + RamSaverDiag.describeObject(state.supplier) + " " + inventoryDetails()
                    );
                }
                return;
            }
            state.supplier = texSupplier;
        }
        texSupplier.cacheKey = textureID;
        textures.put(textureID, texSupplier);
        if (RamSaverDiag.enabled()) {
            RamSaverDiag.logStackRepeat(
                    "register_texture",
                    textureID,
                    "replacing=" + replacing
                            + " supplier=" + RamSaverDiag.describeObject(texSupplier)
                            + " file=" + (texSupplier == null ? "null" : RamSaverDiag.safe(texSupplier.file.path()))
                            + " " + inventoryDetails()
            );
        }
    }

    private static Texture makeTexture(String path) {
        try {
            Texture t = new Texture(path);
            t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            if (RamSaverDiag.enabled()) {
                RamSaverDiag.logStackRepeat("make_texture", path, textureDetails(t));
            }
            return t;
        }
        catch (Exception ignored) {
            System.out.println("Failed to load texture " + path);
            RamSaverDiag.logStackRepeat("make_texture_failed", path, "error=" + ignored.getClass().getName() + ":" + ignored.getMessage());
        }
        return null;
    }

    public static <T> T getAsset(String id) {
        return getAsset(id, true);
    }
    public static <T> T getAsset(String id, boolean refresh) {
        ManagedAsset asset = loadedAssets.get(id);
        if (asset != null && asset.parent != null && asset.parent.retired) {
            dispose(asset);
            return null;
        }
        if (asset != null) {
            if (refresh) {
                asset.refresh();
            }

            //If item has been disposed, will return null
            //This results in loading the item again, replacing entry in loaded assets
            //And existing entry in loadedSets will not be modified
            T item = asset.item();
            if (RamSaverDiag.enabled()) {
                if (item == null) {
                    RamSaverDiag.logRepeat("asset_empty", id, asset.describe() + " " + inventoryDetails());
                }
                else {
                    RamSaverDiag.logRepeat("asset_hit", id, "refresh=" + refresh + " " + asset.describe() + " item=" + RamSaverDiag.describeObject(item));
                }
            }
            return item;
        }
        if (RamSaverDiag.enabled()) {
            RamSaverDiag.logStackRepeat("asset_miss", id, inventoryDetails());
        }
        return null;
    }
    public static ManagedAsset getAssetHolder(String id) {
        ManagedAsset asset = loadedAssets.get(id);
        if (asset != null) {
            asset.refresh();
            if (RamSaverDiag.enabled()) {
                RamSaverDiag.logRepeat("asset_holder_hit", id, asset.describe());
            }
        }
        else {
            if (RamSaverDiag.enabled()) {
                RamSaverDiag.logStackRepeat("asset_holder_miss", id, inventoryDetails());
            }
        }
        return asset;
    }

    //Load methods can be called either fresh, or with an old invalid version still within maps
    public static Texture loadTexture(String id, boolean canAge) {
        boolean diag = RamSaverDiag.enabled();
        long started = diag ? System.nanoTime() : 0L;
        if (id == null) {
            if (diag) {
                RamSaverDiag.logStackRepeat("load_texture_null_id", "null", inventoryDetails());
            }
            return null;
        }
        Supplier<Texture> supplier = textures.get(id);
        if (supplier == null) {
            System.out.println("Attempted to load unknown texture " + id);
            if (diag) {
                RamSaverDiag.logStackRepeat("load_texture_unknown", id, inventoryDetails());
            }
            return null;
        }
        // Uploads and supplier sampler setters bind on the current unit. Query only
        // this cold path; real binds still go through GLTexture's lifecycle tracking.
        IntBuffer binding = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        Gdx.gl.glGetIntegerv(GL20.GL_ACTIVE_TEXTURE, binding);
        int activeTexture = binding.get(0);
        binding.clear();
        Gdx.gl.glGetIntegerv(GL20.GL_TEXTURE_BINDING_2D, binding);
        int previousTexture = binding.get(0);
        Texture t;
        try {
            t = supplier.get();
        } finally {
            Gdx.gl.glActiveTexture(activeTexture);
            Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, previousTexture);
        }
        if (t == null) { //nulls get saved permanently. Usually means invalid filepath.
            ManagedAsset holder = managedAssetPool.obtain();
            holder.canAge = false;
            holder.setAsset(id, null, ManagedAsset.AssetType.REGION);
            loadedAssets.put(id, holder);
            nullAssets.add(id);
            if (diag) {
                RamSaverDiag.logDuration(
                        "load_texture_null",
                        id,
                        started,
                        "canAge=" + canAge + " supplier=" + RamSaverDiag.describeObject(supplier) + " " + holder.describe() + " " + inventoryDetails(),
                        true
                );
            }
            return null;
        }
        ManagedAsset holder = managedAssetPool.obtain();
        holder.setAsset(id, t, ManagedAsset.AssetType.TEXTURE);
        boolean materializationFallback = isMaterializationFallback(id);
        holder.canAge = true;
        t.attachToRamSaver(id);
        if (!canAge || materializationFallback) makeResident(id, t);
        ManagedAsset old = loadedAssets.put(id, holder);
        boolean createdSet = false;
        boolean appendedToExistingSet = false;
        // Replacements retain the existing bucket entry while retiring the previous generation.
        if (old == null) {
            //Store in a set, then return
            for (ArrayList<String> set : loadedSets) {
                if (set.size() < SET_LIMIT) {
                    set.add(id);
                    holder.set = set;
                    appendedToExistingSet = true;
                    if (diag) {
                        RamSaverDiag.logDuration(
                                "load_texture",
                                id,
                                started,
                                "canAge=" + canAge
                                        + " materializationFallback=" + materializationFallback
                                        + " replaced=false appendedToExistingSet=true createdSet=false setSize=" + set.size()
                                        + " " + textureDetails(t)
                                        + " " + holder.describe()
                                        + " " + inventoryDetails(),
                                true
                        );
                    }
                    return t;
                }
            }
            ArrayList<String> newSet = new ArrayList<>(SET_LIMIT);
            newSet.add(id);
            holder.set = newSet;
            loadedSets.add(newSet);
            createdSet = true;
        }
        else {
            // The old holder no longer owns the active cache entry.
            holder.set = old.set;
            dispose(old, false);
        }
        if (diag) {
            RamSaverDiag.logDuration(
                    "load_texture",
                    id,
                    started,
                    "canAge=" + canAge
                            + " materializationFallback=" + materializationFallback
                            + " replaced=" + (old != null)
                            + " appendedToExistingSet=" + appendedToExistingSet
                            + " createdSet=" + createdSet
                            + " " + textureDetails(t)
                            + " " + holder.describe()
                            + " " + inventoryDetails(),
                    true
            );
        }
        return t;
    }

    public static TextureAtlas.AtlasRegion loadRegion(String id, TextureAtlas.AtlasRegion region, ManagedAsset parent, boolean canAge) {
        boolean diag = RamSaverDiag.enabled();
        long started = diag ? System.nanoTime() : 0L;
        ManagedAsset holder = managedAssetPool.obtain();
        holder.setAsset(id, region, ManagedAsset.AssetType.REGION);
        holder.canAge = canAge;
        holder.parent = parent;
        parent.dependent.add(holder);

        ManagedAsset old = loadedAssets.put(id, holder);
        boolean createdSet = false;
        boolean appendedToExistingSet = false;
        //For this to be called, old item is null due to GC
        //If not null, already exists in a set.
        if (old == null) {
            //Store in a set, then return
            for (ArrayList<String> set : loadedSets) {
                if (set.size() < SET_LIMIT) {
                    set.add(id);
                    holder.set = set;
                    appendedToExistingSet = true;
                    if (diag) {
                        RamSaverDiag.logDuration(
                                "load_region",
                                id,
                                started,
                                "canAge=" + canAge
                                        + " replaced=false appendedToExistingSet=true createdSet=false setSize=" + set.size()
                                        + " region=" + describeRegion(region)
                                        + " parent=" + (parent == null ? "null" : parent.describe())
                                        + " " + holder.describe()
                                        + " " + inventoryDetails(),
                                true
                        );
                    }
                    return region;
                }
            }
            ArrayList<String> newSet = new ArrayList<>(SET_LIMIT);
            newSet.add(id);
            holder.set = newSet;
            loadedSets.add(newSet);
            createdSet = true;
        }
        else {
            // Replacement immediately unlinks the old region; queued references become stale.
            holder.set = old.set;
            dispose(old, false);
        }
        if (diag) {
            RamSaverDiag.logDuration(
                    "load_region",
                    id,
                    started,
                    "canAge=" + canAge
                            + " replaced=" + (old != null)
                            + " appendedToExistingSet=" + appendedToExistingSet
                            + " createdSet=" + createdSet
                            + " region=" + describeRegion(region)
                            + " parent=" + (parent == null ? "null" : parent.describe())
                            + " " + holder.describe()
                            + " " + inventoryDetails(),
                    true
            );
        }
        return region;
    }

    public static FileTextureSupplier getTextureSupplier(String id) {
        return textures.get(id);
    }
    public static Texture getExistingTexture(String id) {
        Texture t = getAsset(id);
        boolean usable = t != null && t.getTextureObjectHandle() != 0;
        if (RamSaverDiag.enabled()) {
            RamSaverDiag.logRepeat("get_existing_texture", id, "usable=" + usable + " " + textureDetails(t));
        }
        return usable ? t : null;
    }
    public static Texture getTexture(Texture original, String id) {
        return getTexture(original, id, true);
    }
    public static Texture getTexture(Texture original, String id, boolean canAge) {
        boolean diag = RamSaverDiag.enabled();
        long started = diag ? System.nanoTime() : 0L;
        Texture t = getAsset(id); //get first, if t matches original will cause a refresh

        if (original != null) {
            if (diag) {
                RamSaverDiag.logRepeat("get_texture_original", id, "canAge=" + canAge + " original=" + textureDetails(original));
            }
            return original;
        }

        if (nullAssets.contains(id)) {
            if (diag) {
                RamSaverDiag.logRepeat("get_texture_null_asset", id, "canAge=" + canAge + " " + inventoryDetails());
            }
            return t;
        }

        if (t != null && t.getTextureObjectHandle() != 0) {
            if (!canAge) {
                makeResident(id);
            }
            if (diag) {
                RamSaverDiag.logRepeat("get_texture_cache_hit", id, "canAge=" + canAge + " " + textureDetails(t));
            }
            return t;
        }

        if (diag) {
            RamSaverDiag.logStackRepeat("get_texture_cache_miss", id, "canAge=" + canAge + " existing=" + textureDetails(t) + " " + inventoryDetails());
        }
        Texture loaded = loadTexture(id, canAge);
        if (diag) {
            RamSaverDiag.logDuration(
                    "get_texture_load_path",
                    id,
                    started,
                    "canAge=" + canAge + " loaded=" + textureDetails(loaded) + " " + inventoryDetails(),
                    false
            );
        }
        return loaded;
    }

    private static void makeResident(String id) {
        ManagedAsset asset = loadedAssets.get(id);
        if (asset != null) {
            makeResident(id, asset.<Texture>item());
        }
    }
    private static void makeResident(String id, Texture texture) {
        if (texture == null || HOT_PIN_BUDGET_BYTES == 0L) return;
        FakeTextureState state = getOrCreateFakeTextureState(id);
        long now = System.nanoTime();
        state.estimatedBytes = estimateTextureBytes(texture.getWidth(), texture.getHeight());
        if (texture.getTextureData().useMipMaps()) state.estimatedBytes += state.estimatedBytes / 3L;
        pin(state, now);
    }

    /** Real bind sites refresh only their current owner, without allocating or scanning pin budgets. */
    public static void touchTexture(String id, Texture texture) {
        ManagedAsset asset = loadedAssets.get(id);
        if (asset != null && asset.item() == texture) asset.refresh();
    }
    public static Texture getTextureForBindFallback(String id) {
        boolean diag = RamSaverDiag.enabled();
        long started = diag ? System.nanoTime() : 0L;
        Texture t = getAsset(id);

        if (nullAssets.contains(id)) {
            if (diag) {
                RamSaverDiag.logRepeat("get_bind_fallback_null_asset", id, inventoryDetails());
            }
            return t;
        }

        if (t != null && t.getTextureObjectHandle() != 0) {
            if (diag) {
                RamSaverDiag.logRepeat("get_bind_fallback_cache_hit", id, textureDetails(t));
            }
            return t;
        }

        if (diag) {
            RamSaverDiag.logStackRepeat("get_bind_fallback_cache_miss", id, "existing=" + textureDetails(t) + " " + inventoryDetails());
        }
        Texture loaded = loadTexture(id, true);
        if (diag) {
            RamSaverDiag.logDuration(
                    "get_bind_fallback_load_path",
                    id,
                    started,
                    "loaded=" + textureDetails(loaded) + " " + inventoryDetails(),
                    false
            );
        }
        return loaded;
    }
    public static TextureAtlas.AtlasRegion getTextureAsRegion(TextureAtlas.AtlasRegion original, String id) {
        return getTextureAsRegion(original, id, false);
    }
    public static TextureAtlas.AtlasRegion getTextureAsRegion(TextureAtlas.AtlasRegion original, String id, boolean canAge) {
        boolean diag = RamSaverDiag.enabled();
        long started = diag ? System.nanoTime() : 0L;
        //See if already loaded
        String regionID = id + "RGN";
        TextureAtlas.AtlasRegion region = getAsset(regionID);

        if (original != null) {
            if (diag) {
                RamSaverDiag.logRepeat("get_region_original", regionID, "source=" + RamSaverDiag.safe(id) + " region=" + describeRegion(original));
            }
            return original;
        }

        if (region != null) {
            if (diag) {
                RamSaverDiag.logRepeat("get_region_cache_hit", regionID, "source=" + RamSaverDiag.safe(id) + " region=" + describeRegion(region));
            }
            return region;
        }

        //Get/load texture
        if (diag) {
            RamSaverDiag.logStackRepeat("get_region_cache_miss", regionID, "source=" + RamSaverDiag.safe(id) + " canAge=" + canAge + " " + inventoryDetails());
        }
        Texture t = getTexture(null, id, canAge);
        if (t == null) {
            if (diag) {
                RamSaverDiag.logDuration("get_region_null_texture", regionID, started, "source=" + RamSaverDiag.safe(id) + " canAge=" + canAge, true);
            }
            return null;
        }

        //Make atlasregion
        ManagedAsset texture = loadedAssets.get(id);
        region = new TextureAtlas.AtlasRegion(t, 0, 0, t.getWidth(), t.getHeight());

        TextureAtlas.AtlasRegion loaded = loadRegion(regionID, region, texture, false); //the region itself doesn't need to age
        if (diag) {
            RamSaverDiag.logDuration(
                    "get_region_load_path",
                    regionID,
                    started,
                    "source=" + RamSaverDiag.safe(id)
                            + " canAge=" + canAge
                            + " texture=" + textureDetails(t)
                            + " region=" + describeRegion(loaded)
                            + " " + inventoryDetails(),
                    false
            );
        }
        return loaded;
    }

    public static void age(String id) {
        ManagedAsset asset = loadedAssets.get(id);
        if (asset != null) {
            asset.age();
            if (RamSaverDiag.enabled()) {
                RamSaverDiag.logRepeat("age_asset", id, asset.describe());
            }
        }
        else {
            if (RamSaverDiag.enabled()) {
                RamSaverDiag.logRepeat("age_missing_asset", id, inventoryDetails());
            }
        }
    }
    public static void dispose(String id) {
        ManagedAsset asset = loadedAssets.get(id);
        if (asset != null) {
            dispose(asset, true);
        }
        else {
            if (RamSaverDiag.enabled()) {
                RamSaverDiag.logRepeat("dispose_missing_asset", id, inventoryDetails());
            }
        }
    }

    /** Called after an external Texture.dispose() has already released its GL handle. */
    public static void onTextureDisposed(String id, Texture texture) {
        if (id == null || texture == null) {
            return;
        }
        ManagedAsset asset = loadedAssets.get(id);
        if (asset == null || asset.item() != texture) {
            return;
        }
        // The handle is already zero; use the same child/unlink/pool lifecycle as eviction.
        dispose(asset);
    }
    private static void dispose(ManagedAsset asset) {
        dispose(asset, true);
    }
    private static void dispose(ManagedAsset asset, boolean removeKey) {
        if (asset.retired) return;
        asset.retired = true;
        boolean diag = RamSaverDiag.enabled();
        long started = diag ? System.nanoTime() : 0L;
        String id = asset.ID;
        String before = diag ? asset.describe() : "";
        asset.dispose();
        FakeTextureState state = getFakeTextureState(id);
        if (state != null && (removeKey || !loadedAssets.containsKey(id))) {
            unpin(state);
            state.materializationFallback = null;
        }
        if (removeKey && loadedAssets.get(asset.ID) == asset) {
            loadedAssets.remove(asset.ID);
            if (asset.set != null) {
                asset.set.remove(asset.ID);
            }
        }
        if (asset.dependent.isEmpty()) managedAssetPool.free(asset);
        else retiredAssets.add(asset);
        if (diag) {
            RamSaverDiag.logDuration(
                    "dispose_asset",
                    id,
                    started,
                    "removeKey=" + removeKey + " before=" + before + " " + inventoryDetails(),
                    true
            );
        }
    }

    private static String inventoryDetails() {
        if (!RamSaverDiag.enabled()) {
            return "";
        }
        return "loadedAssets=" + (loadedAssets == null ? -1 : loadedAssets.size())
                + " nullAssets=" + (nullAssets == null ? -1 : nullAssets.size())
                + " loadedSets=" + (loadedSets == null ? -1 : loadedSets.size())
                + " registeredTextures=" + (textures == null ? -1 : textures.size())
                + " fakeTextureStates=" + (fakeTextureStates == null ? -1 : fakeTextureStates.size());
    }

    private static FakeTextureState getFakeTextureState(String textureID) {
        if (textureID == null) {
            return null;
        }
        synchronized (fakeTextureStates) {
            return fakeTextureStates.get(textureID);
        }
    }

    private static FakeTextureState getOrCreateFakeTextureState(String textureID) {
        synchronized (fakeTextureStates) {
            FakeTextureState state = fakeTextureStates.get(textureID);
            if (state == null) {
                state = new FakeTextureState(textureID);
                fakeTextureStates.put(textureID, state);
            }
            return state;
        }
    }

    private static boolean shouldProbeRenderCreateStack(int createCount) {
        return createCount <= 3
                || createCount == 5
                || createCount == 10
                || createCount == 25
                || createCount == 100
                || createCount == 1000
                || createCount % 10000 == 0;
    }

    private static boolean isRenderCreateMilestone(int renderCreateCount) {
        return renderCreateCount == 25
                || renderCreateCount == 100
                || renderCreateCount == 1000
                || renderCreateCount % 10000 == 0;
    }

    private static long estimateTextureBytes(int width, int height) {
        if (width <= 0 || height <= 0) {
            return 0L;
        }
        return (long) width * (long) height * 4L;
    }

    private static void pin(FakeTextureState state, long nowNanos) {
        synchronized (hotPins) {
            unpin(state);
            state.hotPinned = true;
            state.hotPinnedAtNanos = nowNanos;
            state.hotPinnedUntilNanos = nowNanos + HOT_PIN_NANOS;
            hotPins.put(state, state.estimatedBytes);
            hotPinBytes += state.estimatedBytes;
            enforceHotPinBudget(nowNanos);
        }
    }

    private static void unpin(FakeTextureState state) {
        synchronized (hotPins) {
            Long bytes = hotPins.remove(state);
            if (bytes != null) hotPinBytes -= bytes;
            state.hotPinned = false;
            state.hotPinnedUntilNanos = 0L;
        }
    }

    private static void enforceHotPinBudget(long nowNanos) {
        synchronized (hotPins) {
            Iterator<Map.Entry<FakeTextureState, Long>> iterator = hotPins.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<FakeTextureState, Long> entry = iterator.next();
                FakeTextureState state = entry.getKey();
                boolean expired = nowNanos >= state.hotPinnedUntilNanos;
                if (!expired && hotPinBytes <= HOT_PIN_BUDGET_BYTES) break;
                hotPinBytes -= entry.getValue();
                iterator.remove();
                state.hotPinned = false;
                state.hotPinnedUntilNanos = 0L;
                if (!expired && RamSaverDiag.enabled()) {
                    RamSaverDiag.logRepeat("hot_texture_unpinned_budget", state.textureID,
                            "estimatedBytes=" + entry.getValue() + " totalBytes=" + hotPinBytes
                                    + " hotCount=" + hotPins.size() + " budgetBytes=" + HOT_PIN_BUDGET_BYTES);
                }
            }
        }
    }

    private static String hotPinInventoryDetails(long nowNanos) {
        if (!RamSaverDiag.enabled()) {
            return "";
        }
        synchronized (hotPins) {
            enforceHotPinBudget(nowNanos);
            return "hotCount=" + hotPins.size()
                    + " hotBytes=" + hotPinBytes
                    + " hotBudgetBytes=" + HOT_PIN_BUDGET_BYTES;
        }
    }

    private static String findRenderTextureCreationSignature() {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        String creator = null;
        String loader = null;
        String render = null;
        String publisher = null;
        for (StackTraceElement element : trace) {
            String className = element.getClassName();
            String methodName = element.getMethodName();
            if (className == null || methodName == null) {
                continue;
            }
            if (className.startsWith("optispire.RamSaver") || className.startsWith("optispire.RamSaverDiag")) {
                continue;
            }
            if (className.equals(Thread.class.getName())
                    || className.equals(Texture.class.getName())
                    || className.equals(RealTexture.class.getName())
                    || className.equals("com.badlogic.gdx.graphics.GLTexture")) {
                continue;
            }
            if (creator == null) {
                creator = simpleFrame(element);
            }
            if (loader == null && className.indexOf("TextureLoader") >= 0) {
                loader = simpleFrame(element);
            }
            if (render == null && ("render".equals(methodName) || methodName.endsWith("Render"))) {
                render = simpleFrame(element);
            }
            if (publisher == null && (className.indexOf("BaseMod") >= 0 || methodName.indexOf("PreRoomRender") >= 0)) {
                publisher = simpleFrame(element);
            }
        }
        if (render == null) {
            return null;
        }
        String source = loader == null ? creator : loader;
        if (source == null) {
            return null;
        }
        return source + " <- " + render + (publisher == null ? "" : " <- " + publisher);
    }

    private static String simpleFrame(StackTraceElement element) {
        return element.getClassName() + '#' + element.getMethodName() + ':' + element.getLineNumber();
    }

    private static int readInt(String property, int defaultValue, int minValue, int maxValue) {
        String raw = System.getProperty(property);
        if (raw == null) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.max(minValue, Math.min(maxValue, value));
        }
        catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static long readLong(String property, long defaultValue, long minValue, long maxValue) {
        String raw = System.getProperty(property);
        if (raw == null) {
            return defaultValue;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return Math.max(minValue, Math.min(maxValue, value));
        }
        catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static float readFloat(String property, float defaultValue, float minValue, float maxValue) {
        String raw = System.getProperty(property);
        if (raw == null) {
            return defaultValue;
        }
        try {
            float value = Float.parseFloat(raw.trim());
            if (Float.isNaN(value) || Float.isInfinite(value)) return defaultValue;
            return Math.max(minValue, Math.min(maxValue, value));
        }
        catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static class FakeTextureState {
        final String textureID;
        FileTextureSupplier supplier;
        boolean rejected;
        String rejectionDetails;
        int createCount;
        int renderCreateCount;
        String renderSignature;
        boolean repeatedRenderCreate;
        boolean knowSize;
        int width;
        int height;
        int materializeCount;
        int recentMaterializeCount;
        long lastMaterializedNanos;
        long lastMaterializeElapsedNanos;
        boolean materializationFailed;
        int materializationFailureCount;
        String materializationFailureDetails;
        Texture materializationFallback;
        boolean hotPinned;
        long hotPinnedUntilNanos;
        long hotPinnedAtNanos;
        long estimatedBytes;

        FakeTextureState(String textureID) {
            this.textureID = textureID;
        }
    }

    private static String textureDetails(Texture texture) {
        if (!RamSaverDiag.enabled()) {
            return "";
        }
        if (texture == null) {
            return "texture=null";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("texture=").append(RamSaverDiag.describeObject(texture));
        builder.append(" fake=").append(texture.isFake);
        if (texture.file != null) {
            builder.append(" file=").append(RamSaverDiag.safe(texture.file.path()));
        }
        if (!texture.isFake) {
            try {
                builder.append(" handle=").append(texture.getTextureObjectHandle());
                builder.append(" size=").append(texture.getWidth()).append('x').append(texture.getHeight());
            }
            catch (RuntimeException e) {
                builder.append(" detailError=").append(e.getClass().getName()).append(':').append(e.getMessage());
            }
        }
        return builder.toString();
    }

    private static String describeRegion(TextureAtlas.AtlasRegion region) {
        if (!RamSaverDiag.enabled()) {
            return "";
        }
        if (region == null) {
            return "null";
        }
        return RamSaverDiag.describeObject(region)
                + " name=" + RamSaverDiag.safe(region.name)
                + " index=" + region.index
                + " region=" + region.getRegionX() + ',' + region.getRegionY() + ' '
                + region.getRegionWidth() + 'x' + region.getRegionHeight()
                + " texture=" + textureDetails(region.getTexture());
    }

    private static final Pool<ManagedAsset> managedAssetPool = new Pool<ManagedAsset>(128) {
        @Override
        protected ManagedAsset newObject() {
            return new ManagedAsset();
        }
    };

    private static class ManagedAsset implements Pool.Poolable {
        String ID = "";
        ManagedAssetReference asset = null;

        List<String> set = null;
        ManagedAsset parent = null;
        final List<ManagedAsset> dependent = new ArrayList<>();
        AssetType type = AssetType.NONE;

        private boolean fresh = true;
        private boolean canAge = false;
        private Texture texture = null;
        private boolean retired;

        enum AssetType {
            NONE,
            TEXTURE,
            REGION
        }

        public void setAsset(String ID, Object o, AssetType type) {
            this.ID = ID;
            this.type = type;
            asset = new ManagedAssetReference(this, o, referenceQueue);
            texture = type == AssetType.TEXTURE ? (Texture) o : null;
            if (RamSaverDiag.enabled()) {
                RamSaverDiag.logStackRepeat("managed_asset_set", ID, describe() + " item=" + RamSaverDiag.describeObject(o));
            }
        }
        public void setNull(String ID) {
            this.ID = ID;
            this.type = AssetType.REGION;
            asset = new LockedNullReference(this, referenceQueue);
            canAge = false;
            if (RamSaverDiag.enabled()) {
                RamSaverDiag.logStackRepeat("managed_asset_set_null", ID, describe());
            }
        }

        public boolean canAge() {
            return canAge;
        }

        public boolean isFresh() {
            return !retired && (parent != null ? parent.isFresh() : fresh);
        }

        public boolean isHotPinned() {
            return type == AssetType.TEXTURE && RamSaver.isHotTexturePinned(ID);
        }

        public void age() {
            fresh = false;
            if (RamSaverDiag.enabled()) {
                RamSaverDiag.logRepeat("managed_asset_age", ID, describe());
            }
        }

        public void refresh() {
            if ((fresh = (asset instanceof LockedNullReference || (asset.get() != null))) && parent != null)
                parent.refresh();
        }

        @SuppressWarnings("unchecked")
        public <T> T item() {
            return (T) (texture != null ? texture : asset == null ? null : asset.get());
        }

        //Dispose of texture, clear reference, make it old
        public void dispose() {
            if (RamSaverDiag.enabled()) {
                RamSaverDiag.logRepeat("managed_asset_dispose_begin", ID, describe());
            }
            if (parent != null) {
                parent.dependent.remove(this);
                parent = null;
            }

            if (texture != null) texture.disposeForRamSaver();
            texture = null;

            asset.clear();
            age();
        }

        public String describe() {
            if (!RamSaverDiag.enabled()) {
                return "";
            }
            Object item = asset == null ? null : asset.get();
            return "id=" + RamSaverDiag.safe(ID)
                    + " type=" + type
                    + " canAge=" + canAge
                    + " fresh=" + fresh
                    + " parent=" + (parent == null ? "null" : RamSaverDiag.safe(parent.ID))
                    + " dependents=" + dependent.size()
                    + " setSize=" + (set == null ? -1 : set.size())
                    + " item=" + RamSaverDiag.describeObject(item);
        }

        @Override
        public void reset() {
            if (asset != null)
                asset.clear();
            ID = "";
            asset = null;
            type = AssetType.NONE;

            fresh = true;
            canAge = false;

            set = null;
            parent = null;
            dependent.clear();
            texture = null;
            retired = false;
        }

        static class ManagedAssetReference extends WeakReference<Object> {
            final ManagedAsset holder;
            public ManagedAssetReference(ManagedAsset holder, Object referent, ReferenceQueue<? super Object> q) {
                super(referent, q);
                this.holder = holder;
            }
        }
        static class LockedNullReference extends ManagedAssetReference {
            private static final Object lock = new Object();
            public LockedNullReference(ManagedAsset holder, ReferenceQueue<? super Object> q) {
                super(holder, lock, q);
            }

            @Override
            public Object get() {
                return null;
            }
        }
    }
}
