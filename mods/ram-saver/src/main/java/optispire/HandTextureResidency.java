package optispire;

import com.badlogic.gdx.graphics.Texture;

import java.util.HashMap;
import java.util.Map;

/** Render-thread-only automatic-release protection, owned by the current hand rather than a budget. */
public final class HandTextureResidency {
    // Reuse entries while the hand is unchanged; a per-frame scan must not allocate per texture.
    private static final Map<String, Boolean> keys = new HashMap<>();
    private static final Map<String, Boolean> sources = new HashMap<>();

    private HandTextureResidency() { }

    public static void beginUpdate() {
        keys.replaceAll((key, seen) -> false);
        sources.replaceAll((key, seen) -> false);
    }

    public static void protect(Texture texture) {
        if (texture != null) protect(texture.getRamSaverKey());
    }

    public static void protect(String key) {
        if (key == null) return;
        keys.put(key, true);
        RamSaver.FileTextureSupplier supplier = RamSaver.getTextureSupplier(key);
        if (supplier != null) sources.put(supplier.sourceKey, true);
    }

    public static void endUpdate() {
        keys.values().removeIf(seen -> !seen);
        sources.values().removeIf(seen -> !seen);
    }

    public static boolean isProtected(String key) {
        if (keys.containsKey(key)) return true;
        if (sources.isEmpty()) return false;
        RamSaver.FileTextureSupplier supplier = RamSaver.getTextureSupplier(key);
        // Sampler/format/mipmap variants of a hand image share its source, never its GL handle.
        return supplier != null && sources.containsKey(supplier.sourceKey);
    }

    public static void clear() {
        keys.clear();
        sources.clear();
    }
}
