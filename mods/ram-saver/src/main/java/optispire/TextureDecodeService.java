package optispire;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.RealTexture;
import com.badlogic.gdx.graphics.glutils.FileTextureData;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Render-thread facade. Only immutable file requests and decoded bytes cross the worker boundary. */
public final class TextureDecodeService {
    public static final int RUN = 1, FIRST_COMBAT = 2;
    private static ExecutorService executor;
    private static BoundedTextureDecoder<PngTextureDecoder.Pixels> decoder;
    private static boolean stopped;

    private TextureDecodeService() { }

    private static BoundedTextureDecoder<PngTextureDecoder.Pixels> decoder() {
        if (decoder == null && !stopped) {
            executor = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "RamSaver-PNG-decode");
                thread.setDaemon(true);
                return thread;
            });
            decoder = new BoundedTextureDecoder<>(executor,
                    property("ramsaver.decode.staging_mb", 96, 1, 256) * 1024L * 1024L,
                    property("ramsaver.decode.max_pending", 8, 1, 64));
        }
        return decoder;
    }

    public static BoundedTextureDecoder.State request(String key, RamSaver.FileTextureSupplier supplier, int scope) {
        // Overridden suppliers may have different semantics. Never call their code off-thread.
        if (stopped || !Boolean.parseBoolean(System.getProperty("ramsaver.decode.enabled", "true"))
                || supplier.getClass() != RamSaver.FileTextureSupplier.class
                || !supplier.file.extension().equalsIgnoreCase("png") || Gdx.gl20 == null)
            return BoundedTextureDecoder.State.FALLBACK;
        return decoder().request(key, new PngTextureDecoder(supplier.file), scope);
    }

    /** Demand never waits: take an already-published result, or invalidate it before synchronous fallback. */
    static BoundedTextureDecoder<PngTextureDecoder.Pixels>.Lease take(String key) {
        if (decoder == null) return null;
        BoundedTextureDecoder<PngTextureDecoder.Pixels>.Lease lease = decoder.take(key);
        if (lease == null) decoder.cancel(key);
        return lease;
    }

    static RealTexture upload(RamSaver.FileTextureSupplier supplier, PngTextureDecoder.Pixels pixels) {
        Pixmap pixmap = new Pixmap(pixels.width, pixels.height,
                pixels.channels == 3 ? Pixmap.Format.RGB888 : Pixmap.Format.RGBA8888);
        // Keep ownership until the whole upload finishes, including glGen/bind/prepare failures.
        StagedData data = null;
        try {
            pixmap.getPixels().put(pixels.bytes()).position(0);
            data = new StagedData(supplier, pixmap);
            RealTexture texture = new RealTexture(data);
            texture.format = supplier.format;
            texture.useMipMaps = supplier.useMipMaps;
            return texture;
        } finally {
            try { if (data != null) data.releaseInitial(); }
            finally { pixmap.dispose(); }
        }
    }

    /** FileTextureData preserves managed reload/source attribution; only the initial pixels are borrowed. */
    private static final class StagedData extends FileTextureData {
        private boolean initial = true;
        StagedData(RamSaver.FileTextureSupplier supplier, Pixmap pixmap) {
            super(supplier.file, pixmap, supplier.format, supplier.useMipMaps);
        }
        public boolean disposePixmap() { return !initial; }
        void releaseInitial() {
            // A failure before consumePixmap must not leave a disposed Pixmap in managed data.
            if (isPrepared()) consumePixmap();
            initial = false;
        }
    }

    public static void cancel(String key) { if (decoder != null) decoder.cancel(key); }
    public static void cancel(String key, int scope) { if (decoder != null) decoder.cancel(key, scope); }
    public static void cancelScope(int scope) { if (decoder != null) decoder.cancelScope(scope); }
    public static void cancelAll() { if (decoder != null) decoder.cancelAll(); }
    public static long reservedBytes() { return decoder == null ? 0L : decoder.reservedBytes(); }
    public static void shutdown() {
        stopped = true;
        if (decoder != null) decoder.shutdown();
        if (executor != null) executor.shutdownNow(); // no join on render thread
    }
    private static int property(String name, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(System.getProperty(name, "" + fallback)))); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
