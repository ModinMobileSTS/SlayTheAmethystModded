package optispire.patches;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.RamSaverTestSupport;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.GdxNativesLoader;
import optispire.*;
import org.junit.Test;
import java.lang.reflect.Proxy;
import static org.junit.Assert.*;

public class BackgroundPrewarmWiringTest extends RamSaverTestSupport {
    @Test public void actualFirstCombatUiHookWaitsForDecodeThenUploadsWithSharedCadence() throws Exception {
        GdxNativesLoader.load();
        BoundedTextureDecoderTest.ManualExecutor executor = new BoundedTextureDecoderTest.ManualExecutor();
        BoundedTextureDecoder<PngTextureDecoder.Pixels> queue = new BoundedTextureDecoder<>(executor, 1024 * 1024, 2);
        set(TextureDecodeService.class, "decoder", queue);
        com.megacrit.cardcrawl.core.CardCrawlGame.GameMode previous = com.megacrit.cardcrawl.core.CardCrawlGame.mode;
        Files previousFiles = Gdx.files;
        String[] flags = {"baseModGlowInitialized", "monsterIntentSwitchInitialized", "baseModCardDescriptionCnInitialized"};
        Object[] old = new Object[flags.length];
        Object oldPass = field(FirstCombatUiPrewarm.class, "attemptedPass");
        Object oldIndex = field(FirstCombatUiPrewarm.class, "nextTextureIndex");
        try {
            byte[] png = PngFixtures.image(1, 1, 4, new byte[] {1, 2, 3, 4}, 0);
            Gdx.files = (Files) Proxy.newProxyInstance(Files.class.getClassLoader(), new Class<?>[] {Files.class},
                    (object, method, args) -> new MemoryFile((String) args[0], png));
            com.megacrit.cardcrawl.core.CardCrawlGame.mode = com.megacrit.cardcrawl.core.CardCrawlGame.GameMode.CHAR_SELECT;
            for (int i = 0; i < flags.length; i++) { old[i] = field(FirstCombatUiPrewarm.class, flags[i]); set(FirstCombatUiPrewarm.class, flags[i], true); }
            set(FirstCombatUiPrewarm.class, "attemptedPass", 0);
            set(FirstCombatUiPrewarm.class, "nextTextureIndex", 8); // first combat-status file, no atlas
            set(FirstCombatPrewarmBudget.class, "stopped", false);
            set(FirstCombatPrewarmBudget.class, "gameplayStarted", -1L);
            set(FirstCombatPrewarmBudget.class, "cooldownFrames", 0);
            FirstCombatPrewarmBudget.beginFrame(0, false, false);
            FirstCombatUiPrewarm.CardCrawlGameUpdatePatch.Postfix();
            assertEquals(8, field(FirstCombatUiPrewarm.class, "nextTextureIndex"));
            assertEquals(0, generated);
            executor.drainWorker();
            for (int frame = 1; frame <= 2; frame++) {
                FirstCombatPrewarmBudget.beginFrame(frame, false, false);
                FirstCombatUiPrewarm.CardCrawlGameUpdatePatch.Postfix();
                assertEquals(0, generated);
            }
            FirstCombatPrewarmBudget.beginFrame(3, false, false);
            FirstCombatUiPrewarm.CardCrawlGameUpdatePatch.Postfix();
            assertEquals(1, generated);
            assertEquals(9, field(FirstCombatUiPrewarm.class, "nextTextureIndex"));
            assertEquals(0, queue.reservedBytes());
            assertFalse("Upload consumed the shared step", FirstCombatPrewarmBudget.tryTextureStep());
            FileHandle file = Gdx.files.internal("images/ui/combat/left7.png");
            RamSaver.registerPrewarmTexture(file);
            RamSaver.prewarmTexture(RamSaver.prewarmKey(file), true, TextureDecodeService.FIRST_COMBAT);
            executor.drainWorker();
            FirstCombatPrewarmBudget.beginFrame(4, true, true);
            assertEquals(0, queue.reservedBytes());
            assertEquals(0, queue.pendingCount());
        } finally {
            queue.shutdown(); executor.drainWorker();
            set(TextureDecodeService.class, "decoder", null);
            Gdx.files = previousFiles;
            com.megacrit.cardcrawl.core.CardCrawlGame.mode = previous;
            for (int i = 0; i < flags.length; i++) set(FirstCombatUiPrewarm.class, flags[i], old[i]);
            set(FirstCombatUiPrewarm.class, "attemptedPass", oldPass);
            set(FirstCombatUiPrewarm.class, "nextTextureIndex", oldIndex);
            set(FirstCombatPrewarmBudget.class, "stopped", false);
            set(FirstCombatPrewarmBudget.class, "gameplayStarted", -1L);
            set(FirstCombatPrewarmBudget.class, "cooldownFrames", 0);
            set(FirstCombatPrewarmBudget.class, "claimed", false);
        }
    }

    @Test public void atlasRegistryUsesActualPageFormatAndSamplerWithBackgroundDecode() throws Exception {
        GdxNativesLoader.load();
        BoundedTextureDecoderTest.ManualExecutor executor = new BoundedTextureDecoderTest.ManualExecutor();
        BoundedTextureDecoder<PngTextureDecoder.Pixels> queue = new BoundedTextureDecoder<>(executor, 1024 * 1024, 2);
        set(TextureDecodeService.class, "decoder", queue);
        Files previousFiles = Gdx.files;
        try {
            byte[] png = PngFixtures.image(2, 2, 4, new byte[16], 0);
            byte[] atlas = ("page.png\nsize: 2, 2\nformat: RGB565\nfilter: Linear, Nearest\nrepeat: xy\n"
                    + "region\n  rotate: false\n  xy: 0, 0\n  size: 2, 2\n  orig: 2, 2\n  offset: 0, 0\n  index: -1\n")
                    .getBytes("UTF-8");
            class AtlasFile extends MemoryFile {
                AtlasFile(String path) { super(path, path.endsWith(".atlas") ? atlas : png); }
                @Override public FileHandle parent() { return new AtlasFile("background-atlas"); }
                @Override public FileHandle child(String name) { return new AtlasFile("background-atlas/" + name); }
            }
            Gdx.files = (Files) Proxy.newProxyInstance(Files.class.getClassLoader(), new Class<?>[] {Files.class},
                    (object, method, args) -> new AtlasFile((String) args[0]));
            String path = "background-atlas/page.png", atlasPath = "background-atlas/test.atlas";
            assertFalse(PrewarmTextureRegistry.prewarm(path, atlasPath, "test"));
            String key = PrewarmTextureRegistry.register(path, atlasPath);
            assertEquals(0, generated);
            executor.drainWorker();
            assertTrue(PrewarmTextureRegistry.prewarm(path, atlasPath, "test"));
            Texture real = RamSaver.getExistingTexture(key);
            assertNotNull(real);
            assertEquals(com.badlogic.gdx.graphics.Pixmap.Format.RGB565, real.getTextureData().getFormat());
            assertEquals(Texture.TextureFilter.Linear, real.getMinFilter());
            assertEquals(Texture.TextureFilter.Nearest, real.getMagFilter());
            assertEquals(Texture.TextureWrap.Repeat, real.getUWrap());
            assertEquals(Texture.TextureWrap.Repeat, real.getVWrap());
            assertEquals(0, queue.reservedBytes());
        } finally {
            queue.shutdown(); executor.drainWorker();
            set(TextureDecodeService.class, "decoder", null);
            Gdx.files = previousFiles;
        }
    }
}
