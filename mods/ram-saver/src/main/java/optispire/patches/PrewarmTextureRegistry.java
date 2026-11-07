package optispire.patches;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureAtlas.TextureAtlasData;
import com.badlogic.gdx.graphics.g2d.TextureAtlas.TextureAtlasData.Page;
import optispire.RamSaver;
import optispire.RamSaverDiag;
import optispire.RunTextureResidency;

import java.util.HashMap;
import java.util.Map;

final class PrewarmTextureRegistry {
    private static final Map<String, Map<String, Page>> ATLAS_PAGES = new HashMap<>();

    private PrewarmTextureRegistry() { }

    static String register(String path, String atlasPath) {
        FileHandle file = Gdx.files.internal(path);
        if (atlasPath == null) {
            String key = RamSaver.prewarmKey(file);
            if (!RamSaver.textureExists(key) && file.exists()) RamSaver.registerPrewarmTexture(file);
            return key;
        }

        Map<String, Page> pages = ATLAS_PAGES.get(atlasPath);
        if (pages == null) {
            pages = new HashMap<>();
            // Cache missing/bad metadata too; never substitute ImageMaster defaults for atlas pages.
            ATLAS_PAGES.put(atlasPath, pages);
            FileHandle atlas = Gdx.files.internal(atlasPath);
            if (atlas.exists()) {
                TextureAtlasData data = new TextureAtlasData(atlas, atlas.parent(), false);
                for (Page page : data.getPages()) pages.put(page.textureFile.path(), page);
            }
        }
        Page page = pages.get(file.path());
        if (page == null) return null;
        String key = RamSaver.textureKey(page.textureFile, page.format, page.useMipMaps,
                page.minFilter, page.magFilter, page.uWrap, page.vWrap);
        RamSaver.cacheTextureSize(key, (int) page.width, (int) page.height);
        // Parse every page's metadata, but register/materialize only the explicitly requested page.
        if (!RamSaver.textureExists(key) && page.textureFile.exists()) {
            RamSaver.FileTextureSupplier supplier = new RamSaver.FileTextureSupplier(
                    page.textureFile, page.format, page.useMipMaps);
            supplier.setFilter(page.minFilter, page.magFilter);
            supplier.setWrap(page.uWrap, page.vWrap);
            RamSaver.registerTexture(key, supplier);
        }
        return key;
    }

    static boolean prewarm(String path, String atlasPath, String event) {
        if (RunTextureResidency.isUnderPressure()) return false;
        boolean diag = RamSaverDiag.enabled();
        long started = diag ? System.nanoTime() : 0L;
        String result = "missing";
        boolean complete = true;
        try {
            String key = register(path, atlasPath);
            if (key != null && RamSaver.textureExists(key)) {
                boolean cached = RamSaver.getExistingTexture(key) != null;
                complete = RamSaver.prewarmTexture(key, false, optispire.TextureDecodeService.FIRST_COMBAT);
                result = complete ? (cached ? "cached" : "attempted") : "pending";
            }
        }
        catch (RuntimeException error) {
            result = "failed:" + error.getClass().getName();
        }
        if (diag) RamSaverDiag.logDuration(event, path, started, "result=" + result, false);
        return complete;
    }
}
