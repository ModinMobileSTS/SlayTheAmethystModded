package optispire.patches;

import com.badlogic.gdx.Gdx;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

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
            "vfx/vfx.png",
    };

    private static int nextTextureIndex;
    private static int attemptedPass = -1;

    private CombatTexturePrewarm() { }

    @SpirePatch2(clz = CardCrawlGame.class, method = "update")
    public static class CardCrawlGameUpdatePatch {
        public static void Postfix() {
            prewarmOneTextureDuringNonCombat();
        }
    }

    private static void prewarmOneTextureDuringNonCombat() {
        if (Gdx.files == null || !shouldPrewarmDuringUpdate()) return;
        int pass = FirstCombatPrewarmBudget.texturePass();
        if (pass < 0) return;
        if (pass != attemptedPass) {
            attemptedPass = pass;
            nextTextureIndex = 0;
        }
        if (nextTextureIndex >= COMBAT_TEXTURES.length || !FirstCombatPrewarmBudget.tryStep()) return;
        String path = COMBAT_TEXTURES[nextTextureIndex++];
        PrewarmTextureRegistry.prewarm(path, atlasPath(path), "combat_texture_prewarm_step");
    }

    static String atlasPath(String path) {
        String directory = path.substring(0, path.indexOf('/'));
        return directory + "/" + (directory.equals("bottomScene") ? "scene" : directory) + ".atlas";
    }

    private static boolean shouldPrewarmDuringUpdate() {
        try {
            if (CardCrawlGame.mode == CardCrawlGame.GameMode.SPLASH
                    || CardCrawlGame.mode == CardCrawlGame.GameMode.CHAR_SELECT) return true;
            if (CardCrawlGame.mode != CardCrawlGame.GameMode.GAMEPLAY) return false;
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            return room == null || room.phase != AbstractRoom.RoomPhase.COMBAT;
        }
        catch (RuntimeException ignored) {
            return false;
        }
    }
}
