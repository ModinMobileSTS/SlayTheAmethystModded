package optispire.patches;

import com.badlogic.gdx.graphics.g2d.SpriteCache;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpireRawPatch;
import javassist.CannotCompileException;
import javassist.CtBehavior;

public class SpriteCacheFakeTextures {
    // UVs are baked by add(); Texture.bind() resolves fake textures on demand.
    // Full draws need no patch or temporary replacement of the cache's textures.
    @SpirePatch2(
            clz = SpriteCache.class,
            method = "draw",
            paramtypez = { int.class, int.class, int.class }
    )
    public static class DrawPartialCache {
        @SpireRawPatch
        public static void drawOnlyRequestedTextures(CtBehavior method) throws CannotCompileException {
            // This bundled GDX version starts binding at textures[0], even for a
            // nonzero offset, and binds the next segment when length reaches zero.
            method.setBody("{"
                    + "if (!drawing) throw new IllegalStateException(\"SpriteCache.begin must be called before draw.\");"
                    + "com.badlogic.gdx.graphics.g2d.SpriteCache.Cache cache ="
                    + " (com.badlogic.gdx.graphics.g2d.SpriteCache.Cache)caches.get($1);"
                    + "long total = 0;"
                    + "for (int i = 0; i < cache.textureCount; i++) total += cache.counts[i];"
                    + "if ($2 < 0 || $3 < 0 || ((long)$2 + $3) * 6 > total)"
                    + " throw new IllegalArgumentException(\"Invalid SpriteCache draw range\");"
                    + "if ($3 == 0) return;"
                    + "int verticesPerImage = mesh.getNumIndices() > 0 ? 4 : 6;"
                    + "int renderOffset = cache.offset / (verticesPerImage * 5) * 6 + $2 * 6;"
                    + "int skip = $2 * 6;"
                    + "int remaining = $3 * 6;"
                    + "for (int i = 0; i < cache.textureCount && remaining > 0; i++) {"
                    + " int count = cache.counts[i];"
                    + " if (skip >= count) { skip -= count; continue; }"
                    + " count = Math.min(count - skip, remaining);"
                    + " skip = 0;"
                    + " cache.textures[i].bind();"
                    + " mesh.render(customShader != null ? customShader : shader, 4, renderOffset, count);"
                    + " renderOffset += count;"
                    + " remaining -= count;"
                    + " renderCalls++;"
                    + " totalRenderCalls++;"
                    + "}"
                    + "}");
        }
    }
}
