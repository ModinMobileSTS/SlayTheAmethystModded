package optispire.patches;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.rooms.MonsterRoom;
import com.megacrit.cardcrawl.saveAndContinue.SaveFile;
import optispire.RamSaver;
import optispire.RamSaverDiag;

public final class CombatTexturePrewarm {
    private static final String[] COMBAT_TEXTURES = {
            "cardui/cardui.png",
            "cardui/cardui2.png",
            "cardui/cardui3.png",
            "cardui/cardui4.png",
            "cards/cards.png",
            "cards/cards2.png",
            "cards/cards3.png",
            "cards/cards4.png",
            "cards/cards5.png",
            "powers/powers.png",
            "bottomScene/scene.jpg",
            "bottomScene/scene.png",
            "bottomScene/scene2.jpg",
            "bottomScene/scene2.png",
            "bottomScene/scene3.jpg",
            // vfx atlas used by all card animations and VFX effects.
            // Without pinning this, every VFX region draw during DrawCardAction
            // triggers a fake-texture materialize → 2 extra SpriteBatch flushes.
            // With 700+ fake region draws per spike frame this accounts for
            // ~1400 of the observed 3400+ extra flushes.
            "vfx/vfx.png",
    };

    private static final boolean[] PREWARMED_TEXTURES = new boolean[COMBAT_TEXTURES.length];
    private static int nextTextureIndex = 0;
    private static int completedTextureCount = 0;
    private static boolean registeredKnownTextures = false;
    private static boolean fallbackAttempted = false;

    private enum PrewarmResult {
        ALREADY_DONE,
        LOADED,
        CACHED,
        MISSING,
        SKIPPED,
        FAILED
    }

    private CombatTexturePrewarm() {
    }

    @SpirePatch2(
            clz = CardCrawlGame.class,
            method = "update"
    )
    public static class CardCrawlGameUpdatePatch {
        public static void Postfix() {
            prewarmOneTextureDuringNonCombat();
        }
    }

    @SpirePatch2(
            clz = AbstractDungeon.class,
            method = "nextRoomTransition",
            paramtypez = {SaveFile.class}
    )
    public static class AbstractDungeonNextRoomTransitionPatch {
        @SpirePrefixPatch
        public static void Prefix() {
            prewarmRemainingForUpcomingCombat();
        }
    }

    private static void prewarmOneTextureDuringNonCombat() {
        if (!shouldPrewarmDuringUpdate()) {
            return;
        }

        registerKnownTextures();
        if (completedTextureCount >= COMBAT_TEXTURES.length) {
            return;
        }

        prewarmNextTexture("background");
    }

    private static void prewarmRemainingForUpcomingCombat() {
        if (fallbackAttempted || completedTextureCount >= COMBAT_TEXTURES.length || !isNextRoomCombat()) {
            return;
        }

        fallbackAttempted = true;
        boolean diag = RamSaverDiag.enabled();
        long started = diag ? System.nanoTime() : 0L;
        int loaded = 0;
        int cached = 0;
        int missing = 0;
        int skipped = 0;
        int failed = 0;
        for (int i = 0; i < COMBAT_TEXTURES.length; i++) {
            switch (prewarmTextureAtIndex(i, "fallback")) {
                case LOADED:
                    loaded++;
                    break;
                case CACHED:
                case ALREADY_DONE:
                    cached++;
                    break;
                case MISSING:
                    missing++;
                    break;
                case SKIPPED:
                    skipped++;
                    break;
                case FAILED:
                    failed++;
                    break;
            }
        }

        if (diag) {
            RamSaverDiag.logDuration(
                    "combat_texture_prewarm_done",
                    "first-combat",
                    started,
                    "loaded=" + loaded
                            + " cached=" + cached
                            + " missing=" + missing
                            + " skipped=" + skipped
                            + " failed=" + failed
                            + " completed=" + completedTextureCount + "/" + COMBAT_TEXTURES.length,
                    false
            );
        }
    }

    private static PrewarmResult prewarmNextTexture(String reason) {
        int start = nextTextureIndex;
        for (int inspected = 0; inspected < COMBAT_TEXTURES.length; inspected++) {
            int index = (start + inspected) % COMBAT_TEXTURES.length;
            if (PREWARMED_TEXTURES[index]) {
                continue;
            }
            nextTextureIndex = (index + 1) % COMBAT_TEXTURES.length;
            return prewarmTextureAtIndex(index, reason);
        }
        return PrewarmResult.ALREADY_DONE;
    }

    private static PrewarmResult prewarmTextureAtIndex(int index, String reason) {
        if (PREWARMED_TEXTURES[index]) {
            return PrewarmResult.ALREADY_DONE;
        }

        String path = COMBAT_TEXTURES[index];
        if (!RamSaver.textureExists(path)) {
            markPrewarmed(index);
            return PrewarmResult.MISSING;
        }

        boolean diag = RamSaverDiag.enabled();
        long started = diag ? System.nanoTime() : 0L;
        try {
            Texture existing = RamSaver.getExistingTexture(path);
            if (existing != null) {
                markPrewarmed(index);
                logStep(diag, started, reason, path, PrewarmResult.CACHED);
                return PrewarmResult.CACHED;
            }

            Texture texture = RamSaver.getTexture(null, path, false);
            if (texture != null && texture.getTextureObjectHandle() != 0) {
                markPrewarmed(index);
                logStep(diag, started, reason, path, PrewarmResult.LOADED);
                return PrewarmResult.LOADED;
            }

            markPrewarmed(index);
            logStep(diag, started, reason, path, PrewarmResult.SKIPPED);
            return PrewarmResult.SKIPPED;
        }
        catch (RuntimeException error) {
            markPrewarmed(index);
            if (diag) {
                RamSaverDiag.logRepeat(
                        "combat_texture_prewarm_failed",
                        path,
                        "reason=" + reason
                                + " error=" + error.getClass().getName() + ":" + RamSaverDiag.safe(error.getMessage())
                );
            }
            return PrewarmResult.FAILED;
        }
    }

    private static void registerKnownTextures() {
        if (registeredKnownTextures || Gdx.files == null) {
            return;
        }

        int registered = 0;
        int alreadyRegistered = 0;
        int missing = 0;
        for (int i = 0; i < COMBAT_TEXTURES.length; i++) {
            String path = COMBAT_TEXTURES[i];
            if (RamSaver.textureExists(path)) {
                alreadyRegistered++;
                continue;
            }

            try {
                FileHandle file = Gdx.files.internal(path);
                if (file.exists()) {
                    RamSaver.registerTexture(path, new RamSaver.FileTextureSupplier(file, null, false));
                    registered++;
                }
                else {
                    markPrewarmed(i);
                    missing++;
                }
            }
            catch (RuntimeException ignored) {
                markPrewarmed(i);
                missing++;
            }
        }

        registeredKnownTextures = true;
        if (RamSaverDiag.enabled()) {
            RamSaverDiag.logRepeat(
                    "combat_texture_prewarm_registered",
                    "known-textures",
                    "registered=" + registered
                            + " alreadyRegistered=" + alreadyRegistered
                            + " missing=" + missing
                            + " completed=" + completedTextureCount + "/" + COMBAT_TEXTURES.length
            );
        }
    }

    private static void markPrewarmed(int index) {
        if (!PREWARMED_TEXTURES[index]) {
            PREWARMED_TEXTURES[index] = true;
            completedTextureCount++;
        }
    }

    private static void logStep(boolean diag, long started, String reason, String path, PrewarmResult result) {
        if (!diag) {
            return;
        }
        RamSaverDiag.logDuration(
                "combat_texture_prewarm_step",
                path,
                started,
                "reason=" + reason
                        + " result=" + result
                        + " completed=" + completedTextureCount + "/" + COMBAT_TEXTURES.length,
                false
        );
    }

    private static boolean shouldPrewarmDuringUpdate() {
        try {
            if (CardCrawlGame.mode == CardCrawlGame.GameMode.SPLASH
                    || CardCrawlGame.mode == CardCrawlGame.GameMode.CHAR_SELECT) {
                return true;
            }
            if (CardCrawlGame.mode != CardCrawlGame.GameMode.GAMEPLAY) {
                return false;
            }
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            return room == null || room.phase != AbstractRoom.RoomPhase.COMBAT;
        }
        catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isNextRoomCombat() {
        try {
            MapRoomNode nextRoom = AbstractDungeon.nextRoom;
            AbstractRoom room = nextRoom == null ? null : nextRoom.room;
            return room instanceof MonsterRoom;
        }
        catch (RuntimeException ignored) {
            return false;
        }
    }
}
